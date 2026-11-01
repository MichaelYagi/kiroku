package dev.kiroku.format.isobmff

import dev.kiroku.format.NativeContext
import dev.kiroku.format.icc.IccParser
import dev.kiroku.format.tiff.TiffReader
import dev.kiroku.format.xmp.XmpParser
import dev.kiroku.io.ByteSource
import dev.kiroku.io.Bytes
import dev.kiroku.model.ContainerInfo
import dev.kiroku.model.DiagnosticCode
import dev.kiroku.model.DirectoryKind
import dev.kiroku.model.EmbeddedImageInfo
import dev.kiroku.model.EmbeddedImageKind
import dev.kiroku.model.MediaFormat
import dev.kiroku.model.NativeType
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.Rational
import dev.kiroku.model.Severity
import dev.kiroku.model.StreamInfo
import dev.kiroku.model.StreamKind
import dev.kiroku.model.TypeSystem
import java.time.Duration
import java.time.Instant

/**
 * ISO Base Media File Format parser, covering QuickTime (MOV), MP4/M4V/M4A/3GP, and HEIF/HEIC/AVIF.
 *
 * It reads box structure only. Media samples (`mdat`) are never read, except for metadata items
 * (HEIF Exif/XMP) located through `iloc`. Groups follow ExifTool's QuickTime family-1 names where
 * they exist: `MovieHeader`, `Track<n>`, `UserData`, `Keys`, `ItemList`, `Meta`.
 */
internal class IsoBmffParser(private val ctx: NativeContext, private val s: ByteSource, private val format: MediaFormat) {
    private class Track(val number: Int) {
        var handler: String? = null
        var handlerName: String? = null
        val props = LinkedHashMap<String, NormalizedValue>()
        var codec: String? = null
        var codecTag: String? = null
        var timescale: Long = 0
        var durationUnits: Long = 0
        var sampleCount: Long? = null
        var sttsDeltas: MutableMap<Long, Long> = HashMap()
        var totalSampleBytes: Long? = null
    }

    private var movieTimescale = 0L
    private var movieDuration = 0L
    private val tracks = ArrayList<Track>()
    private var current: Track? = null
    private val keys = ArrayList<String>()
    private var brands: List<String> = emptyList()

    // HEIF item state
    private var primaryItem: Long? = null
    private val items = LinkedHashMap<Long, Item>()
    private val itemLocations = HashMap<Long, List<Pair<Long, Long>>>()
    private val itemConstruction = HashMap<Long, Int>()
    private var idatOffset: Long? = null
    private val properties = ArrayList<Pair<String, Long>>() // (type, absolute payload offset), in ipco order
    private val propertyBoxes = ArrayList<Triple<String, Long, Long>>() // (type, payload offset, payload length)
    private val associations = HashMap<Long, List<Int>>()
    private val references = ArrayList<Triple<String, Long, List<Long>>>()

    private data class Item(val id: Long, val type: String, val name: String?, val contentType: String?, val hidden: Boolean)

    fun parse() {
        walk(0, s.size, "ISOBMFF", 0, null)
        finishTracks()
        finishHeif()
        val duration = if (movieTimescale > 0 &&
            movieDuration > 0
        ) {
            Duration.ofNanos((movieDuration.toDouble() / movieTimescale * 1e9).toLong())
        } else {
            null
        }
        val bitrate = duration?.let { d -> if (d.toMillis() > 0) (s.size * 8.0 / (d.toNanos() / 1e9)).toLong() else null }
        if (format != MediaFormat.HEIF && format != MediaFormat.HEIC && format != MediaFormat.AVIF || tracks.isNotEmpty()) {
            ctx.collector.container(ContainerInfo(format.id, brands, duration, bitrate))
        }
    }

    // ---- Box walking ----------------------------------------------------------------------------------------

    private fun walk(start: Long, end: Long, path: String, depth: Int, parent: String?) {
        if (depth > ctx.limits.maxNestingDepth) {
            ctx.warn(DiagnosticCode.LIMIT_EXCEEDED, "Box nesting deeper than ${ctx.limits.maxNestingDepth}")
            return
        }
        var pos = start
        val counters = HashMap<String, Int>()
        while (pos + 8 <= end) {
            ctx.countBox()
            var size = s.u32(pos, true)
            val typeBytes = s.read(pos + 4, 4)
            val type = Bytes.fourCC(typeBytes, 0)
            var header = 8L
            if (size == 1L) {
                if (pos + 16 > end) break
                size = s.u64(pos + 8, true)
                header = 16
            } else if (size == 0L) {
                size = end - pos
            }
            if (size < header) {
                // QuickTime allows a 32-bit zero terminator inside udta.
                if (size == 0L || parent == "udta") break
                ctx.warn(DiagnosticCode.MALFORMED, "Invalid box size $size for '$type'")
                break
            }
            if (type == "uuid") header += 16
            val payload = pos + header
            val boxEnd = pos + size
            if (boxEnd > end) {
                if (type != "mdat") ctx.warn(DiagnosticCode.TRUNCATED, "Box '$type' extends beyond its parent or the file")
                if (type == "mdat" || type == "free" || type == "skip") break
            }
            val clampedEnd = minOf(boxEnd, end)
            val n = counters.merge(type, 1, Int::plus)!! - 1
            val bpath = "$path/$type[$n]"
            if (type != "mdat" && type != "free" && type != "skip" && type != "wide") {
                ctx.collector.directory(bpath.split('/'), DirectoryKind.ISOBMFF_BOX, s.absolute(pos), size)
            }
            box(type, typeBytes, pos, payload, clampedEnd, bpath, depth, parent)
            if (boxEnd > end) break
            pos = boxEnd
        }
    }

    private fun box(type: String, typeBytes: ByteArray, boxStart: Long, p: Long, end: Long, path: String, depth: Int, parent: String?) {
        val len = end - p
        when (type) {
            "ftyp" -> ftyp(p, len, path)

            "moov", "mdia", "minf", "stbl", "edts", "dinf", "mvex", "tref", "gmhd", "iprp", "sinf", "schi" -> walk(
                p,
                end,
                path,
                depth + 1,
                type,
            )

            "trak" -> {
                val t = Track(tracks.size + 1)
                tracks += t
                current = t
                walk(p, end, path, depth + 1, type)
                current = null
            }

            "udta" -> walk(p, end, path, depth + 1, "udta")

            "meta" -> {
                // ISO meta is a FullBox. QuickTime meta is not: detect from whether 'hdlr' follows directly.
                val isFull = len >= 12 && s.ascii(p + 4, 4) != "hdlr"
                walk(if (isFull) p + 4 else p, end, path, depth + 1, "meta")
            }

            "mvhd" -> mvhd(p, len, path)

            "tkhd" -> tkhd(p, len, path)

            "mdhd" -> mdhd(p, len, path)

            "hdlr" -> hdlr(p, len, path, parent)

            "stsd" -> stsd(p, end, path, depth)

            "stts" -> stts(p, len)

            "stsz" -> stsz(p, len)

            "keys" -> keys(p, len)

            "ilst" -> ilst(p, end, path)

            "pitm" -> primaryItem = if (s.u8(p) == 0) s.u16(p + 4, true).toLong() else s.u32(p + 4, true)

            "iinf" -> iinf(p, end, path, depth)

            "iloc" -> iloc(p, len)

            "idat" -> idatOffset = p

            "iref" -> iref(p, end)

            "ipco" -> ipco(p, end, path)

            "ipma" -> ipma(p, len)

            "uuid" -> uuid(boxStart, p, end, path)

            "XMP_" -> XmpParser.parse(ctx, s, p, len, "$path/XMP")

            "free", "skip", "wide", "mdat", "moof", "mfra", "sidx", "styp", "emsg", "prft", "pdin" -> Unit

            else ->
                if (parent == "udta") {
                    udtaItem(type, typeBytes, p, len, end, path, depth)
                } else if (parent == "meta" && type == "dinf") {
                    Unit
                } else {
                    raw(group(), type, typeBytes, p, len, path, known = false)
                }
        }
    }

    private fun group(): List<String> = current?.let { listOf("QuickTime", "Track${it.number}") } ?: listOf("QuickTime", "QuickTime")

    private fun stream(): Int? = current?.let { it.number - 1 }

    private fun obs(
        g: List<String>,
        name: String,
        v: NormalizedValue,
        off: Long,
        n: Long,
        path: String,
        type: String = "int${n * 8}u",
        display: String? = null,
        unit: String? = null,
        tagId: Long? = null,
        namespace: String? = null,
        known: Boolean = true,
    ) = ctx.observe(
        g, name, v, tagId = tagId, original = ctx.original(s, off, n),
        nativeType = NativeType(TypeSystem.ISOBMFF, type, null, n), displayValue = display, unit = unit,
        streamIndex = stream(), sourcePath = path, namespace = namespace, known = known,
    )

