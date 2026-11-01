package dev.kiroku.format.raf

import dev.kiroku.format.NativeContext
import dev.kiroku.format.jpeg.JpegParser
import dev.kiroku.format.tiff.TiffReader
import dev.kiroku.io.ByteSource
import dev.kiroku.model.ByteOrderMark
import dev.kiroku.model.DiagnosticCode
import dev.kiroku.model.DirectoryKind
import dev.kiroku.model.EmbeddedImageInfo
import dev.kiroku.model.EmbeddedImageKind
import dev.kiroku.model.NativeType
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.Severity
import dev.kiroku.model.TypeSystem

/**
 * Fujifilm RAF. The file starts with a fixed header (magic `FUJIFILMCCD-RAW `, version, camera ID and model) whose
 * big-endian pointers at 0x54..0x6B locate three blocks:
 * - a full-size JPEG preview. Its EXIF holds the main metadata, including the Fujifilm MakerNote;
 * - the RAF directory: a count, then records of tag (u16), length (u16) and big-endian data, with the raw image sizes;
 * - the raw data. On newer cameras it is a TIFF whose IFD0 points to the FujiIFD (raw size, bit depth, levels).
 * The raw pixels are never decoded.
 */
internal object RafParser {
    private val GROUP = listOf("RAF", "RAF")
    private const val HEADER_SIZE = 0x6CL
    private const val MAX_DIRECTORY_ENTRIES = 4096

    fun parse(ctx: NativeContext, s: ByteSource) {
        if (s.size < HEADER_SIZE) {
            ctx.warn(DiagnosticCode.TRUNCATED, "RAF shorter than its header")
            return
        }
        ctx.collector.directory(listOf("RAF", "Header"), DirectoryKind.FILE, 0, HEADER_SIZE, ByteOrderMark.BIG_ENDIAN)
        fun o(name: String, v: NormalizedValue, off: Long, n: Long, type: String, display: String? = null, tagId: Long? = off) = ctx.observe(
            GROUP,
            name,
            v,
            tagId = tagId,
            original = ctx.original(s, off, n, ByteOrderMark.BIG_ENDIAN),
            nativeType = NativeType(TypeSystem.RAF, type, null, 1),
            displayValue = display,
            sourcePath = "RAF/Header",
        )
        o("RAFVersion", NormalizedValue.Text(s.ascii(0x3C, 4).trimEnd('\u0000', ' ')), 0x3C, 4, "string[4]")
        val jpegOffset = s.u32(0x54, true)
        val jpegLength = s.u32(0x58, true)
        val dirOffset = s.u32(0x5C, true)
        val dirLength = s.u32(0x60, true)
        val rawOffset = s.u32(0x64, true)
        val rawLength = s.u32(0x68, true)
        o("JpgFromRawStart", NormalizedValue.Integer(jpegOffset), 0x54, 4, "int32u")
        o("JpgFromRawLength", NormalizedValue.Integer(jpegLength), 0x58, 4, "int32u")
        if (s.contains(0x6C, 4)) {
            val c = s.u32(0x6C, true)
            o("RAFCompression", NormalizedValue.Integer(c), 0x6C, 4, "int32u", compressionName(c))
        }

        if (jpegLength > 0 && s.contains(jpegOffset, jpegLength)) {
            ctx.collector.embeddedImage(
                EmbeddedImageInfo(
                    0,
                    "",
                    EmbeddedImageKind.PREVIEW,
                    GROUP,
                    compression = "JPEG",
                    mimeType = "image/jpeg",
                    fileOffset = s.absolute(jpegOffset),
                    length = jpegLength,
                ),
            )
            JpegParser.parseEmbedded(ctx, s.slice(jpegOffset, jpegLength), "RAF/JpgFromRaw", "JpgFromRaw")
        } else {
            ctx.warn(DiagnosticCode.TRUNCATED, "RAF JPEG preview is outside the file; camera metadata unavailable")
        }

        if (dirLength >= 4 && s.contains(dirOffset, dirLength)) {
            directory(ctx, s, dirOffset, dirLength)
        } else if (dirLength > 0) {
            ctx.warn(DiagnosticCode.TRUNCATED, "RAF directory is outside the file")
        }

        if (!ctx.fast && rawLength >= 8 && s.contains(rawOffset, 8) &&
            TiffReader.readHeader(s.slice(rawOffset, minOf(rawLength, s.size - rawOffset))) != null
        ) {
            TiffReader(ctx, s.slice(rawOffset, minOf(rawLength, s.size - rawOffset)), "RAF/RawData/TIFF", family0 = "RAF").parse()
        }
    }

