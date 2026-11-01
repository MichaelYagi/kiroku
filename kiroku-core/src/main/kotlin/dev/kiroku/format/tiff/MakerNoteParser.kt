package dev.kiroku.format.tiff

import dev.kiroku.format.NativeContext
import dev.kiroku.io.ByteSource
import dev.kiroku.io.Bytes
import dev.kiroku.model.DiagnosticCode
import dev.kiroku.model.DirectoryKind
import dev.kiroku.model.Severity

/**
 * Detects the MakerNote vendor layout and parses its IFD entries byte-exactly.
 *
 * MakerNotes are proprietary, and their layout is inferred from header signatures and the `Make` tag.
 * Each candidate layout is checked structurally (entry types, counts, offsets in bounds) before it is
 * interpreted. If no candidate passes, the MakerNote is kept only as the byte-exact blob that the
 * enclosing IFD already recorded, and a `MAKERNOTE_UNPARSED` diagnostic is emitted.
 */
internal class MakerNoteParser(
    private val ctx: NativeContext,
    private val reader: TiffReader,
    private val source: ByteSource,
    private val header: TiffHeader,
) {
    /** [vendor] names the group (`MakerNotes:<vendor>`); [table] selects the tag table when a vendor has several layouts. */
    internal data class Layout(val vendor: String, val ifdOffset: Long, val base: Long, val bigEndian: Boolean, val table: String = vendor)

    fun parse(offset: Long, length: Long, path: String, depth: Int, parentBigEndian: Boolean, tiffBase: Long = 0) {
        if (length < 8 || !source.contains(offset, length)) return
        val make = ctx.facts["make"].orEmpty()
        val head = source.readAtMost(offset, 32)
        val candidates = candidates(head, offset, make, parentBigEndian, tiffBase)
        val chosen = candidates.firstOrNull {
            looksLikeIfd(
                source,
                it.ifdOffset,
                it.bigEndian,
                it.base,
                ctx.limits.maxEntriesPerDirectory,
                offset + length,
            )
        }
        if (chosen == null) {
            ctx.collector.directory(
                path.split('/'),
                DirectoryKind.MAKERNOTE,
                source.absolute(offset),
                length,
                parsed = false,
                note = "Unrecognised MakerNote layout (make='${make.take(32)}'); kept as byte-exact blob",
            )
            ctx.warnOnce(
                "makernote",
                DiagnosticCode.MAKERNOTE_UNPARSED,
                "MakerNote layout not recognised for make '${make.take(32)}'; preserved as raw bytes only",
                severity = Severity.INFO,
            )
            return
        }
        ctx.collector.directory(
            path.split('/'),
            DirectoryKind.MAKERNOTE,
            source.absolute(offset),
            length,
            TiffReader.orderOf(chosen.bigEndian),
            note = "MakerNote vendor ${chosen.vendor}",
        )
        ctx.facts["makernote.vendor"] = chosen.vendor
        reader.readIfd(
            offset = chosen.ifdOffset,
            group = chosen.vendor,
            path = "$path/${chosen.vendor}",
            tagTable = TagTable.MAKERNOTE,
            depth = depth,
            base = chosen.base,
            bigEndian = chosen.bigEndian,
            groupFamily0 = "MakerNotes",
            makerNoteTags = MakerNoteTags.forVendor(chosen.table),
            subIfdTags = MakerNoteTags.subIfdTags(chosen.table),
        )
    }

    /** DNG `DNGPrivateData` containing Adobe `MakN`: the original MakerNote plus its original offset. */
    fun parseDngPrivateData(offset: Long, length: Long, path: String, depth: Int) {
        if (length < 20) return
        val h = source.read(offset, 20)
        if (!Bytes.startsWith(h, Bytes.ascii("Adobe\u0000MakN"))) return
        val count = Bytes.u32(h, 10, true)
        val be =
            when {
                h[14] == 'M'.code.toByte() && h[15] == 'M'.code.toByte() -> true
                h[14] == 'I'.code.toByte() && h[15] == 'I'.code.toByte() -> false
                else -> return
            }
        val originalOffset = Bytes.u32(h, 16, true)
        val mnStart = offset + 20
        val mnLen = minOf(count - 6, length - 20)
        if (mnLen <= 0) return
        parse(mnStart, mnLen, path, depth, be, tiffBase = mnStart - originalOffset)
    }

    private fun candidates(h: ByteArray, mn: Long, make: String, parentBe: Boolean, tiffBase: Long): List<Layout> {
        fun starts(s: String) = Bytes.startsWith(h, Bytes.ascii(s))

        fun orderAt(i: Int): Boolean? = when {
            h.size > i + 1 && h[i] == 'M'.code.toByte() && h[i + 1] == 'M'.code.toByte() -> true
            h.size > i + 1 && h[i] == 'I'.code.toByte() && h[i + 1] == 'I'.code.toByte() -> false
            else -> null
        }
        val m = make.uppercase()
        val out = ArrayList<Layout>()
        when {
            starts("Nikon\u0000\u0002") -> {
                val be = orderAt(10) ?: parentBe
                val ifd = if (h.size >= 18) Bytes.u32(h, 14, be) else 8
                out += Layout("Nikon", mn + 10 + ifd, mn + 10, be)
            }

            starts("Nikon\u0000\u0001") -> out += Layout("Nikon", mn + 8, tiffBase, parentBe)

            starts("OLYMPUS\u0000") -> out += Layout("Olympus", mn + 12, mn, orderAt(8) ?: parentBe)

            starts("OM SYSTEM\u0000") -> out += Layout("Olympus", mn + 16, mn, orderAt(12) ?: parentBe)

            starts("OLYMP\u0000") || starts("EPSON\u0000") || starts("CAMER\u0000") || starts("MINOL\u0000") ->
                out += Layout("Olympus", mn + 8, tiffBase, parentBe)

            starts("SONY DSC ") || starts("SONY CAM ") || starts("SONY MOBILE") -> out += Layout("Sony", mn + 12, tiffBase, parentBe)

            starts("Panasonic\u0000") -> out += Layout("Panasonic", mn + 12, tiffBase, parentBe)

            // Leica X1/X2/X Vario ("Leica5" in ExifTool, LeicaType5 in metadata-extractor): "LEICA\0\x05\0", then an IFD.
            starts("LEICA\u0000\u0005\u0000") -> {
                // Value offsets are relative to the start of the MakerNote.
                out += Layout("Leica", mn + 8, mn, parentBe, "LeicaType5")
            }

            starts("LEICA\u0000") -> {
                out += Layout("Leica", mn + 8, tiffBase, parentBe)
                out += Layout("Leica", mn + 8, mn, parentBe)
            }

            starts("FUJIFILM") || starts("GENERALE") -> {
                val ifd = if (h.size >= 12) Bytes.u32(h, 8, false) else 12
                out += Layout("FujiFilm", mn + ifd, mn, false)
            }

            starts("AOC\u0000") -> out += Layout("Pentax", mn + 6, tiffBase, orderAt(4) ?: parentBe)

            starts("PENTAX \u0000") -> out += Layout("Pentax", mn + 10, mn, orderAt(8) ?: parentBe)

            starts("Apple iOS\u0000") -> out += Layout("Apple", mn + 14, mn, orderAt(12) ?: true)

            starts("QVC\u0000") -> out += Layout("Casio", mn + 6, tiffBase, parentBe)

            // Ricoh ("RICOH\0\0\0" or "Ricoh" + 3 bytes) and Sanyo ("SANYO\0\x01\0"): an 8-byte header, then a TIFF-relative IFD.
            starts("RICOH\u0000") || starts("Ricoh") -> out += Layout("Ricoh", mn + 8, tiffBase, parentBe)

            starts("SANYO\u0000") -> out += Layout("Sanyo", mn + 8, tiffBase, parentBe)

            starts("SIGMA\u0000") || starts("FOVEON\u0000") -> out += Layout("Sigma", mn + 10, tiffBase, parentBe)

            starts("Ricoh") || starts("RICOH") -> out += Layout("Ricoh", mn + 8, tiffBase, parentBe)

            starts("SAMSUNG") -> out += Layout("Samsung", mn + 12, tiffBase, parentBe)
        }
        // Headerless layouts, by Make.
        val vendor =
            when {
                m.startsWith("CANON") -> "Canon"
                m.startsWith("NIKON") -> "Nikon"
                m.startsWith("SONY") -> "Sony"
                m.startsWith("SAMSUNG") -> "Samsung"
                m.startsWith("PANASONIC") -> "Panasonic"
                m.startsWith("LEICA") -> "Leica"
                m.startsWith("PENTAX") || m.startsWith("RICOH IMAGING") || m.startsWith("ASAHI") -> "Pentax"
                m.startsWith("OLYMPUS") || m.startsWith("OM DIGITAL") -> "Olympus"
                m.startsWith("FUJI") -> "FujiFilm"
                m.startsWith("CASIO") -> "Casio"
                m.startsWith("KODAK") -> "Kodak"
                m.startsWith("APPLE") -> "Apple"
                m.startsWith("DJI") -> "DJI"
                else -> "Unknown"
            }
        if (out.isEmpty()) {
            // Casio's header-less notes are the older "type 1" layout; "QVC" notes (type 2) are matched above.
            val table = if (vendor == "Casio") "CasioType1" else vendor
            if (vendor == "Samsung") out += Layout(vendor, mn, mn, parentBe, table)
            out += Layout(vendor, mn, tiffBase, parentBe, table)
            if (vendor != "Samsung") out += Layout(vendor, mn, mn, parentBe, table)
        }
        return out
    }

    companion object {
        /** Structural check: entry count, type codes and value offsets must be plausible. */
        fun looksLikeIfd(
            source: ByteSource,
            ifd: Long,
            be: Boolean,
            base: Long,
            maxEntries: Int,
            @Suppress("UNUSED_PARAMETER") limit: Long = source.size,
        ): Boolean {
            if (!source.contains(ifd, 2)) return false
            val count = source.u16(ifd, be)
            if (count == 0 || count > minOf(maxEntries, 2000)) return false
            val tableLen = 2L + count * 12L
            if (!source.contains(ifd, tableLen)) return false
            val t = source.read(ifd, tableLen.toInt())
            var validType = 0
            var validOffset = 0
            var outOfLine = 0
            for (i in 0 until count) {
                val e = 2 + i * 12
                val type = TiffType.of(Bytes.u16(t, e + 2, be)) ?: continue
                validType++
                val cnt = Bytes.u32(t, e + 4, be)
                if (cnt > Int.MAX_VALUE / type.size) continue
                val size = cnt * type.size
                if (size > 4) {
                    outOfLine++
                    if (source.contains(Bytes.u32(t, e + 8, be) + base, size)) validOffset++
                }
            }
            if (validType * 10 < count * 9) return false
            return outOfLine == 0 || validOffset * 4 >= outOfLine * 3
        }
    }
}
