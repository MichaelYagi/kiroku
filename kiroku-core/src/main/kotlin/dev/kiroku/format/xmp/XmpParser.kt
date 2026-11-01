package dev.kiroku.format.xmp

import dev.kiroku.format.NativeContext
import dev.kiroku.io.ByteSource
import dev.kiroku.io.Bytes
import dev.kiroku.model.DiagnosticCode
import dev.kiroku.model.DirectoryKind
import dev.kiroku.model.NativeType
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.TypeSystem
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses XMP (RDF/XML) packets with a hardened JDK XML parser: no DTDs, no external entities, and
 * bounded size.
 *
 * - The whole packet is recorded byte-exactly as `XMP:XMP:XMPPacket`.
 * - Each top-level property becomes an observation under `["XMP", "XMP-<prefix>"]`, with the namespace URI.
 *   Arrays (Bag/Seq) become lists, language alternatives become objects keyed by `xml:lang`,
 *   and structures become objects. Individual property values have no byte offsets (XML is text),
 *   so they are `BACKEND_DECODED`.
 */
internal object XmpParser {
    const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private const val XML_NS = "http://www.w3.org/XML/1998/namespace"

    /** Canonical prefixes by namespace URI, so prefixes chosen by the document don't affect group names. */
    val prefixes: Map<String, String> =
        mapOf(
            "http://purl.org/dc/elements/1.1/" to "dc",
            "http://ns.adobe.com/xap/1.0/" to "xmp",
            "http://ns.adobe.com/xap/1.0/mm/" to "xmpMM",
            "http://ns.adobe.com/xap/1.0/rights/" to "xmpRights",
            "http://ns.adobe.com/xap/1.0/bj/" to "xmpBJ",
            "http://ns.adobe.com/xap/1.0/t/pg/" to "xmpTPg",
            "http://ns.adobe.com/xmp/1.0/DynamicMedia/" to "xmpDM",
            "http://ns.adobe.com/exif/1.0/" to "exif",
            "http://cipa.jp/exif/1.0/" to "exifEX",
            "http://ns.adobe.com/tiff/1.0/" to "tiff",
            "http://ns.adobe.com/exif/1.0/aux/" to "aux",
            "http://ns.adobe.com/photoshop/1.0/" to "photoshop",
            "http://ns.adobe.com/camera-raw-settings/1.0/" to "crs",
            "http://ns.adobe.com/lightroom/1.0/" to "lr",
            "http://ns.adobe.com/pdf/1.3/" to "pdf",
            "http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/" to "iptcCore",
            "http://iptc.org/std/Iptc4xmpExt/2008-02-29/" to "iptcExt",
            "http://ns.useplus.org/ldf/xmp/1.0/" to "plus",
            "http://ns.google.com/photos/1.0/panorama/" to "GPano",
            "http://ns.google.com/photos/1.0/camera/" to "GCamera",
            "http://ns.google.com/photos/1.0/container/" to "GContainer",
            "http://ns.google.com/photos/1.0/image/" to "GImage",
            "http://ns.google.com/photos/dd/1.0/device/" to "GDevice",
            "http://ns.apple.com/adjustment-settings/1.0/" to "AdjustmentSettings",
            "http://ns.apple.com/HDRGainMap/1.0/" to "HDRGainMap",
            "http://ns.microsoft.com/photo/1.0/" to "MicrosoftPhoto",
            "http://ns.microsoft.com/photo/1.2/" to "MP",
            "http://www.metadataworkinggroup.com/schemas/regions/" to "mwg-rs",
            "http://www.metadataworkinggroup.com/schemas/keywords/" to "mwg-kw",
            "http://ns.adobe.com/xmp/note/" to "xmpNote",
            "http://ns.adobe.com/hdr-gain-map/1.0/" to "hdrgm",
            "http://ns.adobe.com/xap/1.0/g/img/" to "xmpGImg",
            "http://ns.adobe.com/xap/1.0/sType/ResourceEvent#" to "stEvt",
            "http://ns.adobe.com/xap/1.0/sType/ResourceRef#" to "stRef",
            "http://www.dji.com/drone-dji/1.0/" to "drone-dji",
        )

