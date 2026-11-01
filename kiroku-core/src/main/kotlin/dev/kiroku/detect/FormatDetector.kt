package dev.kiroku.detect

import dev.kiroku.format.eps.EpsParser
import dev.kiroku.format.ico.IcoParser
import dev.kiroku.format.mp3.Mp3Parser
import dev.kiroku.format.pcx.PcxParser
import dev.kiroku.format.tiff.TiffReader
import dev.kiroku.format.tiff.TiffTags
import dev.kiroku.format.tiff.TiffType
import dev.kiroku.format.tiff.TiffVariant
import dev.kiroku.io.ByteSource
import dev.kiroku.io.Bytes
import dev.kiroku.model.DiagnosticCode
import dev.kiroku.model.MediaFormat
import dev.kiroku.model.MediaKind
import dev.kiroku.model.MetadataDiagnostic
import dev.kiroku.model.Severity

internal data class Detection(
    val format: MediaFormat,
    val kind: MediaKind,
    val brands: List<String> = emptyList(),
    val diagnostics: List<MetadataDiagnostic> = emptyList(),
)

/**
 * Identifies the format from file signatures and structure. The [extension] only breaks ties
 * between structurally identical formats (for example NEF and NRW). It never overrides a signature.
 */
internal object FormatDetector {
    fun detect(s: ByteSource, extension: String?): Detection {
        val h = s.readAtMost(0, 64)
        if (h.size < 4) return Detection(MediaFormat.UNKNOWN, MediaKind.UNKNOWN)
        fun at(o: Int, str: String) = Bytes.startsWith(h, Bytes.ascii(str), o)
        return when {
            h[0] == 0xFF.toByte() && h[1] == 0xD8.toByte() && h[2] == 0xFF.toByte() -> image(MediaFormat.JPEG)

            Bytes.startsWith(
                h,
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            ) -> image(if (isApng(s)) MediaFormat.APNG else MediaFormat.PNG)

            at(0, "GIF87a") || at(0, "GIF89a") -> image(MediaFormat.GIF)

            at(0, "BM") && h.size >= 18 && Bytes.u32(h, 14, false) in setOf(12L, 40L, 52L, 56L, 64L, 108L, 124L) -> image(MediaFormat.BMP)

            (at(0, "RIFF") || at(0, "RF64") || at(0, "BW64")) && h.size >= 12 ->
                when {
                    at(8, "WEBP") -> image(MediaFormat.WEBP)
                    at(8, "AVI ") || at(8, "AVIX") -> Detection(MediaFormat.AVI, MediaKind.VIDEO)
                    at(8, "WAVE") -> Detection(MediaFormat.WAV, MediaKind.AUDIO)
                    else -> Detection(MediaFormat.UNKNOWN, MediaKind.UNKNOWN)
                }

            (at(0, "II") || at(0, "MM")) && TiffReader.readHeader(s) != null -> classifyTiff(s, extension)

            h.size >= 16 && at(0, "FUJIFILMCCD-RAW ") -> image(MediaFormat.RAF)

            at(0, "8BPS") && h.size >= 6 && (h[5].toInt() == 1 || h[5].toInt() == 2) && h[4].toInt() == 0 -> image(MediaFormat.PSD)

            EpsParser.looksLikePostScript(h) -> image(MediaFormat.EPS)

            at(0, "ID3") && Mp3Parser.looksLikeMp3(s) -> Detection(MediaFormat.MP3, MediaKind.AUDIO)

            h.size >= 8 && at(4, "ftyp") -> isoBmff(s)

            h.size >= 8 && QT_TOP_LEVEL.any { at(4, it) } -> Detection(MediaFormat.MOV, MediaKind.VIDEO)

            Bytes.startsWith(h, byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())) -> ebml(s)

            isHevcAnnexB(h) -> Detection(MediaFormat.HEVC, MediaKind.VIDEO)

            // Weak signatures last, each confirmed by structure: a bare MPEG audio stream needs two consecutive frames.
            h[0] == 0xFF.toByte() && Mp3Parser.looksLikeMp3(s) -> Detection(MediaFormat.MP3, MediaKind.AUDIO)

            h[0] == 0x0A.toByte() && PcxParser.looksLikePcx(s.readAtMost(0, 128)) -> image(MediaFormat.PCX)

            h[0] == 0.toByte() && h[1] == 0.toByte() && IcoParser.looksLikeIco(s) -> image(MediaFormat.ICO)

            else -> Detection(MediaFormat.UNKNOWN, MediaKind.UNKNOWN)
        }
    }

    private val QT_TOP_LEVEL = listOf("moov", "mdat", "wide", "free", "skip", "pnot")

    private fun image(f: MediaFormat) = Detection(f, f.defaultKind)

    /** APNG has an `acTL` chunk before the first `IDAT`. */
    private fun isApng(s: ByteSource): Boolean {
        var pos = 8L
        var n = 0
        while (pos + 8 <= s.size && n++ < 64) {
            val len = s.u32(pos, true)
            val type = s.ascii(pos + 4, 4)
            if (type == "acTL") return true
            if (type == "IDAT" || type == "IEND") return false
            pos += 12 + len
        }
        return false
    }

    private fun isoBmff(s: ByteSource): Detection {
        val size = s.u32(0, true).coerceIn(8, 4096)
        val b = s.readAtMost(0, size.toInt())
        if (b.size < 12) return Detection(MediaFormat.MP4, MediaKind.VIDEO)
        // Brands are four characters padded with spaces ("crx ", "qt  "); compare them trimmed.
        val major = Bytes.fourCC(b, 8).trim()
        val brands = buildList {
            add(major)
            var p = 16
            while (p + 4 <= b.size) {
                add(Bytes.fourCC(b, p))
                p += 4
            }
        }.map { it.trim() }.distinct()
        val all = brands.toSet()
        val f =
            when {
                major == "qt" -> MediaFormat.MOV

                major in setOf("heic", "heix", "heim", "heis", "hevc", "hevx") -> MediaFormat.HEIC

                major == "avif" || major == "avis" -> MediaFormat.AVIF

                major == "mif1" || major == "msf1" || major == "mif2" ->
                    when {
                        all.any { it in setOf("heic", "heix", "heim", "heis", "hevc", "hevx") } -> MediaFormat.HEIC
                        "avif" in all || "avis" in all -> MediaFormat.AVIF
                        else -> MediaFormat.HEIF
                    }

                major == "M4A" || major == "M4B" || major == "M4P" -> MediaFormat.M4A

                major.startsWith("3g") -> MediaFormat.THREE_GP

                major == "crx" -> MediaFormat.CR3

                "qt" in all && all.none { it in setOf("isom", "mp41", "mp42") } -> MediaFormat.MOV

                else -> MediaFormat.MP4
            }
        return Detection(f, f.defaultKind, brands)
    }

    private fun ebml(s: ByteSource): Detection {
        // The EBML header DocType (element 0x4282) appears within the first few dozen bytes.
        val b = s.readAtMost(0, 256)
        val i = Bytes.indexOf(b, byteArrayOf(0x42, 0x82.toByte()))
        if (i >= 0 && i + 3 < b.size) {
            val lenByte = b[i + 2].toInt() and 0xFF
            if (lenByte and 0x80 != 0) {
                val len = lenByte and 0x7F
                if (i + 3 + len <= b.size) {
                    val doc = String(b, i + 3, len, Charsets.ISO_8859_1).trimEnd('\u0000')
                    return when (doc) {
                        "webm" -> Detection(MediaFormat.WEBM, MediaKind.VIDEO)
                        "matroska" -> Detection(MediaFormat.MATROSKA, MediaKind.VIDEO)
                        else -> Detection(MediaFormat.UNKNOWN, MediaKind.UNKNOWN)
                    }
                }
            }
        }
        return Detection(MediaFormat.MATROSKA, MediaKind.VIDEO)
    }

    /** Annex-B start code followed by a plausible HEVC VPS/SPS/PPS/AUD/SEI NAL header. */
    internal fun isHevcAnnexB(h: ByteArray): Boolean {
        val start =
            when {
                h.size >= 6 && h[0] == 0.toByte() && h[1] == 0.toByte() && h[2] == 0.toByte() && h[3] == 1.toByte() -> 4
                h.size >= 5 && h[0] == 0.toByte() && h[1] == 0.toByte() && h[2] == 1.toByte() -> 3
                else -> return false
            }
        val b0 = h[start].toInt() and 0xFF
        val b1 = h[start + 1].toInt() and 0xFF
        if (b0 and 0x80 != 0) return false
        val type = (b0 shr 1) and 0x3F
        val tid = b1 and 0x07
        return tid != 0 && type in setOf(32, 33, 34, 35, 39, 40)
    }

    private fun classifyTiff(s: ByteSource, extension: String?): Detection {
        val header = TiffReader.readHeader(s)!!
        val ext = extension?.lowercase()
        when (header.variant) {
            TiffVariant.CR2 -> return Detection(MediaFormat.CR2, MediaKind.RAW_IMAGE)
            TiffVariant.ORF -> return Detection(MediaFormat.ORF, MediaKind.RAW_IMAGE)
            TiffVariant.BIGTIFF -> return Detection(MediaFormat.BIGTIFF, MediaKind.IMAGE)
            else -> Unit
        }
        val ifd0 = peekIfd(s, header.firstIfdOffset, header.bigEndian)
        val make = (ifd0[TiffTags.MAKE] as? String)?.trim()?.uppercase().orEmpty()
        if (header.variant == TiffVariant.RW2) {
            return Detection(if (make.startsWith("LEICA") || ext == "rwl") MediaFormat.RWL else MediaFormat.RW2, MediaKind.RAW_IMAGE)
        }
        if (ifd0.containsKey(TiffTags.DNG_VERSION)) return Detection(MediaFormat.DNG, MediaKind.RAW_IMAGE)
        // CFA or linear-raw photometric in IFD0 or the first SubIFDs means RAW.
        val photometrics = mutableListOf(ifd0[TiffTags.PHOTOMETRIC] as? Long)
        val compressions = mutableListOf(ifd0[TiffTags.COMPRESSION] as? Long)
        (ifd0[TiffTags.SUB_IFDS] as? List<*>)?.take(4)?.forEach { off ->
            val sub = peekIfd(s, off as Long, header.bigEndian)
            photometrics += sub[TiffTags.PHOTOMETRIC] as? Long
            compressions += sub[TiffTags.COMPRESSION] as? Long
        }
        val hasRawEvidence = photometrics.any { it == 32803L || it == 34892L } || compressions.any { it in RAW_COMPRESSIONS }
        val vendorRaw =
            when {
                make.startsWith("NIKON") -> MediaFormat.NEF
                make.startsWith("SONY") -> MediaFormat.ARW
                make.startsWith("SAMSUNG") -> MediaFormat.SRW
                make.startsWith("PENTAX") || make.startsWith("RICOH") || make.startsWith("ASAHI") -> MediaFormat.PEF
                make.startsWith("CANON") -> MediaFormat.CR2
                else -> null
            }
        if (vendorRaw != null && (hasRawEvidence || ext in vendorRaw.extensions)) {
            return Detection(vendorRaw, MediaKind.RAW_IMAGE)
        }
        if (hasRawEvidence) return Detection(MediaFormat.TIFF_RAW, MediaKind.RAW_IMAGE)
        val diags =
            if (ext != null && MediaFormat.fromExtension(ext)?.defaultKind == MediaKind.RAW_IMAGE) {
                listOf(
                    MetadataDiagnostic(
                        DiagnosticCode.TIFF_CLASSIFICATION_UNCERTAIN,
                        Severity.WARNING,
                        "TIFF structure has no RAW evidence (no CFA/linear photometric, DNG version, or RAW compression); classified as IMAGE",
                        "native",
                    ),
                )
            } else {
                emptyList()
            }
        return Detection(MediaFormat.TIFF, MediaKind.IMAGE, diagnostics = diags)
    }

    private val RAW_COMPRESSIONS = setOf(34713L, 34316L, 32769L, 32770L, 32867L, 65535L, 34892L, 65000L)

    /** A minimal IFD read for classification: tag to Long, String or List<Long>. Never throws. */
    private fun peekIfd(s: ByteSource, offset: Long, be: Boolean): Map<Int, Any> {
        val out = HashMap<Int, Any>()
        try {
            if (!s.contains(offset, 2)) return out
            val n = s.u16(offset, be)
            if (n > 1024) return out
            for (i in 0 until n) {
                val e = offset + 2 + i * 12L
                if (!s.contains(e, 12)) break
                val tag = s.u16(e, be)
                val type = TiffType.of(s.u16(e + 2, be)) ?: continue
                val count = s.u32(e + 4, be)
                val size = count * type.size
                val dataOff = if (size <= 4) e + 8 else s.u32(e + 8, be)
                if (size > 4096 || !s.contains(dataOff, size)) continue
                val b = s.read(dataOff, size.toInt())
                out[tag] =
                    when (type) {
                        TiffType.ASCII -> String(b, Charsets.ISO_8859_1).trimEnd('\u0000')

                        TiffType.SHORT -> if (count ==
                            1L
                        ) {
                            Bytes.u16(b, 0, be).toLong()
                        } else {
                            (0 until count.toInt()).map { Bytes.u16(b, it * 2, be).toLong() }
                        }

                        TiffType.LONG, TiffType.IFD -> if (count == 1L &&
                            tag != TiffTags.SUB_IFDS
                        ) {
                            Bytes.u32(b, 0, be)
                        } else {
                            (0 until count.toInt()).map { Bytes.u32(b, it * 4, be) }
                        }

                        else -> b.size.toLong()
                    }
            }
        } catch (_: Exception) {
            // Classification is best-effort. The full parser reports structural problems.
        }
        return out
    }
}
