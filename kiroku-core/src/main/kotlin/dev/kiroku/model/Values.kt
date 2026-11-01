package dev.kiroku.model

import java.util.Base64

/**
 * An exact rational number as stored in EXIF/TIFF, QuickTime and similar formats.
 *
 * The numerator and denominator are kept exactly as stored (not reduced) so the original
 * representation stays visible. A zero denominator is legal in EXIF (it means "unknown");
 * in that case [toDouble] returns `null` instead of infinity or NaN.
 */
public data class Rational(val numerator: Long, val denominator: Long) : Comparable<Rational> {
    /** The value as a double, or `null` when the denominator is zero. */
    public fun toDouble(): Double? = if (denominator == 0L) null else numerator.toDouble() / denominator.toDouble()

    /** `true` when the denominator is non-zero. */
    public val isDefined: Boolean get() = denominator != 0L

    /** The same value reduced by the greatest common divisor, with a positive denominator. */
    public fun reduced(): Rational {
        if (denominator == 0L) return this
        val g = gcd(kotlin.math.abs(numerator), kotlin.math.abs(denominator)).coerceAtLeast(1)
        val sign = if (denominator < 0) -1 else 1
        return Rational(sign * numerator / g, sign * denominator / g)
    }

    override fun compareTo(other: Rational): Int {
        val a = toDouble() ?: Double.NaN
        val b = other.toDouble() ?: Double.NaN
        return a.compareTo(b)
    }

    override fun toString(): String = "$numerator/$denominator"

    public companion object {
        @JvmStatic
        public fun of(numerator: Long, denominator: Long): Rational = Rational(numerator, denominator)

        @JvmStatic
        public fun of(value: Int): Rational = Rational(value.toLong(), 1)

        internal tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
    }
}

/**
 * An immutable byte sequence. It is used for original binary values so that the model types stay
 * immutable and get content-based `equals`.
 */
public class ByteString private constructor(private val bytes: ByteArray) {
    public val size: Int get() = bytes.size

    public operator fun get(index: Int): Byte = bytes[index]

    /** A defensive copy of the bytes. */
    public fun toByteArray(): ByteArray = bytes.copyOf()

    public fun base64(): String = Base64.getEncoder().encodeToString(bytes)

    public fun hex(maxBytes: Int = Int.MAX_VALUE): String {
        val n = minOf(maxBytes, bytes.size)
        val sb = StringBuilder(n * 2)
        for (i in 0 until n) {
            val v = bytes[i].toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0xF])
        }
        return sb.toString()
    }

    override fun equals(other: Any?): Boolean = other is ByteString && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "ByteString(size=$size, hex=${hex(16)}${if (size > 16) "…" else ""})"

    public companion object {
        private val HEX = "0123456789abcdef".toCharArray()

        @JvmField
        public val EMPTY: ByteString = ByteString(ByteArray(0))

        /** Copies [bytes]. Later changes to the source array do not affect the result. */
        @JvmStatic
        public fun copyOf(bytes: ByteArray): ByteString = ByteString(bytes.copyOf())

        @JvmStatic
        public fun copyOf(bytes: ByteArray, offset: Int, length: Int): ByteString = ByteString(bytes.copyOfRange(offset, offset + length))

        @JvmStatic
        public fun fromBase64(value: String): ByteString = ByteString(Base64.getDecoder().decode(value))

        /** Wraps without copying. Internal use only, for arrays that are never exposed or mutated. */
        internal fun wrap(bytes: ByteArray): ByteString = ByteString(bytes)
    }
}

/** The JSON-visible type of a [NormalizedValue]. */
public enum class ValueType { TEXT, INTEGER, DECIMAL, RATIONAL, BOOLEAN, BINARY, LIST, OBJECT, NULL }

/**
 * A typed, JSON-friendly value. It is a closed hierarchy with an explicit [type] discriminator, so it
 * never needs `Any` in the public contract.
 */
public sealed interface NormalizedValue {
    public val type: ValueType

    public data class Text(val value: String) : NormalizedValue {
        override val type: ValueType get() = ValueType.TEXT

        override fun toString(): String = value
    }

    /** A signed 64-bit integer. Unsigned 32-bit TIFF values always fit. */
    public data class Integer(val value: Long) : NormalizedValue {
        override val type: ValueType get() = ValueType.INTEGER

        override fun toString(): String = value.toString()
    }

    public data class Decimal(val value: Double) : NormalizedValue {
        override val type: ValueType get() = ValueType.DECIMAL

        override fun toString(): String = value.toString()
    }

    public data class RationalValue(val value: Rational) : NormalizedValue {
        override val type: ValueType get() = ValueType.RATIONAL

        override fun toString(): String = value.toString()
    }

    public data class Bool(val value: Boolean) : NormalizedValue {
        override val type: ValueType get() = ValueType.BOOLEAN

        override fun toString(): String = value.toString()
    }

    /**
     * A binary value. The bytes themselves are in [RawObservation.original] (when captured).
     * This value only states the length and, optionally, a short preview.
     */
    public data class Binary(val length: Long) : NormalizedValue {
        override val type: ValueType get() = ValueType.BINARY

        override fun toString(): String = "(binary $length bytes)"
    }

    public data class ListValue(val items: List<NormalizedValue>) : NormalizedValue {
        override val type: ValueType get() = ValueType.LIST

