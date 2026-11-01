package dev.kiroku.format.jpeg

import dev.kiroku.format.NativeContext
import dev.kiroku.format.icc.IccParser
import dev.kiroku.format.iptc.PhotoshopParser
import dev.kiroku.format.tiff.TiffReader
import dev.kiroku.format.xmp.XmpParser
import dev.kiroku.io.ArraySource
import dev.kiroku.io.ByteSource
import dev.kiroku.io.Bytes
import dev.kiroku.model.DiagnosticCode
import dev.kiroku.model.DirectoryKind
import dev.kiroku.model.EmbeddedImageInfo
import dev.kiroku.model.EmbeddedImageKind
import dev.kiroku.model.NativeType
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.TypeSystem
import java.io.ByteArrayOutputStream

/**
 * JPEG marker-segment parser. It reads segments up to the first SOS and never decodes entropy-coded data.
 *
 * - EXIF (APP1) blocks are byte-exact TIFF; several blocks are allowed.
 * - XMP (APP1) packets are byte-exact. Extended XMP is reassembled (marked reconstructed).
 * - ICC (APP2) is byte-exact when it fits in one segment, and reassembled otherwise.
 * - MPF (APP2), Photoshop/IPTC (APP13), JFIF/JFXX (APP0) and Adobe (APP14) are parsed.
 * - Other APPn segments and COM are kept as byte-exact observations.
 */
internal object JpegParser {
    private val EXIF = Bytes.ascii("Exif\u0000")
    private val XMP = Bytes.ascii("http://ns.adobe.com/xap/1.0/\u0000")
    private val XMP_EXT = Bytes.ascii("http://ns.adobe.com/xmp/extension/\u0000")
    private val ICC = Bytes.ascii("ICC_PROFILE\u0000")
    private val MPF = Bytes.ascii("MPF\u0000")
    private val PHOTOSHOP = Bytes.ascii("Photoshop 3.0\u0000")

    fun parse(ctx: NativeContext, source: ByteSource) = parseInternal(ctx, source, "JPEG", null)

    /** An embedded JPEG (for example Panasonic `JpgFromRaw`). Its frame header goes to `["JPEG", label]`, not `File`. */
    fun parseEmbedded(ctx: NativeContext, source: ByteSource, prefix: String, label: String) = parseInternal(ctx, source, prefix, label)

