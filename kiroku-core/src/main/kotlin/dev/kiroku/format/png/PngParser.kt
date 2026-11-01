package dev.kiroku.format.png

import dev.kiroku.format.NativeContext
import dev.kiroku.format.icc.IccParser
import dev.kiroku.format.tiff.TiffReader
import dev.kiroku.format.xmp.XmpParser
import dev.kiroku.io.ArraySource
import dev.kiroku.io.ByteSource
import dev.kiroku.io.Bytes
import dev.kiroku.model.DiagnosticCode
import dev.kiroku.model.DirectoryKind
import dev.kiroku.model.NativeType
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.Rational
import dev.kiroku.model.TypeSystem
import java.util.zip.Inflater

/**
 * The PNG/APNG chunk parser. IDAT/fdAT image data is skipped and never decoded.
 * Values from compressed chunks (zTXt, compressed iTXt, iCCP) are inflated within bounds and
 * marked `BACKEND_RECONSTRUCTED`, since their bytes do not appear verbatim in the file.
 */
internal object PngParser {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val GROUP = listOf("PNG", "PNG")

    data class Result(val animated: Boolean)

    fun parse(ctx: NativeContext, source: ByteSource): Result {
        if (!Bytes.startsWith(source.readAtMost(0, 8), SIGNATURE)) {
            ctx.warn(DiagnosticCode.MALFORMED, "PNG signature missing")
            return Result(false)
        }
        var pos = 8L
        var index = 0
        var animated = false
        var frameControls = 0
        var sawIend = false
        while (pos + 12 <= source.size) {
            ctx.countBox()
            val len = source.u32(pos, true)
            val typeBytes = source.read(pos + 4, 4)
            val type = String(typeBytes, Charsets.ISO_8859_1)
            val data = pos + 8
            if (len > Int.MAX_VALUE || !source.contains(data, len + 4)) {
                ctx.warn(DiagnosticCode.TRUNCATED, "PNG chunk '$type' is truncated")
                break
            }
            val path = "PNG/$type[$index]"
            val fourcc = Bytes.u32(typeBytes, 0, true)
            if (type != "IDAT" && type != "fdAT") {
                ctx.collector.directory(path.split('/'), DirectoryKind.PNG_CHUNK, source.absolute(pos), len + 12)
            }
            fun o(name: String, v: NormalizedValue, off: Long, n: Long, display: String? = null) = ctx.observe(
                GROUP,
                name,
                v,
                tagId = fourcc,
                original = ctx.original(source, off, n),
                nativeType = NativeType(TypeSystem.PNG, type, null, n),
                displayValue = display,
                sourcePath = path,
            )
            when (type) {
                "IHDR" ->
                    if (len >= 13) {
                        o("ImageWidth", NormalizedValue.Integer(source.u32(data, true)), data, 4)
                        o("ImageHeight", NormalizedValue.Integer(source.u32(data + 4, true)), data + 4, 4)
                        o("BitDepth", NormalizedValue.Integer(source.u8(data + 8).toLong()), data + 8, 1)
                        val ct = source.u8(data + 9).toLong()
                        o("ColorType", NormalizedValue.Integer(ct), data + 9, 1, colorType(ct))
                        o("Compression", NormalizedValue.Integer(source.u8(data + 10).toLong()), data + 10, 1)
                        o("Filter", NormalizedValue.Integer(source.u8(data + 11).toLong()), data + 11, 1)
                        o("Interlace", NormalizedValue.Integer(source.u8(data + 12).toLong()), data + 12, 1)
                    }

                "eXIf" -> TiffReader(ctx, source.slice(data, len), "$path/TIFF").parse()

                "iCCP" -> {
                    val b = source.read(data, minOf(len, 80).toInt())
                    val nul = b.indexOf(0)
                    if (nul > 0) {
                        o("ProfileName", NormalizedValue.Text(String(b, 0, nul, Charsets.ISO_8859_1)), data, nul.toLong())
                        val compStart = data + nul + 2
                        inflate(ctx, source, compStart, len - nul - 2)?.let { icc ->
                            IccParser.parse(ctx, ArraySource(icc, null), 0, icc.size.toLong(), "$path/ICC_Profile")
                        }
                    }
                }

                "tEXt", "zTXt", "iTXt" -> text(ctx, source, type, data, len, path, fourcc)

                "pHYs" ->
                    if (len >= 9) {
                        o("PixelsPerUnitX", NormalizedValue.Integer(source.u32(data, true)), data, 4)
                        o("PixelsPerUnitY", NormalizedValue.Integer(source.u32(data + 4, true)), data + 4, 4)
                        o(
                            "PixelUnits",
                            NormalizedValue.Integer(source.u8(data + 8).toLong()),
                            data + 8,
                            1,
                            if (source.u8(data + 8) ==
                                1
                            ) {
                                "meters"
                            } else {
                                "unknown"
                            },
                        )
                    }

                "tIME" ->
                    if (len >= 7) {
                        val t = "%04d:%02d:%02d %02d:%02d:%02d".format(
                            source.u16(data, true),
                            source.u8(data + 2),
                            source.u8(data + 3),
                            source.u8(data + 4),
                            source.u8(data + 5),
                            source.u8(data + 6),
                        )
                        o("ModifyDate", NormalizedValue.Text(t), data, 7)
                    }

                "gAMA" -> if (len >= 4) o("Gamma", NormalizedValue.RationalValue(Rational(100000, source.u32(data, true))), data, 4)

                "sRGB" -> if (len >= 1) o("SRGBRendering", NormalizedValue.Integer(source.u8(data).toLong()), data, 1)

                "cICP" ->
                    if (len >= 4) {
                        o("ColorPrimaries", NormalizedValue.Integer(source.u8(data).toLong()), data, 1)
                        o("TransferCharacteristics", NormalizedValue.Integer(source.u8(data + 1).toLong()), data + 1, 1)
                        o("MatrixCoefficients", NormalizedValue.Integer(source.u8(data + 2).toLong()), data + 2, 1)
                        o("VideoFullRangeFlag", NormalizedValue.Integer(source.u8(data + 3).toLong()), data + 3, 1)
                    }

                "cHRM" ->
                    if (len >= 32) {
                        val names = listOf("WhitePointX", "WhitePointY", "RedX", "RedY", "GreenX", "GreenY", "BlueX", "BlueY")
                        names.forEachIndexed { i, n ->
                            o(
                                n,
                                NormalizedValue.RationalValue(Rational(source.u32(data + i * 4, true), 100000)),
                                data + i * 4,
                                4,
                            )
                        }
                    }

                "sBIT" -> o(
                    "SignificantBits",
                    NormalizedValue.list(
                        source.read(data, len.toInt()).map {
                            NormalizedValue.Integer(it.toLong() and 0xFF)
                        },
                    ),
                    data,
                    len,
                )

                "acTL" ->
                    if (len >= 8) {
                        animated = true
                        o("AnimationFrames", NormalizedValue.Integer(source.u32(data, true)), data, 4)
                        o("AnimationPlays", NormalizedValue.Integer(source.u32(data + 4, true)), data + 4, 4)
                    }

                "fcTL" -> {
                    frameControls++
                    if (len >= 26) {
                        val num = source.u16(data + 20, true).toLong()
                        val den = source.u16(data + 22, true).toLong().let { if (it == 0L) 100L else it }
                        o("FrameDelay", NormalizedValue.RationalValue(Rational(num, den)), data + 20, 4)
                    }
                }

                "IDAT", "fdAT", "PLTE", "tRNS" -> Unit

                "IEND" -> sawIend = true

                else ->
                    o(type, NormalizedValue.Binary(len), data, len).also {
                        // Unknown or uncommon ancillary chunks are kept byte-exactly.
                    }
            }
            if (sawIend) break
            pos = data + len + 4
            index++
        }
        if (!sawIend) ctx.warn(DiagnosticCode.TRUNCATED, "PNG has no IEND chunk", severity = dev.kiroku.model.Severity.INFO)
        if (frameControls > 0) {
            ctx.observe(
                GROUP,
                "FrameControlCount",
                NormalizedValue.Integer(frameControls.toLong()),
                nativeType = NativeType(TypeSystem.PNG, "derived"),
            )
        }
        return Result(animated)
    }