    private fun raw(g: List<String>, name: String, typeBytes: ByteArray, p: Long, len: Long, path: String, known: Boolean) =
        obs(g, name, NormalizedValue.Binary(len), p, len, path, type = "box", tagId = Bytes.fourCCValue(typeBytes, 0), known = known)

    // ---- Movie/track headers -----------------------------------------------------------------------------

    private fun ftyp(p: Long, len: Long, path: String) {
        if (len < 8) return
        val b = s.read(p, minOf(len, 1024).toInt())
        val g = listOf("QuickTime", "FileType")
        obs(g, "MajorBrand", NormalizedValue.Text(Bytes.fourCC(b, 0).trim()), p, 4, path, "fourcc")
        obs(g, "MinorVersion", NormalizedValue.Integer(Bytes.u32(b, 4, true)), p + 4, 4, path)
        val compat = (8 until b.size - 3 step 4).map { Bytes.fourCC(b, it).trim() }
        if (compat.isNotEmpty()) {
            obs(
                g,
                "CompatibleBrands",
                NormalizedValue.ListValue(
                    compat.map {
                        NormalizedValue.Text(it)
                    },
                ),
                p + 8,
                (b.size - 8).toLong(),
                path,
                "fourcc[]",
            )
        }
        brands = (listOf(Bytes.fourCC(b, 0).trim()) + compat).distinct()
    }

    private fun mvhd(p: Long, len: Long, path: String) {
        val g = listOf("QuickTime", "MovieHeader")
        val v = s.u8(p)
        obs(g, "MovieHeaderVersion", NormalizedValue.Integer(v.toLong()), p, 1, path)
        var o = p + 4
        val w = if (v == 1) 8L else 4L
        if (len < (if (v == 1) 112 else 100)) return
        timeObs(g, "CreateDate", o, w, path)
        timeObs(g, "ModifyDate", o + w, w, path)
        o += 2 * w
        movieTimescale = s.u32(o, true)
        obs(g, "TimeScale", NormalizedValue.Integer(movieTimescale), o, 4, path)
        movieDuration = if (v == 1) s.u64(o + 4, true) else s.u32(o + 4, true)
        obs(
            g,
            "Duration",
            NormalizedValue.Integer(movieDuration),
            o + 4,
            w,
            path,
            display = seconds(movieDuration, movieTimescale),
            unit = "timescale units",
        )
        o += 4 + w
        obs(g, "PreferredRate", NormalizedValue.Decimal(s.u32(o, true).toInt() / 65536.0), o, 4, path, "fixed16.16")
        obs(g, "PreferredVolume", NormalizedValue.Decimal(s.u16(o + 4, true).toShort() / 256.0), o + 4, 2, path, "fixed8.8")
        obs(g, "MatrixStructure", matrix(o + 16), o + 16, 36, path, "matrix")
        obs(g, "NextTrackID", NormalizedValue.Integer(s.u32(o + 76, true)), o + 76, 4, path)
    }

    private fun tkhd(p: Long, len: Long, path: String) {
        val t = current ?: return
        val g = group()
        val v = s.u8(p)
        val flags = s.u32(p, true) and 0xFFFFFF
        val w = if (v == 1) 8L else 4L
        if (len < (if (v == 1) 96 else 84)) return
        obs(g, "TrackHeaderVersion", NormalizedValue.Integer(v.toLong()), p, 1, path)
        obs(g, "TrackEnabled", NormalizedValue.Bool(flags and 1L != 0L), p + 1, 3, path)
        var o = p + 4
        timeObs(g, "TrackCreateDate", o, w, path)
        timeObs(g, "TrackModifyDate", o + w, w, path)
        o += 2 * w
        obs(g, "TrackID", NormalizedValue.Integer(s.u32(o, true)), o, 4, path)
        val dur = if (v == 1) s.u64(o + 8, true) else s.u32(o + 8, true)
        obs(
            g,
            "TrackDuration",
            NormalizedValue.Integer(dur),
            o + 8,
            w,
            path,
            display = seconds(dur, movieTimescale),
            unit = "movie timescale units",
        )
        o += 8 + w + 8
        obs(g, "TrackLayer", NormalizedValue.Integer(s.u16(o, true).toShort().toLong()), o, 2, path)
        obs(g, "AlternateGroup", NormalizedValue.Integer(s.u16(o + 2, true).toLong()), o + 2, 2, path)
        obs(g, "TrackVolume", NormalizedValue.Decimal(s.u16(o + 4, true).toShort() / 256.0), o + 4, 2, path, "fixed8.8")
        o += 8
        val m = matrix(o)
        obs(g, "MatrixStructure", m, o, 36, path, "matrix")
        t.props["matrix"] = m
        val tw = s.u32(o + 36, true) / 65536.0
        val th = s.u32(o + 40, true) / 65536.0
        obs(g, "ImageWidth", NormalizedValue.Decimal(tw), o + 36, 4, path, "fixed16.16")
        obs(g, "ImageHeight", NormalizedValue.Decimal(th), o + 40, 4, path, "fixed16.16")
        t.props["enabled"] = NormalizedValue.Bool(flags and 1L != 0L)
        if (tw > 0) t.props["displayWidth"] = NormalizedValue.Decimal(tw)
        if (th > 0) t.props["displayHeight"] = NormalizedValue.Decimal(th)
    }

    private fun mdhd(p: Long, len: Long, path: String) {
        val t = current ?: return
        val g = group()
        val v = s.u8(p)
        val w = if (v == 1) 8L else 4L
        if (len < 4 + 2 * w + 4 + w + 4) return
        var o = p + 4
        timeObs(g, "MediaCreateDate", o, w, path)
        timeObs(g, "MediaModifyDate", o + w, w, path)
        o += 2 * w
        t.timescale = s.u32(o, true)
        t.durationUnits = if (v == 1) s.u64(o + 4, true) else s.u32(o + 4, true)
        obs(g, "MediaTimeScale", NormalizedValue.Integer(t.timescale), o, 4, path)
        obs(
            g,
            "MediaDuration",
            NormalizedValue.Integer(t.durationUnits),
            o + 4,
            w,
            path,
            display = seconds(t.durationUnits, t.timescale),
            unit = "media timescale units",
        )
        val langCode = s.u16(o + 4 + w, true)
        val lang = language(langCode)
        obs(g, "MediaLanguageCode", NormalizedValue.Text(lang ?: langCode.toString()), o + 4 + w, 2, path, "packedISO639")
        if (lang != null && lang != "und") t.props["language"] = NormalizedValue.Text(lang)
        if (t.timescale > 0) t.props["durationSeconds"] = NormalizedValue.Decimal(t.durationUnits.toDouble() / t.timescale)
    }

    private fun hdlr(p: Long, len: Long, path: String, parent: String?) {
        if (len < 24) return
        val type = s.ascii(p + 8, 4)
        val nameLen = (len - 24).toInt().coerceIn(0, 256)
        var name = if (nameLen > 0) Bytes.decodeText(s.read(p + 24, nameLen)).trimEnd('\u0000') else ""
        // QuickTime stores a Pascal string. ISO stores a NUL-terminated one.
        if (name.isNotEmpty() && name[0].code == name.length - 1) name = name.substring(1)
        val g = if (parent == "meta") listOf("QuickTime", "Meta") else group()
        obs(g, "HandlerType", NormalizedValue.Text(type), p + 8, 4, path, "fourcc", display = handlerName(type))
        if (name.isNotBlank()) obs(g, "HandlerDescription", NormalizedValue.Text(name), p + 24, nameLen.toLong(), path, "string")
        val t = current
        if (t != null && parent != "meta" && t.handler == null) {
            t.handler = type
            t.handlerName = name.ifBlank { null }
        }
    }

    // ---- Sample descriptions -------------------------------------------------------------------------------

    private fun stsd(p: Long, end: Long, path: String, depth: Int) {
        val t = current ?: return
        if (end - p < 8) return
        val count = s.u32(p + 4, true)
        var pos = p + 8
        for (i in 0 until minOf(count, 16L).toInt()) {
            if (pos + 8 > end) break
            ctx.countBox()
            val size = s.u32(pos, true)
            if (size < 16 || pos + size > end) break
            val fmt = s.ascii(pos + 4, 4)
            val epath = "$path/$fmt[$i]"
            if (i == 0) {
                t.codecTag = fmt
                obs(group(), "CompressorID", NormalizedValue.Text(fmt), pos + 4, 4, epath, "fourcc")
            }
            when (t.handler) {
                "vide" -> videoEntry(t, pos + 8, pos + size, epath, depth, i == 0)
                "soun" -> audioEntry(t, pos + 8, pos + size, epath, depth, i == 0)
                else -> if (i == 0) t.codec = otherCodec(fmt)
            }
            pos += size
        }
    }

