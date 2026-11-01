package dev.kiroku.normalize

import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.Orientation
import dev.kiroku.model.asDouble
import dev.kiroku.model.asList
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/** A coordinate fix parsed from one source. */
public data class GeoPoint(val latitude: Double, val longitude: Double, val altitude: Double? = null)

/** GPS and orientation maths, shared by every source format. */
public object Geo {
    /**
     * Converts EXIF degrees/minutes/seconds (1–3 rationals) plus a hemisphere reference to signed
     * decimal degrees. A value already negative is not negated twice.
     */
    @JvmStatic
    public fun dmsToDecimal(value: NormalizedValue, ref: String?): Double? {
        val parts = value.asList().map { it.asDouble() ?: return null }
        if (parts.isEmpty() || parts.size > 3) return null
        val deg = parts[0]
        val min = parts.getOrElse(1) { 0.0 }
        val sec = parts.getOrElse(2) { 0.0 }
        if (min < 0 || sec < 0 || min >= 60.0001 || sec >= 60.0001) return null
        var v = abs(deg) + min / 60.0 + sec / 3600.0
        val negative = deg < 0 || ref?.trim()?.uppercase()?.firstOrNull() in setOf('S', 'W')
        if (negative) v = -v
        return v
    }

    /** Parses an XMP GPS coordinate: `DDD,MM,SSk`, `DDD,MM.mmk`, or a signed decimal. */
    @JvmStatic
    public fun parseXmpCoordinate(text: String?): Double? {
        val s = text?.trim()?.uppercase() ?: return null
        if (s.isEmpty()) return null
        s.toDoubleOrNull()?.let { return it }
        val dir = s.last()
        if (dir !in "NSEW") return null
        val parts = s.dropLast(1).split(',').map { it.trim().toDoubleOrNull() ?: return null }
        if (parts.isEmpty() || parts.size > 3) return null
        var v = parts[0] + parts.getOrElse(1) { 0.0 } / 60.0 + parts.getOrElse(2) { 0.0 } / 3600.0
        if (dir == 'S' || dir == 'W') v = -v
        return v
    }

    private val ISO6709 = Regex("""^\s*([+\-]\d+(?:\.\d+)?)([+\-]\d+(?:\.\d+)?)([+\-]\d+(?:\.\d+)?)?(?:CRS[^/]*)?/?\s*$""")

    /**
     * Parses ISO 6709 strings as used by QuickTime (`+37.3318-122.0312+010.000/`), including the
     * degree-minute(-second) forms `±DDMM.mm` / `±DDDMMSS.ss`.
     */
    @JvmStatic
    public fun parseIso6709(text: String?): GeoPoint? {
        val m = ISO6709.matchEntire(text ?: return null) ?: return null
        val lat = iso6709Component(m.groupValues[1], 2) ?: return null
        val lon = iso6709Component(m.groupValues[2], 3) ?: return null
        val alt = m.groupValues[3].takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        return GeoPoint(lat, lon, alt).takeIf { valid(it.latitude, it.longitude) }
    }

    private fun iso6709Component(s: String, degDigits: Int): Double? {
        val sign = if (s[0] == '-') -1.0 else 1.0
        val body = s.substring(1)
        val intPart = body.substringBefore('.')
        val frac = body.substringAfter('.', "")
        val v =
            when (intPart.length) {
                degDigits -> body.toDoubleOrNull()

                degDigits + 2 -> {
                    val d = intPart.substring(0, degDigits).toDouble()
                    val mins = (intPart.substring(degDigits) + if (frac.isNotEmpty()) ".$frac" else "").toDouble()
                    d + mins / 60.0
                }

                degDigits + 4 -> {
                    val d = intPart.substring(0, degDigits).toDouble()
                    val mins = intPart.substring(degDigits, degDigits + 2).toDouble()
                    val secs = (intPart.substring(degDigits + 2) + if (frac.isNotEmpty()) ".$frac" else "").toDouble()
                    d + mins / 60.0 + secs / 3600.0
                }

                else -> null
            } ?: return null
        return sign * v
    }

    @JvmStatic
    public fun valid(lat: Double?, lon: Double?): Boolean =
        lat != null && lon != null && lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

    /** Converts EXIF GPSSpeed to metres per second. Refs: `K` km/h, `M` mph, `N` knots. The EXIF default is `K`. */
    @JvmStatic
    public fun speedToMetersPerSecond(value: Double, ref: String?): Double? = when (ref?.trim()?.uppercase()?.firstOrNull() ?: 'K') {
        'K' -> value / 3.6
        'M' -> value * 0.44704
        'N' -> value * 0.514444
        else -> null
    }

    /** EXIF orientation 1–8 to (clockwise rotation, mirrored). Mirroring is a horizontal flip applied before the rotation. */
    @JvmStatic
    public fun exifOrientation(code: Int): Orientation? = when (code) {
        1 -> Orientation(1, 0, false)
        2 -> Orientation(2, 0, true)
        3 -> Orientation(3, 180, false)
        4 -> Orientation(4, 180, true)
        5 -> Orientation(5, 270, true)
        6 -> Orientation(6, 90, false)
        7 -> Orientation(7, 90, true)
        8 -> Orientation(8, 270, false)
        else -> null
    }

    /**
     * Decodes a QuickTime/ISO display matrix `(a, b, u, c, d, v, x, y, w)` (row-vector convention)
     * to a clockwise rotation (0/90/180/270) and a mirror flag. Returns `null` for non-right-angle transforms.
     */
    @JvmStatic
    public fun matrixOrientation(m: List<Double>): Orientation? {
        if (m.size < 5) return null
        var a = m[0]
        var b = m[1]
        val c = m[3]
        val d = m[4]
        val det = a * d - b * c
        if (abs(det) < 1e-6) return null
        val mirrored = det < 0
        if (mirrored) {
            // Undo a horizontal flip applied first: R = F * M, F = diag(-1, 1).
            a = -a
            b = -b
        }
        val angle = Math.toDegrees(atan2(b, a))
        val rounded = ((angle / 90.0).roundToInt() * 90 + 360) % 360
        if (abs(((angle + 360) % 360) - rounded).let { minOf(it, 360 - it) } > 1.0) return null
        return Orientation(null, rounded, mirrored, m.take(9))
    }

    /** HEIF `irot` (counter-clockwise quarter turns) plus `imir` (axis), applied in [order], as (clockwise rotation, mirrored). */
    @JvmStatic
    public fun heifOrientation(irotCcwDegrees: Int?, mirrorAxis: Int?, mirrorFirst: Boolean): Orientation {
        var rot = ((360 - (irotCcwDegrees ?: 0)) % 360 + 360) % 360
        var mirrored = false
        if (mirrorAxis != null) {
            mirrored = true
            // Axis 1 (horizontal axis) is a vertical flip = horizontal flip + 180°.
            val extra = if (mirrorAxis == 1) 180 else 0
            rot =
                if (mirrorFirst) {
                    (rot + extra) % 360
                } else {
                    // Rotation then flip == flip then rotation by the negative angle.
                    ((360 - rot) % 360 + extra) % 360
                }
        }
        return Orientation(null, rot, mirrored)
    }
}
