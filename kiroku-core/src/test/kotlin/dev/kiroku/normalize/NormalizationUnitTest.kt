package dev.kiroku.normalize

import dev.kiroku.model.ByteString
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.Rational
import dev.kiroku.model.TimestampPrecision
import dev.kiroku.model.asDouble
import dev.kiroku.model.asLong
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatesTest {
    @Test
    fun `EXIF date without offset stays local with unknown time zone`() {
        val p = Dates.parse("2021:12:24 18:05:00")!!
        assertEquals(LocalDateTime.of(2021, 12, 24, 18, 5, 0), p.local)
        assertNull(p.offset)
        assertNull(p.instant, "no instant without an offset; never assume UTC")
        assertEquals(TimestampPrecision.SECOND, p.precision)
        assertEquals(false, p.toCapture("x").timezoneKnown)
    }

    @Test
    fun `EXIF date with OffsetTimeOriginal and SubSecTimeOriginal`() {
        val p = Dates.parse("2023:06:17 14:32:10", "123", "+02:00")!!
        assertEquals(Instant.parse("2023-06-17T12:32:10.123Z"), p.instant)
        assertEquals(TimestampPrecision.MILLISECOND, p.precision)
        assertEquals(ZoneOffset.ofHours(2), p.offset)
    }

    @ParameterizedTest
    @CsvSource(
        "2023-06-17T14:32:10+02:00, 2023-06-17T12:32:10Z",
        "2024-05-03T10:15:30+0900, 2024-05-03T01:15:30Z",
        "2023-06-17T14:32:10Z, 2023-06-17T14:32:10Z",
        "2023-06-17T14:32:10.5-05:30, 2023-06-17T20:02:10.500Z",
        "20230617T143210+0200, 2023-06-17T12:32:10Z",
    )
    fun `ISO 8601 and QuickTime variants with offsets`(text: String, expected: String) {
        assertEquals(Instant.parse(expected), Dates.parse(text)!!.instant)
    }

    @Test
    fun `partial precision dates`() {
        assertEquals(TimestampPrecision.YEAR, Dates.parse("2010")!!.precision)
        assertEquals(TimestampPrecision.MONTH, Dates.parse("2010-04")!!.precision)
        assertEquals(TimestampPrecision.DAY, Dates.parse("20040226")!!.precision)
        assertEquals(TimestampPrecision.MINUTE, Dates.parse("2004:02:26 10:11")!!.precision)
    }

    @Test
    fun `ctime style dates from AVI IDIT`() {
        assertEquals(LocalDateTime.of(2012, 3, 12, 10, 0, 1), Dates.parse("Mon Mar 12 10:00:01 2012")!!.local)
    }

    @ParameterizedTest
    @CsvSource(
        "'0000:00:00 00:00:00'",
        "'    :  :     :  :  '",
        "''",
        "garbage",
        "'1799:01:01 00:00:00'",
        "'2023:02:30 10:00:00'",
        "'2023:06:17 25:00:00'",
    )
    fun `implausible or invalid dates are rejected`(text: String) {
        assertNull(Dates.parse(text))
    }

    @Test
    fun `future dates beyond tolerance are rejected`() {
        assertNull(Dates.parse("2999:01:01 00:00:00"))
    }

    @Test
    fun `offset parsing`() {
        assertEquals(ZoneOffset.ofHoursMinutes(-5, -30), Dates.parseOffset("-05:30"))
        assertEquals(ZoneOffset.ofHours(9), Dates.parseOffset("+0900"))
        assertEquals(ZoneOffset.UTC, Dates.parseOffset("Z"))
        assertNull(Dates.parseOffset("   :  "))
        assertNull(Dates.parseOffset("+25:00"))
    }
}

class GeoTest {
    private fun dms(vararg parts: Pair<Long, Long>) = NormalizedValue.ListValue(parts.map { NormalizedValue.RationalValue(Rational(it.first, it.second)) })

    @Test
    fun `DMS rationals with references`() {
        val v = dms(48L to 1L, 51L to 1L, 2959L to 100L)
        assertEquals(48.858219, Geo.dmsToDecimal(v, "N")!!, 1e-6)
        assertEquals(-48.858219, Geo.dmsToDecimal(v, "S")!!, 1e-6)
        assertEquals(-2.0, Geo.dmsToDecimal(dms(2L to 1L), "W")!!, 1e-9)
    }

    @Test
    fun `negative stored degrees are not negated twice`() {
        assertEquals(-10.5, Geo.dmsToDecimal(dms(-10L to 1L, 30L to 1L), "S")!!, 1e-9)
    }

    @Test
    fun `invalid DMS is rejected rather than guessed`() {
        assertNull(Geo.dmsToDecimal(dms(10L to 1L, 75L to 1L), "N"))
        assertNull(Geo.dmsToDecimal(NormalizedValue.ListValue(listOf(NormalizedValue.RationalValue(Rational(1, 0)))), "N"))
    }

    @ParameterizedTest
    @CsvSource(
        "'+35.6895+139.6917+040.000/', 35.6895, 139.6917, 40.0",
        "'+37.4220-122.0841/', 37.4220, -122.0841, NaN",
        "'-33.8688+151.2093/', -33.8688, 151.2093, NaN",
        "'+4851.5-00217.6/', 48.858333, -2.293333, NaN",
        "'+485129.5+0021740.2/', 48.858194, 2.294500, NaN",
        "'+35.6895+139.6917+040.000CRSWGS_84/', 35.6895, 139.6917, 40.0",
    )
    fun `ISO 6709 variants`(text: String, lat: Double, lon: Double, alt: Double) {
        val p = Geo.parseIso6709(text)!!
        assertEquals(lat, p.latitude, 1e-5)
        assertEquals(lon, p.longitude, 1e-5)
        if (alt.isNaN()) assertNull(p.altitude) else assertEquals(alt, p.altitude!!, 1e-9)
    }