    private fun videoEntry(t: Track, p: Long, end: Long, path: String, depth: Int, primary: Boolean) {
        if (end - p < 78) return
        val g = group()
        val w = s.u16(p + 24, true)
        val h = s.u16(p + 26, true)
        val compNameLen = s.u8(p + 42).coerceAtMost(31)
        val compName = if (compNameLen > 0) Bytes.decodeText(s.read(p + 43, compNameLen)) else ""
        val bitDepth = s.u16(p + 74, true)
        val fmt = t.codecTag.orEmpty()
        if (primary) {
            t.codec = videoCodec(fmt)
            t.props["width"] = NormalizedValue.Integer(w.toLong())
            t.props["height"] = NormalizedValue.Integer(h.toLong())
            obs(g, "SourceImageWidth", NormalizedValue.Integer(w.toLong()), p + 24, 2, path)
            obs(g, "SourceImageHeight", NormalizedValue.Integer(h.toLong()), p + 26, 2, path)
            obs(g, "XResolution", NormalizedValue.Decimal(s.u32(p + 28, true) / 65536.0), p + 28, 4, path, "fixed16.16")
            obs(g, "YResolution", NormalizedValue.Decimal(s.u32(p + 32, true) / 65536.0), p + 32, 4, path, "fixed16.16")
            if (compName.isNotBlank()) {
                obs(g, "CompressorName", NormalizedValue.Text(compName), p + 43, compNameLen.toLong(), path, "pstring")
                t.props["compressorName"] = NormalizedValue.Text(compName)
            }
            obs(g, "BitDepth", NormalizedValue.Integer(bitDepth.toLong()), p + 74, 2, path)
        }
        // Child boxes follow the 78-byte visual sample entry.
        var pos = p + 78
        while (pos + 8 <= end) {
            ctx.countBox()
            val size = s.u32(pos, true)
            if (size < 8 || pos + size > end) break
            val type = s.ascii(pos + 4, 4)
            val d = pos + 8
            val len = size - 8
            val cpath = "$path/$type"
            if (primary) {
                when (type) {
                    "avcC" ->
                        if (len >= 4) {
                            val prof = s.u8(d + 1)
                            val level = s.u8(d + 3)
                            t.props["profile"] = NormalizedValue.Text(avcProfile(prof))
                            t.props["level"] = NormalizedValue.Text("%.1f".format(level / 10.0))
                            obs(
                                g,
                                "AVCProfileIndication",
                                NormalizedValue.Integer(prof.toLong()),
                                d + 1,
                                1,
                                cpath,
                                display = avcProfile(prof),
                            )
                            obs(g, "AVCLevelIndication", NormalizedValue.Integer(level.toLong()), d + 3, 1, cpath)
                        }

                    "hvcC" ->
                        if (len >= 23) {
                            val b = s.read(d, 23)
                            val profIdc = b[1].toInt() and 0x1F
                            val tier = (b[1].toInt() shr 5) and 1
                            val level = b[12].toInt() and 0xFF
                            val chroma = b[16].toInt() and 3
                            val luma = (b[17].toInt() and 7) + 8
                            t.props["profile"] = NormalizedValue.Text(hevcProfile(profIdc))
                            t.props["level"] = NormalizedValue.Text("%.1f".format(level / 30.0))
                            t.props["tier"] = NormalizedValue.Text(if (tier == 1) "High" else "Main")
                            t.props["chromaFormat"] = NormalizedValue.Text(chromaName(chroma))
                            t.props["bitDepthLuma"] = NormalizedValue.Integer(luma.toLong())
                            obs(g, "HEVCConfigurationVersion", NormalizedValue.Integer((b[0].toLong() and 0xFF)), d, 1, cpath)
                            obs(
                                g,
                                "GeneralProfileIDC",
                                NormalizedValue.Integer(profIdc.toLong()),
                                d + 1,
                                1,
                                cpath,
                                display = hevcProfile(profIdc),
                            )
                            obs(g, "GeneralLevelIDC", NormalizedValue.Integer(level.toLong()), d + 12, 1, cpath)
                            obs(g, "ChromaFormat", NormalizedValue.Integer(chroma.toLong()), d + 16, 1, cpath, display = chromaName(chroma))
                            obs(g, "BitDepthLuma", NormalizedValue.Integer(luma.toLong()), d + 17, 1, cpath)
                            obs(g, "BitDepthChroma", NormalizedValue.Integer(((b[18].toInt() and 7) + 8).toLong()), d + 18, 1, cpath)
                            // The SPS in the parameter-set arrays carries the VUI (colour description, SAR, timing),
                            // which is often the only colour signalling when no 'colr' box is written.
                            hvcCParameterSets(d, len, cpath, g, t)
                        }

                    "av1C" ->
                        if (len >= 4) {
                            val b = s.read(d, 4)
                            val prof = (b[1].toInt() shr 5) and 7
                            val level = b[1].toInt() and 0x1F
                            val highBd = (b[2].toInt() shr 6) and 1
                            val twelve = (b[2].toInt() shr 5) and 1
                            t.props["profile"] =
                                NormalizedValue.Text(listOf("Main", "High", "Professional").getOrElse(prof) { prof.toString() })
                            t.props["level"] = NormalizedValue.Integer(level.toLong())
                            t.props["bitDepthLuma"] = NormalizedValue.Integer(
                                if (twelve == 1) {
                                    12
                                } else if (highBd == 1) {
                                    10
                                } else {
                                    8
                                },
                            )
                            obs(g, "AV1Profile", NormalizedValue.Integer(prof.toLong()), d + 1, 1, cpath)
                        }

                    "vpcC" ->
                        if (len >= 8) {
                            obs(g, "VPProfile", NormalizedValue.Integer(s.u8(d + 4).toLong()), d + 4, 1, cpath)
                            t.props["profile"] = NormalizedValue.Text(s.u8(d + 4).toString())
                            t.props["bitDepthLuma"] = NormalizedValue.Integer((s.u8(d + 6) shr 4).toLong())
                        }

                    "colr" -> colr(d, len, cpath, g, t.props)

                    "pasp" ->
                        if (len >= 8) {
                            val hs = s.u32(d, true)
                            val vs = s.u32(d + 4, true)
                            obs(g, "PixelAspectRatio", NormalizedValue.RationalValue(Rational(hs, vs)), d, 8, cpath, "uint32[2]")
                            if (hs > 0 && vs > 0) t.props["pixelAspectRatio"] = NormalizedValue.RationalValue(Rational(hs, vs))
                        }

                    "btrt" ->
                        if (len >= 12) {
                            obs(g, "BufferSize", NormalizedValue.Integer(s.u32(d, true)), d, 4, cpath)
                            obs(g, "MaxBitrate", NormalizedValue.Integer(s.u32(d + 4, true)), d + 4, 4, cpath, unit = "bit/s")
                            obs(g, "AverageBitrate", NormalizedValue.Integer(s.u32(d + 8, true)), d + 8, 4, cpath, unit = "bit/s")
                            if (s.u32(d + 8, true) > 0) t.props["bitRate"] = NormalizedValue.Integer(s.u32(d + 8, true))
                        }

                    "clli" ->
                        if (len >= 4) {
                            obs(g, "MaxContentLightLevel", NormalizedValue.Integer(s.u16(d, true).toLong()), d, 2, cpath, unit = "cd/m2")
                            obs(
                                g,
                                "MaxPicAverageLightLevel",
                                NormalizedValue.Integer(s.u16(d + 2, true).toLong()),
                                d + 2,
                                2,
                                cpath,
                                unit = "cd/m2",
                            )
                            t.props["maxCLL"] = NormalizedValue.Integer(s.u16(d, true).toLong())
                        }

                    "mdcv" ->
                        if (len >= 24) {
                            obs(
                                g,
                                "MasteringDisplayMaxLuminance",
                                NormalizedValue.Decimal(s.u32(d + 16, true) / 10000.0),
                                d + 16,
                                4,
                                cpath,
                                unit = "cd/m2",
                            )
                            obs(
                                g,
                                "MasteringDisplayMinLuminance",
                                NormalizedValue.Decimal(s.u32(d + 20, true) / 10000.0),
                                d + 20,
                                4,
                                cpath,
                                unit = "cd/m2",
                            )
                            t.props["masteringDisplay"] = NormalizedValue.Bool(true)
                        }

                    "dvcC", "dvvC", "dvwC" ->
                        if (len >= 4) {
                            val profile = (s.u8(d + 2) shr 1) and 0x7F
                            obs(g, "DolbyVisionProfile", NormalizedValue.Integer(profile.toLong()), d + 2, 1, cpath)
                            t.props["dolbyVisionProfile"] = NormalizedValue.Integer(profile.toLong())
                        }

                    "fiel" -> if (len >= 2) obs(g, "FieldCount", NormalizedValue.Integer(s.u8(d).toLong()), d, 1, cpath)

                    "gama" -> if (len >= 4) obs(g, "Gamma", NormalizedValue.Decimal(s.u32(d, true) / 65536.0), d, 4, cpath, "fixed16.16")

                    "st3d", "sv3d", "uuid" -> obs(g, type, NormalizedValue.Binary(len), d, len, cpath, "box", known = false)
                }
            }
            pos += size
        }
    }

