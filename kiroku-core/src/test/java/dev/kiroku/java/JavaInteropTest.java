package dev.kiroku.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kiroku.CloseableIterator;
import dev.kiroku.ExtractionDepth;
import dev.kiroku.ExtractionOptions;
import dev.kiroku.ExtractionStatus;
import dev.kiroku.GpsPolicy;
import dev.kiroku.MediaMetadata;
import dev.kiroku.MediaMetadataExtractor;
import dev.kiroku.MetadataConfig;
import dev.kiroku.MetadataResult;
import dev.kiroku.model.CameraMetadata;
import dev.kiroku.model.NormalizedValue;
import dev.kiroku.model.RawObservation;
import dev.kiroku.model.ValueFidelity;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The public API must be straightforward from Java: factory, builders, futures, iterators, getters. */
class JavaInteropTest {
    private static final Path FIXTURES = Path.of(System.getProperty("kiroku.fixtures"), "media", "generated");

    @Test
    void extractWithDefaultsAndBuilders() throws Exception {
        MetadataConfig config = MetadataConfig.builder().batchParallelism(2).build();
        ExtractionOptions options =
                ExtractionOptions.builder()
                        .depth(ExtractionDepth.STANDARD)
                        .gpsPolicy(GpsPolicy.COARSE_GALLERY)
                        .timeout(Duration.ofSeconds(5))
                        .build();
        try (MediaMetadataExtractor extractor = MediaMetadata.create(config)) {
            MetadataResult result = extractor.extract(FIXTURES.resolve("jpeg_full_exif.jpg"), options);
            assertEquals(ExtractionStatus.COMPLETE, result.getStatus());
            CameraMetadata camera = result.getGallery().getCamera();
            assertNotNull(camera);
            assertEquals("KT-1000 Mark II", camera.getModel());
            assertEquals(Integer.valueOf(400), result.getGallery().getExposure().getIso());
            assertEquals(48.86, result.getGallery().getLocation().getLatitude(), 1e-9);

            RawObservation iso = result.getFull().find("EXIF:ExifIFD:ISO").get(0);
            assertEquals(Long.valueOf(0x8827), iso.getTagId());
            assertEquals(ValueFidelity.BYTE_EXACT, iso.getFidelity());
            assertTrue(iso.getValue() instanceof NormalizedValue.Integer);
            assertEquals(400L, ((NormalizedValue.Integer) iso.getValue()).getValue());
            assertEquals(2, iso.getOriginal().getBytes().toByteArray().length);

            MetadataResult defaults = extractor.extract(FIXTURES.resolve("video_iphone_style.mov"));
            assertEquals("Apple", defaults.getGallery().getCamera().getMake());
        }
    }

    @Test
    void futuresAndIterators() throws Exception {
        try (MediaMetadataExtractor extractor = MediaMetadata.create()) {
            MetadataResult r = extractor.extractFuture(FIXTURES.resolve("bmp_plain.bmp")).get(10, TimeUnit.SECONDS);
            assertNull(r.getGallery().getCamera());
            List<Path> files = List.of(FIXTURES.resolve("bmp_plain.bmp"), FIXTURES.resolve("gif_animated.gif"));
            int n = 0;
            try (CloseableIterator<MetadataResult> it = extractor.extractBatchIterator(files, ExtractionOptions.DEFAULT)) {
                while (it.hasNext()) {
                    assertNotNull(it.next().getGallery());
                    n++;
                }
            }
            assertEquals(2, n);
            assertTrue(extractor.capabilities().getFormats().size() > 20);
        }
    }

    @Test
    void valueFactoriesAreStatic() {
        NormalizedValue v = NormalizedValue.rational(1, 250);
        assertEquals("1/250", v.toString());
        assertEquals(NormalizedValue.Null.INSTANCE, NormalizedValue.Null.INSTANCE);
    }
}