        override fun toString(): String = items.joinToString(prefix = "[", postfix = "]")
    }

    /** An ordered set of named fields (insertion order is kept for deterministic output). */
    public data class ObjectValue(val fields: Map<String, NormalizedValue>) : NormalizedValue {
        override val type: ValueType get() = ValueType.OBJECT

        override fun toString(): String = fields.entries.joinToString(prefix = "{", postfix = "}") { "${it.key}=${it.value}" }
    }

    public data object Null : NormalizedValue {
        override val type: ValueType get() = ValueType.NULL

        override fun toString(): String = "null"
    }

    public companion object {
        @JvmStatic public fun text(value: String): NormalizedValue = Text(value)

        @JvmStatic public fun integer(value: Long): NormalizedValue = Integer(value)

        @JvmStatic public fun decimal(value: Double): NormalizedValue = Decimal(value)

        @JvmStatic public fun rational(numerator: Long, denominator: Long): NormalizedValue = RationalValue(
            Rational(numerator, denominator),
        )

        @JvmStatic public fun bool(value: Boolean): NormalizedValue = Bool(value)

        @JvmStatic public fun list(items: List<NormalizedValue>): NormalizedValue = if (items.size == 1) items[0] else ListValue(items)
    }
}

/** Convenience accessors that never throw. */
public fun NormalizedValue.asText(): String? = when (this) {
    is NormalizedValue.Text -> value
    is NormalizedValue.Integer -> value.toString()
    is NormalizedValue.Decimal -> value.toString()
    is NormalizedValue.RationalValue -> value.toString()
    is NormalizedValue.Bool -> value.toString()
    else -> null
}

public fun NormalizedValue.asLong(): Long? = when (this) {
    is NormalizedValue.Integer -> value
    is NormalizedValue.Decimal -> if (value.isFinite() && value == kotlin.math.floor(value)) value.toLong() else null
    is NormalizedValue.RationalValue -> value.toDouble()?.let { if (it == kotlin.math.floor(it)) it.toLong() else null }
    is NormalizedValue.Text -> value.trim().toLongOrNull()
    is NormalizedValue.ListValue -> items.firstOrNull()?.asLong()
    else -> null
}

public fun NormalizedValue.asDouble(): Double? = when (this) {
    is NormalizedValue.Integer -> value.toDouble()
    is NormalizedValue.Decimal -> value.takeIf { it.isFinite() }
    is NormalizedValue.RationalValue -> value.toDouble()
    is NormalizedValue.Text -> value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
    is NormalizedValue.ListValue -> if (items.size == 1) items[0].asDouble() else null
    else -> null
}

public fun NormalizedValue.asRational(): Rational? = when (this) {
    is NormalizedValue.RationalValue -> value
    is NormalizedValue.Integer -> Rational(value, 1)
    is NormalizedValue.ListValue -> if (items.size == 1) items[0].asRational() else null
    else -> null
}

/** The items of a list, or the single value itself as a one-element list. */
public fun NormalizedValue.asList(): List<NormalizedValue> = if (this is NormalizedValue.ListValue) items else listOf(this)

/**
 * How faithfully an observation preserves the original stored value.
 * See `09-DECISIONS.md` D3.
 */
public enum class ValueFidelity {
    /** Kiroku's native parser copied the bytes verbatim from a known file offset. */
    BYTE_EXACT,

    /** An external backend supplied bytes. They are probably original but not guaranteed identical. */
    BACKEND_RECONSTRUCTED,

    /** Only a typed decoded value is available, with no original bytes. */
    BACKEND_DECODED,

    /** Only a human-readable rendering is available. */
    DESCRIPTION_ONLY,
}

public enum class ByteOrderMark { BIG_ENDIAN, LITTLE_ENDIAN }

/** Why original bytes were not captured inline. */
public enum class OmissionReason {
    /** Larger than `rawValueByteLimit` / `maxBinaryPayloadBytes`. The offset and length still allow a byte-exact read. */
    SIZE_LIMIT,

    /** The declared value lies outside the file or its parent structure. */
    OUT_OF_BOUNDS,

    /** Raw value capture is disabled (`includeRawTags = false`). */
    NOT_REQUESTED,

    /** Removed by a privacy policy (for example GPS redaction). */
    REDACTED,
}

/** The native type system a value was read from. */
public enum class TypeSystem { TIFF, IPTC, ISOBMFF, RIFF, EBML, XMP, PNG, GIF, JPEG, BMP, ICC, HEVC, EXIFTOOL, FFPROBE, FILESYSTEM, RAF }

/**
 * The native data type of a value, for example TIFF `RATIONAL` (code 5) with count 2.
 */
public data class NativeType(val system: TypeSystem, val name: String, val code: Int? = null, val count: Long? = null)

/**
 * Where an observation's original bytes are and how faithful they are.
 *
 * With [ValueFidelity.BYTE_EXACT], [fileOffset] and [byteLength] address the exact value bytes in the
 * source file, and [bytes] (when present) is identical to them.
 */
public data class OriginalValue(
    val fidelity: ValueFidelity,
    val bytes: ByteString? = null,
    val fileOffset: Long? = null,
    val byteLength: Long? = null,
    val byteOrder: ByteOrderMark? = null,
    val omittedReason: OmissionReason? = null,
)
