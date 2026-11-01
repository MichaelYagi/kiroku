package dev.kiroku.format.riff

import dev.kiroku.format.NativeContext
import dev.kiroku.format.icc.IccParser
import dev.kiroku.format.tiff.TiffReader
import dev.kiroku.format.xmp.XmpParser
import dev.kiroku.io.ByteSource
import dev.kiroku.io.Bytes
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

/**
 * The RIFF chunk walker for WebP, AVI and WAV (including RF64/BW64). Media payload chunks
 * (`movi`, `data`, VP8 bitstreams) are skipped. Only their headers are read.
 */
internal class RiffParser(private val ctx: NativeContext, private val s: ByteSource) {
    private var form = ""
    private var ds64DataSize: Long? = null
    private val streams = ArrayList<MutableMap<String, NormalizedValue>>()
    private val streamKinds = ArrayList<StreamKind>()
    private val streamCodecs = ArrayList<String?>()
    private var avih: Map<String, Long> = emptyMap()
    private var wavFmt: Map<String, Long> = emptyMap()
    private var wavDataSize: Long? = null
    private var wavCodec: String? = null
    private var webpFrames = 0
    private var webpDurationMs = 0L

    fun parse() {
        val riff = s.ascii(0, 4)
        form = s.ascii(8, 4)
        val declared = s.u32(4, false)
        val end = if (riff == "RF64" || riff == "BW64" || declared == 0xFFFFFFFFL) s.size else minOf(s.size, 8 + declared)
        if (8 + declared > s.size &&
            riff == "RIFF"
        ) {
            ctx.warn(
                DiagnosticCode.TRUNCATED,
                "RIFF data shorter than declared ($declared bytes)",
                severity = dev.kiroku.model.Severity.INFO,
            )
        }
        ctx.collector.directory(listOf("RIFF", form.trim()), DirectoryKind.RIFF_CHUNK, 0, end)
        walk(12, end, "RIFF/${form.trim()}", 0)
        finish()
    }

    private fun group(): List<String> = when (form) {
        "WEBP" -> listOf("RIFF", "WEBP")
        "AVI " -> listOf("RIFF", "AVI")
        "WAVE" -> listOf("RIFF", "WAVE")
        else -> listOf("RIFF", form.trim())
    }

    private fun walk(start: Long, end: Long, path: String, depth: Int) {
        if (depth > ctx.limits.maxNestingDepth) return
        var pos = start
        val counters = HashMap<String, Int>()
        while (pos + 8 <= end) {
            ctx.countBox()
            val idBytes = s.read(pos, 4)
            val id = Bytes.fourCC(idBytes, 0)
            var size = s.u32(pos + 4, false)
            if (id == "data" && ds64DataSize != null && size == 0xFFFFFFFFL) size = ds64DataSize!!
            val data = pos + 8
            val avail = end - data
            val truncated = size > avail
            if (truncated) {
                if (id != "data" && id != "movi" &&
                    id != "LIST"
                ) {
                    ctx.warn(DiagnosticCode.TRUNCATED, "RIFF chunk '$id' extends beyond the file")
                }
            }
            val n = counters.merge(id, 1, Int::plus)!! - 1
            val cpath = "$path/$id[$n]"
            val len = minOf(size, avail)
            if (id == "LIST" && len >= 4) {
                val listType = s.ascii(data, 4)
                ctx.collector.directory("$path/LIST:$listType[$n]".split('/'), DirectoryKind.RIFF_CHUNK, s.absolute(pos), size + 8)
                when (listType) {
                    "movi", "rec " -> Unit
                    "INFO" -> info(data + 4, data + len, "$path/INFO")
                    else -> walk(data + 4, data + len, "$path/$listType", depth + 1)
                }
            } else {
                chunk(id, Bytes.fourCCValue(idBytes, 0), data, len, size, cpath, depth)
            }
            if (truncated) break
            pos = data + size + (size and 1)
        }
    }

    private fun obs(
        name: String,
        v: NormalizedValue,
        off: Long,
        n: Long,
        path: String,
        fourcc: Long? = null,
        display: String? = null,
        unit: String? = null,
        stream: Int? = null,
        group: List<String> = group(),
    ) = ctx.observe(
        group, name, v, tagId = fourcc, original = ctx.original(s, off, n),
        nativeType = NativeType(TypeSystem.RIFF, if (n in 1..8) "int${n * 8}" else "bytes", null, n),
        displayValue = display, unit = unit, streamIndex = stream, sourcePath = path,
    )

