package dev.kiroku.format.ebml

import dev.kiroku.format.NativeContext
import dev.kiroku.format.isobmff.CICP
import dev.kiroku.io.ByteSource
import dev.kiroku.model.ChapterInfo
import dev.kiroku.model.ContainerInfo
import dev.kiroku.model.DiagnosticCode
import dev.kiroku.model.DirectoryKind
import dev.kiroku.model.NativeType
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.Rational
import dev.kiroku.model.StreamInfo
import dev.kiroku.model.StreamKind
import dev.kiroku.model.TypeSystem
import java.time.Duration
import java.time.Instant

/**
 * Matroska/WebM EBML parser for the EBML header, Segment Info, Tracks, Tags, Chapters and Attachments.
 * Clusters (media data) are skipped by size. The tag ID is the EBML element ID including its
 * length-marker bits, as in the Matroska specification.
 */
internal class MatroskaParser(private val ctx: NativeContext, private val s: ByteSource, private val formatId: String) {
    private enum class T { UINT, INT, FLOAT, STRING, UTF8, DATE, BINARY, MASTER }

    private data class Def(val name: String, val type: T)

    private class Track {
        var number: Long? = null
        var type: Long? = null
        val props = LinkedHashMap<String, NormalizedValue>()
        var codec: String? = null
        var codecId: String? = null
    }

    private var timecodeScale = 1_000_000L
    private var durationTicks: Double? = null
    private val tracks = ArrayList<Track>()
    private var track: Track? = null
    private var tagTarget: String = "Tags"
    private val chapters = ArrayList<ChapterInfo>()
    private var chapterStart: Long? = null
    private var chapterEnd: Long? = null
    private var chapterTitle: String? = null
    private var simpleTagName: String? = null

    fun parse() {
        walk(0, s.size, "EBML", 0, 0L)
        finish()
    }

    private fun readVint(pos: Long, keepMarker: Boolean): Pair<Long, Int>? {
        if (pos >= s.size) return null
        val first = s.u8(pos)
        if (first == 0) return null
        val len = Integer.numberOfLeadingZeros(first) - 23
        if (len > 8 || pos + len > s.size) return null
        var v = if (keepMarker) first.toLong() else (first and (0xFF shr len)).toLong()
        var allOnes = (first and (0xFF shr len)) == (0xFF shr len)
        for (i in 1 until len) {
            val b = s.u8(pos + i)
            if (b != 0xFF) allOnes = false
            v = (v shl 8) or b.toLong()
        }
        return (if (!keepMarker && allOnes) -1L else v) to len
    }

    private fun walk(start: Long, end: Long, path: String, depth: Int, parent: Long) {
        if (depth > ctx.limits.maxNestingDepth) return
        var pos = start
        while (pos < end) {
            ctx.countBox()
            val (id, idLen) = readVint(pos, true) ?: break
            val (size, sizeLen) = readVint(pos + idLen, false) ?: break
            val data = pos + idLen + sizeLen
            val unknownSize = size < 0
            val elemEnd = if (unknownSize) end else data + size
            if (!unknownSize && elemEnd > end) {
                if (id != CLUSTER &&
                    id != SEGMENT
                ) {
                    ctx.warn(DiagnosticCode.TRUNCATED, "EBML element 0x%X extends beyond its parent".format(id))
                }
            }
            val def = DEFS[id]
            val epath = "$path/${def?.name ?: "0x%X".format(id)}"
            when {
                id == CLUSTER -> {
                    if (unknownSize) return // Unknown-size clusters cannot be skipped without parsing blocks.
                }

                id == CUES || id == SEEKHEAD || id == VOID || id == CRC32 -> Unit

                def?.type == T.MASTER -> {
                    ctx.collector.directory(
                        epath.split('/'),
                        DirectoryKind.EBML_ELEMENT,
                        s.absolute(pos),
                        if (unknownSize) {
                            null
                        } else {
                            elemEnd -
                                pos
                        },
                    )
                    enter(id)
                    walk(data, minOf(elemEnd, end), epath, depth + 1, id)
                    leave(id)
                }

                else -> leaf(id, def, data, if (unknownSize) 0 else minOf(size, end - data), epath, parent)
            }
            if (unknownSize) return
            pos = elemEnd
        }
    }