    @Test
    fun `invalid ISO 6709 is rejected`() {
        assertNull(Geo.parseIso6709("+95.0+10.0/"))
        assertNull(Geo.parseIso6709("hello"))
    }

    @Test
    fun `XMP coordinates`() {
        assertEquals(48.858333, Geo.parseXmpCoordinate("48,51.5N")!!, 1e-6)
        assertEquals(-2.2945, Geo.parseXmpCoordinate("2,17,40.2W")!!, 1e-6)
        assertEquals(12.5, Geo.parseXmpCoordinate("12.5")!!, 1e-9)
    }

    @Test
    fun `speed units`() {
        assertEquals(10.0, Geo.speedToMetersPerSecond(36.0, "K")!!, 1e-9)
        assertEquals(0.44704, Geo.speedToMetersPerSecond(1.0, "M")!!, 1e-9)
        assertEquals(0.514444, Geo.speedToMetersPerSecond(1.0, "N")!!, 1e-9)
    }

    @ParameterizedTest
    @CsvSource("1,0,false", "2,0,true", "3,180,false", "4,180,true", "5,270,true", "6,90,false", "7,90,true", "8,270,false")
    fun `EXIF orientation 1 to 8`(code: Int, rotation: Int, mirrored: Boolean) {
        val o = Geo.exifOrientation(code)!!
        assertEquals(rotation, o.rotationDegrees)
        assertEquals(mirrored, o.mirrored)
        assertEquals(code, o.exifCode)
    }

    @Test
    fun `invalid EXIF orientation`() {
        assertNull(Geo.exifOrientation(0))
        assertNull(Geo.exifOrientation(9))
    }

    @ParameterizedTest
    @CsvSource(
        "1,0,0,1,0,false",
        "0,1,-1,0,90,false",
        "-1,0,0,-1,180,false",
        "0,-1,1,0,270,false",
        "-1,0,0,1,0,true",
        "0,1,1,0,270,true",
    )
    fun `display matrix decoding`(a: Double, b: Double, c: Double, d: Double, rotation: Int, mirrored: Boolean) {
        val o = Geo.matrixOrientation(listOf(a, b, 0.0, c, d, 0.0, 0.0, 0.0, 1.0))!!
        assertEquals(rotation, o.rotationDegrees)
        assertEquals(mirrored, o.mirrored)
    }

    @Test
    fun `non right-angle matrix is not forced into a rotation`() {
        val r = Math.toRadians(30.0)
        assertNull(Geo.matrixOrientation(listOf(Math.cos(r), Math.sin(r), 0.0, -Math.sin(r), Math.cos(r), 0.0, 0.0, 0.0, 1.0)))
    }

    @Test
    fun `HEIF irot is counter-clockwise`() {
        assertEquals(270, Geo.heifOrientation(90, null, false).rotationDegrees)
        assertEquals(90, Geo.heifOrientation(270, null, false).rotationDegrees)
        assertEquals(0, Geo.heifOrientation(null, null, false).rotationDegrees)
        val vflip = Geo.heifOrientation(null, 1, false)
        assertEquals(180, vflip.rotationDegrees)
        assertEquals(true, vflip.mirrored)
    }
}

class ValuesTest {
    @Test
    fun `rationals keep stored form and handle zero denominators`() {
        val r = Rational(10, 2500)
        assertEquals("10/2500", r.toString())
        assertEquals(Rational(1, 250), r.reduced())
        assertEquals(0.004, r.toDouble()!!, 1e-12)
        assertNull(Rational(0, 0).toDouble())
        assertEquals(false, Rational(5, 0).isDefined)
        assertEquals(Rational(-1, 2), Rational(1, -2).reduced())
    }

    @Test
    fun `null and zero are distinct`() {
        assertEquals(0L, NormalizedValue.Integer(0).asLong())
        assertNull(NormalizedValue.Null.asLong())
        assertNull(NormalizedValue.RationalValue(Rational(0, 0)).asDouble())
        assertEquals(0.0, NormalizedValue.RationalValue(Rational(0, 1)).asDouble())
    }

    @Test
    fun `ByteString is immutable with content equality`() {
        val src = byteArrayOf(1, 2, 3)
        val b = ByteString.copyOf(src)
        src[0] = 9
        assertEquals(1, b[0].toInt())
        val copy = b.toByteArray()
        copy[1] = 9
        assertEquals(2, b[1].toInt())
        assertEquals(ByteString.copyOf(byteArrayOf(1, 2, 3)), b)
        assertNotEquals(ByteString.copyOf(byteArrayOf(1, 2)), b)
        assertEquals("010203", b.hex())
        assertEquals(b, ByteString.fromBase64(b.base64()))
    }

    @Test
    fun `aspect ratio reduction`() {
        assertEquals(Rational(16, 9), Rational(1920, 1080).reduced())
        assertEquals(Rational(3, 4), Rational(480, 640).reduced())
        assertTrue(Rational(1, 3) < Rational(1, 2))
    }
}
