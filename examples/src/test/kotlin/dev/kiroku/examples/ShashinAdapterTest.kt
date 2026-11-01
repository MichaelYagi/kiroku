package dev.kiroku.examples

import dev.kiroku.MediaMetadata
import dev.kiroku.MetadataConfig
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShashinAdapterTest {
    private val fixtures = Path.of(System.getProperty("kiroku.fixtures"))

    private fun fields(rel: String) = MediaMetadata.create(MetadataConfig(discoverBackends = false)).use {
        ShashinAdapter.indexedFields(it.extract(fixtures.resolve(rel)))
    }

    @Test
    fun `photo, RAW and video map to the same columns`() {
        val jpeg = fields("media/generated/jpeg_full_exif.jpg")
        assertEquals("KT-1000 Mark II", jpeg.cameraModel)
        assertEquals(400, jpeg.iso)
        assertEquals(640 to 480, jpeg.originalWidth to jpeg.originalHeight)
        assertEquals(480 to 640, jpeg.displayWidth to jpeg.displayHeight, "portrait layout uses display dimensions")
        val raw = fields("media/exiftool/Nikon.nef")
        assertEquals("IMAGE", raw.mediaKind)
        assertEquals("NIKON D70", raw.cameraModel)
        val mov = fields("media/generated/video_iphone_style.mov")
        assertEquals("iPhone 15 Pro", mov.cameraModel)
        assertEquals(1000L, mov.durationMillis)
        assertEquals(35.6895, mov.latitude!!, 1e-9)
        assertEquals(90, mov.rotationDegrees)
        assertEquals(Instant.parse("2024-05-03T01:15:30Z"), mov.sortKey)
    }

    @Test
    fun `stripped video maps to nulls and an unsupported file to a warning`() {
        val s = fields("media/generated/video_stripped.mp4")
        assertNull(s.cameraMake)
        assertNull(s.sortKey)
        assertTrue(fields("media/generated/corrupt_header.mp4").warning != null)
    }
}
