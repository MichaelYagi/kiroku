package dev.kiroku.ffprobe

import dev.kiroku.BackendMode
import dev.kiroku.ExternalToolConfig
import dev.kiroku.ExtractionDepth
import dev.kiroku.ExtractionOptions
import dev.kiroku.ExtractionStatus
import dev.kiroku.MediaMetadata
import dev.kiroku.MetadataConfig
import dev.kiroku.model.BackendStatus
import dev.kiroku.model.StreamKind
import dev.kiroku.model.ValueFidelity
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FfprobeBackendTest {
    private val fixtures = Path.of(System.getProperty("kiroku.fixtures"))
    private val exe: Path? =
        if (System.getenv("KIROKU_NO_EXTERNAL") ==
            "1"
        ) {
            null
        } else {
            listOfNotNull(System.getProperty("kiroku.ffprobe"), System.getenv("KIROKU_FFPROBE")).map { Path.of(it) }.firstOrNull { Files.isExecutable(it) }
                ?: dev.kiroku.spi.ProcessRunner.findOnPath("ffprobe")
        }
    private val extractor by lazy { MediaMetadata.create(MetadataConfig(ffprobe = ExternalToolConfig(BackendMode.DEEP_ONLY, exe))) }
    private val deep = ExtractionOptions(depth = ExtractionDepth.DEEP)

    @AfterAll
    fun close() = extractor.close()

    private fun requireTool() = assumeTrue(exe != null, "ffprobe not installed: integration test skipped")

    @Test
    fun `streams, side data and container from ffprobe`() {
        requireTool()
        val r = extractor.extract(fixtures.resolve("media/generated/video_iphone_style.mov"), deep)
        assertEquals(BackendStatus.USED, r.backendRuns.single { it.backend == "ffprobe" }.status, r.diagnostics.toString())
        assertEquals(ExtractionStatus.COMPLETE, r.status)
        val ff = r.full.byBackend("ffprobe")
        assertTrue(ff.all { it.fidelity == ValueFidelity.BACKEND_DECODED })
        assertTrue(ff.any { it.groupPath == listOf("ffprobe", "stream:side_data") && it.tagName == "Display Matrix" })
        assertTrue(ff.any { it.tagName == "com.apple.quicktime.make" })
        assertTrue(ff.none { it.tagName == "filename" }, "no paths in output")
        val ffVideo = r.full.streams.single { it.backend == "ffprobe" && it.kind == StreamKind.VIDEO }
        assertEquals("hevc", ffVideo.codec)
        // Native and ffprobe agree on the display rotation: ffprobe's counter-clockwise -90 is 90 clockwise.
        assertEquals(
            -90.0,
            ffVideo.properties["rotation"]!!.let {
                (it as dev.kiroku.model.NormalizedValue).let { v ->
                    (v as? dev.kiroku.model.NormalizedValue.Decimal)?.value
                        ?: (v as dev.kiroku.model.NormalizedValue.Integer).value.toDouble()
                }
            },
        )
        assertEquals(90, r.gallery.orientation!!.rotationDegrees)
    }

    @Test
    fun `native and ffprobe agree on technical fields`() {
        requireTool()
        for (name in listOf("video_android_style.mp4", "video_basic.webm", "video_basic.avi", "video_hdr10.mp4", "audio_bwf.wav", "video_raw.hevc")) {
            val r = extractor.extract(fixtures.resolve("media/generated/$name"), deep)
            val native = r.full.streams.filter { it.backend == "native" }
            val ff = r.full.streams.filter { it.backend == "ffprobe" }
            assertEquals(ff.count { it.kind == StreamKind.VIDEO }, native.count { it.kind == StreamKind.VIDEO }, name)
            assertEquals(ff.count { it.kind == StreamKind.AUDIO }, native.count { it.kind == StreamKind.AUDIO }, name)
            for ((n, f) in native.filter { it.kind == StreamKind.VIDEO }.zip(ff.filter { it.kind == StreamKind.VIDEO })) {
                assertEquals(f.properties["width"], n.properties["width"], "$name width")
                assertEquals(f.properties["height"], n.properties["height"], "$name height")
                assertEquals(f.codec, n.codec, "$name codec")
            }
            for ((n, f) in native.filter { it.kind == StreamKind.AUDIO }.zip(ff.filter { it.kind == StreamKind.AUDIO })) {
                assertEquals(f.properties["sampleRate"], n.properties["sampleRate"], "$name sample rate")
                assertEquals(f.properties["channels"], n.properties["channels"], "$name channels")
            }
        }
    }

    @Test
    fun `ffprobe failure on non-media is reported, not fatal`() {
        requireTool()
        MediaMetadata.create(MetadataConfig(ffprobe = ExternalToolConfig(BackendMode.ALWAYS, exe))).use { ex ->
            val r = ex.extract(fixtures.resolve("media/generated/truncated.jpg"))
            assertTrue(
                r.backendRuns.none { it.backend == "ffprobe" } || r.backendRuns.single { it.backend == "ffprobe" }.status != BackendStatus.USED ||
                    r.status != ExtractionStatus.FAILED,
            )
        }
    }
}
