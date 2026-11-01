package dev.kiroku

import dev.kiroku.model.ByteString
import dev.kiroku.model.DirectoryKind
import dev.kiroku.model.MediaFormat
import dev.kiroku.model.NativeType
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.OmissionReason
import dev.kiroku.model.OriginalValue
import dev.kiroku.model.TypeSystem
import dev.kiroku.model.ValueFidelity
import dev.kiroku.spi.BackendAvailability
import dev.kiroku.spi.BackendContext
import dev.kiroku.spi.BackendKind
import dev.kiroku.spi.BackendOutcome
import dev.kiroku.spi.MetadataBackend
import dev.kiroku.testing.Fixtures
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Original-value guarantees from 09-DECISIONS.md D3. */
class FidelityTest {
    @TestFactory
    fun `every BYTE_EXACT observation matches the file bytes at its offset`(): List<DynamicTest> = Fixtures.all().map { f ->
        DynamicTest.dynamicTest(f.name) {
            val r = Fixtures.extractor.extract(f, ExtractionOptions(includeBinaryPayloads = true))
            var checked = 0
            FileChannel.open(f, StandardOpenOption.READ).use { ch ->
                for (o in r.full.observations) {
                    val orig = o.original ?: continue
                    if (orig.fidelity != ValueFidelity.BYTE_EXACT) continue
                    assertEquals(
                        ValueFidelity.BYTE_EXACT,
                        o.fidelity,
                        "observation fidelity must match original fidelity: ${o.qualifiedName}",
                    )
                    val off = assertNotNull(orig.fileOffset, "BYTE_EXACT needs a file offset: ${o.qualifiedName}")
                    val len = assertNotNull(orig.byteLength, "BYTE_EXACT needs a length: ${o.qualifiedName}")
                    if (orig.omittedReason == OmissionReason.OUT_OF_BOUNDS) continue
                    assertTrue(off >= 0 && off + len <= ch.size(), "offset/length out of file: ${o.qualifiedName} $off+$len")
                    val bytes = orig.bytes ?: continue
                    assertEquals(len, bytes.size.toLong(), "captured length mismatch for ${o.qualifiedName}")
                    val buf = ByteBuffer.allocate(bytes.size)
                    ch.read(buf, off)
                    if (!buf.array().contentEquals(bytes.toByteArray())) fail("bytes differ for ${o.qualifiedName} at $off (${f.name})")
                    checked++
                }
            }
            if (r.full.observations.isNotEmpty() &&
                r.full.observations.any { it.fidelity == ValueFidelity.BYTE_EXACT }
            ) {
                assertTrue(checked > 0)
            }
        }
    }

    @Test
    fun `numeric tag IDs, native types and counts are exposed for EXIF`() {
        val r = Fixtures.extractor.extract(Fixtures.gen("jpeg_full_exif.jpg"))
        val iso = r.full.find(listOf("EXIF", "ExifIFD"), 0x8827).single()
        assertEquals("ISO", iso.tagName)
        assertEquals("0x8827", iso.tagIdHex)
        assertEquals(NativeType(TypeSystem.TIFF, "SHORT", 3, 1), iso.nativeType)
        assertEquals(NormalizedValue.Integer(400), iso.value)
        assertEquals(ValueFidelity.BYTE_EXACT, iso.fidelity)
        assertEquals(2, iso.original!!.byteLength)
        val fnum = r.full.find("EXIF:ExifIFD:FNumber").single()
        assertEquals(5, fnum.nativeType!!.code)
        assertEquals(8, fnum.original!!.bytes!!.size)
    }

    @Test
    fun `MakerNotes are kept as a byte-exact blob and parsed into vendor IFD entries`() {
        val r = Fixtures.extractor.extract(Fixtures.et("Canon.jpg"))
        val blob = r.full.find(listOf("EXIF", "ExifIFD"), 0x927C).single()
        assertEquals(ValueFidelity.BYTE_EXACT, blob.fidelity)
        assertTrue(blob.original!!.bytes!!.size > 100)
        val canon = r.full.observations.filter { it.groupPath == listOf("MakerNotes", "Canon") }
        assertTrue(canon.size > 10, "expected Canon MakerNote entries, got ${canon.size}")
        assertTrue(canon.all { it.fidelity == ValueFidelity.BYTE_EXACT && it.tagId != null })
        // Canon 0x0010 CanonModelID is a LONG; 0x0006 CanonImageType is ASCII.
        assertEquals(NativeType(TypeSystem.TIFF, "LONG", 4, 1), canon.first { it.tagId == 0x0010L }.nativeType)
        assertTrue(canon.first { it.tagId == 0x0006L }.value is NormalizedValue.Text)
        assertTrue(r.full.directories.any { it.kind == DirectoryKind.MAKERNOTE && it.note?.contains("Canon") == true })
    }

