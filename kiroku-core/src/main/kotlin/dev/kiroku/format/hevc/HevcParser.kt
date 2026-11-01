package dev.kiroku.format.hevc

import dev.kiroku.format.NativeContext
import dev.kiroku.format.isobmff.CICP
import dev.kiroku.format.isobmff.IsoBmffParser
import dev.kiroku.io.ByteSource
import dev.kiroku.model.ContainerInfo
import dev.kiroku.model.DiagnosticCode
import dev.kiroku.model.DirectoryKind
import dev.kiroku.model.NativeType
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.Rational
import dev.kiroku.model.Severity
import dev.kiroku.model.StreamInfo
import dev.kiroku.model.StreamKind
import dev.kiroku.model.TypeSystem

/**
 * Raw HEVC (H.265) Annex-B elementary streams. Kiroku scans the first [SCAN_BYTES] for VPS/SPS/SEI NAL
 * units and decodes the SPS: profile, tier, level, chroma format, bit depth, coded and conformance
 * size, and the VUI (aspect ratio, colour description, timing).
 *
 * Elementary streams carry no container metadata: no capture time, device or GPS. Duration is unknown
 * without counting every access unit, so it is left `null`. ffprobe deep mode can estimate it.
 */
internal object HevcParser {
    private const val SCAN_BYTES = 2 * 1024 * 1024
    private val GROUP = listOf("H265", "H265")

    fun parse(ctx: NativeContext, s: ByteSource) {
        val buf = s.readAtMost(0, SCAN_BYTES)
        val nals = findNals(buf)
        var spsDone = false
        val props = LinkedHashMap<String, NormalizedValue>()
        for ((start, end) in nals) {
            ctx.countBox()
            if (end - start < 2) continue
            val type = (buf[start].toInt() shr 1) and 0x3F
            val path = "HEVC/NAL[$start]"
            when (type) {
                33 ->
                    if (!spsDone) {
                        spsDone = true
                        ctx.collector.directory(
                            listOf("HEVC", "SPS"),
                            DirectoryKind.HEVC_NAL,
                            s.absolute(start.toLong()),
                            (end - start).toLong(),
                        )
                        ctx.observe(
                            GROUP,
                            "SequenceParameterSet",
                            NormalizedValue.Binary((end - start).toLong()),
                            tagId = 33,
                            original = ctx.original(
                                s,
                                start.toLong(),
                                (
                                    end -
                                        start
                                    ).toLong(),
                            ),
                            nativeType = NativeType(TypeSystem.HEVC, "NAL"),
                            sourcePath = path,
                        )
                        try {
                            sps(ctx, unescape(buf, start + 2, end), props, path, GROUP, null)
                        } catch (e: IndexOutOfBoundsException) {
                            ctx.warn(DiagnosticCode.TRUNCATED, "HEVC SPS ended early", e)
                        } catch (e: IllegalStateException) {
                            ctx.warn(DiagnosticCode.MALFORMED, "HEVC SPS is invalid: ${e.message}", e)
                        }
                    }

                39, 40 -> sei(ctx, s, buf, start, end, path)
            }
        }
        if (!spsDone) {
            ctx.warn(
                DiagnosticCode.MALFORMED,
                "No HEVC SPS found in the first ${SCAN_BYTES / 1024} KiB",
                severity = Severity.WARNING,
            )
        }
        ctx.observe(
            GROUP,
            "NALUnitsScanned",
            NormalizedValue.Integer(nals.size.toLong()),
            nativeType = NativeType(TypeSystem.HEVC, "derived"),
        )
        ctx.collector.stream(StreamInfo(0, "", StreamKind.VIDEO, "hevc", null, props))
        ctx.collector.container(ContainerInfo("HEVC"))
    }

