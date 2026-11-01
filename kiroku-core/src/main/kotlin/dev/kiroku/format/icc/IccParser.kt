package dev.kiroku.format.icc

import dev.kiroku.format.NativeContext
import dev.kiroku.io.ByteSource
import dev.kiroku.io.Bytes
import dev.kiroku.model.DiagnosticCode
import dev.kiroku.model.DirectoryKind
import dev.kiroku.model.NativeType
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.TypeSystem

/**
 * ICC colour profiles (v2 and v4): the 128-byte header plus the tag table. Text tags (`desc`, `mluc`,
 * `text`) and XYZ tags are decoded. Other tag types are kept as byte-exact binary values.
 * In FAST mode only the header and the profile description are decoded.
 */
internal object IccParser {
    private val tagNames =
        mapOf(
            "desc" to "ProfileDescription", "cprt" to "ProfileCopyright", "dmnd" to "DeviceMfgDesc",
            "dmdd" to "DeviceModelDesc", "wtpt" to "MediaWhitePoint", "bkpt" to "MediaBlackPoint",
            "rXYZ" to "RedMatrixColumn", "gXYZ" to "GreenMatrixColumn", "bXYZ" to "BlueMatrixColumn",
            "rTRC" to "RedTRC", "gTRC" to "GreenTRC", "bTRC" to "BlueTRC", "kTRC" to "GrayTRC",
            "chad" to "ChromaticAdaptation", "lumi" to "Luminance", "meas" to "Measurement", "tech" to "Technology",
            "view" to "ViewingConditions", "vued" to "ViewingCondDesc", "chrm" to "Chromaticity",
            "A2B0" to "AToB0", "A2B1" to "AToB1", "A2B2" to "AToB2", "B2A0" to "BToA0", "B2A1" to "BToA1", "B2A2" to "BToA2",
            "gamt" to "Gamut", "ciis" to "ColorimetricIntentImageState", "cicp" to "CICP",
            "lumi" to "Luminance", "targ" to "CharTarget", "calt" to "CalibrationDateTime",
            "pseq" to "ProfileSequenceDesc", "psid" to "ProfileSequenceIdentifier", "dscm" to "AppleMultiLanguageDescription",
            "MS00" to "WCSProfiles", "clrt" to "ColorantTable", "clro" to "ColorantOrder",
        )

    fun parse(ctx: NativeContext, source: ByteSource, offset: Long, length: Long, path: String) {
        if (length < 128 || !source.contains(offset, length)) {
            if (length > 0) ctx.warn(DiagnosticCode.MALFORMED, "ICC profile shorter than its 128-byte header")
            return
        }
        val h = source.read(offset, 128)
        if (h.copyOfRange(36, 40).toString(Charsets.ISO_8859_1) != "acsp") {
            ctx.warn(DiagnosticCode.MALFORMED, "ICC profile signature 'acsp' missing")
            return
        }
        ctx.collector.directory(path.split('/'), DirectoryKind.ICC_PROFILE, source.absolute(offset), length)
        val headerGroup = listOf("ICC_Profile", "ICC-header")

        fun obs(name: String, v: NormalizedValue, off: Int, len: Int, type: String) = ctx.observe(
            headerGroup,
            name,
            v,
            tagId = off.toLong(),
            original = ctx.original(source, offset + off, len.toLong()),
            nativeType = NativeType(TypeSystem.ICC, type, null, len.toLong()),
            sourcePath = "$path/header",
        )

        fun sig(off: Int) = String(h, off, 4, Charsets.ISO_8859_1).trimEnd('\u0000', ' ')
        obs("ProfileSize", NormalizedValue.Integer(Bytes.u32(h, 0, true)), 0, 4, "uInt32")
        obs("ProfileCMMType", NormalizedValue.Text(sig(4)), 4, 4, "signature")
        val v = h[8].toInt() and 0xFF
        val minor = (h[9].toInt() and 0xF0) shr 4
        val bug = h[9].toInt() and 0x0F
        obs("ProfileVersion", NormalizedValue.Text("$v.$minor.$bug"), 8, 4, "version")
        obs("ProfileClass", NormalizedValue.Text(sig(12)), 12, 4, "signature")
        obs("ColorSpaceData", NormalizedValue.Text(sig(16)), 16, 4, "signature")
        obs("ProfileConnectionSpace", NormalizedValue.Text(sig(20)), 20, 4, "signature")
        val y = Bytes.u16(h, 24, true)
        if (y != 0) {
            obs(
                "ProfileDateTime",
                NormalizedValue.Text(
                    "%04d:%02d:%02d %02d:%02d:%02d".format(
                        y,
                        Bytes.u16(h, 26, true),
                        Bytes.u16(h, 28, true),
                        Bytes.u16(h, 30, true),
                        Bytes.u16(h, 32, true),
                        Bytes.u16(h, 34, true),
                    ),
                ),
                24,
                12,
                "dateTimeNumber",
            )
        }
        obs("ProfileFileSignature", NormalizedValue.Text(sig(36)), 36, 4, "signature")
        obs("PrimaryPlatform", NormalizedValue.Text(sig(40)), 40, 4, "signature")
        obs("CMMFlags", NormalizedValue.Integer(Bytes.u32(h, 44, true)), 44, 4, "uInt32")
        obs("DeviceManufacturer", NormalizedValue.Text(sig(48)), 48, 4, "signature")
        obs("DeviceModel", NormalizedValue.Text(sig(52)), 52, 4, "signature")
        obs("DeviceAttributes", NormalizedValue.Integer(Bytes.u64(h, 56, true)), 56, 8, "uInt64")
        obs("RenderingIntent", NormalizedValue.Integer(Bytes.u32(h, 64, true)), 64, 4, "uInt32")
        obs("ConnectionSpaceIlluminant", xyz(h, 68), 68, 12, "XYZNumber")
        obs("ProfileCreator", NormalizedValue.Text(sig(80)), 80, 4, "signature")
        obs("ProfileID", NormalizedValue.Text(h.copyOfRange(84, 100).joinToString("") { "%02x".format(it) }), 84, 16, "uInt8[16]")

        if (length < 132) return
        val count = source.u32(offset + 128, true)
        if (count > ctx.limits.maxEntriesPerDirectory || 132 + count * 12 > length) {
            ctx.warn(DiagnosticCode.MALFORMED, "ICC tag table count $count is invalid")
            return
        }
        val tagGroup = listOf("ICC_Profile", "ICC_Profile")
        for (i in 0 until count.toInt()) {
            val e = offset + 132 + i * 12L
            val sigBytes = source.read(e, 4)
            val tagSig = String(sigBytes, Charsets.ISO_8859_1)
            val tOff = source.u32(e + 4, true)
            val tLen = source.u32(e + 8, true)
            if (ctx.fast && tagSig != "desc") continue
            if (tOff + tLen > length) {
                ctx.warnOnce("icc-oob", DiagnosticCode.MALFORMED, "ICC tag '$tagSig' points outside the profile")
                continue
            }
            val abs = offset + tOff
            val typeSig = if (tLen >= 4) source.ascii(abs, 4) else ""
            val value = decodeTag(ctx, source, abs, tLen, typeSig)
            val name = tagNames[tagSig]
            ctx.observe(
                tagGroup,
                name ?: tagSig,
                value,
                tagId = Bytes.u32(sigBytes, 0, true),
                original = ctx.original(source, abs, tLen),
                nativeType = NativeType(TypeSystem.ICC, typeSig.trim(), null, tLen),
                known = name != null,
                sourcePath = "$path/$tagSig",
            )
        }
    }

