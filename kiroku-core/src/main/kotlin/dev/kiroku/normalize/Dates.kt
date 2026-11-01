package dev.kiroku.normalize

import dev.kiroku.model.CaptureTimestamp
import dev.kiroku.model.TimestampPrecision
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/** A parsed date-time. [offset] is set only when the source states it. It is never assumed. */
public data class ParsedDateTime(val local: LocalDateTime, val offset: ZoneOffset?, val precision: TimestampPrecision) {
    val instant: Instant? get() = offset?.let { local.toInstant(it) }

    public fun toCapture(source: String): CaptureTimestamp = CaptureTimestamp(
        instant = instant,
        localDateTime = local,
        offset = offset,
        precision = precision,
        source = source,
        timezoneKnown = offset != null,
    )
}

/**
 * Tolerant date parsing for EXIF, XMP/ISO 8601, QuickTime, IPTC, RIFF and similar formats.
 * Implausible values (all zeros, year < 1800, more than one day in the future) return `null`.
 */
public object Dates {
    private val EXIF =
        Regex(
            """^\s*(\d{4})[:\-./](\d{1,2})[:\-./](\d{1,2})(?:[ T]+(\d{1,2}):(\d{2})(?::(\d{2})(?:[.,](\d{1,9}))?)?)?\s*(Z|[+\-]\d{2}:?\d{2}|[+\-]\d{2})?\s*$""",
        )
    private val YEAR_MONTH = Regex("""^\s*(\d{4})(?:-(\d{2}))?\s*$""")
    private val COMPACT = Regex("""^\s*(\d{4})(\d{2})(\d{2})(?:T?(\d{2})(\d{2})(\d{2})?)?\s*(Z|[+\-]\d{2}:?\d{2})?\s*$""")
    private val CTIME = Regex("""^\s*(?:[A-Za-z]{3}\s+)?([A-Za-z]{3})\s+(\d{1,2})\s+(\d{1,2}):(\d{2}):(\d{2})\s+(\d{4})\s*$""")
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** Parses a date-time string in any supported format. [subSeconds] (EXIF SubSecTime) and [offset] (EXIF OffsetTime*) are applied when given. */
    @JvmStatic
    @JvmOverloads
    public fun parse(text: String?, subSeconds: String? = null, offset: String? = null): ParsedDateTime? {
        val s = text?.trim()?.trimEnd('\u0000')?.trim() ?: return null
        if (s.isEmpty() || s.all { it == '0' || it == ':' || it == ' ' || it == '-' || it == 'T' }) return null
        val parsed = parseCore(s) ?: return null
        var result = parsed
        val sub = subSeconds?.trim()?.takeWhile { it.isDigit() }
        if (!sub.isNullOrEmpty() && result.precision == TimestampPrecision.SECOND) {
            val digits = sub.take(9)
            val nanos = digits.padEnd(9, '0').toLong()
            val precision =
                when {
                    digits.length <= 3 -> TimestampPrecision.MILLISECOND
                    digits.length <= 6 -> TimestampPrecision.MICROSECOND
                    else -> TimestampPrecision.NANOSECOND
                }
            result = result.copy(local = result.local.withNano(nanos.toInt()), precision = precision)
        }
        if (result.offset == null && offset != null) {
            parseOffset(offset)?.let { result = result.copy(offset = it) }
        }
        return result.takeIf { plausible(it.local) }
    }