    private fun hvcCParameterSets(d: Long, len: Long, path: String, g: List<String>, t: Track) {
        if (len < 23) return
        var pos = d + 23
        val end = d + len
        val arrays = s.u8(d + 22)
        val vui = LinkedHashMap<String, NormalizedValue>()
        for (a in 0 until arrays) {
            if (pos + 3 > end) return
            val type = s.u8(pos) and 0x3F
            val n = s.u16(pos + 1, true)
            pos += 3
            for (k in 0 until n) {
                if (pos + 2 > end) return
                val l = s.u16(pos, true)
                if (pos + 2 + l > end) return
                if (type == 33 && k == 0) {
                    dev.kiroku.format.hevc.HevcParser.decodeSpsNal(ctx, s.read(pos + 2, l), vui, "$path/SPS", g, stream())
                }
                pos += 2 + l
            }
        }
        // The SPS supplies colour/VUI facts only when the sample entry did not (a 'colr' box takes precedence).
        for (key in listOf("colorPrimaries", "transferCharacteristics", "matrixCoefficients", "fullRange", "pixelAspectRatio")) {
            vui[key]?.let { t.props.putIfAbsent(key, it) }
        }
    }

    private fun colr(d: Long, len: Long, path: String, g: List<String>, props: MutableMap<String, NormalizedValue>) {
        if (len < 4) return
        val kind = s.ascii(d, 4)
        obs(g, "ColorProfiles", NormalizedValue.Text(kind), d, 4, path, "fourcc")
        when (kind) {
            "nclx", "nclc" ->
                if (len >= 10) {
                    val pri = s.u16(d + 4, true).toLong()
                    val trc = s.u16(d + 6, true).toLong()
                    val mat = s.u16(d + 8, true).toLong()
                    obs(g, "ColorPrimaries", NormalizedValue.Integer(pri), d + 4, 2, path, display = CICP.primaries(pri))
                    obs(g, "TransferCharacteristics", NormalizedValue.Integer(trc), d + 6, 2, path, display = CICP.transfer(trc))
                    obs(g, "MatrixCoefficients", NormalizedValue.Integer(mat), d + 8, 2, path, display = CICP.matrix(mat))
                    props["colorPrimaries"] = NormalizedValue.Integer(pri)
                    props["transferCharacteristics"] = NormalizedValue.Integer(trc)
                    props["matrixCoefficients"] = NormalizedValue.Integer(mat)
                    if (kind == "nclx" && len >= 11) {
                        val full = s.u8(d + 10) shr 7
                        obs(g, "VideoFullRangeFlag", NormalizedValue.Integer(full.toLong()), d + 10, 1, path)
                        props["fullRange"] = NormalizedValue.Bool(full == 1)
                    }
                }

            "prof", "rICC" -> IccParser.parse(ctx, s, d + 4, len - 4, "$path/ICC_Profile")
        }
    }

    private fun audioEntry(t: Track, p: Long, end: Long, path: String, depth: Int, primary: Boolean) {
        if (end - p < 28) return
        val g = group()
        val version = s.u16(p + 8, true)
        var channels = s.u16(p + 16, true).toLong()
        var bits = s.u16(p + 18, true).toLong()
        var rate = s.u32(p + 24, true) / 65536.0
        var childStart = p + 28
        if (version == 1) childStart += 16
        if (version == 2 && end - p >= 64) {
            rate = java.lang.Double.longBitsToDouble(s.u64(p + 32, true))
            channels = s.u32(p + 40, true)
            bits = s.u32(p + 48, true)
            childStart = p + 64
        }
        if (primary) {
            t.codec = audioCodec(t.codecTag.orEmpty())
            t.props["channels"] = NormalizedValue.Integer(channels)
            if (bits > 0) t.props["bitsPerSample"] = NormalizedValue.Integer(bits)
            if (rate > 0) t.props["sampleRate"] = NormalizedValue.Integer(rate.toLong())
            obs(g, "AudioChannels", NormalizedValue.Integer(channels), p + 16, 2, path)
            obs(g, "AudioBitsPerSample", NormalizedValue.Integer(bits), p + 18, 2, path)
            obs(g, "AudioSampleRate", NormalizedValue.Decimal(rate), p + 24, 4, path, "fixed16.16", unit = "Hz")
        }
        // esds may sit inside a QuickTime 'wave' box.
        scanAudioChildren(t, childStart, end, path, depth, primary)
    }

    private fun scanAudioChildren(t: Track, start: Long, end: Long, path: String, depth: Int, primary: Boolean) {
        if (depth > ctx.limits.maxNestingDepth) return
        var pos = start
        while (pos + 8 <= end) {
            ctx.countBox()
            val size = s.u32(pos, true)
            if (size < 8 || pos + size > end) break
            val type = s.ascii(pos + 4, 4)
            val d = pos + 8
            if (primary) {
                when (type) {
                    "esds" -> esds(t, d + 4, pos + size, "$path/esds")

                    "wave" -> scanAudioChildren(t, d, pos + size, "$path/wave", depth + 1, primary)

                    "btrt" -> if (size >= 20) s.u32(d + 8, true).takeIf { it > 0 }?.let { t.props["bitRate"] = NormalizedValue.Integer(it) }

                    "chan" ->
                        if (size >= 20) {
                            val tag = s.u32(d + 4, true)
                            obs(group(), "AudioChannelLayoutTag", NormalizedValue.Integer(tag), d + 4, 4, "$path/chan")
                        }

                    "dOps" -> if (size >= 19) t.props["channels"] = NormalizedValue.Integer(s.u8(d + 1).toLong())
                }
            }
            pos += size
        }
    }

    /** The MPEG-4 ES descriptor: object type and average bit rate. */
    private fun esds(t: Track, start: Long, end: Long, path: String) {
        var pos = start
        fun readLen(): Long {
            var v = 0L
            for (i in 0 until 4) {
                if (pos >= end) return 0
                val b = s.u8(pos++)
                v = (v shl 7) or (b and 0x7F).toLong()
                if (b and 0x80 == 0) break
            }
            return v
        }
        while (pos + 2 <= end) {
            val tag = s.u8(pos++)
            val len = readLen()
            when (tag) {
                0x03 -> {
                    if (pos + 3 > end) return
                    val flags = s.u8(pos + 2)
                    pos += 3
                    if (flags and 0x80 != 0) pos += 2
                    if (flags and 0x40 != 0) pos += 1 + s.u8(pos)
                    if (flags and 0x20 != 0) pos += 2
                }

                0x04 -> {
                    if (pos + 13 > end) return
                    val oti = s.u8(pos)
                    val avg = s.u32(pos + 9, true)
                    obs(group(), "AudioObjectType", NormalizedValue.Integer(oti.toLong()), pos, 1, path)
                    if (avg > 0) {
                        t.props["bitRate"] = NormalizedValue.Integer(avg)
                        obs(group(), "AverageBitrate", NormalizedValue.Integer(avg), pos + 9, 4, path, unit = "bit/s")
                    }
                    when (oti) {
                        0x40, 0x66, 0x67, 0x68 -> t.codec = "aac"
                        0x69, 0x6B -> t.codec = "mp3"
                        0xA5 -> t.codec = "ac3"
                        0xA6 -> t.codec = "eac3"
                        0xAD -> t.codec = "opus"
                    }
                    pos += 13
                }

                else -> pos += len
            }
        }
    }

    private fun stts(p: Long, len: Long) {
        val t = current ?: return
        if (len < 8) return
        val n = s.u32(p + 4, true)
        val maxEntries = minOf(n, 65536L).toInt()
        if (8 + maxEntries * 8L > len) return
        val b = s.read(p + 8, maxEntries * 8)
        var count = 0L
        for (i in 0 until maxEntries) {
            val c = Bytes.u32(b, i * 8, true)
            val d = Bytes.u32(b, i * 8 + 4, true)
            count += c
            t.sttsDeltas.merge(d, c, Long::plus)
        }
        if (t.sampleCount == null) t.sampleCount = count
        if (n > maxEntries) t.props["sttsTruncated"] = NormalizedValue.Bool(true)
    }

    private fun stsz(p: Long, len: Long) {
        val t = current ?: return
        if (len < 12) return
        val sampleSize = s.u32(p + 4, true)
        val count = s.u32(p + 8, true)
        t.sampleCount = count
        if (sampleSize != 0L) {
            t.totalSampleBytes = sampleSize * count
        } else if (count * 4 <= minOf(len - 12, 8L shl 20)) {
            // Up to 8 MiB of size entries, summed in chunks.
            var total = 0L
            var read = 0L
            while (read < count) {
                val n = minOf(count - read, 16384L).toInt()
                val b = s.read(p + 12 + read * 4, n * 4)
                for (i in 0 until n) total += Bytes.u32(b, i * 4, true)
                read += n
            }
            t.totalSampleBytes = total
        }
    }

