package dev.kiroku.oracle

import com.fasterxml.jackson.databind.JsonNode
import dev.kiroku.MetadataResult
import dev.kiroku.model.MediaKind
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.RawObservation
import dev.kiroku.model.asDouble
import dev.kiroku.model.asList
import dev.kiroku.model.asText
import dev.kiroku.testing.Fixtures
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.math.abs
import kotlin.test.fail

/**
 * Cross-checks Kiroku's native parsers against ExifTool (an independent implementation) on every
 * fixture. The test is skipped when ExifTool is not installed.
 *
 * Known, intentional divergences are listed in [KNOWN_DIVERGENCES] with the reason.
 */
class ExifToolOracleTest {
    @TestFactory
    fun `native output agrees with ExifTool`(): List<DynamicTest> {
        assumeTrue(Fixtures.exiftool != null, "ExifTool not available")
        return Fixtures.all().map { f -> DynamicTest.dynamicTest(f.name) { compare(f) } }
    }

    private fun compare(file: Path) {
        val et = Fixtures.exiftoolJson(file)
        val r = Fixtures.extractor.extract(file)
        // A format Kiroku declines (for example a PostScript font) has nothing to compare; it must not claim data.
        if (r.status == dev.kiroku.ExtractionStatus.UNSUPPORTED) {
            if (r.full.observations.any { it.backend == "native" }) fail("${file.name}: UNSUPPORTED but has native observations")
            return
        }
        val problems = ArrayList<String>()
        compareExifTags(file, et, r, problems)
        compareGroups(file, et, r, problems)
        compareGallery(file, et, r, problems)
        val unexpected = problems.filterNot { p -> KNOWN_DIVERGENCES.any { (k, _) -> p.startsWith("${file.name}: $k") } }
        if (unexpected.isNotEmpty()) fail(unexpected.joinToString("\n"))
    }

    /** Every EXIF tag ExifTool reports in IFD0/ExifIFD/GPS/InteropIFD must exist natively, with an equal value. */
    private fun compareExifTags(file: Path, et: JsonNode, r: MetadataResult, problems: MutableList<String>) {
        val native = r.full.observations.filter { it.backend == "native" && it.groupPath.getOrNull(0) == "EXIF" }
        val byKey = native.groupBy { "${it.groupPath[1]}:${it.tagName}" }
        for ((key, value) in et.properties()) {
            val group = key.substringBefore(':')
            val tag = key.substringAfter(':')
            if (group !in setOf("IFD0", "ExifIFD", "GPS", "InteropIFD")) continue
            if (tag.contains("_0x") || tag in EXIFTOOL_DERIVED) continue
            val obs = byKey["$group:$tag"] ?: NAME_ALIASES[tag]?.firstNotNullOfOrNull { byKey["$group:$it"] }
            if (obs.isNullOrEmpty()) {
                problems += "${file.name}: missing $group:$tag (ExifTool=${value.toString().take(60)})"
                continue
            }
            if (obs.none { valuesAgree(it, value) || exifToolConverted(tag, it, value) }) {
                problems +=
                    "${file.name}: value $group:$tag kiroku=${obs.first().value.toString().take(60)} exiftool=${value.toString().take(60)}"
            }
        }
    }

    /**
     * Non-EXIF groups, keyed by ExifTool's family-1 name. In [COMPLETE_GROUPS] every ExifTool tag must exist natively
     * with an equal value; in [AGREE_GROUPS] (where Kiroku decodes a documented subset) tags present in both must agree.
     */
    private fun compareGroups(file: Path, et: JsonNode, r: MetadataResult, problems: MutableList<String>) {
        val native = r.full.observations.filter { it.backend == "native" && it.groupPath.size >= 2 }
        val byKey = native.groupBy { "${it.groupPath[1]}:${it.tagName}" }
        for ((key, value) in et.properties()) {
            val group = key.substringBefore(':')
            val tag = key.substringAfter(':')
            val complete = group in COMPLETE_GROUPS || COMPLETE_GROUP_PREFIXES.any { group.startsWith(it) }
            if (!complete && group !in AGREE_GROUPS) continue
            val obs = byKey["$group:$tag"]
            if (obs.isNullOrEmpty()) {
                if (complete) problems += "${file.name}: missing $group:$tag (ExifTool=${value.toString().take(60)})"
                continue
            }
            if (obs.none { valuesAgree(it, value) }) {
                problems += "${file.name}: value $group:$tag kiroku=${obs.first().value.toString().take(60)} exiftool=${value.toString().take(60)}"
            }
        }
    }