    private fun parseInternal(ctx: NativeContext, source: ByteSource, prefix: String, embeddedLabel: String?) {
        if (source.size < 4 || source.u16(0, true) != 0xFFD8) {
            ctx.warn(DiagnosticCode.MALFORMED, "Missing JPEG SOI marker in $prefix")
            return
        }
        val fileGroup = if (embeddedLabel == null) listOf("File", "File") else listOf("JPEG", embeddedLabel)
        var pos = 2L
        val counters = HashMap<Int, Int>()
        val iccChunks = sortedMapOf<Int, Pair<Long, Int>>()
        var iccTotal = 0
        val extXmp = HashMap<String, MutableList<Triple<Long, Long, ByteArray>>>()
        val extXmpLen = HashMap<String, Long>()
        var sawSos = false
        while (pos + 4 <= source.size) {
            ctx.countBox()
            if (source.u8(pos) != 0xFF) {
                ctx.warn(DiagnosticCode.MALFORMED, "Invalid JPEG marker at offset $pos in $prefix; stopped")
                break
            }
            var marker = source.u8(pos + 1)
            // Fill bytes.
            while (marker == 0xFF && pos + 2 < source.size) {
                pos++
                marker = source.u8(pos + 1)
            }
            if (marker == 0xD9) break
            if (marker == 0x01 || marker in 0xD0..0xD7) {
                pos += 2
                continue
            }
            val len = source.u16(pos + 2, true)
            if (len < 2) {
                ctx.warn(DiagnosticCode.MALFORMED, "JPEG segment with invalid length at offset $pos")
                break
            }
            val dataStart = pos + 4
            val dataLen = (len - 2).toLong()
            if (!source.contains(dataStart, dataLen)) {
                ctx.warn(DiagnosticCode.TRUNCATED, "JPEG segment 0x%02X is truncated".format(marker))
                break
            }
            val idx = counters.merge(marker, 1, Int::plus)!! - 1
            val segName = segmentName(marker)
            val segPath = "$prefix/$segName[$idx]"
            ctx.collector.directory(segPath.split('/'), DirectoryKind.JPEG_SEGMENT, source.absolute(pos), len + 2L)
            val head = source.readAtMost(dataStart, minOf(dataLen, 80).toInt())
            when {
                marker == 0xE1 && Bytes.startsWith(head, EXIF) -> {
                    // "Exif\0\0" then TIFF. Some writers omit the second NUL.
                    val skip = if (head.size > 5 && head[5] == 0.toByte()) 6L else 5L
                    // EXIF inside an embedded JPEG (RW2/RWL JpgFromRaw) is the file's primary EXIF, as in ExifTool:
                    // the RAW IFD0 only holds PanasonicRaw tags. sourcePath keeps the embedding visible.
                    TiffReader(ctx, source.slice(dataStart + skip, dataLen - skip), "$segPath/TIFF").parse()
                }

                marker == 0xE1 && Bytes.startsWith(head, XMP) ->
                    XmpParser.parse(ctx, source, dataStart + XMP.size, dataLen - XMP.size, "$segPath/XMP")

                marker == 0xE1 && Bytes.startsWith(head, XMP_EXT) && dataLen > XMP_EXT.size + 40 -> {
                    val guid = String(source.read(dataStart + XMP_EXT.size, 32), Charsets.ISO_8859_1)
                    val full = source.u32(dataStart + XMP_EXT.size + 32, true)
                    val off = source.u32(dataStart + XMP_EXT.size + 36, true)
                    val body = dataStart + XMP_EXT.size + 40
                    val bodyLen = dataLen - XMP_EXT.size - 40
                    if (full <= ctx.limits.maxXmpBytes) {
                        extXmp.getOrPut(guid) { ArrayList() } += Triple(off, bodyLen, source.read(body, bodyLen.toInt()))
                        extXmpLen[guid] = full
                    } else {
                        ctx.warn(DiagnosticCode.LIMIT_EXCEEDED, "Extended XMP of $full bytes exceeds the limit")
                    }
                }

                marker == 0xE2 && Bytes.startsWith(head, ICC) && dataLen > 14 -> {
                    val seq = head[12].toInt() and 0xFF
                    iccTotal = head[13].toInt() and 0xFF
                    iccChunks[seq] = (dataStart + 14) to (dataLen - 14).toInt()
                }

                marker == 0xE2 && Bytes.startsWith(head, MPF) -> parseMpf(ctx, source, dataStart + 4, dataLen - 4, "$segPath/MPF")

                marker == 0xED && Bytes.startsWith(head, PHOTOSHOP) ->
                    PhotoshopParser.parse(ctx, source, dataStart + PHOTOSHOP.size, dataLen - PHOTOSHOP.size, "$segPath/Photoshop")

                marker == 0xE0 && Bytes.startsWith(head, Bytes.ascii("JFIF\u0000")) && dataLen >= 14 -> {
                    val g = listOf("JFIF", "JFIF")
                    obs(
                        ctx,
                        g,
                        "JFIFVersion",
                        NormalizedValue.Text("${head[5]}.%02d".format(head[6].toInt())),
                        source,
                        dataStart + 5,
                        2,
                        segPath,
                    )
                    obs(ctx, g, "ResolutionUnit", NormalizedValue.Integer((head[7].toLong() and 0xFF)), source, dataStart + 7, 1, segPath)
                    obs(
                        ctx,
                        g,
                        "XResolution",
                        NormalizedValue.Integer(Bytes.u16(head, 8, true).toLong()),
                        source,
                        dataStart + 8,
                        2,
                        segPath,
                    )
                    obs(
                        ctx,
                        g,
                        "YResolution",
                        NormalizedValue.Integer(Bytes.u16(head, 10, true).toLong()),
                        source,
                        dataStart + 10,
                        2,
                        segPath,
                    )
                    obs(ctx, g, "ThumbnailWidth", NormalizedValue.Integer((head[12].toLong() and 0xFF)), source, dataStart + 12, 1, segPath)
                    obs(
                        ctx,
                        g,
                        "ThumbnailHeight",
                        NormalizedValue.Integer((head[13].toLong() and 0xFF)),
                        source,
                        dataStart + 13,
                        1,
                        segPath,
                    )
                }

                marker == 0xEE && Bytes.startsWith(head, Bytes.ascii("Adobe")) && dataLen >= 12 -> {
                    val g = listOf("APP14", "Adobe")
                    obs(
                        ctx,
                        g,
                        "DCTEncodeVersion",
                        NormalizedValue.Integer(Bytes.u16(head, 5, true).toLong()),
                        source,
                        dataStart + 5,
                        2,
                        segPath,
                    )
                    obs(
                        ctx,
                        g,
                        "APP14Flags0",
                        NormalizedValue.Integer(Bytes.u16(head, 7, true).toLong()),
                        source,
                        dataStart + 7,
                        2,
                        segPath,
                    )
                    obs(
                        ctx,
                        g,
                        "APP14Flags1",
                        NormalizedValue.Integer(Bytes.u16(head, 9, true).toLong()),
                        source,
                        dataStart + 9,
                        2,
                        segPath,
                    )
                    obs(ctx, g, "ColorTransform", NormalizedValue.Integer((head[11].toLong() and 0xFF)), source, dataStart + 11, 1, segPath)
                }

                marker == 0xFE -> {
                    val b = source.read(dataStart, minOf(dataLen, ctx.limits.maxStringLength.toLong()).toInt())
                    ctx.observe(
                        listOf("File", "File"),
                        "Comment",
                        NormalizedValue.Text(Bytes.decodeText(b).trimEnd('\u0000')),
                        tagId = 0xFFFEL,
                        original = ctx.original(source, dataStart, dataLen),
                        nativeType = NativeType(TypeSystem.JPEG, "COM", null, dataLen),
                        sourcePath = segPath,
                    )
                }

                isSof(marker) -> parseSof(ctx, source, marker, dataStart, dataLen, segPath, fileGroup)

                marker == 0xDA -> sawSos = true

                marker in 0xE0..0xEF -> {
                    val ident = head.takeWhile { it != 0.toByte() }.take(40).toByteArray().toString(Charsets.ISO_8859_1)
                    ctx.observe(
                        listOf("JPEG", segName), segName, NormalizedValue.Binary(dataLen),
                        tagId = (0xFF00 or marker).toLong(), original = ctx.original(source, dataStart, dataLen),
                        nativeType = NativeType(TypeSystem.JPEG, segName, null, dataLen),
                        displayValue = ident.takeIf { it.isNotBlank() && it.all { c -> c.code in 0x20..0x7E } },
                        known = false, sourcePath = segPath,
                    )
                }
            }
            if (sawSos) break
            pos = dataStart + dataLen
        }
        finishIcc(ctx, source, iccChunks, iccTotal, prefix)
        for ((guid, parts) in extXmp) {
            val total = extXmpLen[guid] ?: continue
            val buf = ByteArray(total.toInt())
            var covered = 0L
            for ((off, len, bytes) in parts) {
                if (off + len <= total) {
                    System.arraycopy(bytes, 0, buf, off.toInt(), len.toInt())
                    covered += len
                }
            }
            if (covered < total) ctx.warn(DiagnosticCode.TRUNCATED, "Extended XMP $guid is incomplete ($covered of $total bytes)")
            // Reassembled from several segments, so not byte-exact.
            XmpParser.parseBytes(ctx, buf, ArraySource(buf, null), 0, "$prefix/ExtendedXMP[$guid]")
        }
    }

