package dev.kiroku.format.mp3

import dev.kiroku.format.NativeContext
import dev.kiroku.io.ArraySource
import dev.kiroku.io.ByteSource
import dev.kiroku.model.ByteOrderMark
import dev.kiroku.model.ContainerInfo
import dev.kiroku.model.DiagnosticCode
import dev.kiroku.model.DirectoryKind
import dev.kiroku.model.EmbeddedImageInfo
import dev.kiroku.model.EmbeddedImageKind
import dev.kiroku.model.NativeType
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.Severity
import dev.kiroku.model.StreamInfo
import dev.kiroku.model.StreamKind
import dev.kiroku.model.TypeSystem
import dev.kiroku.spi.StreamProps
import java.time.Duration

/**
 * MP3 (MPEG-1/2/2.5 audio, layers I-III) with ID3 tags.
 *
 * - ID3v2.2, 2.3 and 2.4 frames: text frames in all four encodings, comments, lyrics, pictures (listed, not decoded),
 *   user text and URLs. Unsynchronisation is undone, so values from such tags are reconstructed, not byte-exact.
 * - ID3v1 / v1.1 (the last 128 bytes).
 * - The first MPEG audio frame header, and a Xing/Info or VBRI header for the exact frame count. Without one, the
 *   duration is estimated from the bit rate and reported as an estimate.
 *
 * Names follow ExifTool (groups `ID3:ID3v2_3`, `ID3:ID3v1`, `MPEG:MPEG`).
 */
internal object Mp3Parser {
    private val MPEG = listOf("MPEG", "MPEG")
    private const val MAX_TAG = 64L shl 20
    private const val MAX_FRAMES = 4096
    private const val SYNC_SEARCH = 64 * 1024

    /** `ID3` followed by MPEG audio, or two consecutive valid MPEG audio frames at the start. */
    fun looksLikeMp3(s: ByteSource): Boolean {
        val start = id3v2End(s) ?: 0L
        val h = header(s, start) ?: return false
        if (start > 0) return true
        val next = start + h.frameLength
        return h.frameLength > 0 && header(s, next) != null
    }

    fun parse(ctx: NativeContext, s: ByteSource) {
        var audioStart = 0L
        if (s.size >= 10 && s.ascii(0, 3) == "ID3") {
            audioStart = id3v2(ctx, s) ?: 0L
        }
        val hasV1 = s.size >= 128 && s.ascii(s.size - 128, 3) == "TAG"
        if (hasV1) id3v1(ctx, s, s.size - 128)
        // ExifTool's ID3Size: all ID3 bytes, v2 tag and the 128-byte v1 tag together. Computed, so no original bytes.
        if (audioStart > 0 || hasV1) {
            ctx.observe(
                listOf("File", "File"),
                "ID3Size",
                NormalizedValue.Integer(audioStart + if (hasV1) 128 else 0),
                nativeType = NativeType(TypeSystem.ID3, "computed"),
                sourcePath = "ID3",
            )
        }
        val audioEnd = if (hasV1) s.size - 128 else s.size
        // Skip padding between the tag and the first frame.
        val frameAt = findSync(s, audioStart) ?: run {
            ctx.warn(DiagnosticCode.MALFORMED, "No MPEG audio frame found after the ID3 tag")
            return
        }
        mpeg(ctx, s, frameAt, audioEnd)
    }

    // ---- ID3v2 -----------------------------------------------------------------------------------------------

    private fun synchsafe(s: ByteSource, off: Long): Long = ((s.u8(off).toLong() and 0x7F) shl 21) or ((s.u8(off + 1).toLong() and 0x7F) shl 14) or
        ((s.u8(off + 2).toLong() and 0x7F) shl 7) or (s.u8(off + 3).toLong() and 0x7F)

    private fun id3v2End(s: ByteSource): Long? {
        if (s.size < 10 || s.ascii(0, 3) != "ID3") return null
        val footer = if (s.u8(5) and 0x10 != 0) 10 else 0
        return 10 + synchsafe(s, 6) + footer
    }

