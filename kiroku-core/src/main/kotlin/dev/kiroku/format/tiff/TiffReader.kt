package dev.kiroku.format.tiff

import dev.kiroku.format.LimitExceededException
import dev.kiroku.format.NativeContext
import dev.kiroku.format.icc.IccParser
import dev.kiroku.format.iptc.IptcParser
import dev.kiroku.format.iptc.PhotoshopParser
import dev.kiroku.format.jpeg.JpegParser
import dev.kiroku.format.xmp.XmpParser
import dev.kiroku.io.ByteSource
import dev.kiroku.io.Bytes
import dev.kiroku.model.ByteOrderMark
import dev.kiroku.model.DiagnosticCode
import dev.kiroku.model.DirectoryKind
import dev.kiroku.model.EmbeddedImageInfo
import dev.kiroku.model.EmbeddedImageKind
import dev.kiroku.model.NativeType
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.Rational
import dev.kiroku.model.Severity
import dev.kiroku.model.TypeSystem
import java.io.EOFException

/** The TIFF header variants Kiroku recognises. */
internal enum class TiffVariant { STANDARD, BIGTIFF, ORF, RW2, CR2 }

internal data class TiffHeader(val bigEndian: Boolean, val variant: TiffVariant, val firstIfdOffset: Long) {
    val order: ByteOrderMark get() = if (bigEndian) ByteOrderMark.BIG_ENDIAN else ByteOrderMark.LITTLE_ENDIAN
    val big: Boolean get() = variant == TiffVariant.BIGTIFF
}

/** One decoded IFD entry, kept so that summaries and sub-parsers can use it. */
internal data class IfdEntry(
    val tag: Int,
    val type: TiffType?,
    val typeCode: Int,
    val count: Long,
    /** The data offset in the reader's source coordinates. */
    val dataOffset: Long,
    val dataSize: Long,
    val inBounds: Boolean,
    val value: NormalizedValue,
    /** The value field as an unsigned integer (offset or inline value). */
    val rawValueField: Long,
)

/** Image facts from one IFD, used for embedded-image listing, RAW classification and dimensions. */
internal data class IfdImage(
    val group: String,
    val width: Long?,
    val height: Long?,
    val compression: Long?,
    val photometric: Long?,
    val subfileType: Long?,
    val bitsPerSample: Long?,
    val jpegOffset: Long?,
    val jpegLength: Long?,
)

internal data class TiffSummary(val header: TiffHeader, val make: String?, val model: String?, val dngVersion: Boolean, val images: List<IfdImage>)

/**
 * A bounded, byte-exact TIFF IFD walker. It works on plain TIFF, BigTIFF and the RAW variants
 * (NEF, CR2, ARW, SRW, DNG, PEF, ORF, RW2/RWL), and on EXIF blocks embedded in JPEG, PNG, WebP and HEIF.
 *
 * Every entry becomes a [dev.kiroku.model.RawObservation] with the numeric tag ID, TIFF type code and
 * count, the decoded value, and the original bytes (offset, length, byte order). Unknown tags are kept.
 * Sub-IFDs (EXIF, GPS, Interop, SubIFDs), MakerNotes, XMP, IPTC, Photoshop IRB and ICC are followed.
 */
