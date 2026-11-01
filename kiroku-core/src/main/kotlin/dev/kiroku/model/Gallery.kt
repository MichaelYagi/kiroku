package dev.kiroku.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The stable, typed, gallery-oriented projection. Every field is `null` when no valid source exists.
 * It is computed from the same observations as [FullMetadata] and is never a separate extraction.
 */
public data class GalleryMetadata(
    val mediaKind: MediaKind,
    /** The detected format id (see [MediaFormat.id]), the same as [GalleryFile.detectedFormat]. */
    val format: String?,
    val mimeType: String?,
    val file: GalleryFile,
    val capturedAt: CaptureTimestamp?,
    /** The embedded modification time (EXIF `DateTime`, XMP `ModifyDate`, QuickTime modification date). Not the filesystem time. */
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
    val extra: Map<String, NormalizedValue> = emptyMap(),
)

public data class GalleryFile(
    val name: String,
    val extension: String?,
    val sizeBytes: Long,
    val detectedFormat: String,
    val mimeType: String?,
    val createdAtFileSystem: Instant? = null,
    val modifiedAtFileSystem: Instant? = null,
    val checksum: String? = null,
)

public enum class TimestampPrecision { YEAR, MONTH, DAY, MINUTE, SECOND, MILLISECOND, MICROSECOND, NANOSECOND }

/**
 * A capture time that keeps time-zone uncertainty.
 *
 * - With a known offset: [instant], [localDateTime] and [offset] are all set, and [timezoneKnown] is `true`.
 * - With no offset (common for EXIF without `OffsetTimeOriginal`): only [localDateTime] is set,
 *   [instant] is `null`, and [timezoneKnown] is `false`. Kiroku never pretends the time is UTC.
 * - For sources that are UTC by definition (QuickTime `mvhd` creation time, GPS time): [instant] is set,
 *   [offset] is `Z`, and [localDateTime] is `null` because the wall-clock time at the capture location is unknown.
 */
public data class CaptureTimestamp(
    val instant: Instant?,
    val localDateTime: LocalDateTime?,
    val offset: ZoneOffset?,
    val precision: TimestampPrecision,
    /** The qualified name of the source tag, for example `EXIF:ExifIFD:DateTimeOriginal`. */
    val source: String,
    val timezoneKnown: Boolean,
)

public data class Dimensions(
    val storedWidth: Int?,
    val storedHeight: Int?,
    /** The width after the orientation/display transform and the pixel aspect ratio are applied. */
    val displayWidth: Int?,
    val displayHeight: Int?,
    val megapixels: Double? = null,
    val pixelAspectRatio: Rational? = null,
    /** The reduced display aspect ratio, for example 16/9. */
    val aspectRatio: Rational? = null,
)

/**
 * Display orientation. [rotationDegrees] is the clockwise rotation a viewer must apply to the stored
 * pixels. When [mirrored] is set, a horizontal flip is applied *before* that rotation. Apply this
 * once. [Dimensions.displayWidth]/[Dimensions.displayHeight] already include it.
 */
public data class Orientation(
    /** The EXIF/TIFF orientation code 1–8, when the source is EXIF. */
    val exifCode: Int?,
    val rotationDegrees: Int?,
    val mirrored: Boolean?,
    /** For video: the 3×3 display matrix in row-major order (a, b, u, c, d, v, x, y, w), as decoded doubles. */
    val transformMatrix: List<Double>? = null,
    val source: String? = null,
)

/** Capture device. The same fields are used for photos, RAW files and videos. */
public data class CameraMetadata(
    val make: String? = null,
    val model: String? = null,
    val deviceName: String? = null,
    val lensMake: String? = null,
    val lensModel: String? = null,
    val lensSerialNumber: String? = null,
    val cameraSerialNumber: String? = null,
    val firmware: String? = null,
    val software: String? = null,
    val owner: String? = null,
    val cameraType: String? = null,
    val deviceIdentifier: String? = null,
)

public data class FlashInfo(
    /** The raw EXIF Flash bit field, when the source is EXIF. */
    val code: Int?,
    val fired: Boolean?,
    /** `ON`, `OFF`, `AUTO`, or `null`. */
    val mode: String?,
    val redEyeReduction: Boolean?,
    val present: Boolean?,
)

