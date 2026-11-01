# Integrating Kiroku into Shashin

Kiroku does not depend on Shashin.

## The Shashin integration

Shashin replaced metadata-extractor with `dev.kiroku:kiroku-core` (native parsers only, `ExtractionDepth.STANDARD`;
no ExifTool or ffprobe). All Kiroku calls are in one Shashin file, `service/KirokuMetadata.kt`. It holds one shared
extractor and converts gallery fields into the formats Shashin already stores, so new records match existing ones.
`MetadataProcessing`, `ImageProcessing`, `MediaServiceController` and the `TestController` repair script call it.

| Shashin field | From Kiroku | Format kept |
|---|---|---|
| `type`, `expectedExtension` | `gallery.mimeType`, `gallery.format` | metadata-extractor's extension spelling (`tiff`, `heif`), because Shashin checks it against its RAW list |
| `takenAt`, `createdAt`, year/month/day/time | `gallery.capturedAt`, else EXIF `IFD0:ModifyDate` | wall-clock `yyyy-MM-dd HH:mm:ss`; UTC-only video times in the server's zone |
| `modifiedAt` | the file's last-modified time | unchanged |
| `camera`, `lens` | `make` + `model`, `lensMake` + `lensModel` | "Make Model" |
| `iso`, `exposure`, `fstopNumber`, `focalLength` | `gallery.exposure` | `"1/250"`, one decimal place |
| `lat`, `lng` | `gallery.location` | decimal-degree strings; 0.0 skipped |
| `duration` | `video.duration` or `audio.duration` | `m:ss`, seconds rounded up |
| `compressionType` | the video track's compressor name or codec; JPEG encoding process; PNG `Deflate` | as before, because MOV transcoding depends on it |
| `originalImageWidth/Height` | stored size, swapped for 90°/270° | as before |
| rotation and mirroring for thumbnails | `gallery.orientation` | all 8 EXIF orientations, including mirrored ones |
| `.exif.yaml` sidecar | `full.observations`, grouped by `groupPath` (`EXIF:IFD0`, `MakerNotes:Nikon`, …) | shown in the EXIF tab |

Before the switch, every fixture in `fixtures/` was run through Shashin's `populateMetadata()` and `createThumbnails()`
with metadata-extractor and again with Kiroku, and the stored fields were compared. Values matched wherever
metadata-extractor was right, and all thumbnail sizes were unchanged. The differences were fixes:
- Canon exposure times (`65312`, `384`) are now real shutter speeds.
- Placeholder zeros (f/0.0, ISO 0, 0 mm) and `----` lenses are no longer stored.
- Pentax focal length: 100 → 10 mm.
- iPhone and Android videos now get GPS.
- An iPhone video's taken time is the local time at the place of capture, like the photos taken with it.
- AVI, WAV, WebM, MKV and raw HEVC files now get a type, date, duration and size.
- Mirrored EXIF orientations are now applied.

## Adapter sketch

The adapter sketch in `examples/src/main/kotlin/dev/kiroku/examples/ShashinAdapter.kt` (compiled and tested) was
written before Shashin's code was available. It targets `ShashinMetadataTarget`, an interface holding the
**suggested** targets from `08-SHASHIN-INTEGRATION.md`, and also stores the versioned full JSON. The real
integration above is the reference for Shashin itself.

## Wiring

```kotlin
// One extractor for the application, created at startup and closed at shutdown.
val kiroku = MediaMetadata.create(
    MetadataConfig(
        allowedRoots = listOf(galleryRoot),                // refuse paths outside the library
        exiftool = ExternalToolConfig(BackendMode.DEEP_ONLY),
        ffprobe = ExternalToolConfig(BackendMode.DEEP_ONLY),
    ),
)

fun ingest(file: File, entity: ShashinMetadataTarget) {
    val result = kiroku.extract(file.toPath(), ExtractionOptions(gpsPolicy = GpsPolicy.INCLUDE))
    ShashinAdapter.apply(result, entity)                   // typed columns + versioned full JSON document
    // Thumbnails, preview generation, pixel rotation and Argus recognition stay in Shashin's ImageProcessing/VideoProcessing.
}
```

With Spring, expose the extractor as a singleton bean that closes on shutdown. Kiroku needs no Spring dependency:

```kotlin
@Configuration
class KirokuConfiguration {
    @Bean(destroyMethod = "close")
    fun mediaMetadataExtractor(): MediaMetadataExtractor = MediaMetadata.create(MetadataConfig.builder().batchParallelism(4).build())
}
```

For non-blocking ingest, use `extractAsync` from coroutines, or `extractFuture` from Java or reactive code.

## Migration rules (from the spec) and how the adapter applies them

| Rule | Adapter behaviour |
|---|---|
| Replace tag-description scans for orientation and size | `rotationDegrees` and `mirrored` come from `gallery.orientation`. Width and height come from `gallery.dimensions`. Kiroku never parses description strings. |
| Use camera, exposure, location and capture time identically for photos and video | One code path. Video `camera` comes from Apple/Android keys. Stripped files give `null`. |
| Keep original pixel dimensions separate from display dimensions | `originalWidth/Height` = stored. `displayWidth/Height` = after orientation and pixel aspect. Thumbnail processing must not overwrite the originals. |
| Keep thumbnails, rotation and recognition in Shashin | Kiroku only reads metadata. It never decodes or transforms pixels. |
| Store `full` separately, as a versioned document | `setMetadataDocument` receives `KirokuJson.toJson(result, includeOriginalBytes = false)`. The schema version is inside. Large binaries are not stored. |
| Tolerate PARTIAL | Only `FAILED`/`UNSUPPORTED` set `metadataWarning`. PARTIAL data is used. |
| Gallery date sorting | `sortKey` = the capture instant when the time zone is known, otherwise the local time treated as UTC. This approximation is documented; `capturedAtLocal` keeps the original wall-clock time. |

## Suggested column mapping

| Kiroku | Suggested Shashin target |
|---|---|
| `g.camera?.make`, `g.camera?.model`, `g.camera?.lensModel` | cameraMake, cameraModel, lensModel |
| `g.exposure?.iso`, `g.exposure?.apertureFNumber`, `exposureTimeSeconds`, `focalLengthMm` | iso, aperture, … |
| `g.video?.duration` (or `g.audio?.duration`) | duration |
| `g.location?.latitude/longitude` | map coordinates |
| `g.dimensions?.displayWidth/Height` | UI layout |
| `g.dimensions?.storedWidth/Height` | original dimensions |
| `g.capturedAt` | date sorting and display (show "time zone unknown" when `timezoneKnown == false`) |
| `g.extra["livePhotoContentIdentifier"]` | pairing a Live Photo's HEIC and MOV |

## Test before migrating

Re-extract a sample of existing entries (image, video, RAW) and compare:
- date sorting
- camera filters
- map pins
- portrait layout (EXIF 6/8 photos and rotated phone videos)