    private fun enter(id: Long) {
        when (id) {
            TRACK_ENTRY -> track = Track().also { tracks += it }

            TAG -> tagTarget = "Tags"

            CHAPTER_ATOM -> {
                chapterStart = null
                chapterEnd = null
                chapterTitle = null
            }
        }
    }

    private fun leave(id: Long) {
        when (id) {
            TRACK_ENTRY -> track = null

            CHAPTER_ATOM ->
                chapters +=
                    ChapterInfo(
                        chapters.size,
                        "",
                        chapterStart?.let {
                            Duration.ofNanos(it)
                        },
                        chapterEnd?.let { Duration.ofNanos(it) },
                        chapterTitle,
                    )
        }
    }

    private fun leaf(id: Long, def: Def?, d: Long, len: Long, path: String, parent: Long) {
        val type = def?.type ?: T.BINARY
        val value: NormalizedValue =
            when (type) {
                T.UINT -> if (len in 1..8) NormalizedValue.Integer(uint(d, len)) else NormalizedValue.Integer(0)

                T.INT -> if (len in 1..8) NormalizedValue.Integer(sint(d, len)) else NormalizedValue.Integer(0)

                T.FLOAT ->
                    when (len) {
                        4L -> NormalizedValue.Decimal(java.lang.Float.intBitsToFloat(s.u32(d, true).toInt()).toDouble())
                        8L -> NormalizedValue.Decimal(java.lang.Double.longBitsToDouble(s.u64(d, true)))
                        else -> NormalizedValue.Decimal(0.0)
                    }

                T.STRING, T.UTF8 ->
                    NormalizedValue.Text(
                        ctx.clip(
                            String(
                                s.read(d, minOf(len, ctx.limits.maxStringLength.toLong()).toInt()),
                                if (type ==
                                    T.UTF8
                                ) {
                                    Charsets.UTF_8
                                } else {
                                    Charsets.ISO_8859_1
                                },
                            ).trimEnd('\u0000'),
                        ),
                    )

                T.DATE -> NormalizedValue.Integer(if (len == 8L) s.u64(d, true) else 0)

                else -> NormalizedValue.Binary(len)
            }
        val inTrack = track
        val streamIndex = inTrack?.let { tracks.indexOf(it) }
        val group =
            when {
                parent == SIMPLE_TAG -> listOf("Matroska", tagTarget)
                inTrack != null -> listOf("Matroska", "Track${(streamIndex ?: 0) + 1}")
                else -> listOf("Matroska", "Matroska")
            }
        // SimpleTag name/value pairs become one observation named after TagName.
        if (id == TAG_NAME) {
            simpleTagName = (value as? NormalizedValue.Text)?.value
            return
        }
        if (id == TAG_STRING || id == TAG_BINARY) {
            val tagName = simpleTagName ?: "TagString"
            ctx.observe(
                group,
                tagName,
                value,
                tagId = id,
                original = ctx.original(s, d, len),
                nativeType = NativeType(TypeSystem.EBML, type.name.lowercase(), null, len),
                known = false,
                sourcePath = path,
            )
            return
        }
        val display =
            when (id) {
                DATE_UTC -> mkvDate((value as NormalizedValue.Integer).value).toString()
                TRACK_TYPE -> trackTypeName((value as NormalizedValue.Integer).value)
                COLOUR_PRIMARIES -> CICP.primaries((value as NormalizedValue.Integer).value)
                COLOUR_TRANSFER -> CICP.transfer((value as NormalizedValue.Integer).value)
                COLOUR_MATRIX -> CICP.matrix((value as NormalizedValue.Integer).value)
                else -> null
            }
        ctx.observe(
            group, def?.name ?: "0x%X".format(id), value, tagId = id, original = ctx.original(s, d, len),
            nativeType = NativeType(TypeSystem.EBML, type.name.lowercase(), null, len), displayValue = display,
            known = def != null, streamIndex = streamIndex, sourcePath = path,
        )
        when (id) {
            TIMECODE_SCALE -> timecodeScale = (value as NormalizedValue.Integer).value.takeIf { it > 0 } ?: 1_000_000L
            DURATION -> durationTicks = (value as NormalizedValue.Decimal).value
            TAG_TRACK_UID -> tagTarget = "Tags"
            TARGET_TYPE -> tagTarget = "Tags"
            CHAP_START -> chapterStart = (value as NormalizedValue.Integer).value
            CHAP_END -> chapterEnd = (value as NormalizedValue.Integer).value
            CHAP_STRING -> if (chapterTitle == null) chapterTitle = (value as NormalizedValue.Text).value
        }
        val t = inTrack ?: return
        val iv = (value as? NormalizedValue.Integer)?.value
        val dv = (value as? NormalizedValue.Decimal)?.value
        when (id) {
            TRACK_NUMBER -> t.number = iv

            TRACK_TYPE -> t.type = iv

            CODEC_ID -> {
                t.codecId = (value as NormalizedValue.Text).value
                t.codec = codecName(t.codecId!!)
            }

            LANGUAGE, LANGUAGE_BCP47 -> t.props["language"] = value

            TRACK_NAME -> t.props["name"] = value

            FLAG_DEFAULT -> t.props["default"] = NormalizedValue.Bool(iv == 1L)

            DEFAULT_DURATION -> if (iv != null &&
                iv > 0
            ) {
                t.props["frameRate"] = NormalizedValue.RationalValue(Rational(1_000_000_000L, iv).reduced())
            }

            PIXEL_WIDTH -> t.props["width"] = value

            PIXEL_HEIGHT -> t.props["height"] = value

            DISPLAY_WIDTH -> t.props["displayWidth"] = value

            DISPLAY_HEIGHT -> t.props["displayHeight"] = value

            DISPLAY_UNIT -> t.props["displayUnit"] = value

            SAMPLING_FREQ -> if (dv != null) t.props["sampleRate"] = NormalizedValue.Integer(dv.toLong())

            CHANNELS -> t.props["channels"] = value

            BIT_DEPTH -> t.props["bitsPerSample"] = value

            BITS_PER_CHANNEL -> t.props["bitDepthLuma"] = value

            COLOUR_PRIMARIES -> t.props["colorPrimaries"] = value

            COLOUR_TRANSFER -> t.props["transferCharacteristics"] = value

            COLOUR_MATRIX -> t.props["matrixCoefficients"] = value

            COLOUR_RANGE -> t.props["fullRange"] = NormalizedValue.Bool(iv == 2L)

            MAX_CLL -> t.props["maxCLL"] = value

            FLAG_INTERLACED -> t.props["interlaced"] = NormalizedValue.Bool(iv == 1L)
        }
    }