public data class ExposureMetadata(
    val iso: Int? = null,
    val exposureTimeSeconds: Double? = null,
    /** The exact exposure time as stored, for example 1/250. */
    val exposureTime: Rational? = null,
    val apertureFNumber: Double? = null,
    val focalLengthMm: Double? = null,
    val focalLength35mmEquivalentMm: Double? = null,
    val exposureBiasEv: Double? = null,
    /** For example `AVERAGE`, `CENTER_WEIGHTED_AVERAGE`, `SPOT`, `MULTI_SPOT`, `PATTERN`, `PARTIAL`. */
    val meteringMode: String? = null,
    /** For example `MANUAL`, `PROGRAM`, `APERTURE_PRIORITY`, `SHUTTER_PRIORITY`. */
    val exposureProgram: String? = null,
    val flash: FlashInfo? = null,
    /** `AUTO` or `MANUAL` (the EXIF WhiteBalance tag), or a vendor-specific value. */
    val whiteBalance: String? = null,
    val focusMode: String? = null,
    val digitalZoomRatio: Double? = null,
    val subjectDistanceMeters: Double? = null,
    val shutterSpeedApex: Double? = null,
    val brightnessValue: Double? = null,
    val sceneType: String? = null,
    val stabilization: String? = null,
    val hdrMode: String? = null,
    val shootingMode: String? = null,
)

public enum class AltitudeReference { ABOVE_SEA_LEVEL, BELOW_SEA_LEVEL, ABOVE_ELLIPSOID, UNKNOWN }

public data class GeoMetadata(
    /** Decimal degrees, positive north. */
    val latitude: Double? = null,
    /** Decimal degrees, positive east. */
    val longitude: Double? = null,
    /** Signed metres (negative below the reference). */
    val altitudeMeters: Double? = null,
    val altitudeReference: AltitudeReference? = null,
    val accuracyMeters: Double? = null,
    /** The direction of movement (EXIF `GPSTrack`), in degrees. */
    val headingDegrees: Double? = null,
    val speedMetersPerSecond: Double? = null,
    /** The direction the camera pointed (EXIF `GPSImgDirection`), in degrees. */
    val directionDegrees: Double? = null,
    val locationTimestamp: Instant? = null,
    val gpsDatum: String? = null,
    val source: String? = null,
)

public data class DescriptionMetadata(
    val title: String? = null,
    val caption: String? = null,
    val comment: String? = null,
    val artist: String? = null,
    val copyright: String? = null,
    val creator: String? = null,
    val credit: String? = null,
    /** A rating, normally 0–5 (XMP `Rating`). `-1` means rejected. */
    val rating: Int? = null,
    val rights: String? = null,
    val originalFilename: String? = null,
    val author: String? = null,
    val album: String? = null,
)

public data class ColorMetadata(
    val colorSpace: String? = null,
    val iccProfileName: String? = null,
    val iccProfileDescription: String? = null,
    val bitDepth: Int? = null,
    val bitsPerPixel: Int? = null,
    val hdr: Boolean? = null,
    val transferCharacteristics: String? = null,
    val colorPrimaries: String? = null,
    val matrixCoefficients: String? = null,
    val pixelFormat: String? = null,
    val chromaSubsampling: String? = null,
)

public data class VideoTrackSummary(
    val streamIndex: Int,
    val codec: String?,
    val width: Int?,
    val height: Int?,
    val frameRate: Rational?,
    val bitRateBitsPerSecond: Long?,
    val duration: Duration?,
    val language: String?,
    val rotationDegrees: Int?,
    val isDefault: Boolean?,
)

public data class AudioTrackSummary(
    val streamIndex: Int,
    val codec: String?,
    val sampleRateHz: Int?,
    val channels: Int?,
    val channelLayout: String?,
    val bitRateBitsPerSecond: Long?,
    val bitsPerSample: Int?,
    val duration: Duration?,
    val language: String?,
    val isDefault: Boolean?,
)

public data class VideoMetadata(
    val duration: Duration? = null,
    /** The nominal frame rate of the primary video stream. */
    val frameRate: Rational? = null,
    val averageFrameRate: Rational? = null,
    val codec: String? = null,
    val codecProfile: String? = null,
    val codecLevel: String? = null,
    val bitRateBitsPerSecond: Long? = null,
    /** The container format id, or `null` for an elementary stream such as `.hevc`. */
    val container: String? = null,
    val streamCount: Int? = null,
    val primaryStreamIndex: Int? = null,
    val rotationDegrees: Int? = null,
    val variableFrameRate: Boolean? = null,
    val timeBase: Rational? = null,
    val creationTime: Instant? = null,
    val encoder: String? = null,
    val audioStreamCount: Int? = null,
    val videoStreamCount: Int? = null,
    val tracks: List<VideoTrackSummary> = emptyList(),
)

public data class AudioMetadata(
    val duration: Duration? = null,
    val codec: String? = null,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
    val channelLayout: String? = null,
    val bitRateBitsPerSecond: Long? = null,
    val bitsPerSample: Int? = null,
    val language: String? = null,
    val tracks: List<AudioTrackSummary> = emptyList(),
)

public data class AnimationMetadata(
    val frameCount: Int? = null,
    /** `0` means loop forever (GIF/WebP/APNG convention). `null` means not specified. */
    val loopCount: Int? = null,
    val duration: Duration? = null,
)