    private fun findNals(b: ByteArray): List<Pair<Int, Int>> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 <= b.size) {
            if (b[i] == 0.toByte() && b[i + 1] == 0.toByte() && b[i + 2] == 1.toByte()) {
                starts += i + 3
                i += 3
            } else {
                i++
            }
            if (starts.size > 100_000) break
        }
        return starts.mapIndexed { k, st ->
            var end = if (k + 1 < starts.size) starts[k + 1] - 3 else b.size
            while (end > st && b[end - 1] == 0.toByte()) end--
            st to end
        }
    }

    /** Removes emulation-prevention bytes (`00 00 03`). */
    private fun unescape(b: ByteArray, from: Int, to: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(to - from)
        var zeros = 0
        for (i in from until to) {
            val v = b[i].toInt() and 0xFF
            if (zeros >= 2 && v == 3) {
                zeros = 0
                continue
            }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private class Bits(val b: ByteArray) {
        var pos = 0

        fun u(n: Int): Long {
            var v = 0L
            repeat(n) {
                val byte = b[pos ushr 3].toInt()
                v = (v shl 1) or ((byte shr (7 - (pos and 7))) and 1).toLong()
                pos++
            }
            return v
        }

        fun flag() = u(1) == 1L

        fun ue(): Long {
            var zeros = 0
            while (u(1) == 0L) {
                zeros++
                check(zeros <= 31) { "Exp-Golomb code too long" }
            }
            return (1L shl zeros) - 1 + u(zeros)
        }

        fun se(): Long {
            val k = ue()
            return if (k and 1L == 1L) (k + 1) / 2 else -(k / 2)
        }
    }

    /**
     * Decodes an SPS NAL unit (2-byte NAL header included), as stored in an ISO-BMFF `hvcC` box, into
     * observations under [group] and stream [props]. Errors are reported, never thrown.
     */
    fun decodeSpsNal(ctx: NativeContext, nal: ByteArray, props: MutableMap<String, NormalizedValue>, path: String, group: List<String>, streamIndex: Int?) {
        if (nal.size < 4) return
        try {
            sps(ctx, unescape(nal, 2, nal.size), props, path, group, streamIndex)
        } catch (e: IndexOutOfBoundsException) {
            ctx.warn(DiagnosticCode.TRUNCATED, "HEVC SPS in hvcC ended early", e, Severity.INFO)
        } catch (e: IllegalStateException) {
            ctx.warn(DiagnosticCode.MALFORMED, "HEVC SPS in hvcC is invalid: ${e.message}", e, Severity.INFO)
        }
    }

    private fun sps(ctx: NativeContext, rbsp: ByteArray, props: MutableMap<String, NormalizedValue>, path: String, group: List<String>, streamIndex: Int?) {
        val r = Bits(rbsp)
        fun o(name: String, v: NormalizedValue, display: String? = null) = ctx.observe(
            group,
            name,
            v,
            tagId = 33,
            nativeType = NativeType(TypeSystem.HEVC, "SPS"),
            displayValue = display,
            streamIndex = streamIndex,
            sourcePath = path,
        )
        r.u(4)
        val maxSub = r.u(3).toInt()
        r.u(1)
        // profile_tier_level(1, maxSub)
        r.u(2)
        val tier = r.u(1)
        val profile = r.u(5).toInt()
        r.u(32)
        r.u(4)
        r.u(43)
        r.u(1)
        val level = r.u(8).toInt()
        val subProfile = BooleanArray(maxSub)
        val subLevel = BooleanArray(maxSub)
        for (i in 0 until maxSub) {
            subProfile[i] = r.flag()
            subLevel[i] = r.flag()
        }
        if (maxSub > 0) for (i in maxSub until 8) r.u(2)
        for (i in 0 until maxSub) {
            if (subProfile[i]) r.u(88)
            if (subLevel[i]) r.u(8)
        }
        r.ue()
        val chroma = r.ue().toInt()
        if (chroma == 3) r.u(1)
        val w = r.ue()
        val h = r.ue()
        var cw = w
        var ch = h
        if (r.flag()) {
            val subW = if (chroma == 1 || chroma == 2) 2 else 1
            val subH = if (chroma == 1) 2 else 1
            val l = r.ue()
            val rr = r.ue()
            val t = r.ue()
            val bt = r.ue()
            cw = w - subW * (l + rr)
            ch = h - subH * (t + bt)
        }
        val bdLuma = r.ue() + 8
        val bdChroma = r.ue() + 8
        val log2MaxPoc = r.ue() + 4
        val ordering = r.flag()
        for (i in (if (ordering) 0 else maxSub)..maxSub) {
            r.ue()
            r.ue()
            r.ue()
        }
        o("GeneralProfileIDC", NormalizedValue.Integer(profile.toLong()), IsoBmffParser.hevcProfile(profile))
        o("GeneralTierFlag", NormalizedValue.Integer(tier), if (tier == 1L) "High" else "Main")
        o("GeneralLevelIDC", NormalizedValue.Integer(level.toLong()), "%.1f".format(level / 30.0))
        o("ChromaFormatIDC", NormalizedValue.Integer(chroma.toLong()), IsoBmffParser.chromaName(chroma))
        o("PicWidthInLumaSamples", NormalizedValue.Integer(w))
        o("PicHeightInLumaSamples", NormalizedValue.Integer(h))
        o("ImageWidth", NormalizedValue.Integer(cw))
        o("ImageHeight", NormalizedValue.Integer(ch))
        o("BitDepthLuma", NormalizedValue.Integer(bdLuma))
        o("BitDepthChroma", NormalizedValue.Integer(bdChroma))
        props["profile"] = NormalizedValue.Text(IsoBmffParser.hevcProfile(profile))
        props["level"] = NormalizedValue.Text("%.1f".format(level / 30.0))
        props["tier"] = NormalizedValue.Text(if (tier == 1L) "High" else "Main")
        props["width"] = NormalizedValue.Integer(cw)
        props["height"] = NormalizedValue.Integer(ch)
        props["chromaFormat"] = NormalizedValue.Text(IsoBmffParser.chromaName(chroma))
        props["bitDepthLuma"] = NormalizedValue.Integer(bdLuma)
        // Skip to the VUI.
        r.ue()
        r.ue()
        r.ue()
        r.ue()
        r.ue()
        r.ue()
        if (r.flag() && r.flag()) scalingListData(r)
        r.u(1)
        r.u(1)
        if (r.flag()) {
            r.u(4)
            r.u(4)
            r.ue()
            r.ue()
            r.u(1)
        }
        val numSets = r.ue().toInt()
        check(numSets <= 64) { "too many short-term RPS" }
        val numDelta = IntArray(numSets + 1)
        for (i in 0 until numSets) numDelta[i] = stRefPicSet(r, i, numDelta)
        if (r.flag()) {
            val n = r.ue().toInt()
            check(n <= 32) { "too many long-term refs" }
            repeat(n) {
                r.u(log2MaxPoc.toInt())
                r.u(1)
            }
        }
        r.u(1)
        r.u(1)
        if (!r.flag()) return
        // vui_parameters()
        if (r.flag()) {
            val idc = r.u(8).toInt()
            val sar =
                if (idc == 255) {
                    Rational(r.u(16), r.u(16))
                } else {
                    SAR.getOrNull(idc)
                }
            if (sar != null && sar.isDefined && sar.numerator > 0) {
                o("SampleAspectRatio", NormalizedValue.RationalValue(sar))
                props["pixelAspectRatio"] = NormalizedValue.RationalValue(sar)
            }
        }
        if (r.flag()) r.u(1)
        if (r.flag()) {
            r.u(3)
            val full = r.flag()
            props["fullRange"] = NormalizedValue.Bool(full)
            o("VideoFullRangeFlag", NormalizedValue.Integer(if (full) 1 else 0))
            if (r.flag()) {
                val pri = r.u(8)
                val trc = r.u(8)
                val mat = r.u(8)
                o("ColorPrimaries", NormalizedValue.Integer(pri), CICP.primaries(pri))
                o("TransferCharacteristics", NormalizedValue.Integer(trc), CICP.transfer(trc))
                o("MatrixCoefficients", NormalizedValue.Integer(mat), CICP.matrix(mat))
                props["colorPrimaries"] = NormalizedValue.Integer(pri)
                props["transferCharacteristics"] = NormalizedValue.Integer(trc)
                props["matrixCoefficients"] = NormalizedValue.Integer(mat)
            }
        }
        if (r.flag()) {
            r.ue()
            r.ue()
        }
        r.u(1)
        r.u(1)
        r.u(1)
        if (r.flag()) {
            r.ue()
            r.ue()
            r.ue()
            r.ue()
        }
        if (r.flag()) {
            val units = r.u(32)
            val scale = r.u(32)
            if (units > 0 && scale > 0) {
                val fr = Rational(scale, units).reduced()
                o("FrameRate", NormalizedValue.RationalValue(fr))
                props["frameRate"] = NormalizedValue.RationalValue(fr)
            }
        }
    }

    private fun stRefPicSet(r: Bits, idx: Int, numDelta: IntArray): Int {
        val inter = idx != 0 && r.flag()
        if (inter) {
            r.u(1)
            r.ue()
            val ref = idx - 1
            var count = 0
            for (j in 0..numDelta[ref]) {
                val used = r.flag()
                val useDelta = if (!used) r.flag() else true
                if (used || useDelta) count++
            }
            return count
        }
        val neg = r.ue().toInt()
        val pos = r.ue().toInt()
        check(neg <= 16 && pos <= 16) { "invalid RPS" }
        repeat(neg + pos) {
            r.ue()
            r.u(1)
        }
        return neg + pos
    }

    private fun scalingListData(r: Bits) {
        for (sizeId in 0 until 4) {
            var matrixId = 0
            while (matrixId < 6) {
                if (!r.flag()) {
                    r.ue()
                } else {
                    val coefNum = minOf(64, 1 shl (4 + (sizeId shl 1)))
                    if (sizeId > 1) r.se()
                    repeat(coefNum) { r.se() }
                }
                matrixId += if (sizeId == 3) 3 else 1
            }
        }
    }

    /** SEI user_data_unregistered (payload type 5) often carries the encoder string, for example x265 settings. */
    private fun sei(ctx: NativeContext, s: ByteSource, buf: ByteArray, start: Int, end: Int, path: String) {
        val rbsp = unescape(buf, start + 2, end)
        var p = 0
        while (p < rbsp.size - 2) {
            var type = 0
            while (p < rbsp.size && rbsp[p] == 0xFF.toByte()) {
                type += 255
                p++
            }
            if (p >= rbsp.size) return
            type += rbsp[p++].toInt() and 0xFF
            var size = 0
            while (p < rbsp.size && rbsp[p] == 0xFF.toByte()) {
                size += 255
                p++
            }
            if (p >= rbsp.size) return
            size += rbsp[p++].toInt() and 0xFF
            if (p + size > rbsp.size) return
            if (type == 5 && size > 16) {
                val text = String(rbsp, p + 16, size - 16, Charsets.ISO_8859_1).trimEnd('\u0000')
                if (text.any { it.isLetter() }) {
                    ctx.observe(
                        GROUP,
                        "UserDataUnregistered",
                        NormalizedValue.Text(ctx.clip(text)),
                        tagId = 5,
                        original = ctx.originalOf(rbsp.copyOfRange(p, p + size), dev.kiroku.io.ArraySource(rbsp, null), p.toLong()),
                        nativeType = NativeType(TypeSystem.HEVC, "SEI", 5, size.toLong()),
                        sourcePath = path,
                    )
                }
            }
            p += size
            if (p < rbsp.size && rbsp[p] == 0x80.toByte()) return
        }
    }

    private val SAR: List<Rational?> =
        listOf(
            null, Rational(1, 1), Rational(12, 11), Rational(10, 11), Rational(16, 11), Rational(40, 33), Rational(24, 11),
            Rational(20, 11), Rational(32, 11), Rational(80, 33), Rational(18, 11), Rational(15, 11), Rational(64, 33),
            Rational(160, 99), Rational(4, 3), Rational(3, 2), Rational(2, 1),
        )
}