    private fun valuesAgree(o: RawObservation, et: JsonNode): Boolean {
        if (et.isNumber) {
            val mine = o.value.asList().mapNotNull { it.asDouble() }
            return mine.size == 1 && close(mine[0], et.asDouble()) ||
                mine.isEmpty() && o.value.asText()?.trim()?.toDoubleOrNull()?.let { close(it, et.asDouble()) } == true
        }
        if (et.isTextual) {
            val t = et.asText().trim()
            val mineItems = o.value.asList()
            val nums = t.split(' ').mapNotNull { it.toDoubleOrNull() }
            if (nums.size > 1 && nums.size == mineItems.size) {
                return mineItems.zip(nums).all { (m, n) -> m.asDouble()?.let { close(it, n) } == true }
            }
            val mineText =
                (o.value as? NormalizedValue.Text)?.value?.trim()
                    ?: (o.value as? NormalizedValue.ListValue)?.items?.joinToString(" ") { it.asText().orEmpty() }?.trim()
            if (mineText != null) return mineText == t || mineText.replace(Regex("\\s+"), " ") == t.replace(Regex("\\s+"), " ")
            // ExifTool prints binary/undefined data as text or "(Binary data ...)"; such values are compared by presence only.
            return o.value is NormalizedValue.Binary || t.startsWith("(Binary data") || o.value is NormalizedValue.ListValue ||
                o.value is NormalizedValue.Integer
        }
        return true
    }

    /**
     * ExifTool applies its ValueConv even with -n (APEX to f-number/seconds, DMS to decimal degrees,
     * GPS time to text, byte arrays to hex, 0/0 to "undef"). Kiroku keeps the stored values, so the
     * comparison converts Kiroku's value the same way.
     */
    private fun exifToolConverted(tag: String, o: RawObservation, et: JsonNode): Boolean {
        val etNum = if (et.isNumber) et.asDouble() else et.asText().trim().toDoubleOrNull()
        val items = o.value.asList()
        val nums = items.map { it.asDouble() }
        // ExifTool reports rationals with zero denominators (unset GPS values) as "" or "00:00:00".
        val unset = items.isNotEmpty() && items.all { (it as? NormalizedValue.RationalValue)?.value?.denominator == 0L }
        if (unset && et.asText().trim().let { it.isEmpty() || it == "00:00:00" }) return true
        return when (tag) {
            "ApertureValue", "MaxApertureValue" -> nums.singleOrNull()?.let {
                etNum != null &&
                    abs(Math.pow(2.0, it / 2) - etNum) <= 1e-4 * maxOf(1.0, etNum)
            } ==
                true

            // A garbage APEX value such as -2^31 overflows, and ExifTool then prints 0.
            "ShutterSpeedValue" -> nums.singleOrNull()?.let {
                etNum != null &&
                    (abs(Math.pow(2.0, -it) - etNum) <= 1e-4 * maxOf(1.0, etNum) || etNum == 0.0 && abs(it) > 1000)
            } ==
                true

            "TimeCodes" -> {
                val t = et.asText().trim().replace(" ", ".")
                items.joinToString(".") { "%02X".format(it.asDouble()?.toInt() ?: -1) }.let { mine ->
                    t.split('.').size == items.size &&
                        t.equals(mine, ignoreCase = true) ||
                        t.startsWith(mine.take(11), ignoreCase = true)
                }
            }

            "GPSLatitude", "GPSLongitude", "GPSDestLatitude", "GPSDestLongitude" ->
                etNum != null && nums.size in 1..3 && nums.all { it != null } &&
                    abs(nums[0]!! + nums.getOrElse(1) { 0.0 }!! / 60 + nums.getOrElse(2) { 0.0 }!! / 3600 - abs(etNum)) < 1e-6

            "GPSTimeStamp" -> {
                val p = et.asText().split(':').mapNotNull { it.toDoubleOrNull() }
                p.size == 3 && nums.size == 3 && nums.zip(p).all { (a, b) -> a != null && abs(a - b) < 0.01 }
            }

            else -> {
                val t = et.asText().trim()
                when {
                    t.startsWith("(Binary data") -> true

                    t.contains("undef") -> t.split(' ').zip(items).all { (s, v) ->
                        s == "undef" && v.asDouble() == null ||
                            s.toDoubleOrNull()?.let { d -> v.asDouble()?.let { close(it, d) } } == true
                    }

                    t.matches(Regex("[0-9A-Fa-f]+")) && items.size * 2 == t.length && items.all { it is NormalizedValue.Integer } ->
                        items.joinToString("") { "%02X".format((it as NormalizedValue.Integer).value) }.equals(t, ignoreCase = true)

                    else -> false
                }
            }
        }
    }