internal class TiffReader(
    private val ctx: NativeContext,
    private val source: ByteSource,
    private val sourcePrefix: String,
    private val family0: String = "EXIF",
) {
    private val visited = HashSet<Long>()
    private val images = ArrayList<IfdImage>()
    private var make: String? = null
    private var model: String? = null
    private var dng = false
    private var subIfdCounter = 0
    private lateinit var header: TiffHeader

    fun parse(): TiffSummary? {
        val h = readHeader(source) ?: run {
            ctx.warn(DiagnosticCode.MALFORMED, "Invalid TIFF header in $sourcePrefix")
            return null
        }
        header = h
        ctx.collector.directory(
            pathOf("TIFF"),
            DirectoryKind.TIFF_IFD,
            source.absolute(0),
            source.size,
            h.order,
            note = "TIFF header (${h.variant})",
        )
        var offset = h.firstIfdOffset
        var index = 0
        val tagTable = if (h.variant == TiffVariant.RW2) TagTable.PANASONIC_RAW else TagTable.MAIN
        try {
            while (offset != 0L && index < MAX_CHAIN) {
                ctx.checkCancelled()
                val group = "IFD$index"
                val next =
                    readIfd(offset, group, "$sourcePrefix/$group", tagTable = if (index == 0) tagTable else TagTable.MAIN, depth = 0)
                        ?: break
                offset = next
                index++
            }
            if (index >= MAX_CHAIN) ctx.warn(DiagnosticCode.LIMIT_EXCEEDED, "IFD chain longer than $MAX_CHAIN; truncated")
        } catch (e: LimitExceededException) {
            ctx.warn(DiagnosticCode.LIMIT_EXCEEDED, e.message ?: "limit exceeded", e)
        }
        return TiffSummary(h, make, model, dng, images.toList())
    }

    /**
     * Reads a TIFF block whose first IFD is a known directory rather than IFD0. Canon CR3 stores its metadata this way:
     * `CMT2` is the Exif IFD, `CMT3` the Canon MakerNote IFD and `CMT4` the GPS IFD, each with its own TIFF header.
     * With [makerNoteVendor] set, the IFD is read as that vendor's MakerNote (group `MakerNotes:<vendor>`).
     */
    fun parseAs(group: String, tagTable: TagTable, makerNoteVendor: String? = null): TiffSummary? {
        val h = readHeader(source) ?: run {
            ctx.warn(DiagnosticCode.MALFORMED, "Invalid TIFF header in $sourcePrefix")
            return null
        }
        header = h
        ctx.collector.directory(pathOf("TIFF"), DirectoryKind.TIFF_IFD, source.absolute(0), source.size, h.order, note = "TIFF header ($group)")
        try {
            if (makerNoteVendor != null) {
                ctx.facts["makernote.vendor"] = makerNoteVendor
                readIfd(
                    offset = h.firstIfdOffset,
                    group = makerNoteVendor,
                    path = "$sourcePrefix/$makerNoteVendor",
                    tagTable = TagTable.MAKERNOTE,
                    depth = 0,
                    groupFamily0 = "MakerNotes",
                    makerNoteTags = MakerNoteTags.forVendor(makerNoteVendor),
                    subIfdTags = MakerNoteTags.subIfdTags(makerNoteVendor),
                )
            } else {
                readIfd(h.firstIfdOffset, group, "$sourcePrefix/$group", tagTable, depth = 0)
            }
        } catch (e: LimitExceededException) {
            ctx.warn(DiagnosticCode.LIMIT_EXCEEDED, e.message ?: "limit exceeded", e)
        }
        return TiffSummary(h, make, model, dng, images.toList())
    }

    private fun pathOf(vararg tail: String): List<String> = sourcePrefix.split('/') + tail

    private val PRINT_IM_TAG = 0xC4A5

    private object FujiTags {
        const val FUJI_IFD = 0xF000

        // Names as ExifTool's FujiFilm::IFD table uses them.
        val FUJI_IFD_TAGS =
            mapOf(
                0xF000 to "FujiIFD",
                0xF001 to "RawImageFullWidth",
                0xF002 to "RawImageFullHeight",
                0xF003 to "BitsPerSample",
                0xF007 to "StripOffsets",
                0xF008 to "StripByteCounts",
                0xF00A to "BlackLevel",
                0xF00C to "WB_GRBLevelsStandard",
                0xF00D to "WB_GRBLevelsAuto",
                0xF00E to "WB_GRBLevels",
            )
    }

    /**
     * Reads one IFD at [offset] (in source coordinates). Returns the next-IFD offset, `0` for none,
     * or `null` when the IFD is unreadable.
     *
     * @param base added to out-of-line value offsets (non-zero for some MakerNotes).
     */
    internal fun readIfd(
        offset: Long,
        group: String,
        path: String,
        tagTable: TagTable,
        depth: Int,
        base: Long = 0,
        bigEndian: Boolean = header.bigEndian,
        groupFamily0: String = family0,
        makerNoteTags: Map<Int, String>? = null,
        subIfdTags: Set<Int> = emptySet(),
    ): Long? {
        if (depth > ctx.limits.maxNestingDepth) throw LimitExceededException("IFD nesting depth exceeded")
        val big = header.big && groupFamily0 == family0 && makerNoteTags == null
        val entrySize = if (big) 20 else 12
        val countSize = if (big) 8 else 2
        if (!source.contains(offset, countSize.toLong())) {
            ctx.warn(DiagnosticCode.TRUNCATED, "IFD $group offset $offset is outside the TIFF data")
            return null
        }
        val abs = source.absolute(offset) ?: (offset + 0x4000_0000_0000L)
        if (!visited.add(abs)) {
            ctx.warn(DiagnosticCode.MALFORMED, "IFD loop detected at $group; not re-read")
            return null
        }
        val count = if (big) source.u64(offset, bigEndian) else source.u16(offset, bigEndian).toLong()
        if (count == 0L || count > ctx.limits.maxEntriesPerDirectory) {
            if (count > ctx.limits.maxEntriesPerDirectory) {
                ctx.warn(DiagnosticCode.LIMIT_EXCEEDED, "IFD $group declares $count entries; limit is ${ctx.limits.maxEntriesPerDirectory}")
            }
            if (count == 0L) {
                ctx.collector.directory(
                    path.split('/'),
                    DirectoryKind.TIFF_IFD,
                    source.absolute(offset),
                    countSize.toLong(),
                    orderOf(bigEndian),
                    0,
                )
            }
            return if (count == 0L) 0 else null
        }
        val tableLen = countSize + count * entrySize + (if (big) 8 else 4)
        val available = source.size - offset
        val readable = if (tableLen <= available) count else ((available - countSize) / entrySize).coerceAtLeast(0)
        if (readable < count) ctx.warn(DiagnosticCode.TRUNCATED, "IFD $group is truncated: $readable of $count entries readable")
        val table = source.read(offset, (countSize + readable * entrySize).toInt())
        ctx.collector.directory(
            path.split('/'),
            if (makerNoteTags != null) DirectoryKind.MAKERNOTE else DirectoryKind.TIFF_IFD,
            source.absolute(offset),
            countSize + readable * entrySize,
            orderOf(bigEndian),
            readable.toInt(),
        )
        val groupPath = listOf(groupFamily0, group)
        val entries = ArrayList<IfdEntry>(readable.toInt())
        var invalidTypes = 0
        for (i in 0 until readable.toInt()) {
            val e = countSize + i * entrySize
            val tag = Bytes.u16(table, e, bigEndian)
            val typeCode = Bytes.u16(table, e + 2, bigEndian)
            val cnt = if (big) Bytes.u64(table, e + 4, bigEndian) else Bytes.u32(table, e + 4, bigEndian)
            val valueFieldOffset = offset + e + (if (big) 12 else 8)
            val inlineSize = if (big) 8 else 4
            val rawField = if (big) Bytes.u64(table, e + 12, bigEndian) else Bytes.u32(table, e + 8, bigEndian)
            val type = TiffType.of(typeCode)
            if (type == null) invalidTypes++
            val unitSize = type?.size ?: 1
            val size = if (cnt < 0 || cnt > Int.MAX_VALUE / unitSize) -1L else cnt * unitSize
            val dataOffset =
                when {
                    type == null -> valueFieldOffset
                    size in 0..inlineSize.toLong() -> valueFieldOffset
                    else -> rawField + base
                }
            val dataSize = if (type == null) inlineSize.toLong() else size
            val inBounds = dataSize >= 0 && source.contains(dataOffset, dataSize)
            val name = makerNoteTags?.get(tag)?.let { MakerNoteTags.byCount(it, cnt) } ?: TiffTags.nameFor(tagTable, tag)
            val tagName = name ?: TiffTags.unknownName(tag)
            val value =
                if (!inBounds || type == null) {
                    NormalizedValue.Null
                } else {
                    decode(tag, tagName, type, cnt, dataOffset, dataSize, bigEndian)
                }
            val entry = IfdEntry(tag, type, typeCode, cnt, dataOffset, dataSize, inBounds, value, rawField)
            entries += entry
            val original =
                if (dataSize >= 0) {
                    ctx.original(source, dataOffset, dataSize, orderOf(bigEndian))
                } else {
                    null
                }
            if (!inBounds) {
                ctx.warnOnce(
                    "oob:$group",
                    DiagnosticCode.MALFORMED,
                    "Tag ${TiffTags.unknownName(tag)} in $group points outside the available data",
                )
            }
            ctx.observe(
                groupPath = groupPath,
                tagName = tagName,
                value = value,
                tagId = tag.toLong(),
                original = original,
                nativeType = NativeType(TypeSystem.TIFF, type?.name ?: "UNKNOWN", typeCode, cnt),
                displayValue = display(tagName, value),
                unit = TiffTags.units[tagName],
                known = name != null,
                sourcePath = path,
            )
        }
        if (invalidTypes >
            0
        ) {
            ctx.warnOnce(
                "type:$group",
                DiagnosticCode.MALFORMED,
                "$invalidTypes entries in $group have unknown TIFF types",
                severity = Severity.INFO,
            )
        }

        if (makerNoteTags == null) recordImage(group, entries, groupFamily0)
        if (makerNoteTags == null && !ctx.fast) GeoTiff.decode(ctx, source, entries, bigEndian, path)
        if (group == "IFD0" && groupFamily0 == family0) {
            entries.firstOrNull { it.tag == TiffTags.MAKE }?.value?.let { make = (it as? NormalizedValue.Text)?.value?.trim() }
            entries.firstOrNull { it.tag == TiffTags.MODEL }?.value?.let { model = (it as? NormalizedValue.Text)?.value?.trim() }
            make?.let { ctx.facts.putIfAbsent("make", it) }
            if (entries.any { it.tag == TiffTags.DNG_VERSION }) dng = true
        }

        // Follow pointers once the whole IFD is read, so facts such as Make are known.
        for (entry in entries) {
            if (!entry.inBounds) continue
            followPointer(entry, group, path, depth, base, bigEndian, groupFamily0, makerNoteTags, subIfdTags)
        }

        // MakerNote IFDs have no meaningful next-IFD pointer (vendors often leave garbage there).
        if (makerNoteTags != null) return 0
        val nextOff = offset + countSize + readable * entrySize
        if (readable < count || !source.contains(nextOff, if (big) 8 else 4)) return 0
        val next = if (big) source.u64(nextOff, bigEndian) else source.u32(nextOff, bigEndian)
        if (next == 0L) return 0
        val nextInSource = next
        if (!source.contains(nextInSource, 2)) {
            ctx.warn(DiagnosticCode.MALFORMED, "Next-IFD pointer after $group is out of bounds", severity = Severity.INFO)
            return 0
        }
        return nextInSource
    }

    private fun followPointer(
        entry: IfdEntry,
        group: String,
        path: String,
        depth: Int,
        base: Long,
        bigEndian: Boolean,
        groupFamily0: String,
        makerNoteTags: Map<Int, String>?,
        subIfdTags: Set<Int>,
    ) {
        val inMakerNote = makerNoteTags != null
        if (entry.tag == PRINT_IM_TAG && !inMakerNote || inMakerNote && makerNoteTags[entry.tag] == "PrintIM") {
            PrintIm.parse(ctx, source, entry.dataOffset, entry.dataSize, bigEndian, "$path/PrintIM")
            return
        }
        if (inMakerNote) {
            // MakerNote sub-IFDs: either type IFD, or vendor tags known to hold offsets.
            if (entry.type == TiffType.IFD || entry.type == TiffType.IFD8 || entry.tag in subIfdTags) {
                val target =
                    when {
                        entry.type == TiffType.UNDEFINED -> entry.dataOffset

                        entry.type == TiffType.IFD || entry.type == TiffType.LONG || entry.type == TiffType.IFD8 ->
                            entry.rawValueField +
                                base

                        else -> return
                    }
                val vendor = ctx.facts["makernote.vendor"] ?: group
                val subName = makerNoteTags[entry.tag] ?: TiffTags.unknownName(entry.tag)
                val subTags = MakerNoteTags.forSubIfd(vendor, subName)
                tryReadSubIfd(target, vendor + subName, "$path/$subName", depth, base, bigEndian, groupFamily0, subTags)
            }
            return
        }
        when (entry.tag) {
            TiffTags.EXIF_IFD ->
                if (group != "ExifIFD") {
                    pointerTargets(entry).firstOrNull()?.let { readIfd(it, "ExifIFD", "$path/ExifIFD", TagTable.MAIN, depth + 1) }
                }

            TiffTags.GPS_IFD -> pointerTargets(entry).firstOrNull()?.let { readIfd(it, "GPS", "$path/GPS", TagTable.GPS, depth + 1) }

            TiffTags.INTEROP_IFD -> pointerTargets(entry).firstOrNull()?.let {
                readIfd(
                    it,
                    "InteropIFD",
                    "$path/InteropIFD",
                    TagTable.INTEROP,
                    depth + 1,
                )
            }

            TiffTags.SUB_IFDS ->
                for (target in pointerTargets(entry)) {
                    val n = subIfdCounter++
                    val name = if (n == 0) "SubIFD" else "SubIFD$n"
                    readIfd(target, name, "$path/$name", TagTable.MAIN, depth + 1)
                }

            TiffTags.MAKER_NOTE ->
                if (!ctx.fast) {
                    MakerNoteParser(
                        ctx,
                        this,
                        source,
                        header,
                    ).parse(entry.dataOffset, entry.dataSize, "$path/MakerNote", depth + 1, bigEndian)
                }

            TiffTags.DNG_PRIVATE_DATA ->
                if (!ctx.fast) {
                    MakerNoteParser(ctx, this, source, header).parseDngPrivateData(
                        entry.dataOffset,
                        entry.dataSize,
                        "$path/DNGPrivateData",
                        depth + 1,
                    )
                }

            TiffTags.XMP -> if (group.startsWith("IFD") ||
                group.startsWith("SubIFD")
            ) {
                XmpParser.parse(ctx, source, entry.dataOffset, entry.dataSize, "$path/XMP")
            }

            TiffTags.IPTC -> IptcParser.parse(ctx, source, entry.dataOffset, entry.dataSize, "$path/IPTC")

            TiffTags.PHOTOSHOP -> PhotoshopParser.parse(ctx, source, entry.dataOffset, entry.dataSize, "$path/Photoshop")

            TiffTags.ICC_PROFILE -> IccParser.parse(ctx, source, entry.dataOffset, entry.dataSize, "$path/ICC_Profile")

            // Fujifilm RAF raw data: IFD0 points to the FujiIFD (raw size, bit depth, black and white levels).
            FujiTags.FUJI_IFD ->
                if (family0 == "RAF" && group == "IFD0") {
                    pointerTargets(entry).firstOrNull()?.let {
                        readIfd(it, "FujiIFD", "$path/FujiIFD", TagTable.MAIN, depth + 1, makerNoteTags = FujiTags.FUJI_IFD_TAGS)
                    }
                }

            TiffTags.PANASONIC_JPG_FROM_RAW ->
                if (header.variant == TiffVariant.RW2 && group == "IFD0" && !ctx.fast && entry.dataSize > 4) {
                    JpegParser.parseEmbedded(ctx, source.slice(entry.dataOffset, entry.dataSize), "$path/JpgFromRaw", "JpgFromRaw")
                    ctx.collector.embeddedImage(
                        EmbeddedImageInfo(
                            0,
                            "",
                            EmbeddedImageKind.PREVIEW,
                            listOf(family0, group),
                            mimeType = "image/jpeg",
                            fileOffset = source.absolute(entry.dataOffset),
                            length = entry.dataSize,
                        ),
                    )
                }
        }
    }

    private fun tryReadSubIfd(target: Long, group: String, path: String, depth: Int, base: Long, bigEndian: Boolean, family: String, tags: Map<Int, String>) {
        if (!source.contains(target, 2)) return
        if (!MakerNoteParser.looksLikeIfd(source, target, bigEndian, base, ctx.limits.maxEntriesPerDirectory)) return
        readIfd(target, group, path, TagTable.MAKERNOTE, depth + 1, base, bigEndian, family, tags)
    }

    private fun pointerTargets(entry: IfdEntry): List<Long> {
        val v = entry.value
        val raw =
            when (v) {
                is NormalizedValue.Integer -> listOf(v.value)
                is NormalizedValue.ListValue -> v.items.mapNotNull { (it as? NormalizedValue.Integer)?.value }
                is NormalizedValue.Binary -> if (entry.type == TiffType.UNDEFINED) listOf(entry.dataOffset) else emptyList()
                else -> emptyList()
            }
        return raw.filter { it > 0 && source.contains(it, 2) }
    }

    private fun recordImage(group: String, entries: List<IfdEntry>, groupFamily0: String) {
        if (group == "ExifIFD" || group == "GPS" || group == "InteropIFD") return
        fun long(tag: Int): Long? = entries.firstOrNull { it.tag == tag }?.value?.let {
            when (it) {
                is NormalizedValue.Integer -> it.value
                is NormalizedValue.ListValue -> (it.items.firstOrNull() as? NormalizedValue.Integer)?.value
                else -> null
            }
        }
        val width = long(TiffTags.IMAGE_WIDTH)
        val height = long(TiffTags.IMAGE_HEIGHT)
        val jpegOffset = long(TiffTags.JPEG_OFFSET)
        val jpegLength = long(TiffTags.JPEG_LENGTH)
        if (width == null && jpegOffset == null) return
        val img =
            IfdImage(
                group, width, height, long(TiffTags.COMPRESSION), long(TiffTags.PHOTOMETRIC),
                long(TiffTags.NEW_SUBFILE_TYPE), long(0x0102), jpegOffset, jpegLength,
            )
        images += img
        val reduced = (img.subfileType ?: 0) and 1L == 1L
        val cfa = img.photometric == 32803L || img.photometric == 34892L || entries.any { it.tag == 0x828D || it.tag == 0x828E }
        val jpegCompressed = img.compression == 6L || img.compression == 7L || jpegOffset != null
        val kind =
            when {
                group == "IFD1" && sourcePrefix.contains("APP1") -> EmbeddedImageKind.THUMBNAIL

                reduced -> EmbeddedImageKind.PREVIEW

                cfa -> EmbeddedImageKind.PRIMARY

                // CR2: IFD0-IFD2 are previews; the raw image (IFD3) has no size tags.
                header.variant == TiffVariant.CR2 -> EmbeddedImageKind.PREVIEW

                group == "IFD0" -> EmbeddedImageKind.PRIMARY

                group.startsWith(
                    "SubIFD",
                ) && img.subfileType == 0L && !jpegCompressed && (img.bitsPerSample ?: 0) >= 10 -> EmbeddedImageKind.PRIMARY

                group.startsWith("IFD") -> EmbeddedImageKind.PAGE

                else -> EmbeddedImageKind.AUXILIARY
            }
        val offset = jpegOffset?.let { source.absolute(it) }
        ctx.collector.embeddedImage(
            EmbeddedImageInfo(
                index = 0, backend = "", kind = kind, groupPath = listOf(groupFamily0, group),
                width = width?.toInt(), height = height?.toInt(),
                compression = img.compression?.let { TiffTags.enums["Compression"]?.get(it) ?: it.toString() },
                mimeType = if (img.compression == 6L || img.compression == 7L || jpegOffset != null) "image/jpeg" else null,
                fileOffset = offset, length = jpegLength,
            ),
        )
    }

    private fun decode(tag: Int, tagName: String, type: TiffType, count: Long, offset: Long, size: Long, bigEndian: Boolean): NormalizedValue {
        if (count == 0L) return NormalizedValue.ListValue(emptyList())
        return when (type) {
            TiffType.ASCII, TiffType.UTF8 -> {
                if (size > ctx.limits.maxStringLength) return NormalizedValue.Binary(size)
                val b = source.read(offset, size.toInt())
                // Casio stores FirmwareDate as NUL-separated parts ("0302", "2809", "45"); join them as ExifTool does.
                // Every other ASCII value ends at its first NUL.
                if (tagName == "FirmwareDate" && ctx.facts["makernote.vendor"] == "Casio") {
                    NormalizedValue.Text(String(b, Charsets.ISO_8859_1).replace("\u0000", ""))
                } else {
                    text(b)
                }
            }

            TiffType.UNDEFINED -> decodeUndefined(tag, tagName, offset, size, bigEndian)

            TiffType.BYTE, TiffType.SBYTE -> {
                if (tag == TiffTags.XMP || tag in XP_TAGS) return decodeUndefined(tag, tagName, offset, size, bigEndian)
                if (count > MAX_DECODED_COMPONENTS) return NormalizedValue.Binary(size)
                val b = source.read(offset, size.toInt())
                NormalizedValue.list(b.map { NormalizedValue.Integer(if (type == TiffType.SBYTE) it.toLong() else (it.toLong() and 0xFF)) })
            }

            else -> {
                if (count > MAX_DECODED_COMPONENTS) return NormalizedValue.Binary(size)
                val b = source.read(offset, size.toInt())
                NormalizedValue.list(numeric(type, b, count.toInt(), bigEndian))
            }
        }
    }

    private fun decodeUndefined(tag: Int, tagName: String, offset: Long, size: Long, bigEndian: Boolean): NormalizedValue {
        if (size > ctx.limits.maxStringLength) return NormalizedValue.Binary(size)
        return when {
            // ExifVersion, FlashpixVersion and InteropVersion are four ASCII digits. Match by name: vendor tags reuse these
            // IDs (Panasonic 0x0002 is FirmwareVersion, four binary bytes).
            tagName in VERSION_TAG_NAMES && size == 4L -> NormalizedValue.Text(source.ascii(offset, 4))

            // Short undefined values that vendors use for version numbers and similar byte tuples.
            tagName.endsWith("Version") && size in 2L..8L -> {
                val b = source.read(offset, size.toInt())
                if (b.all { it in 0x20..0x7E }) {
                    NormalizedValue.Text(String(b, Charsets.ISO_8859_1))
                } else {
                    NormalizedValue.list(b.map { NormalizedValue.Integer(it.toLong() and 0xFF) })
                }
            }

            tag == 0x9286 || tag == 0x001B && tagName == "GPSProcessingMethod" || tag == 0x001C && tagName == "GPSAreaInformation" ->
                decodeCharsetText(source.read(offset, size.toInt()), bigEndian) ?: NormalizedValue.Binary(size)

            tag in XP_TAGS -> {
                val b = source.read(offset, size.toInt())
                NormalizedValue.Text(String(b, Charsets.UTF_16LE).trimEnd('\u0000'))
            }

            size == 1L -> NormalizedValue.Integer(source.u8(offset).toLong())

            else -> NormalizedValue.Binary(size)
        }
    }

    private fun numeric(type: TiffType, b: ByteArray, count: Int, be: Boolean): List<NormalizedValue> {
        val out = ArrayList<NormalizedValue>(count)
        for (i in 0 until count) {
            out +=
                when (type) {
                    TiffType.SHORT -> NormalizedValue.Integer(Bytes.u16(b, i * 2, be).toLong())
                    TiffType.SSHORT -> NormalizedValue.Integer(Bytes.s16(b, i * 2, be).toLong())
                    TiffType.LONG, TiffType.IFD -> NormalizedValue.Integer(Bytes.u32(b, i * 4, be))
                    TiffType.SLONG -> NormalizedValue.Integer(Bytes.s32(b, i * 4, be))
                    TiffType.RATIONAL -> NormalizedValue.RationalValue(Rational(Bytes.u32(b, i * 8, be), Bytes.u32(b, i * 8 + 4, be)))
                    TiffType.SRATIONAL -> NormalizedValue.RationalValue(Rational(Bytes.s32(b, i * 8, be), Bytes.s32(b, i * 8 + 4, be)))
                    TiffType.FLOAT -> NormalizedValue.Decimal(java.lang.Float.intBitsToFloat(Bytes.u32(b, i * 4, be).toInt()).toDouble())
                    TiffType.DOUBLE -> NormalizedValue.Decimal(java.lang.Double.longBitsToDouble(Bytes.u64(b, i * 8, be)))
                    TiffType.LONG8, TiffType.SLONG8, TiffType.IFD8 -> NormalizedValue.Integer(Bytes.u64(b, i * 8, be))
                    else -> NormalizedValue.Null
                }
        }
        return out
    }

    private fun text(bytes: ByteArray): NormalizedValue {
        var end = bytes.size
        while (end > 0 && bytes[end - 1] == 0.toByte()) end--
        val s = Bytes.decodeText(bytes, 0, end)
        // EXIF allows several NUL-separated strings. Writers also leave junk after the terminator. Later parts
        // are kept as list items only when they are clean printable text. The original bytes are always recorded.
        val parts = s.split('\u0000')
        val extra = parts.drop(1)
        if (extra.isEmpty() || extra.any { p -> p.isEmpty() || p.any { it.code < 0x20 || it.code > 0x7E } }) {
            return NormalizedValue.Text(ctx.clip(parts[0]))
        }
        return NormalizedValue.ListValue(parts.map { NormalizedValue.Text(ctx.clip(it)) })
    }

    private fun display(tagName: String, value: NormalizedValue): String? {
        val table = TiffTags.enums[tagName] ?: return null
        val code = (value as? NormalizedValue.Integer)?.value ?: return null
        return table[code]
    }

    companion object {
        private const val MAX_CHAIN = 512
        private const val MAX_DECODED_COMPONENTS = 4096
        private val VERSION_TAG_NAMES = setOf("ExifVersion", "FlashpixVersion", "InteropVersion")
        private val XP_TAGS = setOf(0x9C9B, 0x9C9C, 0x9C9D, 0x9C9E, 0x9C9F)

        fun orderOf(bigEndian: Boolean): ByteOrderMark = if (bigEndian) ByteOrderMark.BIG_ENDIAN else ByteOrderMark.LITTLE_ENDIAN

        /** Parses a TIFF header (all supported variants), or returns `null`. */
        fun readHeader(source: ByteSource): TiffHeader? {
            if (source.size < 8) return null
            val h = source.readAtMost(0, 16)
            val be =
                when {
                    h[0] == 'I'.code.toByte() && h[1] == 'I'.code.toByte() -> false
                    h[0] == 'M'.code.toByte() && h[1] == 'M'.code.toByte() -> true
                    else -> return null
                }
            val magic = Bytes.u16(h, 2, be)
            return try {
                when (magic) {
                    42 -> {
                        val isCr2 = h.size >= 10 && h[8] == 'C'.code.toByte() && h[9] == 'R'.code.toByte()
                        TiffHeader(be, if (isCr2) TiffVariant.CR2 else TiffVariant.STANDARD, Bytes.u32(h, 4, be))
                    }

                    43 -> if (h.size >= 16 && Bytes.u16(h, 4, be) == 8) TiffHeader(be, TiffVariant.BIGTIFF, Bytes.u64(h, 8, be)) else null

                    0x4F52, 0x5352 -> TiffHeader(be, TiffVariant.ORF, Bytes.u32(h, 4, be))

                    0x0055 -> TiffHeader(be, TiffVariant.RW2, Bytes.u32(h, 4, be))

                    else -> null
                }
            } catch (_: EOFException) {
                null
            }
        }

        /**
         * Decodes EXIF "charset-prefixed" text (UserComment, GPSProcessingMethod): an 8-byte code
         * (`ASCII`, `UNICODE`, `JIS`, or 8 NULs) followed by the text.
         */
        fun decodeCharsetText(b: ByteArray, bigEndian: Boolean): NormalizedValue? {
            if (b.size < 8) return null
            val code = String(b, 0, 8, Charsets.ISO_8859_1).trimEnd('\u0000', ' ')
            val body = b.copyOfRange(8, b.size)
            val s =
                when (code) {
                    "ASCII" -> Bytes.decodeText(body)

                    "UNICODE" -> {
                        when {
                            body.size >= 2 && body[0] == 0xFE.toByte() && body[1] == 0xFF.toByte() -> String(
                                body,
                                2,
                                body.size - 2,
                                Charsets.UTF_16BE,
                            )

                            body.size >= 2 && body[0] == 0xFF.toByte() && body[1] == 0xFE.toByte() -> String(
                                body,
                                2,
                                body.size - 2,
                                Charsets.UTF_16LE,
                            )

                            else -> String(body, if (bigEndian) Charsets.UTF_16BE else Charsets.UTF_16LE)
                        }
                    }

                    "JIS" -> String(body, charset("ISO-2022-JP"))

                    "" -> Bytes.decodeText(body)

                    else -> return null
                }
            return NormalizedValue.Text(s.trimEnd('\u0000', ' '))
        }
    }
}
