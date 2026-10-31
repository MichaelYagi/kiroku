# Public API Contract (proposed Kotlin)

Use immutable models and stable naming. Code below is a target design to implement, not existing code.

```kotlin
interface MediaMetadataExtractor : AutoCloseable {
    fun extract(path: Path, options: ExtractionOptions = ExtractionOptions()): MetadataResult
    suspend fun extractAsync(path: Path, options: ExtractionOptions = ExtractionOptions()): MetadataResult
    fun extractBatch(paths: Sequence<Path>, options: ExtractionOptions = ExtractionOptions()): Sequence<MetadataResult>
}

object MediaMetadata {
    @JvmStatic fun create(config: MetadataConfig = MetadataConfig()): MediaMetadataExtractor
}

data class MetadataResult(
    val schemaVersion: String,
    val status: ExtractionStatus,
    val gallery: GalleryMetadata,
    val full: FullMetadata,
    val diagnostics: List<MetadataDiagnostic>,
    val backendRuns: List<BackendRun>
)

enum class ExtractionStatus { COMPLETE, PARTIAL, UNSUPPORTED, FAILED }
enum class MediaKind { IMAGE, VIDEO, AUDIO, RAW_IMAGE, UNKNOWN }
enum class ExtractionDepth { FAST, STANDARD, DEEP }

data class ExtractionOptions(
    val depth: ExtractionDepth = ExtractionDepth.STANDARD,
    val includeRawTags: Boolean = true,
    val includeBinaryPayloads: Boolean = false,
    val includeFileSystemTimestamps: Boolean = true,
    val gpsPolicy: GpsPolicy = GpsPolicy.INCLUDE,
    val timeout: Duration = Duration.ofSeconds(30)
)
```

`GalleryMetadata` must be immutable, typed, nullable where source is absent, and consistent across kinds:

```kotlin
data class GalleryMetadata(
    val mediaKind: MediaKind,
    val format: String?,
    val mimeType: String?,
    val file: GalleryFile,
    val capturedAt: CaptureTimestamp?,
    val modifiedAt: Instant?,
    val dimensions: Dimensions?,
    val orientation: Orientation?,
    val camera: CameraMetadata?,
    val exposure: ExposureMetadata?,
    val location: GeoMetadata?,
    val description: DescriptionMetadata?,
    val color: ColorMetadata?,
    val video: VideoMetadata?,
    val audio: AudioMetadata?,
    val animation: AnimationMetadata?,
    val keywords: List<String> = emptyList(),
    val extra: Map<String, NormalizedValue> = emptyMap()
)
```

Required nested model fields:
- `GalleryFile`: name, extension, sizeBytes, detectedFormat, mimeType, createdAtFileSystem?, modifiedAtFileSystem?, checksum? (only when requested).
- `CaptureTimestamp`: `instant: Instant?`, `localDateTime: LocalDateTime?`, `offset: ZoneOffset?`, `precision`, `source`, `timezoneKnown: Boolean`; do not pretend UTC when timezone missing.
- `Dimensions`: storedWidth, storedHeight, displayWidth, displayHeight, megapixels?, pixelAspectRatio?, aspectRatio?.
- `Orientation`: EXIF code?, rotationDegrees?, mirrored?, transformMatrix?; avoid double rotation.
- `CameraMetadata`: make?, model?, deviceName?, lensMake?, lensModel?, lensSerialNumber?, cameraSerialNumber?, firmware?, software?, owner?, cameraType?, deviceIdentifier?; video and photo share these exact fields.
- `ExposureMetadata`: iso?, exposureTimeSeconds?, apertureFNumber?, focalLengthMm?, focalLength35mmEquivalentMm?, exposureBiasEv?, meteringMode?, exposureProgram?, flash?, whiteBalance?, focusMode?, digitalZoomRatio?, subjectDistanceMeters?, shutterSpeedApex?, brightnessValue?, sceneType?, stabilization?, hdrMode?, shootingMode?. These may be present for video too.
- `GeoMetadata`: latitude?, longitude?, altitudeMeters?, altitudeReference?, accuracyMeters?, headingDegrees?, speedMetersPerSecond?, directionDegrees?, locationTimestamp?, gpsDatum?, source?.
- `DescriptionMetadata`: title?, caption?, comment?, artist?, copyright?, creator?, credit?, rating?, rights?, originalFilename?, author?, album?.
- `ColorMetadata`: colorSpace?, iccProfileName?, iccProfileDescription?, bitDepth?, bitsPerPixel?, hdr?, transferCharacteristics?, colorPrimaries?, matrixCoefficients?, pixelFormat?, chromaSubsampling?.
- `VideoMetadata`: duration: Duration?, frameRate?: Rational?, averageFrameRate?: Rational?, codec?, codecProfile?, codecLevel?, bitRateBitsPerSecond?, container?, streamCount?, primaryStreamIndex?, rotationDegrees?, variableFrameRate?, timeBase?, creationTime?, encoder?, audioStreamCount?, videoStreamCount?, tracks: List<VideoTrackSummary>.
- `AudioMetadata`: duration?, codec?, sampleRateHz?, channels?, channelLayout?, bitRateBitsPerSecond?, bitsPerSample?, language?, tracks: List<AudioTrackSummary>.
- `AnimationMetadata`: frameCount?, loopCount?, duration?.
- `NormalizedValue`: typed scalar/list/object, source tag reference and unit; avoid `Any` in public JSON contract.

`FullMetadata` must contain file probe information, every backend's groups/directories/tags, format/container information, every stream, chapters, embedded metadata, and raw observations. A raw observation must include `backend`, `groupPath`, `tagId?`, `tagName`, `namespace?`, `valueType`, `value`, `displayValue?`, `unit?`, `streamIndex?`, `occurrence`, and `sourcePath?`. Preserve collisions using lists, never a flat `Map<String,String>`.

## Ergonomics
```kotlin
MediaMetadata.create().use { extractor ->
    val result = extractor.extract(Path.of("DSC_1001.NEF"))
    println(result.gallery.camera?.model)
    println(result.gallery.capturedAt?.localDateTime)
    println(result.full.observations.size)
}
```

Provide Java-friendly overloads/builders (`@JvmOverloads` where useful), factory, batch iterator, and optional JSON convenience methods in separate module. Document `close()` semantics and cancellation.

## Result semantics
`COMPLETE` means all configured applicable backends succeeded, not that every conceivable tag exists. `PARTIAL` means usable data with backend failure or truncation. `UNSUPPORTED` means no useful parser for detected format. `FAILED` means no meaningful extraction. A missing field is null, not zero or `unknown` string. Provide structured diagnostics with code, severity, backend, message, field and cause class (avoid leaking paths in public logs).