    private fun finishTracks() {
        for (t in tracks) {
            val kind =
                when (t.handler) {
                    "vide" -> StreamKind.VIDEO
                    "soun" -> StreamKind.AUDIO
                    "text", "sbtl", "subt", "clcp" -> StreamKind.SUBTITLE
                    "meta", "mdta" -> StreamKind.TIMED_METADATA
                    "tmcd" -> StreamKind.DATA
                    "pict", "auxv" -> StreamKind.IMAGE
                    else -> StreamKind.OTHER
                }
            t.props["handler"] = NormalizedValue.Text(t.handler ?: "")
            t.handlerName?.let { t.props["handlerName"] = NormalizedValue.Text(it) }
            if (t.timescale > 0 && t.sttsDeltas.isNotEmpty()) {
                val dominant = t.sttsDeltas.maxByOrNull { it.value }!!.key
                if (dominant > 0 && kind == StreamKind.VIDEO) {
                    t.props["frameRate"] = NormalizedValue.RationalValue(Rational(t.timescale, dominant).reduced())
                    t.props["variableFrameRate"] =
                        NormalizedValue.Bool(
                            t.sttsDeltas.size > 1 && t.sttsDeltas.size.toLong() < (t.sampleCount ?: 0) || t.sttsDeltas.size > 2,
                        )
                }
            }
            val count = t.sampleCount
            if (count != null) t.props["sampleCount"] = NormalizedValue.Integer(count)
            if (count != null && t.durationUnits > 0 && t.timescale > 0 && kind == StreamKind.VIDEO) {
                t.props["averageFrameRate"] = NormalizedValue.RationalValue(Rational(count * t.timescale, t.durationUnits).reduced())
            }
            val bytes = t.totalSampleBytes
            if (bytes != null && t.durationUnits > 0 && t.timescale > 0 && !t.props.containsKey("bitRate")) {
                t.props["bitRate"] = NormalizedValue.Integer((bytes * 8.0 * t.timescale / t.durationUnits).toLong())
            }
            ctx.collector.stream(StreamInfo(t.number - 1, "", kind, t.codec, t.codecTag, t.props.toMap()))
        }
    }

    // ---- QuickTime metadata ----------------------------------------------------------------------------------

    private fun keys(p: Long, len: Long) {
        if (len < 8) return
        val n = s.u32(p + 4, true)
        var pos = p + 8
        val end = p + len
        for (i in 0 until minOf(n, 4096L)) {
            if (pos + 8 > end) break
            val size = s.u32(pos, true)
            if (size < 8 || pos + size > end) break
            keys += Bytes.decodeText(s.read(pos + 8, (size - 8).toInt()))
            pos += size
        }
    }

    private fun ilst(p: Long, end: Long, path: String) {
        var pos = p
        var i = 0
        while (pos + 8 <= end) {
            ctx.countBox()
            val size = s.u32(pos, true)
            if (size < 8 || pos + size > end) break
            val typeBytes = s.read(pos + 4, 4)
            val keyIndex = Bytes.u32(typeBytes, 0, true)
            val useKeys = keys.isNotEmpty() && keyIndex in 1..keys.size.toLong()
            val name = if (useKeys) keys[(keyIndex - 1).toInt()] else Bytes.fourCC(typeBytes, 0).replace('?', '©')
            val g = if (useKeys) listOf("QuickTime", "Keys") else listOf("QuickTime", "ItemList")
            var freeName: String? = null
            var c = pos + 8
            while (c + 8 <= pos + size) {
                val csize = s.u32(c, true)
                if (csize < 8 || c + csize > pos + size) break
                when (s.ascii(c + 4, 4)) {
                    "name" -> freeName = Bytes.decodeText(s.read(c + 12, (csize - 12).toInt().coerceAtLeast(0)))

                    "data" -> if (csize >=
                        16
                    ) {
                        dataAtom(g, freeName ?: name, c + 8, csize - 8, "$path/$name[$i]", if (useKeys) null else keyIndex, useKeys)
                    }
                }
                c += csize
            }
            pos += size
            i++
        }
    }

    /** A `data` atom: a 4-byte type indicator, a 4-byte locale, then the value. */
    private fun dataAtom(g: List<String>, name: String, d: Long, len: Long, path: String, tagId: Long?, isKey: Boolean) {
        val typeInd = s.u32(d, true) and 0xFFFFFF
        val v = d + 8
        val n = len - 8
        val value: NormalizedValue =
            when (typeInd) {
                1L -> NormalizedValue.Text(ctx.clip(String(s.read(v, n.toInt().coerceAtMost(ctx.limits.maxStringLength)), Charsets.UTF_8)))

                2L -> NormalizedValue.Text(String(s.read(v, n.toInt().coerceAtMost(ctx.limits.maxStringLength)), Charsets.UTF_16BE))

                21L -> signed(v, n)

                22L -> unsigned(v, n)

                23L -> if (n ==
                    4L
                ) {
                    NormalizedValue.Decimal(java.lang.Float.intBitsToFloat(s.u32(v, true).toInt()).toDouble())
                } else {
                    NormalizedValue.Binary(n)
                }

                24L -> if (n ==
                    8L
                ) {
                    NormalizedValue.Decimal(java.lang.Double.longBitsToDouble(s.u64(v, true)))
                } else {
                    NormalizedValue.Binary(n)
                }

                0L -> if (name == "trkn" || name == "disk") pair(v, n) else NormalizedValue.Binary(n)

                else -> NormalizedValue.Binary(n)
            }
        obs(
            g, name, value, v, n, path, type = "data:$typeInd",
            tagId = tagId, namespace = if (isKey) "mdta" else null,
            known = isKey || ITEM_NAMES.containsKey(name), display = if (!isKey) ITEM_NAMES[name] else null,
        )
    }

    private fun signed(v: Long, n: Long): NormalizedValue = when (n) {
        1L -> NormalizedValue.Integer(s.read(v, 1)[0].toLong())
        2L -> NormalizedValue.Integer(s.u16(v, true).toShort().toLong())
        4L -> NormalizedValue.Integer(s.u32(v, true).toInt().toLong())
        8L -> NormalizedValue.Integer(s.u64(v, true))
        else -> NormalizedValue.Binary(n)
    }

    private fun unsigned(v: Long, n: Long): NormalizedValue = when (n) {
        1L -> NormalizedValue.Integer(s.u8(v).toLong())
        2L -> NormalizedValue.Integer(s.u16(v, true).toLong())
        4L -> NormalizedValue.Integer(s.u32(v, true))
        8L -> NormalizedValue.Integer(s.u64(v, true))
        else -> NormalizedValue.Binary(n)
    }

    private fun pair(v: Long, n: Long): NormalizedValue = if (n >=
        6
    ) {
        NormalizedValue.ListValue(
            listOf(NormalizedValue.Integer(s.u16(v + 2, true).toLong()), NormalizedValue.Integer(s.u16(v + 4, true).toLong())),
        )
    } else {
        NormalizedValue.Binary(n)
    }