    private fun text(ctx: NativeContext, source: ByteSource, type: String, data: Long, len: Long, path: String, fourcc: Long) {
        if (len > ctx.limits.maxXmpBytes) {
            ctx.warn(DiagnosticCode.LIMIT_EXCEEDED, "PNG $type chunk too large ($len bytes)")
            return
        }
        val b = source.read(data, len.toInt())
        val nul = b.indexOf(0)
        if (nul <= 0) return
        val keyword = String(b, 0, nul, Charsets.ISO_8859_1)
        var textBytes: ByteArray
        var textOffset: Long
        var compressed = false
        var lang: String? = null
        var utf8 = false
        when (type) {
            "tEXt" -> {
                textOffset = data + nul + 1
                textBytes = b.copyOfRange(nul + 1, b.size)
            }

            "zTXt" -> {
                compressed = true
                textOffset = data + nul + 2
                textBytes = inflate(ctx, source, textOffset, len - nul - 2) ?: return
            }

            else -> {
                utf8 = true
                if (b.size < nul + 3) return
                compressed = b[nul + 1].toInt() == 1
                var p = nul + 3
                val langEnd = (p until b.size).firstOrNull { b[it] == 0.toByte() } ?: return
                lang = String(b, p, langEnd - p, Charsets.ISO_8859_1).ifEmpty { null }
                p = langEnd + 1
                val transEnd = (p until b.size).firstOrNull { b[it] == 0.toByte() } ?: return
                p = transEnd + 1
                textOffset = data + p
                textBytes = if (compressed) inflate(ctx, source, textOffset, len - p) ?: return else b.copyOfRange(p, b.size)
            }
        }
        if (keyword == "XML:com.adobe.xmp") {
            if (compressed) {
                XmpParser.parseBytes(ctx, textBytes, ArraySource(textBytes, null), 0, "$path/XMP")
            } else {
                XmpParser.parseBytes(ctx, textBytes, source, textOffset, "$path/XMP")
            }
            return
        }
        if (keyword.startsWith("Raw profile type ")) {
            rawProfile(ctx, keyword.removePrefix("Raw profile type ").lowercase(), textBytes, path)
        }
        val str = if (utf8) String(textBytes, Charsets.UTF_8) else String(textBytes, Charsets.ISO_8859_1)
        val original = if (compressed) {
            ctx.originalOf(
                textBytes,
                ArraySource(textBytes, null),
                0,
            )
        } else {
            ctx.original(source, textOffset, textBytes.size.toLong())
        }
        ctx.observe(
            listOf("PNG", "PNG-$type"),
            keyword.replace(' ', '_'),
            NormalizedValue.Text(ctx.clip(str)),
            tagId = fourcc,
            original = original,
            nativeType = NativeType(TypeSystem.PNG, type, null, textBytes.size.toLong()),
            namespace = lang,
            sourcePath = "$path/$keyword",
        )
    }

