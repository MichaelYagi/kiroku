# Shashin Integration Guidance

## Observed from provided `ImageProcessing.kt`
- `createThumbnails()` reads metadata via `ImageMetadataReader.readMetadata(file)` and scans directory/tag *descriptions* for orientation and width/height.
- It has separate paths for RAW, images, and video (`VideoProcessing.getVideoScreenshot()` and rotation fallback).
- It populates existing `Metadata` model with original dimensions, folder, thumbnail paths/URLs; `setThumbnails()` later overwrites original dimensions with processed image dimensions.
- The class also contains unrelated thumbnail, pixel transform, and Argus recognition logic. The new metadata library must not absorb these concerns.
- The attached file does not define the complete Shashin `Metadata` model or its full ingest path. Do not assume additional fields or migration behavior without inspecting those files.

## Target usage
```kotlin
val result = metadataExtractor.extract(file.toPath())
val g = result.gallery
// Adapter maps g fields into Shashin's existing Metadata entity.
// Preserve original stored dimensions separately from display dimensions.
// Continue using VideoProcessing/thumbnail code for previews if desired.
```

## Migration rules
1. Replace ad-hoc tag-description scans with `gallery.orientation` and `gallery.dimensions`.
2. Use `gallery.camera` identically for photos and video; expose `gallery.exposure`, `gallery.location`, and `gallery.capturedAt` consistently.
3. Preserve the original image's pixel dimensions and derived display dimensions separately; do not overwrite source metadata after thumbnail processing.
4. Keep thumbnail generation, preview URLs, image rotation, and face/object recognition in Shashin.
5. Store `full` JSON separately or as a versioned document if deep search/debugging is desired; store indexed gallery fields in typed columns. Avoid stuffing huge binary tags into DB.
6. Make integration non-blocking and tolerate PARTIAL metadata; show a warning only where useful.
7. Test migrations with existing image/video/RAW entries and confirm gallery date sorting, camera filtering, location map, portrait orientation.

## Example response mapping
`g.camera?.make -> cameraMake`; `g.camera?.model -> cameraModel`; `g.camera?.lensModel -> lensModel`; `g.exposure?.iso -> iso`; `g.exposure?.apertureFNumber -> aperture`; `g.video?.duration -> duration`; `g.location?.latitude/longitude -> map coordinates`; `g.dimensions?.displayWidth/Height -> UI layout`. These are *suggested* adapter targets, not claims about current Shashin entity setters.