    private fun finish() {
        tracks.forEachIndexed { i, t ->
            val kind =
                when (t.type) {
                    1L -> StreamKind.VIDEO
                    2L -> StreamKind.AUDIO
                    17L -> StreamKind.SUBTITLE
                    18L, 33L -> StreamKind.DATA
                    else -> StreamKind.OTHER
                }
            ctx.collector.stream(StreamInfo(i, "", kind, t.codec, t.codecId, t.props.toMap()))
        }
        chapters.forEach { ctx.collector.chapter(it) }
        val dur = durationTicks?.let { Duration.ofNanos((it * timecodeScale).toLong()) }
        val bitrate = dur?.let { if (it.toNanos() > 0) (s.size * 8.0 / (it.toNanos() / 1e9)).toLong() else null }
        ctx.collector.container(ContainerInfo(formatId, duration = dur, bitRateBitsPerSecond = bitrate))
    }

    private fun uint(d: Long, len: Long): Long {
        var v = 0L
        val b = s.read(d, len.toInt())
        for (x in b) v = (v shl 8) or (x.toLong() and 0xFF)
        return v
    }

    private fun sint(d: Long, len: Long): Long {
        val u = uint(d, len)
        val shift = 64 - len * 8
        return (u shl shift.toInt()) shr shift.toInt()
    }

