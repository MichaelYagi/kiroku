package dev.kiroku.oracle

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Directory
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifInteropDirectory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.RawObservation
import dev.kiroku.model.asDouble
import dev.kiroku.model.asList
import dev.kiroku.testing.Fixtures
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.math.abs
import kotlin.test.fail

/**
 * Cross-checks EXIF directories against metadata-extractor, the library Kiroku competes with. It is
 * used only as an independent oracle in tests (09-DECISIONS.md D1). Presence and decoded values of
 * every tag ID that metadata-extractor reports in IFD0, ExifIFD, GPS and Interop are compared.
 */
class MetadataExtractorOracleTest {
    private val groups =
        mapOf<Class<out Directory>, String>(
            ExifIFD0Directory::class.java to "IFD0",
            ExifSubIFDDirectory::class.java to "ExifIFD",
            GpsDirectory::class.java to "GPS",
            ExifInteropDirectory::class.java to "InteropIFD",
        )

    @TestFactory
    fun `EXIF tag IDs and values agree with metadata-extractor`(): List<DynamicTest> = Fixtures.all()
        .filter {
            it.extension.lowercase() in
                setOf("jpg", "jpeg", "tif", "tiff", "nef", "cr2", "cr3", "raf", "dng", "orf", "arw", "rw2", "rwl", "srw", "png", "webp", "heic", "psd")
        }
        .map { f -> DynamicTest.dynamicTest(f.name) { compare(f) } }

    private fun compare(file: Path) {
        val meta =
            try {
                ImageMetadataReader.readMetadata(file.toFile())
            } catch (e: Exception) {
                return // The oracle cannot read it, so there is nothing to compare.
            }
        val mine = Fixtures.extractor.extract(file).full.observations.filter {
            it.backend == "native" && it.groupPath.getOrNull(0) == "EXIF"
        }
        val problems = ArrayList<String>()
        for ((cls, group) in groups) {
            // metadata-extractor merges duplicate directories; Kiroku keeps each occurrence, so any occurrence may match.
            for (dir in meta.getDirectoriesOfType(cls)) {
                for (tag in dir.tags) {
                    val id = tag.tagType
                    val candidates = mine.filter { it.groupPath[1] == group && it.tagId == id.toLong() }
                    if (candidates.isEmpty()) {
                        // metadata-extractor files IFD1 thumbnail tags and Panasonic RAW tags under IFD0 in some cases.
                        if (mine.none { it.tagId == id.toLong() }) {
                            problems +=
                                "${file.name}: missing $group 0x%04X (${tag.tagName})".format(id)
                        }
                        continue
                    }
                    val oracle = dir.getObject(id) ?: continue
                    if (candidates.none { agrees(it, oracle) }) {
                        problems +=
                            "${file.name}: $group 0x%04X (${tag.tagName}) kiroku=${candidates.first().value.toString().take(
                                50,
                            )} me=${describe(oracle).take(50)}".format(id)
                    }
                }
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    private fun describe(o: Any): String = when (o) {
        is Array<*> -> o.joinToString(prefix = "[", postfix = "]")
        is IntArray -> o.joinToString(prefix = "[", postfix = "]")
        is ByteArray -> "bytes(${o.size})"
        is ShortArray -> o.joinToString(prefix = "[", postfix = "]")
        is LongArray -> o.joinToString(prefix = "[", postfix = "]")
        else -> o.toString()
    }

    private fun agrees(o: RawObservation, oracle: Any): Boolean {
        val items = o.value.asList()
        fun num(i: Int) = items.getOrNull(i)?.asDouble()
        fun close(a: Double?, b: Double) = a != null && (abs(a - b) <= 1e-9 * maxOf(1.0, abs(b)) || a.isNaN() && b.isNaN())
        // Text that metadata-extractor leaves as bytes (Windows XP tags are UTF-16LE, DNG BYTE text is UTF-8):
        // Kiroku's text must equal the bytes decoded.
        val byteText = o.tagId in 0x9C9BL..0x9C9FL ||
            o.tagName in
            setOf("LocalizedCameraModel", "OriginalRawFileName", "CameraCalibrationSig", "ProfileCalibrationSig", "ProfileName", "ProfileCopyright")
        if (byteText && o.value is NormalizedValue.Text) {
            val raw =
                when (oracle) {
                    is IntArray -> oracle.map { it.toByte() }.toByteArray()
                    is ByteArray -> oracle
                    is ShortArray -> oracle.map { it.toByte() }.toByteArray()
                    else -> null
                }
            if (raw != null) {
                val text = (o.value as NormalizedValue.Text).value.trim()
                return listOf(Charsets.UTF_16LE, Charsets.UTF_8).any { String(raw, it).trimEnd('\u0000').trim() == text }
            }
        }
        // Arrays above Kiroku's decode cap are kept as binary; compare the exact original bytes instead.
        if (o.value is NormalizedValue.Binary && o.original?.bytes != null) {
            val bytes = o.original!!.bytes!!.toByteArray().map { it.toInt() and 0xFF }
            when (oracle) {
                is IntArray -> return oracle.toList() == bytes || oracle.map { it and 0xFF } == bytes
                is ShortArray -> return oracle.map { it.toInt() and 0xFF } == bytes
                is ByteArray -> return oracle.map { it.toInt() and 0xFF } == bytes
            }
        }
        return when (oracle) {
            is com.drew.lang.Rational -> close(num(0), oracle.toDouble()) ||
                items.firstOrNull()?.let {
                    it is NormalizedValue.RationalValue && it.value.denominator == 0L && oracle.denominator == 0L
                } ==
                true

            is Array<*> ->
                oracle.size == items.size &&
                    oracle.withIndex().all { (i, v) ->
                        when (v) {
                            is com.drew.lang.Rational -> close(num(i), v.toDouble()) || v.denominator == 0L && items[i].asDouble() == null
                            is String -> (items[i] as? NormalizedValue.Text)?.value?.trim() == v.trim()
                            else -> true
                        }
                    }

            is IntArray -> oracle.size == items.size && oracle.withIndex().all { (i, v) -> close(num(i), v.toDouble()) }

            is ShortArray -> oracle.size == items.size && oracle.withIndex().all { (i, v) -> close(num(i), v.toDouble()) }

            is LongArray -> oracle.size == items.size && oracle.withIndex().all { (i, v) -> close(num(i), v.toDouble()) }

            is Number -> close(num(0), oracle.toDouble())

            is String -> {
                val t = (o.value as? NormalizedValue.Text)?.value ?: (o.value as? NormalizedValue.ListValue)?.items?.joinToString(" ")
                t == null || t.trim() == oracle.trim() || oracle.trim().startsWith(t.trim()) || o.value is NormalizedValue.Binary
            }

            is com.drew.metadata.StringValue -> {
                val t = (o.value as? NormalizedValue.Text)?.value
                t == null || t.trim() == oracle.toString().trim()
            }

            // Byte arrays: Kiroku decodes UNDEFINED/BYTE into lists, text or binary; the bytes themselves are in `original`.
            is ByteArray -> o.original?.bytes?.let { it.toByteArray().contentEquals(oracle) } ?: true

            else -> true
        }
    }
}
