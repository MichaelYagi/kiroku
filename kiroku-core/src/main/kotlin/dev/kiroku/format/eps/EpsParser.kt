package dev.kiroku.format.eps

import dev.kiroku.format.NativeContext
import dev.kiroku.format.icc.IccParser
import dev.kiroku.format.iptc.PhotoshopParser
import dev.kiroku.format.xmp.XmpParser
import dev.kiroku.io.ArraySource
import dev.kiroku.io.ByteSource
import dev.kiroku.io.Bytes
import dev.kiroku.model.ByteOrderMark
import dev.kiroku.model.DiagnosticCode
import dev.kiroku.model.DirectoryKind
import dev.kiroku.model.EmbeddedImageInfo
import dev.kiroku.model.EmbeddedImageKind
import dev.kiroku.model.NativeType
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.TypeSystem

/**
 * PostScript and EPS. Reads the Document Structuring Convention header comments (`%%Creator:`, `%%BoundingBox:` ...)
 * up to `%%EndComments`, Photoshop's `%ImageData:`, the hex-encoded `%BeginPhotoshop` (IRBs with IPTC) and
 * `%%BeginICCProfile` blocks, and the XMP packet. Documents embedded with `%%BeginDocument` are skipped, as ExifTool
 * does. DOS EPS files start with a binary header that locates the PostScript and optional TIFF/WMF previews.
 *
 * Only the first [MAX_SCAN] bytes of the PostScript are scanned; metadata normally sits at the start.
 * Hex blocks are decoded, so their values are reconstructed rather than byte-exact.
 */
internal object EpsParser {
    private val GROUP = listOf("PostScript", "PostScript")
    private const val MAX_SCAN = 8 shl 20
    private const val MAX_LINE = 64 * 1024
    private const val MAX_HEX_BLOCK = 16 shl 20

    private val DOS_MAGIC = byteArrayOf(0xC5.toByte(), 0xD0.toByte(), 0xD3.toByte(), 0xC6.toByte())

    /** A PostScript document or DOS EPS. PostScript fonts (`%!PS-AdobeFont`, `%!FontType1`) are not documents. */
    fun looksLikePostScript(h: ByteArray): Boolean =
        Bytes.startsWith(h, DOS_MAGIC) || Bytes.startsWith(h, Bytes.ascii("%!PS")) && !Bytes.startsWith(h, Bytes.ascii("%!PS-AdobeFont"))

    // DSC keys and the names ExifTool gives them.
    private val NAMES =
        mapOf(
            "Author" to "Author", "BoundingBox" to "BoundingBox", "Copyright" to "Copyright", "CreationDate" to "CreateDate",
            "Creator" to "Creator", "For" to "For", "HiResBoundingBox" to "HiResBoundingBox", "Keywords" to "Keywords",
            "ModDate" to "ModifyDate", "Pages" to "Pages", "Routing" to "Routing", "Subject" to "Subject", "Title" to "Title",
            "Version" to "Version", "LanguageLevel" to "LanguageLevel",
        )

