package dev.kiroku.format.tiff

import dev.kiroku.format.NativeContext
import dev.kiroku.io.ByteSource
import dev.kiroku.model.NativeType
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.TypeSystem

/**
 * Binary records in Panasonic RW2/RWL IFD0, read as arrays of 16-bit values in the file's byte order:
 * `WBInfo2` (0x0027: an entry count, then per entry a light source and R/G/B levels) and `DistortionInfo` (0x0119).
 * Names follow ExifTool's PanasonicRaw tables; values were checked against ExifTool and metadata-extractor.
 */
internal object PanasonicRaw {
    private const val WB_INFO2 = 0x0027
    private const val DISTORTION_INFO = 0x0119
    private const val MAX_WB_ENTRIES = 64

    fun decode(ctx: NativeContext, s: ByteSource, entries: List<IfdEntry>, bigEndian: Boolean, path: String) {
        entries.firstOrNull { it.tag == WB_INFO2 && it.inBounds }?.let { wbInfo2(ctx, s, it, bigEndian, "$path/WBInfo2") }
        entries.firstOrNull { it.tag == DISTORTION_INFO && it.inBounds }?.let { distortion(ctx, s, it, bigEndian, "$path/DistortionInfo") }
    }

    private fun wbInfo2(ctx: NativeContext, s: ByteSource, e: IfdEntry, be: Boolean, path: String) {
        val words = (e.dataSize / 2).toInt()
        if (words < 1) return
        val g = listOf("PanasonicRaw", "WBInfo2")
        val count = s.u16(e.dataOffset, be)
        obs(ctx, s, e, be, g, path, 0, "NumWBEntries", 1, signed = false)
        val fit = minOf(count, MAX_WB_ENTRIES, (words - 1) / 4)
        val sources = TiffTags.enums["LightSource"].orEmpty()
        for (k in 0 until fit) {
            val at = 1 + 4 * k
            val type = s.u16(e.dataOffset + at * 2L, be).toLong()
            obs(ctx, s, e, be, g, path, at, "WBType${k + 1}", 1, signed = false, display = sources[type])
            obs(ctx, s, e, be, g, path, at + 1, "WB_RGBLevels${k + 1}", 3, signed = false)
        }
    }

    private fun distortion(ctx: NativeContext, s: ByteSource, e: IfdEntry, be: Boolean, path: String) {
        val words = (e.dataSize / 2).toInt()
        val g = listOf("PanasonicRaw", "DistortionInfo")
        val fields =
            listOf(
                2 to "DistortionParam02",
                4 to "DistortionParam04",
                5 to "DistortionScale",
                7 to "DistortionCorrection",
                8 to "DistortionParam08",
                9 to "DistortionParam09",
                11 to "DistortionParam11",
                12 to "DistortionN",
            )
        for ((i, name) in fields) {
            if (i >= words) continue
            if (name == "DistortionCorrection") {
                // The low 4 bits only.
                val v = s.u16(e.dataOffset + i * 2L, be) and 0x0F
                ctx.observe(
                    g,
                    name,
                    NormalizedValue.Integer(v.toLong()),
                    tagId = i.toLong(),
                    original = ctx.original(s, e.dataOffset + i * 2L, 2, TiffReader.orderOf(be)),
                    nativeType = NativeType(TypeSystem.TIFF, "int16s", null, 1),
                    displayValue = when (v) {
                        0 -> "Off"
                        1 -> "On"
                        else -> null
                    },
                    sourcePath = path,
                )
            } else {
                obs(ctx, s, e, be, g, path, i, name, 1, signed = true)
            }
        }
    }

    private fun obs(
        ctx: NativeContext,
        s: ByteSource,
        e: IfdEntry,
        be: Boolean,
        g: List<String>,
        path: String,
        index: Int,
        name: String,
        n: Int,
        signed: Boolean,
        display: String? = null,
    ) {
        val at = e.dataOffset + index * 2L
        val values =
            (0 until n).map {
                val raw = s.u16(at + it * 2L, be)
                NormalizedValue.Integer(if (signed) raw.toShort().toLong() else raw.toLong())
            }
        ctx.observe(
            g,
            name,
            NormalizedValue.list(values),
            tagId = index.toLong(),
            original = ctx.original(s, at, n * 2L, TiffReader.orderOf(be)),
            nativeType = NativeType(TypeSystem.TIFF, if (signed) "int16s" else "int16u", null, n.toLong()),
            displayValue = display,
            sourcePath = path,
        )
    }
}