    private fun close(a: Double, b: Double): Boolean = abs(a - b) <= 1e-6 * maxOf(1.0, abs(a), abs(b)) || abs(a - b) < 1e-9

    private fun compareGallery(file: Path, et: JsonNode, r: MetadataResult, problems: MutableList<String>) {
        val g = r.gallery
        fun etText(vararg keys: String): String? = keys.firstNotNullOfOrNull { k -> et[k]?.takeIf { !it.isNull }?.asText()?.trim()?.takeIf { it.isNotEmpty() } }
        fun check(field: String, mine: String?, theirs: String?) {
            if (theirs == null) return
            if (mine == null) {
                problems += "${file.name}: gallery.$field is null but ExifTool has '${theirs.take(60)}'"
            } else if (!mine.trim().equals(theirs.trim(), ignoreCase = false)) {
                problems += "${file.name}: gallery.$field kiroku='${mine.take(60)}' exiftool='${theirs.take(60)}'"
            }
        }
        check("camera.make", g.camera?.make, etText("IFD0:Make", "Keys:Make", "UserData:Make"))
        check("camera.model", g.camera?.model, etText("IFD0:Model", "Keys:Model", "UserData:Model"))
        etText("ExifIFD:ISO")?.split(' ')?.first()?.toIntOrNull()?.takeIf {
            it in 1..65534
        }?.let { check("exposure.iso", g.exposure?.iso?.toString(), it.toString()) }
        et["ExifIFD:FNumber"]?.takeIf {
            it.isNumber && it.asDouble() > 0
        }?.let {
            check("exposure.apertureFNumber", g.exposure?.apertureFNumber?.let { v -> "%.3f".format(v) }, "%.3f".format(it.asDouble()))
        }
        et["ExifIFD:ExposureTime"]?.takeIf {
            it.isNumber && it.asDouble() > 0
        }?.let {
            check(
                "exposure.exposureTimeSeconds",
                g.exposure?.exposureTimeSeconds?.let { v ->
                    "%.6f".format(v)
                },
                "%.6f".format(it.asDouble()),
            )
        }
        et["ExifIFD:FocalLength"]?.takeIf {
            it.isNumber && it.asDouble() > 0
        }?.let { check("exposure.focalLengthMm", g.exposure?.focalLengthMm?.let { v -> "%.3f".format(v) }, "%.3f".format(it.asDouble())) }
        etText("ExifIFD:DateTimeOriginal")?.takeIf { !it.startsWith("0000") }?.let { dto ->
            if (g.capturedAt?.source == "EXIF:ExifIFD:DateTimeOriginal") {
                val mine = g.capturedAt?.localDateTime?.let {
                    "%04d:%02d:%02d %02d:%02d:%02d".format(it.year, it.monthValue, it.dayOfMonth, it.hour, it.minute, it.second)
                }
                check("capturedAt.local", mine, dto.take(19))
            }
        }
        val lat = et["Composite:GPSLatitude"]
        val lon = et["Composite:GPSLongitude"]
        if (lat != null && lon != null && lat.isNumber && lon.isNumber) {
            val loc = g.location
            if (loc?.latitude == null || abs(loc.latitude!! - lat.asDouble()) > 1e-5 || abs(loc.longitude!! - lon.asDouble()) > 1e-5) {
                problems +=
                    "${file.name}: gallery.location kiroku=${loc?.latitude},${loc?.longitude} exiftool=${lat.asDouble()},${lon.asDouble()}"
            }
        }
        if (g.mediaKind != MediaKind.VIDEO) {
            et["IFD0:Orientation"]?.takeIf {
                it.isNumber && it.asInt() in 1..8 && g.format !in setOf("HEIC", "HEIF", "AVIF")
            }?.let { check("orientation.exifCode", g.orientation?.exifCode?.toString(), it.asInt().toString()) }
        } else {
            et["Composite:Rotation"]?.takeIf {
                it.isNumber
            }?.let { check("orientation.rotationDegrees", g.orientation?.rotationDegrees?.toString(), it.asInt().toString()) }
            et["QuickTime:Duration"]?.takeIf { it.isNumber && it.asDouble() > 0 }?.let { d ->
                val mine = g.video?.duration?.toNanos()?.div(1e9)
                if (mine == null ||
                    abs(mine - d.asDouble()) > 0.05
                ) {
                    problems +=
                        "${file.name}: gallery.video.duration kiroku=$mine exiftool=${d.asDouble()}"
                }
            }
        }
        etText("Composite:ImageSize")?.let { size ->
            val parts = size.split('x', ' ').mapNotNull { it.toIntOrNull() }
            if (parts.size ==
                2
            ) {
                check("dimensions.stored", g.dimensions?.let { "${it.storedWidth}x${it.storedHeight}" }, "${parts[0]}x${parts[1]}")
            }
        }
    }