    private fun decodeTag(ctx: NativeContext, s: ByteSource, off: Long, len: Long, type: String): NormalizedValue {
        if (len > ctx.limits.maxStringLength) return NormalizedValue.Binary(len)
        return try {
            when (type) {
                "desc" -> {
                    if (len < 12) return NormalizedValue.Binary(len)
                    val n = s.u32(off + 8, true).toInt()
                    if (n <= 0 || 12 + n > len) return NormalizedValue.Binary(len)
                    NormalizedValue.Text(String(s.read(off + 12, n), Charsets.ISO_8859_1).trimEnd('\u0000'))
                }

                "mluc" -> {
                    val records = s.u32(off + 8, true).toInt()
                    val recSize = s.u32(off + 12, true).toInt()
                    if (records <= 0 || recSize < 12) return NormalizedValue.Binary(len)
                    val out = LinkedHashMap<String, NormalizedValue>()
                    for (r in 0 until minOf(records, 256)) {
                        val ro = off + 16 + r.toLong() * recSize
                        val lang = s.ascii(ro, 2) + "-" + s.ascii(ro + 2, 2)
                        val sl = s.u32(ro + 4, true)
                        val so = s.u32(ro + 8, true)
                        if (so + sl > len) continue
                        out[lang] = NormalizedValue.Text(String(s.read(off + so, sl.toInt()), Charsets.UTF_16BE).trimEnd('\u0000'))
                    }
                    if (out.size == 1) out.values.first() else NormalizedValue.ObjectValue(out)
                }

                "text" -> NormalizedValue.Text(String(s.read(off + 8, (len - 8).toInt()), Charsets.ISO_8859_1).trimEnd('\u0000'))

                "XYZ " -> {
                    val n = ((len - 8) / 12).toInt()
                    val b = s.read(off + 8, n * 12)
                    NormalizedValue.list((0 until n).map { xyz(b, it * 12) })
                }

                "sig " -> NormalizedValue.Text(s.ascii(off + 8, 4).trimEnd('\u0000', ' '))

                else -> NormalizedValue.Binary(len)
            }
        } catch (_: java.io.EOFException) {
            NormalizedValue.Binary(len)
        }
    }

    private fun s15f16(b: ByteArray, o: Int): Double = Bytes.s32(b, o, true) / 65536.0

    private fun xyz(b: ByteArray, o: Int): NormalizedValue = NormalizedValue.ListValue(
        listOf(
            NormalizedValue.Decimal(s15f16(b, o)),
            NormalizedValue.Decimal(s15f16(b, o + 4)),
            NormalizedValue.Decimal(
                s15f16(
                    b,
                    o + 8,
                ),
            ),
        ),
    )
}
