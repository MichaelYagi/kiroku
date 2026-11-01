package dev.kiroku.exiftool

import dev.kiroku.BackendMode
import dev.kiroku.ExternalToolConfig
import dev.kiroku.ExtractionDepth
import dev.kiroku.ExtractionOptions
import dev.kiroku.ExtractionStatus
import dev.kiroku.MediaMetadata
import dev.kiroku.MetadataConfig
import dev.kiroku.model.BackendStatus
import dev.kiroku.model.DiagnosticCode
import dev.kiroku.model.ValueFidelity
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExifToolBackendTest {
    private val fixtures = Path.of(System.getProperty("kiroku.fixtures"))
    private val exe: Path? =
        if (System.getenv("KIROKU_NO_EXTERNAL") == "1") {
            null
        } else {
            listOfNotNull(
                System.getProperty("kiroku.exiftool"),
                System.getenv("KIROKU_EXIFTOOL"),
                fixtures.parent.resolve(".tools/exiftool-dist/exiftool").toString(),
            )
                .map { Path.of(it) }.firstOrNull { Files.isExecutable(it) } ?: dev.kiroku.spi.ProcessRunner.findOnPath("exiftool")
        }

    private val extractor by lazy {
        MediaMetadata.create(MetadataConfig(exiftool = ExternalToolConfig(BackendMode.DEEP_ONLY, exe), discoverBackends = true))
    }

    private val deep = ExtractionOptions(depth = ExtractionDepth.DEEP)

    @AfterAll
    fun close() = extractor.close()

    private fun requireTool() = assumeTrue(exe != null, "ExifTool not installed: integration test skipped")

    @Test
    fun `the backend is discovered with ServiceLoader and reports its version`() {
        requireTool()
        val cap = extractor.capabilities().backends.single { it.id == "exiftool" }
        assertTrue(cap.available)
        assertNotNull(cap.version)
    }

    @Test
    fun `deep mode adds decoded ExifTool observations next to native ones`() {
        requireTool()
        val r = extractor.extract(fixtures.resolve("media/exiftool/Canon.jpg"), deep)
        val run = r.backendRuns.single { it.backend == "exiftool" }
        assertEquals(BackendStatus.USED, run.status, r.diagnostics.toString())
        assertEquals(ExtractionStatus.COMPLETE, r.status, r.diagnostics.toString())
        val et = r.full.byBackend("exiftool")
        assertTrue(et.size > 50)
        assertTrue(et.none { it.fidelity == ValueFidelity.BYTE_EXACT }, "ExifTool output is never byte-exact")
        val model = et.first { it.qualifiedName == "EXIF:IFD0:Model" }
        assertEquals(0x0110L, model.tagId)
        assertTrue(et.any { it.groupPath == listOf("MakerNotes", "Canon") }, "decoded MakerNotes")
        // The gallery prefers the native byte-exact candidate when values are equivalent.
        assertEquals("native", r.full.fieldProvenance.getValue("camera.model").chosen!!.backend)
        assertTrue(r.full.fieldProvenance.getValue("camera.model").candidates.any { it.source.backend == "exiftool" })
        assertTrue(et.none { it.tagName == "Directory" }, "paths are not echoed")
    }

    @Test
    fun `display and raw values are kept separately`() {
        requireTool()
        val r = extractor.extract(fixtures.resolve("media/generated/jpeg_full_exif.jpg"), deep)
        val o = r.full.byBackend("exiftool").first { it.qualifiedName == "EXIF:IFD0:Orientation" }
        assertEquals(6L, (o.value as dev.kiroku.model.NormalizedValue.Integer).value)
        assertEquals("Rotate 90 CW", o.displayValue)
    }

    @Test
    fun `duplicate tags are preserved`(@TempDir tmp: Path) {
        requireTool()
        // A JPEG whose EXIF APP1 segment appears twice: both copies must survive in both backends.
        val src = Files.readAllBytes(fixtures.resolve("media/generated/jpeg_full_exif.jpg"))
        var pos = 2
        var app1: ByteArray? = null
        while (pos + 4 < src.size && app1 == null) {
            val marker = src[pos + 1].toInt() and 0xFF
            val len = ((src[pos + 2].toInt() and 0xFF) shl 8) or (src[pos + 3].toInt() and 0xFF)
            if (marker == 0xE1 && String(src, pos + 4, 4, Charsets.ISO_8859_1) == "Exif") app1 = src.copyOfRange(pos, pos + 2 + len)
            pos += 2 + len
        }
        val dup = tmp.resolve("dup.jpg")
        Files.write(dup, src.copyOfRange(0, 2) + app1!! + src.copyOfRange(2, src.size))
        val r = extractor.extract(dup, deep)
        val et = r.full.byBackend("exiftool").filter { it.qualifiedName == "EXIF:IFD0:Model" }
        val native = r.full.byBackend("native").filter { it.qualifiedName == "EXIF:IFD0:Model" }
        assertEquals(2, native.size)
        assertEquals(listOf(0, 1), native.map { it.occurrence })
        assertEquals(2, et.size, "ExifTool duplicates (family-4 CopyN) are kept")
        assertEquals(listOf(0, 1), et.map { it.occurrence })
    }

    @Test
    fun `binary payloads are BACKEND_RECONSTRUCTED`() {
        requireTool()
        val r = extractor.extract(fixtures.resolve("media/exiftool/Canon.jpg"), ExtractionOptions(depth = ExtractionDepth.DEEP, includeBinaryPayloads = true))
        val thumb = r.full.byBackend("exiftool").firstOrNull { it.tagName == "ThumbnailImage" }
        if (thumb != null) {
            assertEquals(ValueFidelity.BACKEND_RECONSTRUCTED, thumb.fidelity)
            assertNull(thumb.original!!.fileOffset)
            assertTrue(thumb.original!!.bytes!!.size > 0)
        }
    }

    @Test
    fun `standard depth skips ExifTool in DEEP_ONLY mode`() {
        requireTool()
        val r = extractor.extract(fixtures.resolve("media/exiftool/Canon.jpg"))
        assertEquals(BackendStatus.SKIPPED, r.backendRuns.single { it.backend == "exiftool" }.status)
        assertTrue(r.full.byBackend("exiftool").isEmpty())
    }

    @Test
    fun `missing executable degrades gracefully, REQUIRED makes it PARTIAL`() {
        val missing = Path.of("/nonexistent/exiftool")
        MediaMetadata.create(MetadataConfig(exiftool = ExternalToolConfig(BackendMode.ALWAYS, missing))).use { ex ->
            val r = ex.extract(fixtures.resolve("media/generated/jpeg_full_exif.jpg"))
            assertEquals(BackendStatus.UNAVAILABLE, r.backendRuns.single { it.backend == "exiftool" }.status)
            assertEquals(ExtractionStatus.COMPLETE, r.status, "an optional backend that is unavailable does not downgrade the result")
            assertTrue(r.diagnostics.any { it.code == DiagnosticCode.BACKEND_UNAVAILABLE })
            assertEquals("KT-1000 Mark II", r.gallery.camera!!.model)
        }
        MediaMetadata.create(MetadataConfig(exiftool = ExternalToolConfig(BackendMode.REQUIRED, missing))).use { ex ->
            assertEquals(ExtractionStatus.PARTIAL, ex.extract(fixtures.resolve("media/generated/jpeg_full_exif.jpg")).status)
        }
    }

    @Test
    fun `timeouts are enforced`() {
        requireTool()
        MediaMetadata.create(MetadataConfig(exiftool = ExternalToolConfig(BackendMode.ALWAYS, exe, timeout = Duration.ofMillis(1)))).use { ex ->
            val r = ex.extract(fixtures.resolve("media/exiftool/Canon.jpg"))
            assertEquals(BackendStatus.TIMED_OUT, r.backendRuns.single { it.backend == "exiftool" }.status)
            assertEquals(ExtractionStatus.PARTIAL, r.status)
            // A later call starts a fresh ExifTool process.
            assertEquals("Canon", r.gallery.camera!!.make)
        }
    }

    @Test
    fun `hostile file names are passed safely`(@TempDir tmp: Path) {
        requireTool()
        val f = tmp.resolve("-x \$(touch pwned); ' \" \\ 写真.jpg")
        Files.copy(fixtures.resolve("media/generated/jpeg_full_exif.jpg"), f)
        val r = extractor.extract(f, deep)
        assertEquals(BackendStatus.USED, r.backendRuns.single { it.backend == "exiftool" }.status, r.diagnostics.toString())
        assertTrue(r.full.byBackend("exiftool").any { it.qualifiedName == "EXIF:IFD0:Model" })
        assertTrue(Files.notExists(Path.of("pwned")))
    }

    @Test
    fun `concurrent deep extractions share the process pool`() {
        requireTool()
        val files = Files.list(fixtures.resolve("media/exiftool")).use { it.toList() }.take(12)
        val results = extractor.extractBatch((files + files).asSequence(), deep).toList()
        assertTrue(results.all { r -> r.backendRuns.single { it.backend == "exiftool" }.status == BackendStatus.USED })
    }
}