    private fun chunk(id: String, fourcc: Long, d: Long, len: Long, declared: Long, path: String, depth: Int) {
        ctx.collector.directory(path.split('/'), DirectoryKind.RIFF_CHUNK, s.absolute(d - 8), declared + 8)
        when (id) {
            // WebP
            "VP8 " ->
                if (len >= 10) {
                    val b = s.read(d, 10)
                    if (b[3] == 0x9D.toByte() && b[4] == 0x01.toByte() && b[5] == 0x2A.toByte()) {
                        obs("ImageWidth", NormalizedValue.Integer((Bytes.u16(b, 6, false) and 0x3FFF).toLong()), d + 6, 2, path, fourcc)
                        obs("ImageHeight", NormalizedValue.Integer((Bytes.u16(b, 8, false) and 0x3FFF).toLong()), d + 8, 2, path, fourcc)
                        obs("HorizontalScale", NormalizedValue.Integer((Bytes.u16(b, 6, false) shr 14).toLong()), d + 6, 2, path, fourcc)
                        obs("VerticalScale", NormalizedValue.Integer((Bytes.u16(b, 8, false) shr 14).toLong()), d + 8, 2, path, fourcc)
                        obs("WebPEncoding", NormalizedValue.Text("lossy"), d, 3, path, fourcc)
                    }
                }

            "VP8L" ->
                if (len >= 5 && s.u8(d) == 0x2F) {
                    val v = s.u32(d + 1, false)
                    obs("ImageWidth", NormalizedValue.Integer((v and 0x3FFF) + 1), d + 1, 4, path, fourcc)
                    obs("ImageHeight", NormalizedValue.Integer(((v shr 14) and 0x3FFF) + 1), d + 1, 4, path, fourcc)
                    obs("AlphaIsUsed", NormalizedValue.Bool((v shr 28) and 1L == 1L), d + 1, 4, path, fourcc)
                    obs("WebPEncoding", NormalizedValue.Text("lossless"), d, 1, path, fourcc)
                }

            "VP8X" ->
                if (len >= 10) {
                    val f = s.u8(d)
                    obs("WebPFlags", NormalizedValue.Integer(f.toLong()), d, 1, path, fourcc, display = vp8xFlags(f))
                    obs("Animated", NormalizedValue.Bool(f and 0x02 != 0), d, 1, path, fourcc)
                    obs("ImageWidth", NormalizedValue.Integer(Bytes.u24le(s.read(d + 4, 3)) + 1L), d + 4, 3, path, fourcc)
                    obs("ImageHeight", NormalizedValue.Integer(Bytes.u24le(s.read(d + 7, 3)) + 1L), d + 7, 3, path, fourcc)
                }

            "ANIM" ->
                if (len >= 6) {
                    obs("BackgroundColor", NormalizedValue.Integer(s.u32(d, false)), d, 4, path, fourcc)
                    obs("AnimationLoopCount", NormalizedValue.Integer(s.u16(d + 4, false).toLong()), d + 4, 2, path, fourcc)
                }

            "ANMF" -> {
                webpFrames++
                if (len >= 16) webpDurationMs += Bytes.u24le(s.read(d + 12, 3))
            }

            "ICCP" -> IccParser.parse(ctx, s, d, len, "$path/ICC_Profile")

            "EXIF" -> {
                val skip = if (Bytes.startsWith(s.readAtMost(d, 6), Bytes.ascii("Exif\u0000\u0000"))) 6L else 0L
                if (len > skip + 8) TiffReader(ctx, s.slice(d + skip, len - skip), "$path/TIFF").parse()
            }

            "XMP ", "_PMX" -> XmpParser.parse(ctx, s, d, len, "$path/XMP")

            // AVI
            "avih" -> if (len >= 40) avih(d, path)

            "strh" -> if (len >= 48) strh(d, len, path)

            "strf" -> strf(d, len, path)

            "strn" -> {
                val idx = streams.size - 1
                if (idx >= 0) {
                    val t = Bytes.decodeText(s.read(d, minOf(len, 256).toInt())).trimEnd('\u0000')
                    streams[idx]["name"] = NormalizedValue.Text(t)
                    obs("StreamName", NormalizedValue.Text(t), d, len, path, fourcc, stream = idx)
                }
            }

            "strd" ->
                if (len > 12) {
                    // Some cameras (Pentax, Nikon, Fujifilm) put an EXIF/TIFF block in the stream data chunk.
                    val head = s.readAtMost(d, 16)
                    val tiffAt = (0 until minOf(10, head.size - 4)).firstOrNull { i ->
                        (
                            head[i] == 'I'.code.toByte() &&
                                head[i + 1] == 'I'.code.toByte() &&
                                head[i + 2] == 42.toByte()
                            ) ||
                            (head[i] == 'M'.code.toByte() && head[i + 1] == 'M'.code.toByte() && head[i + 3] == 42.toByte())
                    }
                    if (tiffAt != null) {
                        TiffReader(ctx, s.slice(d + tiffAt, len - tiffAt), "$path/TIFF").parse()
                    } else {
                        obs("StreamData", NormalizedValue.Binary(len), d, len, path, fourcc)
                    }
                }

            "IDIT" -> obs(
                "DateTimeOriginal",
                NormalizedValue.Text(Bytes.decodeText(s.read(d, minOf(len, 64).toInt())).trim('\u0000', '\n', ' ')),
                d,
                len,
                path,
                fourcc,
            )

            "ISMP" -> obs(
                "TimeCode",
                NormalizedValue.Text(Bytes.decodeText(s.read(d, minOf(len, 64).toInt())).trim('\u0000', ' ')),
                d,
                len,
                path,
                fourcc,
            )

            // WAV
            "fmt " -> if (len >= 16) wavFmt(d, len, path)

            "fact" -> if (len >= 4) obs("NumSampleFrames", NormalizedValue.Integer(s.u32(d, false)), d, 4, path, fourcc)

            "data" ->
                if (form == "WAVE") {
                    wavDataSize = declared
                    obs("AudioDataSize", NormalizedValue.Integer(declared), d - 4, 4, path, fourcc, unit = "bytes")
                }

            "ds64" ->
                if (len >= 28) {
                    ds64DataSize = s.u64(d + 8, false)
                    obs("DataSize64", NormalizedValue.Integer(ds64DataSize!!), d + 8, 8, path, fourcc)
                }

            "bext" -> bext(d, len, path)

            "iXML" -> {
                val t = Bytes.decodeText(s.read(d, minOf(len, ctx.limits.maxStringLength.toLong()).toInt()))
                obs("iXML", NormalizedValue.Text(t.trimEnd('\u0000')), d, len, path, fourcc)
                parseIxml(t, path)
            }

            "JUNK", "junk", "PAD ", "idx1", "indx", "ix00", "ix01", "dmlh", "vprp", "ALPH", "VP8 data" -> Unit

            else ->
                obs(id.trim(), NormalizedValue.Binary(len), d, len, path, fourcc).also {
                    // Unrecognised chunks are kept byte-exactly.
                }
        }
    }