    /** QuickTime `udta` items: `©xxx` international text, 3GPP asset boxes, and vendor atoms (kept raw). */
    private fun udtaItem(type: String, typeBytes: ByteArray, p: Long, len: Long, end: Long, path: String, depth: Int) {
        val g = listOf("QuickTime", "UserData")
        val fourcc = Bytes.fourCCValue(typeBytes, 0)
        val name = (if (typeBytes[0] == 0xA9.toByte()) "©" + type.substring(1) else type)
        when {
            typeBytes[0] == 0xA9.toByte() && len >= 4 -> {
                // One or more (u16 length, u16 language, text) records.
                var pos = p
                val values = ArrayList<NormalizedValue>()
                var firstOff = -1L
                var firstLen = 0L
                while (pos + 4 <= end) {
                    val l = s.u16(pos, true)
                    if (l == 0 || pos + 4 + l > end) break
                    val lang = s.u16(pos + 2, true)
                    val bytes = s.read(pos + 4, l)
                    val text = if (lang < 0x400 || lang == 0x7FFF) String(bytes, Charsets.ISO_8859_1) else String(bytes, Charsets.UTF_8)
                    values += NormalizedValue.Text(ctx.clip(text.trimEnd('\u0000')))
                    if (firstOff < 0) {
                        firstOff = pos + 4
                        firstLen = l.toLong()
                    }
                    pos += 4 + l
                }
                if (values.isEmpty()) {
                    raw(g, name, typeBytes, p, len, path, known = false)
                } else {
                    obs(
                        g, name,
                        NormalizedValue.list(
                            values,
                        ),
                        firstOff, firstLen, path, "intlText", tagId = fourcc, display = ITEM_NAMES[name],
                        known = ITEM_NAMES.containsKey(
                            name,
                        ),
                    )
                }
            }

            type in THREE_GPP_TEXT && len > 6 -> {
                val n = (len - 6).toInt().coerceAtMost(ctx.limits.maxStringLength)
                val b = s.read(p + 6, n)
                val text = if (n >= 2 && b[0] == 0xFE.toByte() &&
                    b[1] == 0xFF.toByte()
                ) {
                    String(b, 2, n - 2, Charsets.UTF_16BE)
                } else {
                    String(b, Charsets.UTF_8)
                }
                obs(
                    g,
                    THREE_GPP_TEXT.getValue(type),
                    NormalizedValue.Text(text.trimEnd('\u0000')),
                    p + 6,
                    n.toLong(),
                    path,
                    "3gppText",
                    tagId = fourcc,
                )
            }

            type == "loci" && len > 20 -> loci(p, len, path, fourcc)

            type == "meta" -> walk(p + 4, end, path, depth + 1, "meta")

            type == "XMP_" -> XmpParser.parse(ctx, s, p, len, "$path/XMP")

            type == "uuid" -> Unit

            else -> {
                // Vendor atoms (Nikon NCDT, Canon CNTH, GoPro, Samsung, DJI, ...): kept byte-exact, with an
                // embedded TIFF parsed when one is found at the start.
                val head = s.readAtMost(p, 16)
                val tiffAt = findTiff(head)
                raw(g, type, typeBytes, p, len, path, known = false)
                if (tiffAt != null) TiffReader(ctx, s.slice(p + tiffAt, len - tiffAt), "$path/TIFF").parse()
            }
        }
    }

    /** 3GPP `loci`: name, role, longitude, latitude and altitude (16.16 fixed point). */
    private fun loci(p: Long, len: Long, path: String, fourcc: Long) {
        val g = listOf("QuickTime", "UserData")
        var pos = p + 6
        val end = p + len
        val nameEnd = (pos until end).firstOrNull { s.u8(it) == 0 } ?: return
        val name = Bytes.decodeText(s.read(pos, (nameEnd - pos).toInt()))
        pos = nameEnd + 1
        if (pos + 13 > end) return
        val lon = s.u32(pos + 1, true).toInt() / 65536.0
        val lat = s.u32(pos + 5, true).toInt() / 65536.0
        val alt = s.u32(pos + 9, true).toInt() / 65536.0
        obs(g, "LocationName", NormalizedValue.Text(name), p + 6, nameEnd - p - 6, path, "string", tagId = fourcc)
        obs(g, "LocationLongitude", NormalizedValue.Decimal(lon), pos + 1, 4, path, "fixed16.16", unit = "deg", tagId = fourcc)
        obs(g, "LocationLatitude", NormalizedValue.Decimal(lat), pos + 5, 4, path, "fixed16.16", unit = "deg", tagId = fourcc)
        obs(g, "LocationAltitude", NormalizedValue.Decimal(alt), pos + 9, 4, path, "fixed16.16", unit = "m", tagId = fourcc)
    }

    private fun uuid(boxStart: Long, p: Long, end: Long, path: String) {
        val id = s.read(boxStart + 8, 16).joinToString("") { "%02x".format(it) }
        when (id) {
            XMP_UUID -> XmpParser.parse(ctx, s, p, end - p, "$path/XMP")

            EXIF_UUID_CANON, EXIF_UUID_SONY_PSP -> raw(group(), "uuid:$id", Bytes.ascii("uuid"), p, end - p, path, known = false)

            else -> {
                val head = s.readAtMost(p, 16)
                raw(group(), "uuid:$id", Bytes.ascii("uuid"), p, end - p, path, known = false)
                findTiff(head)?.let { TiffReader(ctx, s.slice(p + it, end - p - it), "$path/TIFF").parse() }
            }
        }
    }

    private fun findTiff(head: ByteArray): Int? = (0 until minOf(10, head.size - 4).coerceAtLeast(0)).firstOrNull { i ->
        (head[i] == 'I'.code.toByte() && head[i + 1] == 'I'.code.toByte() && head[i + 2] == 42.toByte() && head[i + 3] == 0.toByte()) ||
            (head[i] == 'M'.code.toByte() && head[i + 1] == 'M'.code.toByte() && head[i + 2] == 0.toByte() && head[i + 3] == 42.toByte())
    }

    // ---- HEIF items ------------------------------------------------------------------------------------------

    private fun iinf(p: Long, end: Long, path: String, depth: Int) {
        val v = s.u8(p)
        val start = p + 4 + if (v == 0) 2 else 4
        var pos = start
        while (pos + 8 <= end) {
            ctx.countBox()
            val size = s.u32(pos, true)
            if (size < 12 || pos + size > end) break
            if (s.ascii(pos + 4, 4) == "infe") infe(pos + 8, pos + size)
            pos += size
        }
    }

    private fun infe(p: Long, end: Long) {
        val v = s.u8(p)
        val flags = s.u32(p, true) and 0xFFFFFF
        if (v < 2) return
        var pos = p + 4
        val id = if (v == 2) s.u16(pos, true).toLong() else s.u32(pos, true)
        pos += if (v == 2) 2 else 4
        pos += 2 // protection index
        val type = s.ascii(pos, 4)
        pos += 4
        fun cstr(): String? {
            if (pos >= end) return null
            val e = (pos until end).firstOrNull { s.u8(it) == 0 } ?: end
            val str = String(s.read(pos, (e - pos).toInt()), Charsets.UTF_8)
            pos = e + 1
            return str
        }
        val name = cstr()
        val content = if (type == "mime") cstr() else null
        items[id] = Item(id, type, name?.ifEmpty { null }, content, flags and 1L != 0L)
    }

    private fun iloc(p: Long, len: Long) {
        val v = s.u8(p)
        val b = s.read(p + 4, 2)
        val offSize = (b[0].toInt() shr 4) and 0xF
        val lenSize = b[0].toInt() and 0xF
        val baseSize = (b[1].toInt() shr 4) and 0xF
        val idxSize = if (v == 1 || v == 2) b[1].toInt() and 0xF else 0
        var pos = p + 6
        val count = if (v < 2) s.u16(pos, true).toLong().also { pos += 2 } else s.u32(pos, true).also { pos += 4 }
        val end = p + len
        fun read(n: Int): Long {
            val r =
                when (n) {
                    0 -> 0L
                    4 -> s.u32(pos, true)
                    8 -> s.u64(pos, true)
                    2 -> s.u16(pos, true).toLong()
                    else -> 0L
                }
            pos += n
            return r
        }
        for (i in 0 until minOf(count, 100_000L)) {
            if (pos >= end) break
            ctx.countBox()
            val id = if (v < 2) read(2) else read(4)
            var method = 0
            if (v == 1 || v == 2) method = (read(2) and 0xF).toInt()
            pos += 2 // data reference index
            val base = read(baseSize)
            val extents = read(2).toInt()
            val list = ArrayList<Pair<Long, Long>>()
            for (e in 0 until extents) {
                if (idxSize > 0) read(idxSize)
                val off = read(offSize)
                val l = read(lenSize)
                list += (base + off) to l
            }
            itemLocations[id] = list
            itemConstruction[id] = method
        }
    }

    private fun iref(p: Long, end: Long) {
        val v = s.u8(p)
        var pos = p + 4
        val idSize = if (v == 0) 2 else 4
        while (pos + 8 <= end) {
            val size = s.u32(pos, true)
            if (size < 8 || pos + size > end) break
            val type = s.ascii(pos + 4, 4)
            var q = pos + 8
            val from = if (idSize == 2) s.u16(q, true).toLong() else s.u32(q, true)
            q += idSize
            val n = s.u16(q, true)
            q += 2
            val to = (0 until n).mapNotNull { k ->
                val o =
                    q + k * idSize
                if (o + idSize <= pos + size) (if (idSize == 2) s.u16(o, true).toLong() else s.u32(o, true)) else null
            }
            references += Triple(type, from, to)
            pos += size
        }
    }

    private fun ipco(p: Long, end: Long, path: String) {
        var pos = p
        while (pos + 8 <= end) {
            ctx.countBox()
            val size = s.u32(pos, true)
            if (size < 8 || pos + size > end) break
            val type = s.ascii(pos + 4, 4)
            properties += type to (pos + 8)
            propertyBoxes += Triple(type, pos + 8, size - 8)
            pos += size
        }
    }

