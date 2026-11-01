package dev.kiroku.format.gif

import dev.kiroku.format.NativeContext
import dev.kiroku.format.xmp.XmpParser
import dev.kiroku.io.ByteSource
import dev.kiroku.io.Bytes
import dev.kiroku.model.DiagnosticCode
import dev.kiroku.model.DirectoryKind
import dev.kiroku.model.NativeType
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.TypeSystem

/**
 * GIF block parser. It counts frames and sums frame delays without decoding LZW data (sub-blocks are skipped).
 * It reads the NETSCAPE2.0/ANIMEXTS1.0 loop count, comments and XMP application extensions.
 */
internal object GifParser {
    private val GROUP = listOf("GIF", "GIF")

    fun parse(ctx: NativeContext, s: ByteSource) {
        if (s.size < 13) {
            ctx.warn(DiagnosticCode.TRUNCATED, "GIF shorter than its header")
            return
        }
        val version = s.ascii(0, 6)
        fun o(name: String, v: NormalizedValue, off: Long, n: Long, path: String = "GIF/Header", tagId: Long? = null) = ctx.observe(
            GROUP,
            name,
            v,
            tagId = tagId,
            original = ctx.original(s, off, n),
            nativeType = NativeType(TypeSystem.GIF, "block", null, n),
            sourcePath = path,
        )
        o("GIFVersion", NormalizedValue.Text(version.substring(3)), 3, 3)
        o("ImageWidth", NormalizedValue.Integer(s.u16(6, false).toLong()), 6, 2)
        o("ImageHeight", NormalizedValue.Integer(s.u16(8, false).toLong()), 8, 2)
        val flags = s.u8(10)
        val hasGct = flags and 0x80 != 0
        o("HasColorMap", NormalizedValue.Bool(hasGct), 10, 1)
        o("ColorResolutionDepth", NormalizedValue.Integer((((flags shr 4) and 7) + 1).toLong()), 10, 1)
        o("BitsPerPixel", NormalizedValue.Integer(((flags and 7) + 1).toLong()), 10, 1)
        o("BackgroundColor", NormalizedValue.Integer(s.u8(11).toLong()), 11, 1)
        if (s.u8(12) != 0) o("PixelAspectRatio", NormalizedValue.Decimal((s.u8(12) + 15) / 64.0), 12, 1)
        ctx.collector.directory(listOf("GIF", "Header"), DirectoryKind.GIF_BLOCK, s.absolute(0), 13)
        var pos = 13L + if (hasGct) 3L * (1 shl ((flags and 7) + 1)) else 0L
        var frames = 0
        var delayCs = 0L
        var anyDelay = false
        var ext = 0
        try {
            loop@ while (pos < s.size) {
                ctx.countBox()
                when (s.u8(pos)) {
                    0x3B -> break@loop

                    0x2C -> {
                        frames++
                        if (pos + 10 > s.size) throw java.io.EOFException()
                        val f = s.u8(pos + 9)
                        pos += 10
                        if (f and 0x80 != 0) pos += 3L * (1 shl ((f and 7) + 1))
                        pos += 1 // LZW minimum code size
                        pos = skipSubBlocks(ctx, s, pos)
                    }

                    0x21 -> {
                        val label = s.u8(pos + 1)
                        val path = "GIF/Extension[$ext]"
                        ext++
                        val body = pos + 2
                        when (label) {
                            0xF9 -> {
                                if (s.u8(body) >= 4) {
                                    val d = s.u16(body + 2, false).toLong()
                                    delayCs += d
                                    anyDelay = true
                                }
                                pos = skipSubBlocks(ctx, s, body)
                            }

                            0xFE -> {
                                val (text, end) = readSubBlocks(ctx, s, body)
                                // A single sub-block is contiguous in the file (byte-exact). Several are reassembled.
                                val original =
                                    if (text.size <= 255 && s.u8(body) == text.size) {
                                        ctx.original(s, body + 1, text.size.toLong())
                                    } else {
                                        ctx.originalOf(text, dev.kiroku.io.ArraySource(text, null), 0)
                                    }
                                ctx.observe(
                                    GROUP,
                                    "Comment",
                                    NormalizedValue.Text(Bytes.decodeText(text)),
                                    tagId = 0xFE,
                                    original = original,
                                    nativeType = NativeType(TypeSystem.GIF, "comment", null, text.size.toLong()),
                                    sourcePath = path,
                                )
                                pos = end
                            }

                            0xFF -> {
                                val idLen = s.u8(body)
                                val id = s.ascii(body + 1, minOf(idLen, 11))
                                val dataStart = body + 1 + idLen
                                when {
                                    id.startsWith("NETSCAPE2.0") || id.startsWith("ANIMEXTS1.0") -> {
                                        if (s.u8(dataStart) >= 3 && s.u8(dataStart + 1) == 1) {
                                            o(
                                                "AnimationIterations",
                                                NormalizedValue.Integer(s.u16(dataStart + 2, false).toLong()),
                                                dataStart + 2,
                                                2,
                                                path,
                                                0xFF,
                                            )
                                        }
                                        pos = skipSubBlocks(ctx, s, dataStart)
                                    }

                                    id.startsWith("XMP DataXMP") -> {
                                        // XMP is stored raw (sub-block length bytes are XMP text), followed by a 258-byte trailer.
                                        val end = skipSubBlocks(ctx, s, dataStart)
                                        val raw = s.read(dataStart, (end - dataStart).toInt().coerceAtMost(ctx.limits.maxXmpBytes))
                                        val close = Bytes.indexOf(raw, Bytes.ascii("<?xpacket end="))
                                        val stop = if (close >=
                                            0
                                        ) {
                                            Bytes.indexOf(raw, Bytes.ascii("?>"), close).let { if (it >= 0) it + 2 else -1 }
                                        } else {
                                            -1
                                        }
                                        if (stop > 0) XmpParser.parseBytes(ctx, raw.copyOf(stop), s, dataStart, "$path/XMP")
                                        pos = end
                                    }

                                    else -> {
                                        val end = skipSubBlocks(ctx, s, dataStart)
                                        ctx.observe(
                                            GROUP,
                                            "ApplicationExtension",
                                            NormalizedValue.Text(id),
                                            tagId = 0xFF,
                                            original = ctx.original(s, pos, end - pos),
                                            nativeType = NativeType(TypeSystem.GIF, "application"),
                                            known = false,
                                            sourcePath = path,
                                        )
                                        pos = end
                                    }
                                }
                            }

                            else -> pos = skipSubBlocks(ctx, s, body)
                        }
                    }

                    else -> {
                        ctx.warn(DiagnosticCode.MALFORMED, "Unexpected GIF block 0x%02X at $pos".format(s.u8(pos)))
                        break@loop
                    }
                }
            }
        } catch (_: java.io.EOFException) {
            ctx.warn(DiagnosticCode.TRUNCATED, "GIF data ends inside a block")
        }
        ctx.observe(GROUP, "FrameCount", NormalizedValue.Integer(frames.toLong()), nativeType = NativeType(TypeSystem.GIF, "derived"))
        if (anyDelay) {
            ctx.observe(
                GROUP,
                "Duration",
                NormalizedValue.Decimal(delayCs / 100.0),
                unit = "s",
                nativeType = NativeType(TypeSystem.GIF, "derived"),
            )
        }
    }

    private fun skipSubBlocks(ctx: NativeContext, s: ByteSource, start: Long): Long {
        var p = start
        while (true) {
            val n = s.u8(p)
            p += 1 + n
            if (n == 0) return p
            ctx.countBox()
        }
    }

    private fun readSubBlocks(ctx: NativeContext, s: ByteSource, start: Long): Pair<ByteArray, Long> {
        val out = java.io.ByteArrayOutputStream()
        var p = start
        while (true) {
            val n = s.u8(p)
            if (n == 0) return out.toByteArray() to p + 1
            if (out.size() < ctx.limits.maxStringLength) out.write(s.read(p + 1, n))
            p += 1 + n
        }
    }
}
