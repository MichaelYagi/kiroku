package dev.kiroku.examples;

import dev.kiroku.ExtractionOptions;
import dev.kiroku.GpsPolicy;
import dev.kiroku.MediaMetadata;
import dev.kiroku.MediaMetadataExtractor;
import dev.kiroku.MetadataConfig;
import dev.kiroku.MetadataResult;
import dev.kiroku.model.CameraMetadata;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** The same API from Java: factory, builders, getters and futures. */
public final class JavaUsage {
    private JavaUsage() {}

    public static void main(String[] args) throws Exception {
        MetadataConfig config = MetadataConfig.builder().batchParallelism(4).build();
        ExtractionOptions options = ExtractionOptions.builder().gpsPolicy(GpsPolicy.EXCLUDE_GALLERY).build();
        try (MediaMetadataExtractor extractor = MediaMetadata.create(config)) {
            MetadataResult result = extractor.extract(Path.of(args[0]), options);
            CameraMetadata camera = result.getGallery().getCamera();
            System.out.println(result.getStatus() + " " + (camera == null ? "no camera" : camera.getMake() + " " + camera.getModel()));
            CompletableFuture<MetadataResult> future = extractor.extractFuture(Path.of(args[0]));
            System.out.println(future.get().getFull().getObservations().size() + " observations");
        }
    }
}