    /** The RAF directory: u32 count, then (tag u16, length u16, data) records, all big-endian. */
    private fun directory(ctx: NativeContext, s: ByteSource, start: Long, length: Long) {
        val end = start + length
        val count = s.u32(start, true)
        if (count > MAX_DIRECTORY_ENTRIES) {
            ctx.warn(DiagnosticCode.LIMIT_EXCEEDED, "RAF directory declares $count entries; limit is $MAX_DIRECTORY_ENTRIES")
            return
        }
        ctx.collector.directory(listOf("RAF", "RAF"), DirectoryKind.FILE, s.absolute(start), length, ByteOrderMark.BIG_ENDIAN, count.toInt())
        var pos = start + 4
        for (i in 0 until count) {
            ctx.checkCancelled()
            if (pos + 4 > end) {
                ctx.warn(DiagnosticCode.TRUNCATED, "RAF directory ends after $i of $count entries")
                return
            }
            val tag = s.u16(pos, true)
            val size = s.u16(pos + 2, true).toLong()
            val d = pos + 4
            if (d + size > end) {
                ctx.warn(DiagnosticCode.MALFORMED, "RAF directory entry 0x%04X runs past the directory".format(tag), severity = Severity.INFO)
                return
            }
            val name = TAG_NAMES[tag]
            val (value, display) = decode(tag, s, d, size)
            ctx.observe(
                GROUP,
                name ?: "Tag0x%04X".format(tag),
                value,
                tagId = tag.toLong(),
                original = ctx.original(s, d, size, ByteOrderMark.BIG_ENDIAN),
                nativeType = NativeType(TypeSystem.RAF, if (size == 4L && tag in PAIRS) "int16u[2]" else "undef", tag, size),
                displayValue = display,
                known = name != null,
                sourcePath = "RAF/Directory",
            )
            pos = d + size
        }
    }

    private fun decode(tag: Int, s: ByteSource, d: Long, size: Long): Pair<NormalizedValue, String?> {
        if (tag in PAIRS && size == 4L) {
            // Stored as (height, width) or (top, left); ExifTool prints sizes as WxH.
            val a = s.u16(d, true).toLong()
            val b = s.u16(d + 2, true).toLong()
            val v = NormalizedValue.ListValue(listOf(NormalizedValue.Integer(a), NormalizedValue.Integer(b)))
            val display =
                when (tag) {
                    CROP_TOP_LEFT -> "$a $b"
                    ASPECT_RATIO -> "$b:$a"
                    else -> "${b}x$a"
                }
            return v to display
        }
        if (tag == FUJI_LAYOUT && size in 1..64) {
            return NormalizedValue.ListValue((0 until size).map { NormalizedValue.Integer(s.u8(d + it).toLong()) }) to null
        }
        if (size in 1..8 && size % 2 == 0L) {
            return NormalizedValue.list((0 until size / 2).map { NormalizedValue.Integer(s.u16(d + it * 2, true).toLong()) }) to null
        }
        return NormalizedValue.Binary(size) to null
    }

    private fun compressionName(c: Long): String? = when (c) {
        0L -> "Uncompressed"
        2L -> "Lossless"
        3L -> "Lossy"
        else -> null
    }

    private const val FULL_SIZE = 0x100
    private const val CROP_TOP_LEFT = 0x110
    private const val CROPPED_SIZE = 0x111
    private const val ASPECT_RATIO = 0x115
    private const val RAW_SIZE = 0x121
    private const val FUJI_LAYOUT = 0x130

    private val PAIRS = setOf(FULL_SIZE, CROP_TOP_LEFT, CROPPED_SIZE, ASPECT_RATIO, RAW_SIZE)

    // Names as ExifTool's FujiFilm::RAF table uses them. Other tags are kept with their numeric ID.
    private val TAG_NAMES =
        mapOf(
            FULL_SIZE to "RawImageFullSize",
            CROP_TOP_LEFT to "RawImageCropTopLeft",
            CROPPED_SIZE to "RawImageCroppedSize",
            ASPECT_RATIO to "RawImageAspectRatio",
            RAW_SIZE to "RawImageSize",
            FUJI_LAYOUT to "FujiLayout",
            0x131 to "XTransLayout",
            0x2FF0 to "WB_GRGBLevels",
            0xC000 to "RAFData",
        )
}