    companion object {
        const val SEGMENT = 0x18538067L
        const val CLUSTER = 0x1F43B675L
        const val CUES = 0x1C53BB6BL
        const val SEEKHEAD = 0x114D9B74L
        const val VOID = 0xECL
        const val CRC32 = 0xBFL
        const val TRACK_ENTRY = 0xAEL
        const val TAG = 0x7373L
        const val SIMPLE_TAG = 0x67C8L
        const val TAG_NAME = 0x45A3L
        const val TAG_STRING = 0x4487L
        const val TAG_BINARY = 0x4485L
        const val TAG_TRACK_UID = 0x63C5L
        const val TARGET_TYPE = 0x68CAL
        const val CHAPTER_ATOM = 0xB6L
        const val CHAP_START = 0x91L
        const val CHAP_END = 0x92L
        const val CHAP_STRING = 0x85L
        const val TIMECODE_SCALE = 0x2AD7B1L
        const val DURATION = 0x4489L
        const val DATE_UTC = 0x4461L
        const val TRACK_NUMBER = 0xD7L
        const val TRACK_TYPE = 0x83L
        const val CODEC_ID = 0x86L
        const val LANGUAGE = 0x22B59CL
        const val LANGUAGE_BCP47 = 0x22B59DL
        const val TRACK_NAME = 0x536EL
        const val FLAG_DEFAULT = 0x88L
        const val DEFAULT_DURATION = 0x23E383L
        const val PIXEL_WIDTH = 0xB0L
        const val PIXEL_HEIGHT = 0xBAL
        const val DISPLAY_WIDTH = 0x54B0L
        const val DISPLAY_HEIGHT = 0x54BAL
        const val DISPLAY_UNIT = 0x54B2L
        const val SAMPLING_FREQ = 0xB5L
        const val CHANNELS = 0x9FL
        const val BIT_DEPTH = 0x6264L
        const val BITS_PER_CHANNEL = 0x55B2L
        const val COLOUR_MATRIX = 0x55B1L
        const val COLOUR_RANGE = 0x55B9L
        const val COLOUR_TRANSFER = 0x55BAL
        const val COLOUR_PRIMARIES = 0x55BBL
        const val MAX_CLL = 0x55BCL
        const val FLAG_INTERLACED = 0x9AL

        private val DEFS: Map<Long, Def> =
            mapOf(
                0x1A45DFA3L to Def("EBMLHeader", T.MASTER),
                0x4286L to Def("EBMLVersion", T.UINT),
                0x42F7L to Def("EBMLReadVersion", T.UINT),
                0x42F2L to Def("EBMLMaxIDLength", T.UINT),
                0x42F3L to Def("EBMLMaxSizeLength", T.UINT),
                0x4282L to Def("DocType", T.STRING),
                0x4287L to Def("DocTypeVersion", T.UINT),
                0x4285L to Def("DocTypeReadVersion", T.UINT),
                SEGMENT to Def("Segment", T.MASTER),
                0x1549A966L to Def("Info", T.MASTER),
                0x73A4L to Def("SegmentUID", T.BINARY),
                0x7384L to Def("SegmentFilename", T.UTF8),
                TIMECODE_SCALE to Def("TimecodeScale", T.UINT),
                DURATION to Def("Duration", T.FLOAT),
                DATE_UTC to Def("DateTimeOriginal", T.DATE),
                0x7BA9L to Def("Title", T.UTF8),
                0x4D80L to Def("MuxingApp", T.UTF8),
                0x5741L to Def("WritingApp", T.UTF8),
                0x1654AE6BL to Def("Tracks", T.MASTER),
                TRACK_ENTRY to Def("TrackEntry", T.MASTER),
                TRACK_NUMBER to Def("TrackNumber", T.UINT),
                0x73C5L to Def("TrackUID", T.UINT),
                TRACK_TYPE to Def("TrackType", T.UINT),
                0xB9L to Def("FlagEnabled", T.UINT),
                FLAG_DEFAULT to Def("FlagDefault", T.UINT),
                0x55AAL to Def("FlagForced", T.UINT),
                0x9CL to Def("FlagLacing", T.UINT),
                DEFAULT_DURATION to Def("DefaultDuration", T.UINT),
                TRACK_NAME to Def("TrackName", T.UTF8),
                LANGUAGE to Def("TrackLanguage", T.STRING),
                LANGUAGE_BCP47 to Def("TrackLanguageIETF", T.STRING),
                CODEC_ID to Def("CodecID", T.STRING),
                0x63A2L to Def("CodecPrivate", T.BINARY),
                0x258688L to Def("CodecName", T.UTF8),
                0x56AAL to Def("CodecDelay", T.UINT),
                0x56BBL to Def("SeekPreRoll", T.UINT),
                0xE0L to Def("Video", T.MASTER),
                FLAG_INTERLACED to Def("VideoScanType", T.UINT),
                0x53B8L to Def("Stereo3DMode", T.UINT),
                0x53C0L to Def("AlphaMode", T.UINT),
                PIXEL_WIDTH to Def("ImageWidth", T.UINT),
                PIXEL_HEIGHT to Def("ImageHeight", T.UINT),
                0x54AAL to Def("CropBottom", T.UINT),
                0x54BBL to Def("CropTop", T.UINT),
                0x54CCL to Def("CropLeft", T.UINT),
                0x54DDL to Def("CropRight", T.UINT),
                DISPLAY_WIDTH to Def("DisplayWidth", T.UINT),
                DISPLAY_HEIGHT to Def("DisplayHeight", T.UINT),
                DISPLAY_UNIT to Def("DisplayUnit", T.UINT),
                0x2383E3L to Def("FrameRate", T.FLOAT),
                0x55B0L to Def("Colour", T.MASTER),
                COLOUR_MATRIX to Def("MatrixCoefficients", T.UINT),
                BITS_PER_CHANNEL to Def("BitsPerChannel", T.UINT),
                0x55B3L to Def("ChromaSubsamplingHorz", T.UINT),
                0x55B4L to Def("ChromaSubsamplingVert", T.UINT),
                0x55B7L to Def("ChromaSitingHorz", T.UINT),
                0x55B8L to Def("ChromaSitingVert", T.UINT),
                COLOUR_RANGE to Def("Range", T.UINT),
                COLOUR_TRANSFER to Def("TransferCharacteristics", T.UINT),
                COLOUR_PRIMARIES to Def("Primaries", T.UINT),
                MAX_CLL to Def("MaxCLL", T.UINT),
                0x55BDL to Def("MaxFALL", T.UINT),
                0x55D0L to Def("MasteringMetadata", T.MASTER),
                0x55D9L to Def("LuminanceMax", T.FLOAT),
                0x55DAL to Def("LuminanceMin", T.FLOAT),
                0x7670L to Def("Projection", T.MASTER),
                0x7671L to Def("ProjectionType", T.UINT),
                0xE1L to Def("Audio", T.MASTER),
                SAMPLING_FREQ to Def("AudioSampleRate", T.FLOAT),
                0x78B5L to Def("OutputAudioSampleRate", T.FLOAT),
                CHANNELS to Def("AudioChannels", T.UINT),
                BIT_DEPTH to Def("AudioBitsPerSample", T.UINT),
                0x6D80L to Def("ContentEncodings", T.MASTER),
                0x6240L to Def("ContentEncoding", T.MASTER),
                0x1254C367L to Def("Tags", T.MASTER),
                TAG to Def("Tag", T.MASTER),
                0x63C0L to Def("Targets", T.MASTER),
                TARGET_TYPE to Def("TargetTypeValue", T.UINT),
                0x63CAL to Def("TargetType", T.STRING),
                TAG_TRACK_UID to Def("TagTrackUID", T.UINT),
                SIMPLE_TAG to Def("SimpleTag", T.MASTER),
                TAG_NAME to Def("TagName", T.UTF8),
                0x447AL to Def("TagLanguage", T.STRING),
                0x4484L to Def("TagDefault", T.UINT),
                TAG_STRING to Def("TagString", T.UTF8),
                TAG_BINARY to Def("TagBinary", T.BINARY),
                0x1043A770L to Def("Chapters", T.MASTER),
                0x45B9L to Def("EditionEntry", T.MASTER),
                CHAPTER_ATOM to Def("ChapterAtom", T.MASTER),
                0x73C4L to Def("ChapterUID", T.UINT),
                CHAP_START to Def("ChapterTimeStart", T.UINT),
                CHAP_END to Def("ChapterTimeEnd", T.UINT),
                0x80L to Def("ChapterDisplay", T.MASTER),
                CHAP_STRING to Def("ChapterString", T.UTF8),
                0x437CL to Def("ChapterLanguage", T.STRING),
                0x1941A469L to Def("Attachments", T.MASTER),
                0x61A7L to Def("AttachedFile", T.MASTER),
                0x467EL to Def("AttachedFileDescription", T.UTF8),
                0x466EL to Def("AttachedFileName", T.UTF8),
                0x4660L to Def("AttachedFileMIMEType", T.STRING),
                0x465CL to Def("AttachedFileData", T.BINARY),
                0x46AEL to Def("AttachedFileUID", T.UINT),
            )

        private val MKV_EPOCH: Instant = Instant.parse("2001-01-01T00:00:00Z")

        fun mkvDate(ns: Long): Instant = MKV_EPOCH.plusNanos(ns)

        fun trackTypeName(t: Long): String? = when (t) {
            1L -> "Video"
            2L -> "Audio"
            3L -> "Complex"
            16L -> "Logo"
            17L -> "Subtitle"
            18L -> "Buttons"
            32L -> "Control"
            33L -> "Metadata"
            else -> null
        }

        fun codecName(id: String): String? = when {
            id == "V_VP8" -> "vp8"
            id == "V_VP9" -> "vp9"
            id == "V_AV1" -> "av1"
            id == "V_MPEG4/ISO/AVC" -> "h264"
            id == "V_MPEGH/ISO/HEVC" -> "hevc"
            id.startsWith("V_MPEG4/ISO") -> "mpeg4"
            id == "V_MPEG2" -> "mpeg2video"
            id == "V_MJPEG" -> "mjpeg"
            id == "V_THEORA" -> "theora"
            id == "V_PRORES" -> "prores"
            id == "A_OPUS" -> "opus"
            id == "A_VORBIS" -> "vorbis"
            id.startsWith("A_AAC") -> "aac"
            id == "A_FLAC" -> "flac"
            id == "A_AC3" -> "ac3"
            id == "A_EAC3" -> "eac3"
            id == "A_DTS" -> "dts"
            id == "A_MPEG/L3" -> "mp3"
            id == "A_MPEG/L2" -> "mp2"
            id.startsWith("A_PCM") -> "pcm"
            id == "A_TRUEHD" -> "truehd"
            id == "S_TEXT/UTF8" -> "subrip"
            id == "S_TEXT/WEBVTT" -> "webvtt"
            id == "S_TEXT/ASS" || id == "S_TEXT/SSA" -> "ass"
            id == "S_HDMV/PGS" -> "hdmv_pgs_subtitle"
            else -> id.lowercase()
        }
    }
}
