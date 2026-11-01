package dev.kiroku.format.psd

import dev.kiroku.format.NativeContext
import dev.kiroku.format.iptc.PhotoshopParser
import dev.kiroku.io.ByteSource
import dev.kiroku.model.ByteOrderMark
import dev.kiroku.model.DiagnosticCode
import dev.kiroku.model.DirectoryKind
import dev.kiroku.model.NativeType
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.TypeSystem

/**
 * Adobe Photoshop PSD (version 1) and PSB (version 2, large document). Big-endian sections in order: header,
 * colour mode data, image resources (Photoshop IRBs with IPTC, XMP, ICC, EXIF, resolution, thumbnail), layer and mask
 * information, image data. Layers and pixels are not decoded; only the layer count and the image compression are read.
 */
internal object PsdParser {
    private val GROUP = listOf("Photoshop", "Photoshop")

    fun parse(ctx: NativeContext, s: ByteSource) {
        if (s.size < 26) {
            ctx.warn(DiagnosticCode.TRUNCATED, "PSD shorter than its header")
            return
        }
        val psb = s.u16(4, true) == 2
        ctx.collector.directory(listOf("PSD", "Header"), DirectoryKind.FILE, 0, 26, ByteOrderMark.BIG_ENDIAN, note = if (psb) "PSB" else "PSD")
        fun o(name: String, v: Long, off: Long, n: Long, display: String? = null) = ctx.observe(
            GROUP,
            name,
            NormalizedValue.Integer(v),
            tagId = off,
            original = ctx.original(s, off, n, ByteOrderMark.BIG_ENDIAN),
            nativeType = NativeType(TypeSystem.PSD, "int${n * 8}u", null, 1),
            displayValue = display,
            sourcePath = "PSD/Header",
        )
        o("NumChannels", s.u16(12, true).toLong(), 12, 2)
        o("ImageHeight", s.u32(14, true), 14, 4)
        o("ImageWidth", s.u32(18, true), 18, 4)
        o("BitDepth", s.u16(22, true).toLong(), 22, 2)
        val mode = s.u16(24, true).toLong()
        o("ColorMode", mode, 24, 2, colorModeName(mode))

        var pos = 26L
        // Colour mode data (palette or duotone spec).
        if (!s.contains(pos, 4)) return truncated(ctx, "colour mode data")
        pos += 4 + s.u32(pos, true)
        // Image resources.
        if (!s.contains(pos, 4)) return truncated(ctx, "image resources")
        val irbLength = s.u32(pos, true)
        if (irbLength > 0) {
            if (s.contains(pos + 4, irbLength)) {
                PhotoshopParser.parse(ctx, s, pos + 4, irbLength, "PSD/ImageResources")
            } else {
                return truncated(ctx, "image resources")
            }
        }
        pos += 4 + irbLength
        // Layer and mask information: its length is 8 bytes in PSB. The layer info inside starts with a length and
        // a signed layer count (negative when the first alpha channel holds the merged transparency).
        val lenSize = if (psb) 8 else 4
        if (!s.contains(pos, lenSize.toLong())) return truncated(ctx, "layer and mask information")
        val layerMaskLength = if (psb) s.u64(pos, true) else s.u32(pos, true)
        if (layerMaskLength >= lenSize + 2 && s.contains(pos + lenSize, lenSize + 2L)) {
            val layerInfoLength = if (psb) s.u64(pos + lenSize, true) else s.u32(pos + lenSize, true)
            val count = if (layerInfoLength >= 2) kotlin.math.abs(s.u16(pos + 2L * lenSize, true).toShort().toInt()).toLong() else 0L
            o("LayerCount", count, pos + 2L * lenSize, 2)
        } else if (layerMaskLength == 0L) {
            o("LayerCount", 0, pos, lenSize.toLong())
        }
        pos += lenSize + layerMaskLength
        if (s.contains(pos, 2)) {
            val c = s.u16(pos, true).toLong()
            o("Compression", c, pos, 2, compressionName(c))
        }
    }

    private fun truncated(ctx: NativeContext, section: String) =
        ctx.warn(DiagnosticCode.TRUNCATED, "PSD ends before its $section section; partial results kept")

    private fun colorModeName(m: Long): String? = when (m) {
        0L -> "Bitmap"
        1L -> "Grayscale"
        2L -> "Indexed"
        3L -> "RGB"
        4L -> "CMYK"
        7L -> "Multichannel"
        8L -> "Duotone"
        9L -> "Lab"
        else -> null
    }

    private fun compressionName(c: Long): String? = when (c) {
        0L -> "Uncompressed"
        1L -> "RLE"
        2L -> "ZIP without prediction"
        3L -> "ZIP with prediction"
        else -> null
    }
}
