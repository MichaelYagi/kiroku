package dev.kiroku.format.tiff

import dev.kiroku.format.NativeContext
import dev.kiroku.io.ByteSource
import dev.kiroku.model.DiagnosticCode
import dev.kiroku.model.DirectoryKind
import dev.kiroku.model.NativeType
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.Severity
import dev.kiroku.model.TypeSystem

/**
 * Epson PRINT Image Matching (`PrintIM`): `PrintIM\0`, a 4-character version, 2 reserved bytes, an entry count, then
 * 6-byte entries (tag u16, value u32) in the byte order of the enclosing TIFF. It appears as TIFF tag 0xC4A5, inside
 * MakerNotes, and in JPEG APP6 `EPPIM` segments. The entry meanings are not public, so entries keep numeric names
 * (`PrintIM_0x000a`), as in ExifTool.
 */
internal object PrintIm {
    private val GROUP = listOf("PrintIM", "PrintIM")
    private const val MAX_ENTRIES = 1024

    fun parse(ctx: NativeContext, s: ByteSource, offset: Long, length: Long, bigEndian: Boolean, path: String) {
        if (length < 16 || !s.contains(offset, length) || s.ascii(offset, 7) != "PrintIM") return
        // Some writers use the other byte order: pick the one whose entry count fits the block.
        val fits = { be: Boolean -> s.u16(offset + 14, be).let { it in 0..MAX_ENTRIES && 16 + it * 6L <= length } }
        val be = if (fits(bigEndian)) {
            bigEndian
        } else if (fits(!bigEndian)) {
            !bigEndian
        } else {
            ctx.warn(DiagnosticCode.MALFORMED, "PrintIM entry count does not fit its block", severity = Severity.INFO)
            return
        }
        val count = s.u16(offset + 14, be)
        ctx.collector.directory(path.split('/'), DirectoryKind.FILE, s.absolute(offset), length, TiffReader.orderOf(be), count)
        ctx.observe(
            GROUP,
            "PrintIMVersion",
            NormalizedValue.Text(s.ascii(offset + 8, 4)),
            original = ctx.original(s, offset + 8, 4),
            nativeType = NativeType(TypeSystem.TIFF, "string[4]"),
            sourcePath = path,
        )
        for (i in 0 until count) {
            val e = offset + 16 + i * 6L
            val tag = s.u16(e, be)
            ctx.observe(
                GROUP,
                "PrintIM_0x%04x".format(tag),
                NormalizedValue.Integer(s.u32(e + 2, be)),
                tagId = tag.toLong(),
                original = ctx.original(s, e + 2, 4, TiffReader.orderOf(be)),
                nativeType = NativeType(TypeSystem.TIFF, "int32u", 4, 1),
                known = false,
                sourcePath = path,
            )
        }
    }
}