    fun parse(ctx: NativeContext, source: ByteSource, offset: Long, length: Long, path: String) {
        if (length <= 0 || !source.contains(offset, length)) return
        if (length > ctx.limits.maxXmpBytes) {
            ctx.warn(
                DiagnosticCode.LIMIT_EXCEEDED,
                "XMP packet of $length bytes exceeds the ${ctx.limits.maxXmpBytes}-byte limit; recorded but not parsed",
            )
            ctx.observe(
                listOf("XMP", "XMP"),
                "XMPPacket",
                NormalizedValue.Binary(length),
                original = ctx.original(source, offset, length),
                sourcePath = path,
            )
            return
        }
        val bytes = source.read(offset, length.toInt())
        parseBytes(ctx, bytes, source, offset, path)
    }

    /** Parses an XMP packet already in memory. [source]/[offset] say where the bytes came from, for provenance. */
    fun parseBytes(ctx: NativeContext, bytes: ByteArray, source: ByteSource?, offset: Long, path: String) {
        val original = source?.let { ctx.originalOf(bytes, it, offset) }
        ctx.collector.directory(path.split('/'), DirectoryKind.XMP_PACKET, source?.absolute(offset), bytes.size.toLong())
        ctx.observe(
            listOf("XMP", "XMP"),
            "XMPPacket",
            NormalizedValue.Binary(bytes.size.toLong()),
            original = original,
            nativeType = NativeType(TypeSystem.XMP, "packet"),
            sourcePath = path,
        )
        val doc =
            try {
                val start = trimToXml(bytes)
                newBuilderFactory().newDocumentBuilder().apply { setErrorHandler(SilentErrors) }.parse(
                    ByteArrayInputStream(
                        bytes,
                        start,
                        bytes.size - start,
                    ),
                )
            } catch (e: Exception) {
                ctx.warn(DiagnosticCode.MALFORMED, "XMP packet is not well-formed XML: ${e.javaClass.simpleName}", e)
                return
            }
        val descriptions = doc.getElementsByTagNameNS(RDF, "Description")
        var count = 0
        for (i in 0 until descriptions.length) {
            val d = descriptions.item(i) as Element
            // Only top-level descriptions (direct children of rdf:RDF). Nested ones are structures.
            if (d.parentNode?.let { it.namespaceURI == RDF && it.localName == "RDF" } != true) continue
            count += emitDescription(ctx, d, path)
        }
        if (count == 0 && descriptions.length == 0) {
            ctx.warn(DiagnosticCode.MALFORMED, "XMP packet has no rdf:Description", severity = dev.kiroku.model.Severity.INFO)
        }
    }