    /** ImageMagick "Raw profile type exif|xmp|iptc|icc": a text header and hex-encoded bytes. */
    private fun rawProfile(ctx: NativeContext, kind: String, text: ByteArray, path: String) {
        val s = String(text, Charsets.ISO_8859_1).trim()
        val lines = s.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 3) return
        val hex = lines.drop(2).joinToString("")
        if (hex.length % 2 != 0 || hex.length / 2 > ctx.limits.maxXmpBytes) return
        val bytes =
            try {
                ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            } catch (_: NumberFormatException) {
                return
            }
        val src = ArraySource(bytes, null)
        when (kind) {
            "exif" -> {
                val skip = if (Bytes.startsWith(bytes, Bytes.ascii("Exif\u0000\u0000"))) 6L else 0L
                TiffReader(ctx, src.slice(skip, bytes.size - skip), "$path/RawProfileExif").parse()
            }

            "xmp" -> XmpParser.parseBytes(ctx, bytes, src, 0, "$path/RawProfileXmp")

            "icc", "icm" -> IccParser.parse(ctx, src, 0, bytes.size.toLong(), "$path/RawProfileIcc")

            "iptc" -> dev.kiroku.format.iptc.IptcParser.parse(ctx, src, 0, bytes.size.toLong(), "$path/RawProfileIptc")

            "8bim" -> dev.kiroku.format.iptc.PhotoshopParser.parse(ctx, src, 0, bytes.size.toLong(), "$path/RawProfile8bim")
        }
    }

    /** Bounded zlib inflate. Returns `null` on error or when the output limit is exceeded. */
    fun inflate(ctx: NativeContext, source: ByteSource, off: Long, len: Long): ByteArray? {
        if (len <= 0 || len > Int.MAX_VALUE) return null
        val input = source.read(off, len.toInt())
        val limit = ctx.limits.maxXmpBytes
        val inf = Inflater()
        return try {
            inf.setInput(input)
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
                if (out.size() > limit) {
                    ctx.warn(DiagnosticCode.LIMIT_EXCEEDED, "Compressed chunk inflates beyond $limit bytes; skipped")
                    return null
                }
            }
            out.toByteArray()
        } catch (e: java.util.zip.DataFormatException) {
            ctx.warn(DiagnosticCode.MALFORMED, "Invalid zlib data in PNG chunk", e)
            null
        } finally {
            inf.end()
        }
    }

    private fun colorType(c: Long): String? = when (c) {
        0L -> "Grayscale"
        2L -> "RGB"
        3L -> "Palette"
        4L -> "Grayscale with Alpha"
        6L -> "RGB with Alpha"
        else -> null
    }
}