    private fun avih(d: Long, path: String) {
        val names =
            listOf(
                "MicroSecPerFrame", "MaxDataRate", "PaddingGranularity", "Flags", "FrameCount",
                "InitialFrames", "StreamCount", "SuggestedBufferSize", "ImageWidth", "ImageHeight",
            )
        val m = HashMap<String, Long>()
        names.forEachIndexed { i, n ->
            val v = s.u32(d + i * 4L, false)
            m[n] = v
            obs(n, NormalizedValue.Integer(v), d + i * 4L, 4, path, unit = if (n == "MicroSecPerFrame") "us" else null)
        }
        avih = m
    }

    private fun strh(d: Long, len: Long, path: String) {
        val idx = streams.size
        val type = s.ascii(d, 4)
        val handler = s.ascii(d + 4, 4)
        val kind =
            when (type) {
                "vids" -> StreamKind.VIDEO
                "auds" -> StreamKind.AUDIO
                "txts" -> StreamKind.SUBTITLE
                "mids" -> StreamKind.DATA
                else -> StreamKind.OTHER
            }
        val scale = s.u32(d + 20, false)
        val rate = s.u32(d + 24, false)
        val length = s.u32(d + 32, false)
        val props = LinkedHashMap<String, NormalizedValue>()
        props["handler"] = NormalizedValue.Text(handler.trimEnd('\u0000', ' '))
        if (scale > 0 &&
            rate > 0
        ) {
            props[if (kind == StreamKind.VIDEO) "frameRate" else "rate"] =
                NormalizedValue.RationalValue(Rational(rate, scale).reduced())
        }
        props["length"] = NormalizedValue.Integer(length)
        if (scale > 0 && rate > 0) props["durationSeconds"] = NormalizedValue.Decimal(length.toDouble() * scale / rate)
        streams += props
        streamKinds += kind
        streamCodecs += handler.trimEnd('\u0000', ' ').takeIf { it.isNotBlank() && kind == StreamKind.VIDEO }
        obs("StreamType", NormalizedValue.Text(type), d, 4, path, stream = idx)
        obs("StreamHandler", NormalizedValue.Text(handler), d + 4, 4, path, stream = idx)
        obs("Scale", NormalizedValue.Integer(scale), d + 20, 4, path, stream = idx)
        obs("Rate", NormalizedValue.Integer(rate), d + 24, 4, path, stream = idx)
        obs("Start", NormalizedValue.Integer(s.u32(d + 28, false)), d + 28, 4, path, stream = idx)
        obs("Length", NormalizedValue.Integer(length), d + 32, 4, path, stream = idx)
        obs("Quality", NormalizedValue.Integer(s.u32(d + 40, false)), d + 40, 4, path, stream = idx)
        obs("SampleSize", NormalizedValue.Integer(s.u32(d + 44, false)), d + 44, 4, path, stream = idx)
        if (kind == StreamKind.VIDEO && scale > 0) {
            obs("VideoFrameRate", NormalizedValue.RationalValue(Rational(rate, scale)), d + 20, 8, path, stream = idx, unit = "fps")
        }
    }

