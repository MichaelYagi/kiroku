package dev.kiroku.format.bmp

import dev.kiroku.format.NativeContext
import dev.kiroku.format.icc.IccParser
import dev.kiroku.io.ByteSource
import dev.kiroku.model.DiagnosticCode
import dev.kiroku.model.DirectoryKind
import dev.kiroku.model.NativeType
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.TypeSystem

/** BMP/DIB headers: OS/2 core, BITMAPINFOHEADER, and V4/V5 with an embedded ICC profile. BMP has no camera metadata. */
internal object BmpParser {
    private val GROUP = listOf("BMP", "BMP")

    fun parse(ctx: NativeContext, s: ByteSource) {
        if (s.size < 26) {
            ctx.warn(DiagnosticCode.TRUNCATED, "BMP shorter than its headers")
            return
        }
        fun o(name: String, v: NormalizedValue, off: Long, n: Long, display: String? = null) = ctx.observe(
            GROUP,
            name,
            v,
            tagId = off,
            original = ctx.original(s, off, n),
            nativeType = NativeType(TypeSystem.BMP, "int${n * 8}", null, 1),
            displayValue = display,
            sourcePath = "BMP/Header",
        )
        o("FileSize", NormalizedValue.Integer(s.u32(2, false)), 2, 4)
        o("PixelDataOffset", NormalizedValue.Integer(s.u32(10, false)), 10, 4)
        val hs = s.u32(14, false)
        o("BMPVersion", NormalizedValue.Integer(hs), 14, 4, versionName(hs))
        ctx.collector.directory(listOf("BMP", "Header"), DirectoryKind.FILE, 0, 14 + hs)
        if (hs == 12L) {
            o("ImageWidth", NormalizedValue.Integer(s.u16(18, false).toLong()), 18, 2)
            o("ImageHeight", NormalizedValue.Integer(s.u16(20, false).toLong()), 20, 2)
            o("Planes", NormalizedValue.Integer(s.u16(22, false).toLong()), 22, 2)
            o("BitDepth", NormalizedValue.Integer(s.u16(24, false).toLong()), 24, 2)
            return
        }
        if (hs < 40 || !s.contains(14, hs)) {
            ctx.warn(DiagnosticCode.MALFORMED, "Unsupported or truncated BMP header size $hs")
            return
        }
        val w = s.u32(18, false).toInt()
        val h = s.u32(22, false).toInt()
        o("ImageWidth", NormalizedValue.Integer(w.toLong()), 18, 4)
        // A negative height means top-down row order. Report the magnitude, and the order separately.
        o("ImageHeight", NormalizedValue.Integer(kotlin.math.abs(h.toLong())), 22, 4)
        o("RowOrder", NormalizedValue.Text(if (h < 0) "top-down" else "bottom-up"), 22, 4)
        o("Planes", NormalizedValue.Integer(s.u16(26, false).toLong()), 26, 2)
        o("BitDepth", NormalizedValue.Integer(s.u16(28, false).toLong()), 28, 2)
        val comp = s.u32(30, false)
        o("Compression", NormalizedValue.Integer(comp), 30, 4, compressionName(comp))
        o("ImageLength", NormalizedValue.Integer(s.u32(34, false)), 34, 4)
        o("PixelsPerMeterX", NormalizedValue.Integer(s.u32(38, false).toInt().toLong()), 38, 4)
        o("PixelsPerMeterY", NormalizedValue.Integer(s.u32(42, false).toInt().toLong()), 42, 4)
        o("NumColors", NormalizedValue.Integer(s.u32(46, false)), 46, 4)
        o("NumImportantColors", NormalizedValue.Integer(s.u32(50, false)), 50, 4)
        if (hs >= 108) {
            val cs = s.u32(70, false)
            val csName =
                when (cs) {
                    0L -> "Calibrated RGB"
                    0x73524742L -> "sRGB"
                    0x57696E20L -> "Windows Color Space"
                    0x4C494E4BL -> "Linked Profile"
                    0x4D424544L -> "Embedded Profile"
                    else -> null
                }
            o("ColorSpace", NormalizedValue.Integer(cs), 70, 4, csName)
            if (hs >= 124) {
                o("RenderingIntent", NormalizedValue.Integer(s.u32(122, false)), 122, 4)
                val pOff = s.u32(126, false)
                val pLen = s.u32(130, false)
                if (cs == 0x4D424544L && pLen > 0) {
                    IccParser.parse(ctx, s, 14 + pOff, pLen, "BMP/ICC_Profile")
                } else if (cs == 0x4C494E4BL && pLen > 0 && s.contains(14 + pOff, pLen)) {
                    o(
                        "LinkedProfileName",
                        NormalizedValue.Text(String(s.read(14 + pOff, pLen.toInt()), Charsets.ISO_8859_1).trimEnd('\u0000')),
                        14 + pOff,
                        pLen,
                    )
                }
            }
        }
    }

    private fun versionName(hs: Long): String? = when (hs) {
        12L -> "OS/2 V1"
        64L -> "OS/2 V2"
        40L -> "Windows V3"
        52L, 56L -> "Windows V3 (Adobe extensions)"
        108L -> "Windows V4"
        124L -> "Windows V5"
        else -> null
    }

    private fun compressionName(c: Long): String? = when (c) {
        0L -> "None"
        1L -> "8-Bit RLE"
        2L -> "4-Bit RLE"
        3L -> "Bitfields"
        4L -> "JPEG"
        5L -> "PNG"
        6L -> "Alpha Bitfields"
        else -> null
    }
}