    private fun ipma(p: Long, len: Long) {
        val v = s.u8(p)
        val flags = s.u32(p, true) and 0xFFFFFF
        var pos = p + 4
        val count = s.u32(pos, true)
        pos += 4
        val end = p + len
        for (i in 0 until minOf(count, 100_000L)) {
            if (pos >= end) break
            val id = if (v < 1) s.u16(pos, true).toLong().also { pos += 2 } else s.u32(pos, true).also { pos += 4 }
            val n = s.u8(pos)
            pos += 1
            val list = ArrayList<Int>()
            for (k in 0 until n) {
                val idx =
                    if (flags and 1L != 0L) {
                        (s.u16(pos, true) and 0x7FFF).also { pos += 2 }
                    } else {
                        (s.u8(pos) and 0x7F).also { pos += 1 }
                    }
                if (idx > 0) list += idx
            }
            associations[id] = list
        }
    }

    private fun finishHeif() {
        if (items.isEmpty()) return
        val g = listOf("QuickTime", "Meta")
        primaryItem?.let {
            ctx.observe(g, "PrimaryItemReference", NormalizedValue.Integer(it), nativeType = NativeType(TypeSystem.ISOBMFF, "pitm"))
        }
        val tiles = references.filter { it.first == "dimg" }.flatMap { it.third }.toSet()
        val thumbs = references.filter { it.first == "thmb" }.map { it.second }.toSet()
        val aux = references.filter { it.first == "auxl" }.map { it.second }.toSet()
        // Properties of the primary item: dimensions, rotation, mirror, colour, pixel depth.
        val primary = primaryItem
        if (primary != null) {
            items[primary]?.let {
                ctx.observe(g, "PrimaryItemType", NormalizedValue.Text(it.type), nativeType = NativeType(TypeSystem.ISOBMFF, "infe"))
            }
            val order = ArrayList<String>()
            for (idx in associations[primary].orEmpty()) {
                val (type, off, len) = propertyBoxes.getOrNull(idx - 1) ?: continue
                val path = "ISOBMFF/meta/iprp/ipco/$type[${idx - 1}]"
                when (type) {
                    "ispe" ->
                        if (len >= 12) {
                            obs(
                                g,
                                "ImageSpatialExtent",
                                NormalizedValue.ListValue(
                                    listOf(NormalizedValue.Integer(s.u32(off + 4, true)), NormalizedValue.Integer(s.u32(off + 8, true))),
                                ),
                                off + 4,
                                8,
                                path,
                                "uint32[2]",
                            )
                        }

                    "irot" ->
                        if (len >= 1) {
                            order += "irot"
                            val angle = (s.u8(off) and 3) * 90
                            obs(
                                g,
                                "Rotation",
                                NormalizedValue.Integer(angle.toLong()),
                                off,
                                1,
                                path,
                                display = "$angle degrees counter-clockwise",
                                unit = "deg CCW",
                            )
                        }

                    "imir" ->
                        if (len >= 1) {
                            order += "imir"
                            val axis = s.u8(off) and 1
                            obs(
                                g,
                                "MirrorAxis",
                                NormalizedValue.Integer(axis.toLong()),
                                off,
                                1,
                                path,
                                display = if (axis ==
                                    0
                                ) {
                                    "Vertical (left-right flip)"
                                } else {
                                    "Horizontal (top-bottom flip)"
                                },
                            )
                        }

                    "pixi" ->
                        if (len >= 5) {
                            val n = s.u8(off + 4)
                            if (len >= 5 + n) {
                                obs(
                                    g,
                                    "BitsPerChannel",
                                    NormalizedValue.list(
                                        (0 until n).map {
                                            NormalizedValue.Integer(s.u8(off + 5 + it).toLong())
                                        },
                                    ),
                                    off + 5,
                                    n.toLong(),
                                    path,
                                )
                            }
                        }

                    "colr" -> colr(off, len, path, g, HashMap())

                    "hvcC" ->
                        if (len >= 23) {
                            val b = s.read(off, 23)
                            obs(
                                g,
                                "HEVCProfileIDC",
                                NormalizedValue.Integer((b[1].toLong() and 0x1F)),
                                off + 1,
                                1,
                                path,
                                display = hevcProfile(b[1].toInt() and 0x1F),
                            )
                            obs(
                                g,
                                "ChromaFormat",
                                NormalizedValue.Integer((b[16].toLong() and 3)),
                                off + 16,
                                1,
                                path,
                                display = chromaName(b[16].toInt() and 3),
                            )
                            obs(g, "BitDepthLuma", NormalizedValue.Integer(((b[17].toLong() and 7) + 8)), off + 17, 1, path)
                        }

                    "clap" -> if (len >= 32) obs(g, "CleanAperture", NormalizedValue.Binary(len), off, len, path, "clap")

                    "pasp" -> if (len >=
                        8
                    ) {
                        obs(
                            g,
                            "PixelAspectRatio",
                            NormalizedValue.RationalValue(Rational(s.u32(off, true), s.u32(off + 4, true))),
                            off,
                            8,
                            path,
                        )
                    }

                    "clli" -> if (len >=
                        4
                    ) {
                        obs(g, "MaxContentLightLevel", NormalizedValue.Integer(s.u16(off, true).toLong()), off, 2, path, unit = "cd/m2")
                    }

                    "auxC" -> Unit
                }
            }
            if (order.size >
                1
            ) {
                ctx.observe(
                    g,
                    "TransformOrder",
                    NormalizedValue.Text(order.joinToString(",")),
                    nativeType = NativeType(TypeSystem.ISOBMFF, "ipma"),
                )
            }
        }
        // Metadata items: Exif and XMP.
        var exifIndex = 0
        for (item in items.values) {
            val locs = itemLocations[item.id] ?: continue
            val method = itemConstruction[item.id] ?: 0
            if (item.type != "Exif" &&
                !(item.type == "mime" && item.contentType?.contains("xmp") == true || item.contentType == "application/rdf+xml")
            ) {
                continue
            }
            if (method == 2 || method == 1 && idatOffset == null) {
                ctx.warn(
                    DiagnosticCode.UNSUPPORTED_FORMAT,
                    "HEIF item ${item.id} uses construction method $method; not read",
                    severity = Severity.INFO,
                )
                continue
            }
            val base = if (method == 1) idatOffset!! else 0L
            if (locs.size != 1) {
                ctx.warn(
                    DiagnosticCode.UNSUPPORTED_FORMAT,
                    "HEIF metadata item ${item.id} has ${locs.size} extents; not read",
                    severity = Severity.INFO,
                )
                continue
            }
            val (off, len) = locs[0]
            val abs = base + off
            if (len <= 0 || !s.contains(abs, len)) {
                ctx.warn(DiagnosticCode.TRUNCATED, "HEIF metadata item ${item.id} lies outside the file")
                continue
            }
            val path = "ISOBMFF/meta/item[${item.id}]"
            if (item.type == "Exif") {
                if (len < 4) continue
                val tiffOff = s.u32(abs, true)
                val start = abs + 4 + tiffOff
                if (tiffOff < len - 4) {
                    TiffReader(ctx, s.slice(start, len - 4 - tiffOff), "$path/Exif[$exifIndex]/TIFF").parse()
                    exifIndex++
                }
            } else {
                XmpParser.parse(ctx, s, abs, len, "$path/XMP")
            }
        }
        // Image items: the primary, thumbnails and auxiliary images. Grid tiles are counted, not listed.
        var tileCount = 0
        for (item in items.values) {
            if (item.id in tiles) {
                tileCount++
                continue
            }
            if (item.type !in setOf("hvc1", "av01", "grid", "iden", "iovl", "jpeg", "unci", "tmap")) continue
            val ispe =
                associations[item.id].orEmpty().mapNotNull { propertyBoxes.getOrNull(it - 1) }.firstOrNull {
                    it.first == "ispe" &&
                        it.third >= 12
                }
            val kind =
                when (item.id) {
                    primary -> EmbeddedImageKind.PRIMARY
                    in thumbs -> EmbeddedImageKind.THUMBNAIL
                    in aux -> EmbeddedImageKind.AUXILIARY
                    else -> EmbeddedImageKind.OTHER
                }
            val loc = itemLocations[item.id]?.singleOrNull()
            ctx.collector.embeddedImage(
                EmbeddedImageInfo(
                    0, "", kind, listOf("QuickTime", "Item${item.id}"),
                    width = ispe?.let { s.u32(it.second + 4, true).toInt() }, height = ispe?.let { s.u32(it.second + 8, true).toInt() },
                    compression = item.type, fileOffset = loc?.first, length = loc?.second,
                ),
            )
        }
        if (tileCount >
            0
        ) {
            ctx.observe(
                g,
                "GridTileCount",
                NormalizedValue.Integer(tileCount.toLong()),
                nativeType = NativeType(TypeSystem.ISOBMFF, "derived"),
            )
        }
        ctx.observe(g, "ItemCount", NormalizedValue.Integer(items.size.toLong()), nativeType = NativeType(TypeSystem.ISOBMFF, "derived"))
    }