    private fun strf(d: Long, len: Long, path: String) {
        val idx = streams.size - 1
        if (idx < 0) return
        val props = streams[idx]
        when (streamKinds[idx]) {
            StreamKind.VIDEO ->
                if (len >= 40) {
                    val w = s.u32(d + 4, false).toInt()
                    val h = s.u32(d + 8, false).toInt()
                    val bits = s.u16(d + 14, false)
                    val comp = s.ascii(d + 16, 4)
                    props["width"] = NormalizedValue.Integer(w.toLong())
                    props["height"] = NormalizedValue.Integer(kotlin.math.abs(h).toLong())
                    props["bitsPerPixel"] = NormalizedValue.Integer(bits.toLong())
                    props["compression"] = NormalizedValue.Text(comp)
                    streamCodecs[idx] = codecFromFourCC(comp) ?: streamCodecs[idx]
                    obs("ImageWidth", NormalizedValue.Integer(w.toLong()), d + 4, 4, path, stream = idx)
                    obs("ImageHeight", NormalizedValue.Integer(kotlin.math.abs(h).toLong()), d + 8, 4, path, stream = idx)
                    obs("BitDepth", NormalizedValue.Integer(bits.toLong()), d + 14, 2, path, stream = idx)
                    obs("Compression", NormalizedValue.Text(comp), d + 16, 4, path, stream = idx)
                }

            StreamKind.AUDIO ->
                if (len >= 16) {
                    val fmt = readWaveFormat(d, len, path, idx)
                    props.putAll(audioProps(fmt))
                    streamCodecs[idx] = audioCodec(fmt["Encoding"] ?: 0, null)
                }

            else -> Unit
        }
    }