    @Test
    fun `MakerNote vendors are recognised across manufacturers`() {
        val expect =
            mapOf(
                "Nikon.jpg" to "Nikon", "Olympus.jpg" to "Olympus", "Panasonic.jpg" to "Panasonic", "Pentax.jpg" to "Pentax",
                "FujiFilm.jpg" to "FujiFilm", "Sony.jpg" to "Sony", "Apple.jpg" to "Apple", "Nikon.nef" to "Nikon",
                "CanonRaw.cr2" to "Canon",
            )
        for ((file, vendor) in expect) {
            val r = Fixtures.extractor.extract(Fixtures.et(file))
            val entries = r.full.observations.count { it.groupPath == listOf("MakerNotes", vendor) }
            assertTrue(entries > 0, "$file: no MakerNotes:$vendor entries")
        }
    }

    @Test
    fun `unknown tags are preserved with numeric IDs and known=false`() {
        val all = Fixtures.committed().flatMap { Fixtures.extractor.extract(it).full.observations }
        val unknown = all.filter { !it.known && it.tagId != null }
        assertTrue(unknown.isNotEmpty())
        assertTrue(unknown.all { it.tagName.isNotBlank() })
    }

    @Test
    fun `values above the inline cap keep offset and length but omit bytes`() {
        val f = Fixtures.et("Canon.jpg")
        val small = Fixtures.extractor.extract(f, ExtractionOptions(rawValueByteLimit = 16))
        val mn = small.full.find(listOf("EXIF", "ExifIFD"), 0x927C).single()
        assertEquals(OmissionReason.SIZE_LIMIT, mn.original!!.omittedReason)
        assertEquals(null, mn.original!!.bytes)
        assertNotNull(mn.original!!.fileOffset)
        assertEquals(ValueFidelity.BYTE_EXACT, mn.fidelity, "location is still byte-exact even when bytes are omitted")
    }

    @Test
    fun `reassembled or inflated values are never claimed byte-exact`() {
        // Extended XMP is reassembled from several APP1 segments.
        val r = Fixtures.extractor.extract(Fixtures.et("ExtendedXMP.jpg"))
        val ext = r.full.observations.filter { it.sourcePath?.contains("ExtendedXMP") == true && it.tagName == "XMPPacket" }
        assertTrue(ext.isNotEmpty())
        assertTrue(ext.all { it.fidelity == ValueFidelity.BACKEND_RECONSTRUCTED })
        assertTrue(ext.all { it.original?.fileOffset == null })
    }

    @Test
    fun `non-native backends cannot claim BYTE_EXACT`() {
        val liar =
            object : MetadataBackend {
                override val id = "liar"
                override val kind = BackendKind.LIBRARY
                override val metadataFamilies = setOf("Test")

                override fun mode(config: MetadataConfig) = BackendMode.ALWAYS

                override fun probeAvailability() = BackendAvailability(true, "1")

                override fun supports(format: MediaFormat) = true

                override fun extract(context: BackendContext): BackendOutcome {
                    context.collector.observe(
                        listOf("Test", "Test"),
                        "Claim",
                        NormalizedValue.Text("x"),
                        fidelity = ValueFidelity.BYTE_EXACT,
                        original = OriginalValue(ValueFidelity.BYTE_EXACT, ByteString.copyOf(byteArrayOf(1)), 0, 1),
                    )
                    return BackendOutcome.used("1")
                }
            }
        MediaMetadata.create(MetadataConfig(), listOf(liar)).use { ex ->
            val r = ex.extract(Fixtures.gen("bmp_plain.bmp"))
            val o = r.full.byBackend("liar").single()
            assertEquals(ValueFidelity.BACKEND_RECONSTRUCTED, o.fidelity)
            assertEquals(ValueFidelity.BACKEND_RECONSTRUCTED, o.original!!.fidelity)
        }
    }

    @Test
    fun `display values never replace typed raw values`() {
        val r = Fixtures.extractor.extract(Fixtures.gen("jpeg_full_exif.jpg"))
        val orient = r.full.find("EXIF:IFD0:Orientation").single()
        assertEquals(NormalizedValue.Integer(6), orient.value)
        assertEquals("Rotate 90 CW", orient.displayValue)
    }
}