    // ---- Helpers -------------------------------------------------------------------------------------------

    private fun timeObs(g: List<String>, name: String, off: Long, w: Long, path: String) {
        val v = if (w == 8L) s.u64(off, true) else s.u32(off, true)
        obs(
            g,
            name,
            NormalizedValue.Integer(v),
            off,
            w,
            path,
            display = qtTime(v)?.toString() ?: "0000:00:00 00:00:00",
            unit = "s since 1904-01-01T00:00:00Z",
        )
    }

    /** The 3×3 matrix as 9 decimals (a, b, u, c, d, v, x, y, w). u/v/w are 2.30 fixed point, the rest 16.16. */
    private fun matrix(off: Long): NormalizedValue {
        val b = s.read(off, 36)
        return NormalizedValue.ListValue(
            (0 until 9).map { i ->
                val raw = Bytes.s32(b, i * 4, true)
                NormalizedValue.Decimal(if (i % 3 == 2) raw / 1073741824.0 else raw / 65536.0)
            },
        )
    }

    companion object {
        private const val QT_EPOCH_OFFSET = 2082844800L
        private const val XMP_UUID = "be7acfcb97a942e89c71999491e3afac"
        private const val EXIF_UUID_CANON = "85c0b687820f11e08111f4ce462b6a48"
        private const val EXIF_UUID_SONY_PSP = "50524f4621d24fcebb88695cfac9c740"

        /** QuickTime/ISO times are seconds since 1904-01-01 UTC. `0` means unset. */
        fun qtTime(v: Long): Instant? = if (v <= 0) null else Instant.ofEpochSecond(v - QT_EPOCH_OFFSET)

        private fun seconds(v: Long, ts: Long): String? = if (ts > 0) "%.3f s".format(v.toDouble() / ts) else null

        fun language(code: Int): String? {
            if (code == 0 || code == 0x7FFF) return null
            if (code < 0x400) return null // Macintosh language code, not ISO-639.
            val c1 = ((code shr 10) and 0x1F) + 0x60
            val c2 = ((code shr 5) and 0x1F) + 0x60
            val c3 = (code and 0x1F) + 0x60
            return "${c1.toChar()}${c2.toChar()}${c3.toChar()}"
        }

        val THREE_GPP_TEXT =
            mapOf(
                "titl" to "Title", "dscp" to "Description", "cprt" to "Copyright", "perf" to "Performer",
                "auth" to "Author", "gnre" to "Genre", "albm" to "Album", "kywd" to "Keywords", "yrrc" to "Year",
            )

        val ITEM_NAMES =
            mapOf(
                "©nam" to "Title", "©ART" to "Artist", "©alb" to "Album", "©day" to "ContentCreateDate", "©too" to "Encoder",
                "©cmt" to "Comment", "©gen" to "Genre", "©wrt" to "Composer", "©mak" to "Make", "©mod" to "Model",
                "©swr" to "SoftwareVersion", "©xyz" to "GPSCoordinates", "©inf" to "Information", "©des" to "Description",
                "©aut" to "Author", "©cpy" to "Copyright", "©fmt" to "Format", "©req" to "Requirements", "©enc" to "EncodedBy",
                "©dir" to "Director", "©prd" to "Producer", "©prf" to "Performers", "©src" to "SourceCredits",
                "©wrn" to "Warning", "©hst" to "HostComputer", "©mal" to "MakerURL", "©ed1" to "Edit1", "©lyr" to "Lyrics",
                "©grp" to "Grouping", "©st3" to "Subtitle", "©key" to "Keywords",
                "cprt" to "Copyright", "desc" to "Description", "ldes" to "LongDescription", "covr" to "CoverArt",
                "trkn" to "TrackNumber", "disk" to "DiskNumber", "aART" to "AlbumArtist", "rtng" to "Rating",
            )

        fun handlerName(t: String): String? = when (t) {
            "vide" -> "Video Track"
            "soun" -> "Audio Track"
            "meta" -> "Timed Metadata"
            "mdta" -> "Metadata Tags"
            "mdir" -> "Metadata"
            "pict" -> "Picture"
            "tmcd" -> "Time Code"
            "text", "sbtl", "subt" -> "Text/Subtitle"
            "hint" -> "Hint Track"
            "auxv" -> "Auxiliary Video"
            else -> null
        }

        fun videoCodec(f: String): String? = when (f) {
            "avc1", "avc2", "avc3", "avc4", "dvav", "dva1" -> "h264"
            "hvc1", "hev1", "dvh1", "dvhe" -> "hevc"
            "av01" -> "av1"
            "vp08" -> "vp8"
            "vp09" -> "vp9"
            "mp4v" -> "mpeg4"
            "jpeg", "mjpa", "mjpb" -> "mjpeg"
            "apch", "apcn", "apcs", "apco", "ap4h", "ap4x" -> "prores"
            "s263", "h263" -> "h263"
            "mp2v", "xdvc", "xd5c" -> "mpeg2video"
            "dvc ", "dvcp", "dvpp", "dv5n", "dv5p" -> "dvvideo"
            else -> f.trim().ifEmpty { null }
        }

        fun audioCodec(f: String): String? = when (f) {
            "mp4a" -> "aac"
            "ac-3" -> "ac3"
            "ec-3" -> "eac3"
            "Opus" -> "opus"
            "fLaC" -> "flac"
            "alac" -> "alac"
            "samr" -> "amr_nb"
            "sawb" -> "amr_wb"
            ".mp3", "mp3 " -> "mp3"
            "lpcm", "sowt", "twos", "ipcm", "in24", "in32", "fl32", "fl64", "raw " -> "pcm"
            "ulaw" -> "pcm_mulaw"
            "alaw" -> "pcm_alaw"
            else -> f.trim().ifEmpty { null }
        }

        fun otherCodec(f: String): String? = when (f) {
            "tx3g", "text", "c608", "wvtt", "stpp" -> f
            "mebx" -> "mebx"
            "tmcd" -> "timecode"
            "gpmd" -> "gpmd"
            "camm" -> "camm"
            else -> f.trim().ifEmpty { null }
        }

        fun avcProfile(p: Int): String = when (p) {
            66 -> "Baseline"
            77 -> "Main"
            88 -> "Extended"
            100 -> "High"
            110 -> "High 10"
            122 -> "High 4:2:2"
            244 -> "High 4:4:4 Predictive"
            else -> p.toString()
        }

        fun hevcProfile(p: Int): String = when (p) {
            1 -> "Main"
            2 -> "Main 10"
            3 -> "Main Still Picture"
            4 -> "Format Range Extensions"
            5 -> "High Throughput"
            9 -> "Screen Content Coding"
            else -> p.toString()
        }

        fun chromaName(c: Int): String = when (c) {
            0 -> "4:0:0"
            1 -> "4:2:0"
            2 -> "4:2:2"
            3 -> "4:4:4"
            else -> c.toString()
        }
    }
}

/** ITU-T H.273 coding-independent code points (shared by ISO-BMFF, Matroska, HEVC VUI and PNG cICP). */
internal object CICP {
    fun primaries(v: Long): String? = when (v) {
        1L -> "BT.709"
        2L -> "Unspecified"
        4L -> "BT.470 System M"
        5L -> "BT.470 System B, G"
        6L -> "BT.601"
        7L -> "SMPTE 240M"
        8L -> "Generic film"
        9L -> "BT.2020"
        10L -> "SMPTE ST 428-1 (XYZ)"
        11L -> "SMPTE RP 431-2 (DCI-P3)"
        12L -> "SMPTE EG 432-1 (Display P3)"
        22L -> "EBU Tech 3213"
        else -> null
    }

    fun transfer(v: Long): String? = when (v) {
        1L -> "BT.709"
        2L -> "Unspecified"
        4L -> "BT.470 System M (gamma 2.2)"
        5L -> "BT.470 System B, G (gamma 2.8)"
        6L -> "BT.601"
        7L -> "SMPTE 240M"
        8L -> "Linear"
        11L -> "IEC 61966-2-4"
        13L -> "sRGB/sYCC"
        14L -> "BT.2020 10-bit"
        15L -> "BT.2020 12-bit"
        16L -> "SMPTE ST 2084 (PQ)"
        17L -> "SMPTE ST 428-1"
        18L -> "ARIB STD-B67 (HLG)"
        else -> null
    }

    fun matrix(v: Long): String? = when (v) {
        0L -> "Identity"
        1L -> "BT.709"
        2L -> "Unspecified"
        4L -> "FCC"
        5L -> "BT.470 System B, G"
        6L -> "BT.601"
        7L -> "SMPTE 240M"
        8L -> "YCgCo"
        9L -> "BT.2020 non-constant luminance"
        10L -> "BT.2020 constant luminance"
        14L -> "ICtCp"
        else -> null
    }

    fun isHdrTransfer(v: Long?): Boolean = v == 16L || v == 18L
}