    private fun readWaveFormat(d: Long, len: Long, path: String, stream: Int?): Map<String, Long> {
        val m = LinkedHashMap<String, Long>()
        m["Encoding"] = s.u16(d, false).toLong()
        m["NumChannels"] = s.u16(d + 2, false).toLong()
        m["SampleRate"] = s.u32(d + 4, false)
        m["AvgBytesPerSec"] = s.u32(d + 8, false)
        m["BlockAlign"] = s.u16(d + 12, false).toLong()
        m["BitsPerSample"] = s.u16(d + 14, false).toLong()
        val offsets = mapOf(
            "Encoding" to 0L,
            "NumChannels" to 2L,
            "SampleRate" to 4L,
            "AvgBytesPerSec" to 8L,
            "BlockAlign" to 12L,
            "BitsPerSample" to 14L,
        )
        for ((k, v) in m) {
            val o = offsets.getValue(k)
            obs(
                k,
                NormalizedValue.Integer(v),
                d + o,
                if (k == "SampleRate" ||
                    k == "AvgBytesPerSec"
                ) {
                    4
                } else {
                    2
                },
                path,
                stream = stream,
                display = if (k == "Encoding") encodingName(v) else null,
            )
        }
        if (m["Encoding"] == 0xFFFEL && len >= 40) {
            m["ValidBitsPerSample"] = s.u16(d + 18, false).toLong()
            m["ChannelMask"] = s.u32(d + 20, false)
            m["SubFormat"] = s.u16(d + 24, false).toLong()
            obs("ValidBitsPerSample", NormalizedValue.Integer(m["ValidBitsPerSample"]!!), d + 18, 2, path, stream = stream)
            obs("ChannelMask", NormalizedValue.Integer(m["ChannelMask"]!!), d + 20, 4, path, stream = stream)
            obs(
                "SubFormatGUID",
                NormalizedValue.Text(
                    s.read(d + 24, 16).joinToString("") {
                        "%02x".format(it)
                    },
                ),
                d + 24,
                16,
                path,
                stream = stream,
            )
        }
        return m
    }

    private fun wavFmt(d: Long, len: Long, path: String) {
        wavFmt = readWaveFormat(d, len, path, 0)
        wavCodec = audioCodec(wavFmt["Encoding"] ?: 0, wavFmt["SubFormat"])
    }

    /** Maps WAVEFORMAT fields to the shared stream property keys. */
    private fun audioProps(fmt: Map<String, Long>): Map<String, NormalizedValue> {
        val out = LinkedHashMap<String, NormalizedValue>()
        fmt["NumChannels"]?.let { out["channels"] = NormalizedValue.Integer(it) }
        fmt["SampleRate"]?.let { out["sampleRate"] = NormalizedValue.Integer(it) }
        fmt["BitsPerSample"]?.takeIf { it > 0 }?.let { out["bitsPerSample"] = NormalizedValue.Integer(it) }
        fmt["AvgBytesPerSec"]?.takeIf { it > 0 }?.let { out["bitRate"] = NormalizedValue.Integer(it * 8) }
        fmt["Encoding"]?.let { out["formatTag"] = NormalizedValue.Integer(it) }
        fmt["ChannelMask"]?.let { out["channelMask"] = NormalizedValue.Integer(it) }
        return out
    }

    private fun bext(d: Long, len: Long, path: String) {
        if (len < 346) {
            obs("BroadcastExtension", NormalizedValue.Binary(len), d, len, path)
            return
        }
        val g = listOf("RIFF", "BroadcastExtension")
        fun txt(name: String, off: Long, n: Int) {
            val t = Bytes.decodeText(s.read(d + off, n)).trimEnd('\u0000', ' ')
            if (t.isNotEmpty()) obs(name, NormalizedValue.Text(t), d + off, n.toLong(), path, group = g)
        }
        txt("Description", 0, 256)
        txt("Originator", 256, 32)
        txt("OriginatorReference", 288, 32)
        txt("DateTimeOriginal", 320, 10)
        txt("TimeOriginal", 330, 8)
        obs("TimeReference", NormalizedValue.Integer(s.u64(d + 338, false)), d + 338, 8, path, group = g, unit = "samples")
        obs("BWFVersion", NormalizedValue.Integer(s.u16(d + 346, false).toLong()), d + 346, 2, path, group = g)
        if (len >=
            412
        ) {
            obs(
                "BWF_UMID",
                NormalizedValue.Text(
                    s.read(d + 348, 64).joinToString("") {
                        "%02x".format(it)
                    },
                ),
                d + 348,
                64,
                path,
                group = g,
            )
        }
        if (len > 602) {
            val n = minOf(len - 602, ctx.limits.maxStringLength.toLong()).toInt()
            val hist = Bytes.decodeText(s.read(d + 602, n)).trimEnd('\u0000', ' ', '\r', '\n')
            if (hist.isNotEmpty()) obs("CodingHistory", NormalizedValue.Text(hist), d + 602, n.toLong(), path, group = g)
        }
    }