    private fun finishIcc(ctx: NativeContext, source: ByteSource, chunks: Map<Int, Pair<Long, Int>>, total: Int, prefix: String) {
        if (chunks.isEmpty()) return
        if (chunks.size == 1) {
            val (off, len) = chunks.values.first()
            IccParser.parse(ctx, source, off, len.toLong(), "$prefix/ICC_Profile")
            return
        }
        if (total != chunks.size) ctx.warn(DiagnosticCode.TRUNCATED, "ICC profile has ${chunks.size} of $total APP2 chunks")
        val out = ByteArrayOutputStream()
        for ((_, c) in chunks) out.write(source.read(c.first, c.second))
        val bytes = out.toByteArray()
        IccParser.parse(ctx, ArraySource(bytes, null), 0, bytes.size.toLong(), "$prefix/ICC_Profile")
    }

    private fun parseSof(ctx: NativeContext, source: ByteSource, marker: Int, off: Long, len: Long, path: String, group: List<String>) {
        if (len < 6) return
        val b = source.read(off, len.toInt())
        val precision = b[0].toInt() and 0xFF
        val height = Bytes.u16(b, 1, true)
        val width = Bytes.u16(b, 3, true)
        val comps = b[5].toInt() and 0xFF
        obs(
            ctx, group, "EncodingProcess",
            NormalizedValue.Integer(
                (marker - 0xC0).toLong(),
            ),
            source, off - 3, 1, path, encodingName(marker),
        )
        obs(ctx, group, "BitsPerSample", NormalizedValue.Integer(precision.toLong()), source, off, 1, path)
        obs(ctx, group, "ImageHeight", NormalizedValue.Integer(height.toLong()), source, off + 1, 2, path)
        obs(ctx, group, "ImageWidth", NormalizedValue.Integer(width.toLong()), source, off + 3, 2, path)
        obs(ctx, group, "ColorComponents", NormalizedValue.Integer(comps.toLong()), source, off + 5, 1, path)
        if (comps == 3 && len >= 6 + 9) {
            val h0 = (b[7].toInt() shr 4) and 0xF
            val v0 = b[7].toInt() and 0xF
            val h1 = (b[10].toInt() shr 4) and 0xF
            val v1 = b[10].toInt() and 0xF
            if (h1 > 0 && v1 > 0) {
                val hs = h0 / h1
                val vs = v0 / v1
                val chroma =
                    when {
                        hs == 1 && vs == 1 -> "4:4:4"
                        hs == 2 && vs == 1 -> "4:2:2"
                        hs == 2 && vs == 2 -> "4:2:0"
                        hs == 4 && vs == 1 -> "4:1:1"
                        hs == 1 && vs == 2 -> "4:4:0"
                        else -> null
                    }
                obs(
                    ctx, group, "YCbCrSubSampling",
                    NormalizedValue.ListValue(
                        listOf(NormalizedValue.Integer(hs.toLong()), NormalizedValue.Integer(vs.toLong())),
                    ),
                    source, off + 7, 1, path, chroma?.let { "YCbCr$it" },
                )
            }
        }
    }