    /** Parses the ID3v2 tag at offset 0 and returns where the audio starts. */
    private fun id3v2(ctx: NativeContext, s: ByteSource): Long? {
        val major = s.u8(3)
        val flags = s.u8(5)
        val size = synchsafe(s, 6)
        val end = id3v2End(s) ?: return null
        if (major !in 2..4) {
            ctx.warn(DiagnosticCode.UNSUPPORTED_FORMAT, "ID3v2.$major is not supported; tag skipped", severity = Severity.INFO)
            return end
        }
        if (size > MAX_TAG || !s.contains(10, size)) {
            ctx.warn(DiagnosticCode.TRUNCATED, "ID3v2 tag ($size bytes) runs past the end of the file")
            return end
        }
        val g = listOf("ID3", "ID3v2_$major")
        ctx.collector.directory(g, DirectoryKind.FILE, 0, 10 + size, ByteOrderMark.BIG_ENDIAN, note = "ID3v2.$major")
        // Whole-tag unsynchronisation (v2.2/2.3): undo it on a copy; offsets then no longer match the file.
        val wholeUnsync = flags and 0x80 != 0 && major < 4
        val body: ByteSource = if (wholeUnsync) ArraySource(resync(s.read(10, size.toInt())), null) else s.slice(10, size)
        var pos = 0L
        if (flags and 0x40 != 0 && major >= 3 && body.size >= 4) {
            pos = if (major == 3) 4 + body.u32(0, true) else synchsafe(body, 0)
        }
        val idLen = if (major == 2) 3 else 4
        val headerLen = if (major == 2) 6 else 10
        var n = 0
        while (pos + headerLen <= body.size && n++ < MAX_FRAMES) {
            ctx.checkCancelled()
            if (body.u8(pos) == 0) break // padding
            val id = body.ascii(pos, idLen)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) {
                ctx.warn(DiagnosticCode.MALFORMED, "Invalid ID3 frame ID at offset ${10 + pos}; rest of the tag skipped", severity = Severity.INFO)
                break
            }
            val frameSize =
                when (major) {
                    2 -> (body.u8(pos + 3).toLong() shl 16) or (body.u8(pos + 4).toLong() shl 8) or body.u8(pos + 5).toLong()
                    3 -> body.u32(pos + idLen, true)
                    else -> synchsafe(body, pos + idLen)
                }
            val frameFlags = if (major >= 3) body.u16(pos + 8, true) else 0
            val dataAt = pos + headerLen
            if (frameSize <= 0 || dataAt + frameSize > body.size) {
                ctx.warn(DiagnosticCode.TRUNCATED, "ID3 frame $id runs past the end of the tag")
                break
            }
            var data: ByteSource = body.slice(dataAt, frameSize)
            if (major == 4) {
                var skip = 0L
                if (frameFlags and 0x0001 != 0) skip = 4 // data length indicator
                if (frameFlags and 0x0002 != 0 || flags and 0x80 != 0) {
                    data = ArraySource(resync(data.read(skip, (frameSize - skip).toInt())), null)
                } else if (skip > 0) {
                    data = data.slice(skip, frameSize - skip)
                }
            }
            val compressedOrEncrypted = if (major == 3) frameFlags and 0x00C0 != 0 else major == 4 && frameFlags and 0x000C != 0
            frame(ctx, g, id, data, compressedOrEncrypted, major, "ID3/v2.$major/$id")
            pos = dataAt + frameSize
        }
        return end
    }

    private fun resync(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1] == 0.toByte()) i++
            i++
        }
        return out.toByteArray()
    }

    private fun frame(ctx: NativeContext, g: List<String>, id: String, d: ByteSource, opaque: Boolean, major: Int, path: String) {
        val name = (if (major == 2) V22 else V23)[id]
        fun obs(n: String, v: NormalizedValue, display: String? = null, off: Long = 0, len: Long = d.size) = ctx.observe(
            g,
            n,
            v,
            original = ctx.original(d, off, len),
            nativeType = NativeType(TypeSystem.ID3, id, null, len),
            displayValue = display,
            known = name != null,
            sourcePath = path,
        )
        if (opaque || d.size == 0L) {
            obs(name ?: id, NormalizedValue.Binary(d.size))
            return
        }
        val b = d.read(0, minOf(d.size, MAX_TAG).toInt())
        when {
            id == "TXXX" || id == "TXX" -> {
                // Like ExifTool, a user-defined text frame is named after its description ("comment" -> Comment).
                val enc = b[0].toInt()
                val (desc, rest) = terminated(b, 1, enc)
                val tag = desc.filter { it.isLetterOrDigit() }.replaceFirstChar { it.uppercaseChar() }.ifEmpty { "UserDefinedText" }
                obs(tag, NormalizedValue.Text(decode(b, rest, b.size, enc)))
            }

            id.startsWith("T") -> {
                val values = decode(b, 1, b.size, b[0].toInt()).split('\u0000').filter { it.isNotEmpty() }
                val v = if (values.size == 1) NormalizedValue.Text(values[0]) else NormalizedValue.list(values.map { NormalizedValue.Text(it) })
                obs(name ?: id, v, if (id == "TCON" || id == "TCO") genreName(values.firstOrNull()) else null)
            }

            id == "COMM" || id == "COM" || id == "USLT" || id == "ULT" -> {
                if (b.size < 5) {
                    obs(name ?: id, NormalizedValue.Binary(d.size))
                    return
                }
                val enc = b[0].toInt()
                val (_, rest) = terminated(b, 4, enc) // language (3 bytes), then the short description
                obs(name ?: id, NormalizedValue.Text(decode(b, rest, b.size, enc).trimEnd('\u0000')))
            }

            id == "APIC" || id == "PIC" -> picture(ctx, g, d, b, major, path)

            id == "WXXX" || id == "WXX" -> {
                val enc = b[0].toInt()
                val (_, rest) = terminated(b, 1, enc)
                obs("UserDefinedURL", NormalizedValue.Text(String(b, rest, b.size - rest, Charsets.ISO_8859_1).trimEnd('\u0000')))
            }

            id.startsWith("W") -> obs(name ?: id, NormalizedValue.Text(String(b, Charsets.ISO_8859_1).trimEnd('\u0000')))

            else -> obs(name ?: id, NormalizedValue.Binary(d.size))
        }
    }

    private fun picture(ctx: NativeContext, g: List<String>, d: ByteSource, b: ByteArray, major: Int, path: String) {
        if (b.size < 6) return
        val enc = b[0].toInt()
        var p: Int
        val format: String
        if (major == 2) {
            format = String(b, 1, 3, Charsets.ISO_8859_1)
            p = 4
        } else {
            val (mime, after) = terminated(b, 1, 0)
            format = mime
            p = after
        }
        if (p >= b.size) return
        val type = b[p].toInt() and 0xFF
        val (desc, dataAt) = terminated(b, p + 1, enc)
        fun o(name: String, v: NormalizedValue, off: Long, len: Long) = ctx.observe(
            g,
            name,
            v,
            original = ctx.original(d, off, len),
            nativeType = NativeType(TypeSystem.ID3, if (major == 2) "PIC" else "APIC"),
            sourcePath = path,
        )
        o(if (major == 2) "PictureFormat" else "PictureMIMEType", NormalizedValue.Text(format), 1, format.length.toLong())
        o("PictureType", NormalizedValue.Integer(type.toLong()), p.toLong(), 1)
        o("PictureDescription", NormalizedValue.Text(desc), (p + 1).toLong(), (dataAt - p - 1).toLong())
        val len = (b.size - dataAt).toLong()
        o("Picture", NormalizedValue.Binary(len), dataAt.toLong(), len)
        ctx.collector.embeddedImage(
            EmbeddedImageInfo(
                0,
                "",
                EmbeddedImageKind.OTHER,
                g,
                mimeType = if (format.contains('/')) format else "image/${format.lowercase()}",
                fileOffset = d.absolute(dataAt.toLong()),
                length = len,
            ),
        )
    }

    /** Reads a NUL-terminated string in [enc] from [from]; returns it and the index after the terminator. */
    private fun terminated(b: ByteArray, from: Int, enc: Int): Pair<String, Int> {
        val wide = enc == 1 || enc == 2
        var i = from
        if (wide) {
            while (i + 1 < b.size && !(b[i] == 0.toByte() && b[i + 1] == 0.toByte() && (i - from) % 2 == 0)) i += 1
            return decode(b, from, minOf(i, b.size), enc) to minOf(i + 2, b.size)
        }
        while (i < b.size && b[i] != 0.toByte()) i++
        return decode(b, from, i, enc) to minOf(i + 1, b.size)
    }

    private fun decode(b: ByteArray, from: Int, to: Int, enc: Int): String {
        if (to <= from) return ""
        val cs =
            when (enc) {
                1 -> Charsets.UTF_16

                // with BOM
                2 -> Charsets.UTF_16BE

                3 -> Charsets.UTF_8

                else -> Charsets.ISO_8859_1
            }
        return String(b, from, to - from, cs).trimEnd('\u0000').removePrefix("")
    }

    // ---- ID3v1 -----------------------------------------------------------------------------------------------

    private fun id3v1(ctx: NativeContext, s: ByteSource, at: Long) {
        val g = listOf("ID3", "ID3v1")
        ctx.collector.directory(g, DirectoryKind.FILE, s.absolute(at), 128, note = "ID3v1")
        fun text(name: String, off: Long, n: Int) {
            val v = String(s.read(at + off, n), Charsets.ISO_8859_1).substringBefore('\u0000').trimEnd()
            if (v.isNotEmpty()) {
                ctx.observe(
                    g,
                    name,
                    NormalizedValue.Text(v),
                    original = ctx.original(s, at + off, n.toLong()),
                    nativeType = NativeType(TypeSystem.ID3, "string[$n]"),
                    sourcePath = "ID3/v1",
                )
            }
        }
        text("Title", 3, 30)
        text("Artist", 33, 30)
        text("Album", 63, 30)
        text("Year", 93, 4)
        // v1.1: a zero byte at 125 makes the last comment byte a track number.
        val v11 = s.u8(at + 125) == 0 && s.u8(at + 126) != 0
        text("Comment", 97, if (v11) 28 else 30)
        if (v11) {
            ctx.observe(
                g,
                "Track",
                NormalizedValue.Integer(s.u8(at + 126).toLong()),
                original = ctx.original(s, at + 126, 1),
                nativeType = NativeType(TypeSystem.ID3, "int8u"),
                sourcePath = "ID3/v1",
            )
        }
        val genre = s.u8(at + 127)
        ctx.observe(
            g,
            "Genre",
            NormalizedValue.Integer(genre.toLong()),
            original = ctx.original(s, at + 127, 1),
            nativeType = NativeType(TypeSystem.ID3, "int8u"),
            displayValue = GENRES.getOrNull(genre),
            sourcePath = "ID3/v1",
        )
    }

    private fun genreName(v: String?): String? {
        if (v == null) return null
        val m = Regex("^\\((\\d+)\\)").find(v) ?: Regex("^(\\d+)$").find(v) ?: return null
        return GENRES.getOrNull(m.groupValues[1].toInt())
    }

    // ---- MPEG audio frame ------------------------------------------------------------------------------------

    private class FrameHeader(
        val raw: Long,
        val versionBits: Int,
        val layerBits: Int,
        val bitrate: Int,
        val sampleRate: Int,
        val channelMode: Int,
        val frameLength: Int,
        val samplesPerFrame: Int,
    )

    private val BITRATES_V1 = arrayOf(
        intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448), // layer I
        intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384), // layer II
        intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320), // layer III
    )
    private val BITRATES_V2 = arrayOf(
        intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256),
        intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160),
        intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160),
    )
    private val SAMPLE_RATES = mapOf(3 to intArrayOf(44100, 48000, 32000), 2 to intArrayOf(22050, 24000, 16000), 0 to intArrayOf(11025, 12000, 8000))

    /** Decodes a frame header at [at]; `null` when the sync word or a field is invalid. */
    private fun header(s: ByteSource, at: Long): FrameHeader? {
        if (!s.contains(at, 4)) return null
        val h = s.u32(at, true)
        if (h ushr 21 != 0x7FFL) return null
        val version = ((h ushr 19) and 3).toInt()
        val layer = ((h ushr 17) and 3).toInt()
        val bitrateIndex = ((h ushr 12) and 15).toInt()
        val srIndex = ((h ushr 10) and 3).toInt()
        if (version == 1 || layer == 0 || bitrateIndex == 15) return null
        val layerIdx = 3 - layer // 0 = layer I, 1 = II, 2 = III
        val kbps = (if (version == 3) BITRATES_V1 else BITRATES_V2)[layerIdx][bitrateIndex]
        val sr = if (srIndex == 3) 0 else SAMPLE_RATES.getValue(version)[srIndex]
        val padding = ((h ushr 9) and 1).toInt()
        val samples = when (layerIdx) {
            0 -> 384
            1 -> 1152
            else -> if (version == 3) 1152 else 576
        }
        val length =
            if (kbps == 0 || sr == 0) {
                0
            } else if (layerIdx == 0) {
                (12 * kbps * 1000 / sr + padding) * 4
            } else {
                samples / 8 * kbps * 1000 / sr + padding
            }
        return FrameHeader(h, version, layer, kbps * 1000, sr, ((h ushr 6) and 3).toInt(), length, samples)
    }

    private fun findSync(s: ByteSource, from: Long): Long? {
        val window = s.readAtMost(from, SYNC_SEARCH)
        for (i in 0 until window.size - 3) {
            if (window[i] == 0xFF.toByte() && (window[i + 1].toInt() and 0xE0) == 0xE0 && header(s, from + i) != null) return from + i
        }
        return null
    }

    private fun mpeg(ctx: NativeContext, s: ByteSource, at: Long, audioEnd: Long) {
        val f = header(s, at) ?: return
        ctx.collector.directory(MPEG, DirectoryKind.FILE, s.absolute(at), 4, ByteOrderMark.BIG_ENDIAN, note = "first MPEG audio frame")
        fun bits(name: String, v: Long, display: String? = null) = ctx.observe(
            MPEG,
            name,
            NormalizedValue.Integer(v),
            original = ctx.original(s, at, 4, ByteOrderMark.BIG_ENDIAN),
            nativeType = NativeType(TypeSystem.MPEG, "bits"),
            displayValue = display,
            sourcePath = "MPEG/FrameHeader",
        )
        val h = f.raw
        bits(
            "MPEGAudioVersion",
            f.versionBits.toLong(),
            when (f.versionBits) {
                3 -> "1"
                2 -> "2"
                else -> "2.5"
            },
        )
        bits("AudioLayer", f.layerBits.toLong(), (4 - f.layerBits).toString())
        bits("AudioBitrate", f.bitrate.toLong(), if (f.bitrate > 0) "${f.bitrate / 1000} kbps" else "free format")
        bits("SampleRate", f.sampleRate.toLong())
        bits("ChannelMode", f.channelMode.toLong(), arrayOf("Stereo", "Joint Stereo", "Dual Channel", "Single Channel")[f.channelMode])
        if (f.channelMode == 1 && f.layerBits == 1) {
            bits("MSStereo", (h ushr 5) and 1, if ((h ushr 5) and 1L == 1L) "On" else "Off")
            bits("IntensityStereo", (h ushr 4) and 1, if ((h ushr 4) and 1L == 1L) "On" else "Off")
        }
        bits("CopyrightFlag", (h ushr 3) and 1, if ((h ushr 3) and 1L == 1L) "True" else "False")
        bits("OriginalMedia", (h ushr 2) and 1, if ((h ushr 2) and 1L == 1L) "True" else "False")
        bits("Emphasis", h and 3, arrayOf("None", "50/15 ms", "reserved", "CCIT J.17")[(h and 3).toInt()])

        val channels = if (f.channelMode == 3) 1 else 2
        // Xing/Info (layer III, after the side information) or VBRI (32 bytes after the header) give the frame count.
        val side = if (f.versionBits == 3) (if (channels == 1) 17 else 32) else (if (channels == 1) 9 else 17)
        var frames: Long? = null
        var vbrTag: String? = null
        val xingAt = at + 4 + side
        if (s.contains(xingAt, 12) && s.ascii(xingAt, 4).let { it == "Xing" || it == "Info" }) {
            vbrTag = s.ascii(xingAt, 4)
            if (s.u32(xingAt + 4, true) and 1L != 0L) frames = s.u32(xingAt + 8, true)
        } else if (s.contains(at + 36, 18) && s.ascii(at + 36, 4) == "VBRI") {
            vbrTag = "VBRI"
            frames = s.u32(at + 36 + 14, true)
        }
        vbrTag?.let {
            ctx.observe(
                MPEG,
                "VBRHeader",
                NormalizedValue.Text(it),
                original = ctx.original(
                    s,
                    if (it ==
                        "VBRI"
                    ) {
                        at + 36
                    } else {
                        xingAt
                    },
                    4,
                ),
                nativeType = NativeType(TypeSystem.MPEG, "string[4]"),
                sourcePath = "MPEG/VBR",
            )
        }
        frames?.let {
            ctx.observe(
                MPEG,
                "VBRFrames",
                NormalizedValue.Integer(it),
                original = ctx.original(
                    s,
                    if (vbrTag ==
                        "VBRI"
                    ) {
                        at + 50
                    } else {
                        xingAt + 8
                    },
                    4,
                ),
                nativeType = NativeType(TypeSystem.MPEG, "int32u"),
                sourcePath = "MPEG/VBR",
            )
        }
        val estimated = frames == null
        val seconds =
            when {
                f.sampleRate <= 0 -> null
                frames != null && frames > 0 -> frames.toDouble() * f.samplesPerFrame / f.sampleRate
                f.bitrate > 0 -> (audioEnd - at).coerceAtLeast(0).toDouble() * 8 / f.bitrate
                else -> null
            }
        if (estimated && seconds != null) {
            ctx.warnOnce(
                "mp3-duration",
                DiagnosticCode.ESTIMATED_VALUE,
                "MP3 duration estimated from the bit rate (no Xing/VBRI header)",
                severity = Severity.INFO,
            )
        }
        val duration = seconds?.let { Duration.ofNanos((it * 1e9).toLong()) }
        val props = LinkedHashMap<String, NormalizedValue>()
        if (f.sampleRate > 0) props[StreamProps.SAMPLE_RATE] = NormalizedValue.Integer(f.sampleRate.toLong())
        props[StreamProps.CHANNELS] = NormalizedValue.Integer(channels.toLong())
        if (f.bitrate > 0 && vbrTag != "Xing" && vbrTag != "VBRI") props[StreamProps.BIT_RATE] = NormalizedValue.Integer(f.bitrate.toLong())
        seconds?.let { props[StreamProps.DURATION_SECONDS] = NormalizedValue.Decimal(it) }
        val codec = when (4 - f.layerBits) {
            1 -> "mp1"
            2 -> "mp2"
            else -> "mp3"
        }
        ctx.collector.stream(StreamInfo(0, "", StreamKind.AUDIO, codec, "MPEG-${(4 - f.layerBits)}", props))
        ctx.collector.container(ContainerInfo("MP3", duration = duration))
    }

    // ID3v2.3/2.4 and ID3v2.2 frame names, as ExifTool's ID3 tables use them.
    private val V23 = mapOf(
        "TALB" to "Album", "TBPM" to "BeatsPerMinute", "TCOM" to "Composer", "TCON" to "Genre", "TCOP" to "Copyright",
        "TDAT" to "Date", "TDLY" to "PlaylistDelay", "TENC" to "EncodedBy", "TEXT" to "Lyricist", "TFLT" to "FileType",
        "TIME" to "Time", "TIT1" to "Grouping", "TIT2" to "Title", "TIT3" to "Subtitle", "TKEY" to "InitialKey",
        "TLAN" to "Language", "TLEN" to "Length", "TMED" to "Media", "TOAL" to "OriginalAlbum", "TOFN" to "OriginalFileName",
        "TOLY" to "OriginalLyricist", "TOPE" to "OriginalArtist", "TORY" to "OriginalReleaseYear", "TOWN" to "FileOwner",
        "TPE1" to "Artist", "TPE2" to "Band", "TPE3" to "Conductor", "TPE4" to "InterpretedBy", "TPOS" to "PartOfSet",
        "TPUB" to "Publisher", "TRCK" to "Track", "TRDA" to "RecordingDates", "TRSN" to "InternetRadioStationName",
        "TRSO" to "InternetRadioStationOwner", "TSIZ" to "Size", "TSRC" to "ISRC", "TSSE" to "EncoderSettings", "TYER" to "Year",
        "TDRC" to "RecordingTime", "TDRL" to "ReleaseTime", "TDEN" to "EncodingTime", "TDOR" to "OriginalReleaseTime",
        "TDTG" to "TaggingTime", "TSOA" to "AlbumSortOrder", "TSOP" to "PerformerSortOrder", "TSOT" to "TitleSortOrder",
        "TCMP" to "Compilation", "COMM" to "Comment", "USLT" to "Lyrics", "APIC" to "Picture", "TXXX" to "UserDefinedText",
        "WXXX" to "UserDefinedURL", "PRIV" to "Private", "POPM" to "Popularimeter", "PCNT" to "PlayCounter",
        "UFID" to "UniqueFileID", "MCDI" to "MusicCDIdentifier", "SYLT" to "SynLyrics", "RVAD" to "RelativeVolumeAdjustment",
        "RVA2" to "RelativeVolumeAdjustment", "WCOM" to "CommercialURL", "WCOP" to "CopyrightURL", "WOAF" to "FileURL",
        "WOAR" to "ArtistURL", "WOAS" to "SourceURL", "WORS" to "InternetRadioStationURL", "WPAY" to "PaymentURL",
        "WPUB" to "PublisherURL",
    )
    private val V22 = mapOf(
        "TAL" to "Album", "TBP" to "BeatsPerMinute", "TCM" to "Composer", "TCO" to "Genre", "TCR" to "Copyright",
        "TDA" to "Date", "TEN" to "EncodedBy", "TXT" to "Lyricist", "TIM" to "Time", "TT1" to "Grouping", "TT2" to "Title",
        "TT3" to "Subtitle", "TKE" to "InitialKey", "TLA" to "Language", "TLE" to "Length", "TMT" to "Media",
        "TOT" to "OriginalAlbum", "TOF" to "OriginalFileName", "TOL" to "OriginalLyricist", "TOA" to "OriginalArtist",
        "TOR" to "OriginalReleaseYear", "TP1" to "Artist", "TP2" to "Band", "TP3" to "Conductor", "TP4" to "InterpretedBy",
        "TPA" to "PartOfSet", "TPB" to "Publisher", "TRK" to "Track", "TRD" to "RecordingDates", "TSI" to "Size",
        "TRC" to "ISRC", "TSS" to "EncoderSettings", "TYE" to "Year", "COM" to "Comment", "ULT" to "Lyrics", "PIC" to "Picture",
        "TXX" to "UserDefinedText", "WXX" to "UserDefinedURL", "RVA" to "RelativeVolumeAdjustment", "CNT" to "PlayCounter",
        "POP" to "Popularimeter", "UFI" to "UniqueFileID",
    )

    // ID3v1 genres 0-79.
    private val GENRES = listOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal", "New Age", "Oldies",
        "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial", "Alternative", "Ska", "Death Metal", "Pranks",
        "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk", "Fusion", "Trance", "Classical", "Instrumental",
        "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise", "Alt. Rock", "Bass", "Soul", "Punk", "Space", "Meditative",
        "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic", "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk",
        "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta Rap", "Top 40", "Christian Rap", "Pop/Funk", "Jungle",
        "Native American", "Cabaret", "New Wave", "Psychedelic", "Rave", "Showtunes", "Trailer", "Lo-Fi", "Tribal", "Acid Punk",
        "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock",
    )
}