    companion object {
        val COMPLETE_GROUPS = setOf("GeoTiff", "PrintIM", "Ducky", "PostScript", "MPEG")
        val COMPLETE_GROUP_PREFIXES = listOf("ID3v")
        val AGREE_GROUPS = setOf("Photoshop")

        /** ExifTool tags that are computed rather than stored, or named differently on purpose. */
        val EXIFTOOL_DERIVED = setOf("ThumbnailImage", "PreviewImage", "JpgFromRaw", "OtherImage", "ThumbnailTIFF")

        /** ExifTool renames 0x0201/0x0202 by context (thumbnail vs preview vs JpgFromRaw). Kiroku uses the EXIF names. */
        val NAME_ALIASES =
            mapOf(
                "JpgFromRawStart" to listOf("ThumbnailOffset"),
                "JpgFromRawLength" to listOf("ThumbnailLength"),
                "OtherImageStart" to listOf("ThumbnailOffset"),
                "OtherImageLength" to listOf("ThumbnailLength"),
                // CR2 IFD0 stores its preview through the strip tags.
                "PreviewImageStart" to listOf("ThumbnailOffset", "StripOffsets"),
                "PreviewImageLength" to listOf("ThumbnailLength", "StripByteCounts"),
            )

        /**
         * Intentional differences, each with a reason. A divergence is matched by file name and prefix.
         */
        val KNOWN_DIVERGENCES: Map<String, String> =
            mapOf(
                "value ExifIFD:UserComment" to
                    "When a camera stores several NUL-separated strings (Ricoh), ExifTool keeps the first; Kiroku joins them",
                "value MPEG:SampleRate" to
                    "With -n ExifTool reports the frame header's sample-rate index; Kiroku reports the rate in Hz",
                "gallery.dimensions.stored" to
                    "ExifTool's Composite:ImageSize mixes sources per vendor; Kiroku documents its own RAW policy (DNG DefaultCropSize, then the largest full-resolution raw IFD, then EXIF size) — see docs/NORMALIZATION.md",
            )
    }
}