    /** Parses an EXIF-style offset such as `+02:00`, `-0530` or `Z`. Returns `null` for blank or invalid input. */
    @JvmStatic
    public fun parseOffset(text: String?): ZoneOffset? {
        val s = text?.trim()?.trimEnd('\u0000')?.trim() ?: return null
        if (s.isEmpty() || s.startsWith("   ")) return null
        if (s == "Z" || s == "z") return ZoneOffset.UTC
        val m = Regex("""^([+\-])(\d{2}):?(\d{2})?$""").matchEntire(s) ?: return null
        val sign = if (m.groupValues[1] == "-") -1 else 1
        val h = m.groupValues[2].toInt()
        val min = m.groupValues[3].ifEmpty { "0" }.toInt()
        if (h > 18 || min > 59) return null
        return try {
            ZoneOffset.ofHoursMinutes(sign * h, sign * min)
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun parseCore(s: String): ParsedDateTime? {
        EXIF.matchEntire(s)?.let { m ->
            val g = m.groupValues
            val date = date(g[1], g[2], g[3]) ?: return null
            if (g[4].isEmpty()) return ParsedDateTime(date.atStartOfDay(), null, TimestampPrecision.DAY)
            val hour = g[4].toInt()
            val minute = g[5].toInt()
            val second = g[6].ifEmpty { "0" }.toInt()
            if (hour > 23 && !(hour == 24 && minute == 0 && second == 0) || minute > 59 || second > 60) return null
            val frac = g[7]
            val nanos = if (frac.isEmpty()) 0 else frac.padEnd(9, '0').take(9).toInt()
            val precision =
                when {
                    g[6].isEmpty() -> TimestampPrecision.MINUTE
                    frac.isEmpty() -> TimestampPrecision.SECOND
                    frac.length <= 3 -> TimestampPrecision.MILLISECOND
                    frac.length <= 6 -> TimestampPrecision.MICROSECOND
                    else -> TimestampPrecision.NANOSECOND
                }
            val time = LocalTime.of(if (hour == 24) 0 else hour, minute, second.coerceAtMost(59), nanos)
            val local = LocalDateTime.of(if (hour == 24) date.plusDays(1) else date, time)
            return ParsedDateTime(local, g[8].takeIf { it.isNotEmpty() }?.let { parseOffset(it) }, precision)
        }
        COMPACT.matchEntire(s)?.let { m ->
            val g = m.groupValues
            val date = date(g[1], g[2], g[3]) ?: return null
            if (g[4].isEmpty()) return ParsedDateTime(date.atStartOfDay(), null, TimestampPrecision.DAY)
            val h = g[4].toInt()
            val mi = g[5].toInt()
            val sec = g[6].ifEmpty { "0" }.toInt()
            if (h > 23 || mi > 59 || sec > 59) return null
            return ParsedDateTime(
                LocalDateTime.of(date, LocalTime.of(h, mi, sec)),
                g[7].takeIf {
                    it.isNotEmpty()
                }?.let { parseOffset(it) },
                if (g[6].isEmpty()) TimestampPrecision.MINUTE else TimestampPrecision.SECOND,
            )
        }
        YEAR_MONTH.matchEntire(s)?.let { m ->
            val y = m.groupValues[1].toInt()
            val mo = m.groupValues[2].ifEmpty { null }?.toInt()
            if (mo != null && mo !in 1..12) return null
            return ParsedDateTime(
                LocalDate.of(y, mo ?: 1, 1).atStartOfDay(),
                null,
                if (mo ==
                    null
                ) {
                    TimestampPrecision.YEAR
                } else {
                    TimestampPrecision.MONTH
                },
            )
        }
        CTIME.matchEntire(s)?.let { m ->
            val g = m.groupValues
            val month = MONTHS.indexOf(g[1].lowercase()) + 1
            if (month == 0) return null
            val date = date(g[6], month.toString(), g[2]) ?: return null
            val h = g[3].toInt()
            val mi = g[4].toInt()
            val sec = g[5].toInt()
            if (h > 23 || mi > 59 || sec > 59) return null
            return ParsedDateTime(LocalDateTime.of(date, LocalTime.of(h, mi, sec)), null, TimestampPrecision.SECOND)
        }
        return null
    }

    private fun date(y: String, m: String, d: String): LocalDate? = try {
        LocalDate.of(y.toInt(), m.toInt(), d.toInt())
    } catch (_: DateTimeException) {
        null
    }

    /** Rejects years before 1800, and times more than one day in the future relative to [now]. */
    @JvmStatic
    @JvmOverloads
    public fun plausible(local: LocalDateTime, now: Instant = Instant.now()): Boolean {
        if (local.year < 1800) return false
        return !local.toInstant(ZoneOffset.UTC).isAfter(now.plusSeconds(86400 * 2))
    }

    @JvmStatic
    public fun plausible(instant: Instant): Boolean = plausible(LocalDateTime.ofInstant(instant, ZoneOffset.UTC))
}
