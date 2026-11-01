package dev.kiroku.io

import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Bounded, read-only random access to metadata bytes.
 *
 * [absoluteBase] is this source's offset in the original file, or `null` when the bytes are not
 * file-backed (for example after inflating a compressed chunk). Native parsers claim `BYTE_EXACT`
 * only when [absoluteBase] is non-null.
 */
internal abstract class ByteSource {
    abstract val size: Long
    abstract val absoluteBase: Long?

    /** Reads exactly [length] bytes at [offset], or throws [EOFException]. */
    abstract fun read(offset: Long, length: Int): ByteArray

    /** Reads up to [length] bytes at [offset]. Returns fewer at the end of the source. */
    fun readAtMost(offset: Long, length: Int): ByteArray {
        if (offset >= size || offset < 0) return ByteArray(0)
        val n = minOf(length.toLong(), size - offset).toInt()
        return read(offset, n)
    }

    fun contains(offset: Long, length: Long): Boolean = offset >= 0 && length >= 0 && offset <= size && length <= size - offset

    fun absolute(offset: Long): Long? = absoluteBase?.let { it + offset }

    fun slice(offset: Long, length: Long): ByteSource {
        require(contains(offset, length)) { "slice out of bounds" }
        return SliceSource(this, offset, length)
    }

    fun u8(offset: Long): Int = read(offset, 1)[0].toInt() and 0xFF

    fun u16(offset: Long, bigEndian: Boolean): Int = Bytes.u16(read(offset, 2), 0, bigEndian)

    fun u32(offset: Long, bigEndian: Boolean): Long = Bytes.u32(read(offset, 4), 0, bigEndian)

    fun u64(offset: Long, bigEndian: Boolean): Long = Bytes.u64(read(offset, 8), 0, bigEndian)

    fun ascii(offset: Long, length: Int): String = String(read(offset, length), Charsets.ISO_8859_1)
}

internal class ArraySource(private val bytes: ByteArray, override val absoluteBase: Long?) : ByteSource() {
    override val size: Long get() = bytes.size.toLong()

    override fun read(offset: Long, length: Int): ByteArray {
        if (length < 0 || offset < 0 || offset + length > bytes.size) throw EOFException("read beyond end of buffer")
        return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }
}

internal class SliceSource(private val parent: ByteSource, private val start: Long, override val size: Long) : ByteSource() {
    override val absoluteBase: Long? get() = parent.absoluteBase?.let { it + start }

    override fun read(offset: Long, length: Int): ByteArray {
        if (length < 0 || offset < 0 || offset + length > size) throw EOFException("read beyond end of slice")
        return parent.read(start + offset, length)
    }
}

/**
 * A file-backed source with a small block cache. It never maps or loads the whole file.
 */
internal class FileSource private constructor(private val channel: FileChannel, override val size: Long) :
    ByteSource(),
    AutoCloseable {
    override val absoluteBase: Long get() = 0L

    private val blockSize = 64 * 1024
    private val maxBlocks = 32
    private val cache =
        object : LinkedHashMap<Long, ByteArray>(maxBlocks, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean = size > maxBlocks
        }

    /** The total bytes read from the channel, used by tests to check that big files are not fully read. */
    var bytesReadFromDisk: Long = 0
        private set

    override fun read(offset: Long, length: Int): ByteArray {
        if (length < 0 || offset < 0 || offset + length > size) throw EOFException("read beyond end of file")
        if (length > blockSize * 4) return readDirect(offset, length)
        val out = ByteArray(length)
        var pos = 0
        while (pos < length) {
            val abs = offset + pos
            val blockIndex = abs / blockSize
            val block = block(blockIndex)
            val inBlock = (abs - blockIndex * blockSize).toInt()
            val n = minOf(length - pos, block.size - inBlock)
            if (n <= 0) throw EOFException("short block")
            System.arraycopy(block, inBlock, out, pos, n)
            pos += n
        }
        return out
    }

    private fun block(index: Long): ByteArray = cache.getOrPut(index) {
        val start = index * blockSize
        val n = minOf(blockSize.toLong(), size - start).toInt()
        readDirect(start, n)
    }

    private fun readDirect(offset: Long, length: Int): ByteArray {
        val buf = ByteBuffer.allocate(length)
        var pos = offset
        while (buf.hasRemaining()) {
            val r = channel.read(buf, pos)
            if (r < 0) throw EOFException("unexpected end of file")
            pos += r
        }
        bytesReadFromDisk += length
        return buf.array()
    }

    override fun close() {
        channel.close()
    }

    companion object {
        @Throws(IOException::class)
        fun open(path: Path): FileSource {
            val ch = FileChannel.open(path, StandardOpenOption.READ)
            return FileSource(ch, ch.size())
        }
    }
}

internal object Bytes {
    fun u16(b: ByteArray, o: Int, be: Boolean): Int = if (be) {
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    } else {
        ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF)
    }

    fun s16(b: ByteArray, o: Int, be: Boolean): Int = u16(b, o, be).toShort().toInt()

    fun u32(b: ByteArray, o: Int, be: Boolean): Long = if (be) {
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
    } else {
        ((b[o + 3].toLong() and 0xFF) shl 24) or ((b[o + 2].toLong() and 0xFF) shl 16) or
            ((b[o + 1].toLong() and 0xFF) shl 8) or (b[o].toLong() and 0xFF)
    }

    fun s32(b: ByteArray, o: Int, be: Boolean): Long = u32(b, o, be).toInt().toLong()

    fun u64(b: ByteArray, o: Int, be: Boolean): Long = if (be) {
        (u32(b, o, true) shl 32) or u32(b, o + 4, true)
    } else {
        (u32(b, o + 4, false) shl 32) or u32(b, o, false)
    }

    fun u24be(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o + 2].toInt() and 0xFF)

    fun fourCC(b: ByteArray, o: Int): String {
        val sb = StringBuilder(4)
        for (i in 0 until 4) {
            val c = b[o + i].toInt() and 0xFF
            sb.append(if (c in 0x20..0x7E) c.toChar() else '?')
        }
        return sb.toString()
    }

    fun fourCCValue(b: ByteArray, o: Int): Long = u32(b, o, true)

    fun startsWith(b: ByteArray, prefix: ByteArray, at: Int = 0): Boolean {
        if (b.size < at + prefix.size) return false
        for (i in prefix.indices) if (b[at + i] != prefix[i]) return false
        return true
    }

    fun ascii(s: String): ByteArray = s.toByteArray(Charsets.ISO_8859_1)

    fun indexOf(b: ByteArray, needle: ByteArray, from: Int = 0): Int {
        if (needle.isEmpty()) return from
        outer@ for (i in from..b.size - needle.size) {
            for (j in needle.indices) if (b[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    /** Decodes text that should be ASCII but is often UTF-8 in practice. Falls back to ISO-8859-1. */
    fun decodeText(b: ByteArray, off: Int = 0, len: Int = b.size - off): String {
        val decoder =
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(b, off, len)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            String(b, off, len, Charsets.ISO_8859_1)
        }
    }
}