    /** The Multi-Picture Format index (CIPA DC-007): a TIFF structure with MP entries. */
    private fun parseMpf(ctx: NativeContext, source: ByteSource, off: Long, len: Long, path: String) {
        val slice = source.slice(off, len)
        val summary = TiffReader(ctx, slice, path, family0 = "MPF").parse() ?: return
        // MP entries (tag 0xB002): 16 bytes each. Offsets are relative to the MPF TIFF header.
        val first = summary.header.firstIfdOffset
        if (!slice.contains(first, 2)) return
        val be = summary.header.bigEndian
        val n = slice.u16(first, be)
        for (i in 0 until n) {
            val e = first + 2 + i * 12L
            if (!slice.contains(e, 12)) break
            if (slice.u16(e, be) != 0xB002) continue
            val count = slice.u32(e + 4, be)
            val dataOff = slice.u32(e + 8, be)
            val entries = (count / 16).toInt()
            for (k in 0 until entries) {
                val p = dataOff + k * 16L
                if (!slice.contains(p, 16)) break
                val attr = slice.u32(p, be)
                val size = slice.u32(p + 4, be)
                val imgOff = slice.u32(p + 8, be)
                val type = attr and 0xFFFFFF
                val kind =
                    when {
                        k == 0 -> EmbeddedImageKind.PRIMARY
                        type == 0x010001L || type == 0x010002L -> EmbeddedImageKind.PREVIEW
                        else -> EmbeddedImageKind.AUXILIARY
                    }
                ctx.collector.embeddedImage(
                    EmbeddedImageInfo(
                        0,
                        "",
                        kind,
                        listOf("MPF", "MPImage${k + 1}"),
                        mimeType = "image/jpeg",
                        fileOffset = if (k == 0) source.absolute(0) else source.absolute(off + imgOff),
                        length = size,
                    ),
                )
            }
        }
    }

    private fun obs(
        ctx: NativeContext,
        group: List<String>,
        name: String,
        value: NormalizedValue,
        source: ByteSource,
        off: Long,
        len: Long,
        path: String,
        display: String? = null,
    ) = ctx.observe(
        group,
        name,
        value,
        original = ctx.original(source, off, len),
        nativeType = NativeType(TypeSystem.JPEG, if (len == 1L) "int8u" else "int16u", null, 1),
        displayValue = display,
        sourcePath = path,
    )

    private fun isSof(m: Int): Boolean = m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC

    private fun segmentName(m: Int): String = when (m) {
        in 0xE0..0xEF -> "APP${m - 0xE0}"
        0xFE -> "COM"
        0xDB -> "DQT"
        0xC4 -> "DHT"
        0xDD -> "DRI"
        0xDA -> "SOS"
        in 0xC0..0xCF -> "SOF${m - 0xC0}"
        else -> "M%02X".format(m)
    }

    private fun encodingName(m: Int): String = when (m) {
        0xC0 -> "Baseline DCT, Huffman coding"
        0xC1 -> "Extended sequential DCT, Huffman coding"
        0xC2 -> "Progressive DCT, Huffman coding"
        0xC3 -> "Lossless, Huffman coding"
        0xC5 -> "Sequential DCT, differential Huffman coding"
        0xC6 -> "Progressive DCT, differential Huffman coding"
        0xC7 -> "Lossless, Differential Huffman coding"
        0xC9 -> "Extended sequential DCT, arithmetic coding"
        0xCA -> "Progressive DCT, arithmetic coding"
        0xCB -> "Lossless, arithmetic coding"
        0xCD -> "Sequential DCT, differential arithmetic coding"
        0xCE -> "Progressive DCT, differential arithmetic coding"
        0xCF -> "Lossless, differential arithmetic coding"
        else -> "Unknown"
    }
}
