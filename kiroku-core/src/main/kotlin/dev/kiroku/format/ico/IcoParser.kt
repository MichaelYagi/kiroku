package dev.kiroku.format.ico

import dev.kiroku.format.NativeContext
import dev.kiroku.io.ByteSource
import dev.kiroku.model.ByteOrderMark
import dev.kiroku.model.DiagnosticCode
import dev.kiroku.model.DirectoryKind
import dev.kiroku.model.EmbeddedImageInfo
import dev.kiroku.model.EmbeddedImageKind
import dev.kiroku.model.NativeType
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.TypeSystem

/**
 * Windows icons (.ico) and cursors (.cur): a 6-byte header and one 16-byte directory entry per image. Each image is a
 * BMP DIB or a PNG; they are listed, not decoded. Names follow ExifTool (cursors have a hotspot instead of planes/depth).
 */
internal object IcoParser {
    private val GROUP = listOf("File", "File")
    private const val MAX_IMAGES = 256

    /** Reserved 0, type 1 (icon) or 2 (cursor), 1..256 images, and every entry inside the file. */
    fun looksLikeIco(s: ByteSource): Boolean {
        if (s.size < 22 || s.u16(0, false) != 0) return false
        val type = s.u16(2, false)
        val count = s.u16(4, false)
        if (type !in 1..2 || count !in 1..MAX_IMAGES || !s.contains(6, count * 16L)) return false
        return (0 until count).all { i ->
            val e = 6L + i * 16
            val size = s.u32(e + 8, false)
            val offset = s.u32(e + 12, false)
            s.u8(e + 3) == 0 && size >= 12 && offset >= 6 + count * 16L && s.contains(offset, size)
        }
    }

    fun parse(ctx: NativeContext, s: ByteSource) {
        if (s.size < 6) {
            ctx.warn(DiagnosticCode.TRUNCATED, "ICO shorter than its header")
            return
        }
        val cursor = s.u16(2, false) == 2
        val count = s.u16(4, false).coerceAtMost(MAX_IMAGES)
        ctx.collector.directory(listOf("ICO", "Directory"), DirectoryKind.FILE, 0, 6 + count * 16L, ByteOrderMark.LITTLE_ENDIAN, count)
        fun o(name: String, v: Long, off: Long, n: Long) = ctx.observe(
            GROUP,
            name,
            NormalizedValue.Integer(v),
            tagId = off,
            original = ctx.original(s, off, n, ByteOrderMark.LITTLE_ENDIAN),
            nativeType = NativeType(TypeSystem.ICO, "int${n * 8}u", null, 1),
            sourcePath = "ICO/Directory",
        )
        o("ImageCount", count.toLong(), 4, 2)
        for (i in 0 until count) {
            val e = 6L + i * 16
            if (!s.contains(e, 16)) {
                ctx.warn(DiagnosticCode.TRUNCATED, "ICO directory ends after $i of $count entries")
                return
            }
            // A stored 0 means 256 pixels.
            val w = s.u8(e).let { if (it == 0) 256 else it }
            val h = s.u8(e + 1).let { if (it == 0) 256 else it }
            o("ImageWidth", w.toLong(), e, 1)
            o("ImageHeight", h.toLong(), e + 1, 1)
            o("NumColors", s.u8(e + 2).toLong(), e + 2, 1)
            o(if (cursor) "HotspotX" else "ColorPlanes", s.u16(e + 4, false).toLong(), e + 4, 2)
            o(if (cursor) "HotspotY" else "BitsPerPixel", s.u16(e + 6, false).toLong(), e + 6, 2)
            val size = s.u32(e + 8, false)
            val offset = s.u32(e + 12, false)
            o("ImageLength", size, e + 8, 4)
            if (s.contains(offset, minOf(size, 8))) {
                val png = s.size >= offset + 8 && s.u32(offset, true) == 0x89504E47L
                ctx.collector.embeddedImage(
                    EmbeddedImageInfo(
                        0, "", EmbeddedImageKind.PAGE, GROUP, width = w, height = h, compression = if (png) "PNG" else "BMP",
                        mimeType = if (png) "image/png" else "image/bmp", fileOffset = s.absolute(offset), length = size,
                    ),
                )
            }
        }
    }
}