    private fun parseIxml(text: String, path: String) {
        // iXML is flat, well-known XML. A few production fields are extracted; the whole document is kept above.
        for (tag in listOf("PROJECT", "SCENE", "TAKE", "TAPE", "NOTE", "CIRCLED", "FILE_UID", "UBITS")) {
            val m = Regex("<$tag>([^<]{0,4096})</$tag>").find(text) ?: continue
            ctx.observe(
                listOf("RIFF", "iXML"),
                tag.lowercase().replaceFirstChar {
                    it.uppercaseChar()
                },
                NormalizedValue.Text(m.groupValues[1].trim()),
                nativeType = NativeType(TypeSystem.RIFF, "iXML"),
                sourcePath = "$path/$tag",
            )
        }
    }

    private fun info(start: Long, end: Long, path: String) {
        var pos = start
        while (pos + 8 <= end) {
            ctx.countBox()
            val idBytes = s.read(pos, 4)
            val id = Bytes.fourCC(idBytes, 0)
            val size = s.u32(pos + 4, false)
            val d = pos + 8
            if (d + size > end) break
            val text = Bytes.decodeText(s.read(d, minOf(size, ctx.limits.maxStringLength.toLong()).toInt())).trimEnd('\u0000', ' ')
            val name = INFO_NAMES[id]
            ctx.observe(
                listOf("RIFF", "INFO"),
                name ?: id,
                NormalizedValue.Text(text),
                tagId = Bytes.fourCCValue(idBytes, 0),
                original = ctx.original(s, d, size),
                nativeType = NativeType(TypeSystem.RIFF, "ZSTR", null, size),
                known = name != null,
                sourcePath = "$path/$id",
            )
            pos = d + size + (size and 1)
        }
    }

    private fun finish() {
        when (form) {
            "AVI " -> {
                val usPerFrame = avih["MicroSecPerFrame"] ?: 0
                val frames = avih["FrameCount"] ?: 0
                val dur = if (usPerFrame > 0 && frames > 0) Duration.ofNanos(usPerFrame * frames * 1000) else null
                streams.forEachIndexed { i, p ->
                    ctx.collector.stream(
                        StreamInfo(i, "", streamKinds[i], streamCodecs[i], (p["handler"] as? NormalizedValue.Text)?.value, p.toMap()),
                    )
                }
                ctx.collector.container(
                    ContainerInfo(
                        "AVI",
                        duration = dur,
                        properties = avih.mapValues {
                            NormalizedValue.Integer(it.value)
                        },
                    ),
                )
            }

            "WAVE" -> {
                val byteRate = wavFmt["AvgBytesPerSec"] ?: 0
                val size = wavDataSize
                val dur = if (byteRate > 0 && size != null) Duration.ofNanos((size.toDouble() / byteRate * 1e9).toLong()) else null
                val props = LinkedHashMap<String, NormalizedValue>(audioProps(wavFmt))
                dur?.let { props["durationSeconds"] = NormalizedValue.Decimal(it.toNanos() / 1e9) }
                if (wavFmt.isNotEmpty()) {
                    ctx.collector.stream(
                        StreamInfo(
                            0,
                            "",
                            StreamKind.AUDIO,
                            wavCodec,
                            wavFmt["Encoding"]?.let {
                                "0x%04x".format(it)
                            },
                            props,
                        ),
                    )
                }
                ctx.collector.container(
                    ContainerInfo(
                        "WAV",
                        duration = dur,
                        bitRateBitsPerSecond = if (byteRate >
                            0
                        ) {
                            byteRate * 8
                        } else {
                            null
                        },
                    ),
                )
            }

            "WEBP" -> {
                if (webpFrames > 0) {
                    ctx.observe(
                        group(),
                        "FrameCount",
                        NormalizedValue.Integer(webpFrames.toLong()),
                        nativeType = NativeType(TypeSystem.RIFF, "derived"),
                    )
                    ctx.observe(
                        group(),
                        "Duration",
                        NormalizedValue.Decimal(webpDurationMs / 1000.0),
                        unit = "s",
                        nativeType = NativeType(TypeSystem.RIFF, "derived"),
                    )
                }
            }
        }
    }