    fun parse(ctx: NativeContext, s: ByteSource) {
        var psStart = 0L
        var psLength = s.size
        if (s.size >= 30 && Bytes.startsWith(s.readAtMost(0, 4), DOS_MAGIC)) {
            psStart = s.u32(4, false)
            psLength = s.u32(8, false)
            ctx.collector.directory(listOf("PostScript", "DOSHeader"), DirectoryKind.FILE, 0, 30, ByteOrderMark.LITTLE_ENDIAN)
            preview(ctx, s, s.u32(12, false), s.u32(16, false), "WMFPreview", "image/wmf", "WMF")
            preview(ctx, s, s.u32(20, false), s.u32(24, false), "TIFFPreview", "image/tiff", "TIFF")
            if (!s.contains(psStart, minOf(psLength, 1))) {
                ctx.warn(DiagnosticCode.TRUNCATED, "DOS EPS PostScript section is outside the file")
                return
            }
        }
        val window = minOf(psLength, s.size - psStart, MAX_SCAN.toLong()).toInt()
        val b = s.readAtMost(psStart, window)
        if (window.toLong() < psLength) {
            ctx.warn(
                DiagnosticCode.LIMIT_EXCEEDED,
                "Only the first ${MAX_SCAN shr 20} MiB of the PostScript are scanned for metadata",
                severity = dev.kiroku.model.Severity.INFO,
            )
        }
        ctx.collector.directory(listOf("PostScript", "PostScript"), DirectoryKind.FILE, s.absolute(psStart), psLength)

        var depth = 0
        var inHeader = true
        var imageData = false
        var xmpDone = false
        var pos = 0
        var lineNo = 0
        val seen = HashSet<String>()
        while (pos < b.size) {
            ctx.countBox()
            if (++lineNo % 4096 == 0) ctx.checkCancelled()
            val end = lineEnd(b, pos)
            val text = String(b, pos, minOf(end - pos, MAX_LINE), Charsets.ISO_8859_1)
            val next = skipEol(b, end)
            when {
                text.startsWith("%%BeginDocument") -> depth++

                text.startsWith("%%EndDocument") -> depth = (depth - 1).coerceAtLeast(0)

                depth > 0 -> Unit

                inHeader && text.startsWith("%%EndComments") -> inHeader = false

                inHeader && text.startsWith("%%") && text.indexOf(':') > 2 -> {
                    val colon = text.indexOf(':')
                    val key = text.substring(2, colon)
                    val rawValue = text.substring(colon + 1)
                    val value = rawValue.trim()
                    if (value != "(atend)" && seen.add(key) && !key.startsWith("Begin") && !key.startsWith("End")) {
                        val valueAt = pos + colon + 1 + (rawValue.length - rawValue.trimStart().length)
                        dsc(ctx, s, psStart + valueAt, value, NAMES[key] ?: key, known = key in NAMES)
                    }
                }

                inHeader && !text.startsWith("%") -> inHeader = false

                !imageData && text.startsWith("%ImageData:") -> {
                    imageData = true
                    val value = text.substringAfter(':').trim()
                    val valueAt = psStart + pos + text.indexOf(value)
                    dsc(ctx, s, valueAt, value, "ImageData", known = true)
                    imageDataFields(ctx, s, valueAt, value)
                }

                text.startsWith("%BeginPhotoshop:") -> {
                    val (bytes, after) = hexBlock(b, next, "%EndPhotoshop")
                    if (bytes.isNotEmpty()) PhotoshopParser.parse(ctx, ArraySource(bytes, null), 0, bytes.size.toLong(), "PostScript/Photoshop")
                    pos = after
                    continue
                }

                text.startsWith("%%BeginICCProfile:") -> {
                    val (bytes, after) = hexBlock(b, next, "%%EndICCProfile")
                    if (bytes.isNotEmpty()) IccParser.parse(ctx, ArraySource(bytes, null), 0, bytes.size.toLong(), "PostScript/ICC_Profile")
                    pos = after
                    continue
                }

                !xmpDone && text.contains("<?xpacket begin") -> {
                    val start = pos + text.indexOf("<?xpacket begin")
                    val endTag = Bytes.indexOf(b, Bytes.ascii("<?xpacket end"), start)
                    val close = if (endTag >= 0) Bytes.indexOf(b, Bytes.ascii("?>"), endTag) else -1
                    if (close >= 0) {
                        xmpDone = true
                        XmpParser.parse(ctx, s, psStart + start, (close + 2 - start).toLong(), "PostScript/XMP")
                        pos = skipEol(b, lineEnd(b, close + 2))
                        continue
                    }
                }
            }
            pos = next
        }
    }

    private fun dsc(ctx: NativeContext, s: ByteSource, valueOffset: Long, value: String, name: String, known: Boolean) = ctx.observe(
        GROUP,
        name,
        NormalizedValue.Text(value),
        original = ctx.original(s, valueOffset, value.length.toLong()),
        nativeType = NativeType(TypeSystem.POSTSCRIPT, "DSC comment"),
        known = known,
        sourcePath = "PostScript/Comments",
    )

