package dev.kiroku.format.pcx

import dev.kiroku.format.NativeContext
import dev.kiroku.io.ByteSource
import dev.kiroku.model.ByteOrderMark
import dev.kiroku.model.DiagnosticCode
import dev.kiroku.model.DirectoryKind
import dev.kiroku.model.NativeType
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.TypeSystem

/** ZSoft PCX: a 128-byte little-endian header. PCX has no camera, time or GPS metadata. Names follow ExifTool. */
internal object PcxParser {
    private val GROUP = listOf("File", "File")
    private const val HEADER = 128L

    /** Signature check: manufacturer 0x0A, a known version, RLE or no encoding, a valid bit depth and a sane window. */
    fun looksLikePcx(h: ByteArray): Boolean {
        if (h.size < 74) return false
        fun u16(i: Int) = (h[i].toInt() and 0xFF) or ((h[i + 1].toInt() and 0xFF) shl 8)
        return h[0] == 0x0A.toByte() &&
            (h[1].toInt() and 0xFF) in setOf(0, 2, 3, 4, 5) &&
            (h[2].toInt() and 0xFF) in 0..1 &&
            (h[3].toInt() and 0xFF) in setOf(1, 2, 4, 8) &&
            u16(8) >= u16(4) && u16(10) >= u16(6) &&
            (h[65].toInt() and 0xFF) in 1..4 &&
            u16(66) > 0
    }

    fun parse(ctx: NativeContext, s: ByteSource) {
        if (s.size < HEADER) {
            ctx.warn(DiagnosticCode.TRUNCATED, "PCX shorter than its header")
            return
        }
        ctx.collector.directory(listOf("PCX", "Header"), DirectoryKind.FILE, 0, HEADER, ByteOrderMark.LITTLE_ENDIAN)
        fun o(name: String, v: Long, off: Long, n: Long, display: String? = null) = ctx.observe(
            GROUP,
            name,
            NormalizedValue.Integer(v),
            tagId = off,
            original = ctx.original(s, off, n, ByteOrderMark.LITTLE_ENDIAN),
            nativeType = NativeType(TypeSystem.PCX, if (n == 1L) "int8u" else "int16u", null, 1),
            displayValue = display,
            sourcePath = "PCX/Header",
        )
        val version = s.u8(1).toLong()
        val encoding = s.u8(2).toLong()
        o("Manufacturer", s.u8(0).toLong(), 0, 1)
        o("Software", version, 1, 1, softwareName(version))
        o("Encoding", encoding, 2, 1, if (encoding == 1L) "RLE" else null)
        o("BitsPerPixel", s.u8(3).toLong(), 3, 1)
        val xMin = s.u16(4, false).toLong()
        val yMin = s.u16(6, false).toLong()
        o("LeftMargin", xMin, 4, 2)
        o("TopMargin", yMin, 6, 2)
        // The header stores the inclusive window (Xmin..Xmax); width and height are derived from it.
        o("ImageWidth", s.u16(8, false) - xMin + 1, 8, 2)
        o("ImageHeight", s.u16(10, false) - yMin + 1, 10, 2)
        o("XResolution", s.u16(12, false).toLong(), 12, 2)
        o("YResolution", s.u16(14, false).toLong(), 14, 2)
        o("ColorPlanes", s.u8(65).toLong(), 65, 1)
        o("BytesPerLine", s.u16(66, false).toLong(), 66, 2)
        val mode = s.u16(68, false).toLong()
        o(
            "ColorMode",
            mode,
            68,
            2,
            if (mode == 1L) {
                "Color Palette"
            } else if (mode == 2L) {
                "Grayscale"
            } else {
                null
            },
        )
        s.u16(70, false).toLong().takeIf { it > 0 }?.let { o("ScreenWidth", it, 70, 2) }
        s.u16(72, false).toLong().takeIf { it > 0 }?.let { o("ScreenHeight", it, 72, 2) }
    }

    private fun softwareName(v: Long): String? = when (v) {
        0L -> "PC Paintbrush 2.5"
        2L -> "PC Paintbrush 2.8 (with palette)"
        3L -> "PC Paintbrush 2.8 (without palette)"
        4L -> "PC Paintbrush for Windows"
        5L -> "PC Paintbrush 3.0+"
        else -> null
    }
}