    companion object {
        val INFO_NAMES =
            mapOf(
                "IARL" to "ArchivalLocation", "IART" to "Artist", "ICMS" to "Commissioned", "ICMT" to "Comment",
                "ICOP" to "Copyright", "ICRD" to "DateCreated", "ICRP" to "Cropped", "IDIM" to "Dimensions",
                "IDPI" to "DotsPerInch", "IENG" to "Engineer", "IGNR" to "Genre", "IKEY" to "Keywords",
                "ILGT" to "Lightness", "IMED" to "Medium", "INAM" to "Title", "IPLT" to "NumColors",
                "IPRD" to "Product", "ISBJ" to "Subject", "ISFT" to "Software", "ISHP" to "Sharpness",
                "ISRC" to "Source", "ISRF" to "SourceForm", "ITCH" to "Technician", "ITRK" to "TrackNumber",
                "IPRT" to "Part", "ILNG" to "Language", "ISMP" to "TimeCode", "IDIT" to "DateTimeOriginal",
                "TAPE" to "TapeName", "TCOD" to "StartTimecode", "TCDO" to "EndTimecode", "CMNT" to "Comment",
                "IAS1" to "FirstLanguage", "ISGN" to "SecondaryGenre", "IWRI" to "WrittenBy", "IPRO" to "ProducedBy",
                "ICNM" to "Cinematographer", "IEDT" to "EditedBy", "ICNT" to "Country", "ISTR" to "Starring",
                "IBSU" to "BaseURL", "ICAS" to "DefaultAudioStream", "IENC" to "EncodedBy", "IRTD" to "Rating",
                "IMAK" to "Make", "IMOD" to "Model",
            )

        fun codecFromFourCC(f: String): String? = when (f.uppercase().trimEnd('\u0000', ' ')) {
            "H264", "X264", "AVC1", "DAVC" -> "h264"
            "HEVC", "H265", "HVC1", "HEV1" -> "hevc"
            "MJPG", "AVRN", "LJPG", "JPGL" -> "mjpeg"
            "XVID", "DIVX", "DX50", "FMP4", "MP4V" -> "mpeg4"
            "DIV3", "MP43" -> "msmpeg4v3"
            "VP80" -> "vp8"
            "VP90" -> "vp9"
            "MPG2" -> "mpeg2video"
            "DVSD", "DV25", "DVHD" -> "dvvideo"
            "" -> null
            else -> f.trimEnd('\u0000', ' ').lowercase().takeIf { it.isNotBlank() }
        }

        fun audioCodec(tag: Long, sub: Long?): String? = when (if (tag == 0xFFFEL && sub != null) sub else tag) {
            1L -> "pcm"
            2L -> "adpcm_ms"
            3L -> "pcm_float"
            6L -> "pcm_alaw"
            7L -> "pcm_mulaw"
            0x11L -> "adpcm_ima_wav"
            0x50L -> "mp2"
            0x55L -> "mp3"
            0xFFL, 0x1610L -> "aac"
            0x2000L -> "ac3"
            0x2001L -> "dts"
            0xF1ACL -> "flac"
            0x6771L -> "opus"
            0xFFFEL -> null
            else -> "0x%04x".format(tag)
        }

        fun encodingName(tag: Long): String? = when (tag) {
            1L -> "Microsoft PCM"
            3L -> "IEEE Float"
            6L -> "A-law"
            7L -> "mu-law"
            0x55L -> "MPEG Layer 3"
            0xFFFEL -> "Extensible"
            else -> null
        }

        private fun vp8xFlags(f: Int): String = buildList {
            if (f and 0x20 != 0) add("ICC Profile")
            if (f and 0x10 != 0) add("Alpha")
            if (f and 0x08 != 0) add("EXIF")
            if (f and 0x04 != 0) add("XMP")
            if (f and 0x02 != 0) add("Animation")
        }.joinToString(", ").ifEmpty { "(none)" }
    }
}

internal fun Bytes.u24le(b: ByteArray): Int = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or ((b[2].toInt() and 0xFF) shl 16)