    /**
     * Photoshop's `%ImageData: columns rows depth mode padChannels blockSize binary/hex "beginimage"`. The first four
     * fields are also reported on their own in `PostScript:ImageData`; mode is 1 grayscale, 2 Lab, 3 RGB, 4 CMYK.
     */
    private fun imageDataFields(ctx: NativeContext, s: ByteSource, valueAt: Long, value: String) {
        val fields = Regex("\\S+").findAll(value).take(4).toList()
        val nums = fields.map { it.value.toLongOrNull() }
        if (nums.size < 4 || nums.any { it == null || it < 0 }) return
        val names = listOf("ImageWidth", "ImageHeight", "BitsPerSample", "ColorMode")
        for (i in 0 until 4) {
            val v = nums[i]!!
            ctx.observe(
                listOf("PostScript", "ImageData"),
                names[i],
                NormalizedValue.Integer(v),
                original = ctx.original(s, valueAt + fields[i].range.first, fields[i].value.length.toLong()),
                nativeType = NativeType(TypeSystem.POSTSCRIPT, "ImageData field"),
                displayValue = if (i == 3) COLOR_MODES[v.toInt()] else null,
                sourcePath = "PostScript/Comments/ImageData",
            )
        }
    }

    private val COLOR_MODES = mapOf(1 to "Grayscale", 2 to "Lab", 3 to "RGB", 4 to "CMYK")

    private fun preview(ctx: NativeContext, s: ByteSource, offset: Long, length: Long, name: String, mime: String, compression: String) {
        if (length <= 0 || !s.contains(offset, length)) return
        ctx.observe(
            listOf("PostScript", "DOSHeader"),
            name,
            NormalizedValue.Binary(length),
            original = ctx.original(s, offset, length),
            nativeType = NativeType(TypeSystem.POSTSCRIPT, "binary"),
            sourcePath = "PostScript/DOSHeader",
        )
        ctx.collector.embeddedImage(
            EmbeddedImageInfo(
                0,
                "",
                EmbeddedImageKind.PREVIEW,
                listOf("PostScript", "DOSHeader"),
                compression = compression,
                mimeType = mime,
                fileOffset = s.absolute(offset),
                length = length,
            ),
        )
    }

    /** Decodes `% 3842494d...` comment lines until [endMarker]. Returns the bytes and the position after the block. */
    private fun hexBlock(b: ByteArray, start: Int, endMarker: String): Pair<ByteArray, Int> {
        val out = java.io.ByteArrayOutputStream()
        var pos = start
        var hi = -1
        while (pos < b.size) {
            val end = lineEnd(b, pos)
            val next = skipEol(b, end)
            if (end - pos >= endMarker.length && String(b, pos, endMarker.length, Charsets.ISO_8859_1) == endMarker) return out.toByteArray() to next
            if (b[pos] != '%'.code.toByte()) return out.toByteArray() to pos
            for (i in pos + 1 until end) {
                val v = Character.digit(b[i].toInt().toChar(), 16)
                if (v < 0) continue
                if (hi < 0) {
                    hi = v
                } else {
                    out.write((hi shl 4) or v)
                    hi = -1
                    if (out.size() >= MAX_HEX_BLOCK) return out.toByteArray() to next
                }
            }
            pos = next
        }
        return out.toByteArray() to pos
    }

    private fun lineEnd(b: ByteArray, from: Int): Int {
        var i = from
        while (i < b.size && b[i] != '\r'.code.toByte() && b[i] != '\n'.code.toByte()) i++
        return i
    }

    private fun skipEol(b: ByteArray, at: Int): Int {
        var i = at
        if (i < b.size && b[i] == '\r'.code.toByte()) i++
        if (i < b.size && b[i] == '\n'.code.toByte()) i++
        return i
    }
}