    private fun emitDescription(ctx: NativeContext, d: Element, path: String): Int {
        var n = 0
        val attrs = d.attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i)
            val ns = a.namespaceURI ?: continue
            if (ns == RDF || ns == XML_NS || ns == XMLConstants.XMLNS_ATTRIBUTE_NS_URI) continue
            emit(ctx, ns, a.localName ?: a.nodeName, NormalizedValue.Text(ctx.clip(a.nodeValue)), path, a.prefix)
            n++
        }
        var child = d.firstChild
        while (child != null) {
            if (child is Element && child.namespaceURI != null) {
                emit(ctx, child.namespaceURI, child.localName, propertyValue(ctx, child, 0), path, child.prefix)
                n++
            }
            child = child.nextSibling
        }
        return n
    }

    private fun emit(ctx: NativeContext, ns: String, local: String, value: NormalizedValue, path: String, docPrefix: String?) {
        val prefix = prefixes[ns] ?: docPrefix ?: "unknown"
        ctx.observe(
            groupPath = listOf("XMP", "XMP-$prefix"),
            tagName = local.replaceFirstChar { it.uppercaseChar() },
            value = value,
            namespace = ns,
            known = prefixes.containsKey(ns),
            nativeType = NativeType(TypeSystem.XMP, value.type.name.lowercase()),
            sourcePath = "$path/$prefix:$local",
        )
    }

    private fun propertyValue(ctx: NativeContext, e: Element, depth: Int): NormalizedValue {
        if (depth > ctx.limits.maxNestingDepth) return NormalizedValue.Null
        e.getAttributeNodeNS(RDF, "resource")?.let { return NormalizedValue.Text(it.value) }
        val children = elementChildren(e)
        if (children.isEmpty()) {
            val structAttrs = structAttributes(ctx, e)
            if (structAttrs.isNotEmpty()) return NormalizedValue.ObjectValue(structAttrs)
            return NormalizedValue.Text(ctx.clip(e.textContent.orEmpty()))
        }
        if (e.getAttributeNS(RDF, "parseType") == "Resource") return NormalizedValue.ObjectValue(structFields(ctx, e, depth))
        val first = children[0]
        if (first.namespaceURI == RDF) {
            when (first.localName) {
                "Bag", "Seq" -> return NormalizedValue.ListValue(listItems(first).map { liValue(ctx, it, depth + 1) })

                "Alt" -> {
                    val items = listItems(first)
                    val langs = items.map { it.getAttributeNS(XML_NS, "lang") }
                    return if (items.isNotEmpty() && langs.all { it.isNotEmpty() }) {
                        NormalizedValue.ObjectValue(
                            items.indices.associate {
                                langs[it] to liValue(ctx, items[it], depth + 1)
                            }.toMap(LinkedHashMap()),
                        )
                    } else {
                        NormalizedValue.ListValue(items.map { liValue(ctx, it, depth + 1) })
                    }
                }

                "Description" -> return NormalizedValue.ObjectValue(structAttributes(ctx, first) + structFields(ctx, first, depth))
            }
        }
        return NormalizedValue.ObjectValue(structFields(ctx, e, depth))
    }

    private fun liValue(ctx: NativeContext, li: Element, depth: Int): NormalizedValue = propertyValue(ctx, li, depth)

    private fun structFields(ctx: NativeContext, e: Element, depth: Int): Map<String, NormalizedValue> {
        val out = LinkedHashMap<String, NormalizedValue>()
        for (c in elementChildren(e)) {
            val key = (prefixes[c.namespaceURI] ?: c.prefix ?: "") + ":" + c.localName
            out[key] = propertyValue(ctx, c, depth + 1)
        }
        return out
    }

    private fun structAttributes(ctx: NativeContext, e: Element): Map<String, NormalizedValue> {
        val out = LinkedHashMap<String, NormalizedValue>()
        val attrs = e.attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i)
            val ns = a.namespaceURI ?: continue
            if (ns == RDF || ns == XML_NS || ns == XMLConstants.XMLNS_ATTRIBUTE_NS_URI) continue
            out[(prefixes[ns] ?: a.prefix ?: "") + ":" + a.localName] = NormalizedValue.Text(ctx.clip(a.nodeValue))
        }
        return out
    }

    private fun listItems(container: Element): List<Element> = elementChildren(container).filter {
        it.namespaceURI == RDF &&
            it.localName == "li"
    }

    private fun elementChildren(e: Element): List<Element> {
        val out = ArrayList<Element>()
        var c: Node? = e.firstChild
        while (c != null) {
            if (c is Element) out += c
            c = c.nextSibling
        }
        return out
    }

    /** Skips leading padding or garbage before the first `<`. */
    private fun trimToXml(b: ByteArray): Int {
        if (b.size >= 2 && (b[0] == 0xFE.toByte() || b[0] == 0xFF.toByte())) return 0
        val i = Bytes.indexOf(b, byteArrayOf('<'.code.toByte()))
        if (i < 0) return 0
        // UTF-16 packets: keep the code-unit alignment so the parser can detect the encoding.
        if (i > 0 && b[i - 1] == 0.toByte() && (i - 1) % 2 == 0) return i - 1
        return i
    }

    private fun newBuilderFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        isXIncludeAware = false
        setFeatureSafely("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeatureSafely("http://xml.org/sax/features/external-general-entities", false)
        setFeatureSafely("http://xml.org/sax/features/external-parameter-entities", false)
        setFeatureSafely("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setFeatureSafely(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        try {
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        } catch (_: IllegalArgumentException) {
        }
    }

    /** Fails on errors without printing to stderr (the JDK default handler prints). */
    private object SilentErrors : org.xml.sax.ErrorHandler {
        override fun warning(exception: org.xml.sax.SAXParseException) {}

        override fun error(exception: org.xml.sax.SAXParseException): Unit = throw exception

        override fun fatalError(exception: org.xml.sax.SAXParseException): Unit = throw exception
    }

    private fun DocumentBuilderFactory.setFeatureSafely(name: String, value: Boolean) {
        try {
            setFeature(name, value)
        } catch (_: Exception) {
        }
    }
}
