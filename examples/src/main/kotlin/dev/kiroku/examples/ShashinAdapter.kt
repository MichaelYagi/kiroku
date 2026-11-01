package dev.kiroku.examples

import dev.kiroku.ExtractionStatus
import dev.kiroku.MetadataResult
import dev.kiroku.json.JsonOptions
import dev.kiroku.json.KirokuJson
import dev.kiroku.model.MediaKind
import java.time.Instant
import java.time.LocalDateTime

/**
 * A sketch of the Shashin integration from 08-SHASHIN-INTEGRATION.md.
 *
 * Shashin's real `Metadata` entity was not available, so this adapter targets [ShashinMetadataTarget],
 * an interface holding the *suggested* targets from the spec. Implement it over the real entity (or
 * map [ShashinIndexedFields] into its setters). Field names here are not claims about Shashin's code.
 *
 * Rules applied (from the spec):
 * 1. Orientation and dimensions come from `gallery.orientation` / `gallery.dimensions`, never from tag-description scans.
 * 2. `gallery.camera`, `exposure`, `location` and `capturedAt` are used the same way for photos, RAW and video.
 * 3. Original (stored) pixel dimensions are kept separately from display dimensions. Thumbnail processing must
 *    not overwrite them later.
 * 4. Thumbnails, rotation of pixels and recognition stay in Shashin. Kiroku only reads metadata.
 * 5. The full result can be stored as a versioned JSON document. Indexed fields go to typed columns.
 * 6. PARTIAL results are accepted. Only FAILED/UNSUPPORTED are surfaced as warnings.
 */
interface ShashinMetadataTarget {
    fun setMediaKind(kind: String)

    fun setCameraMake(v: String?)

    fun setCameraModel(v: String?)

    fun setLensModel(v: String?)

    fun setIso(v: Int?)

    fun setAperture(v: Double?)

    fun setExposureTimeSeconds(v: Double?)

    fun setFocalLengthMm(v: Double?)

    fun setDurationMillis(v: Long?)

    fun setLatitude(v: Double?)

    fun setLongitude(v: Double?)

    /** The capture instant when the time zone is known. */
    fun setCapturedAtInstant(v: Instant?)

    /** The local wall-clock capture time (always set when any capture time exists). */
    fun setCapturedAtLocal(v: LocalDateTime?)

    fun setOriginalWidth(v: Int?)

    fun setOriginalHeight(v: Int?)

    fun setDisplayWidth(v: Int?)

    fun setDisplayHeight(v: Int?)

    /** Clockwise rotation a viewer applies to the stored pixels (0/90/180/270), plus the mirror flag. */
    fun setRotationDegrees(v: Int?)

    fun setMirrored(v: Boolean?)

    fun setMetadataDocument(json: String?)

    fun setMetadataWarning(v: String?)
}

/** Typed, index-friendly columns derived from the gallery projection. */
data class ShashinIndexedFields(
    val mediaKind: String,
    val cameraMake: String?,
    val cameraModel: String?,
    val lensModel: String?,
    val iso: Int?,
    val aperture: Double?,
    val exposureTimeSeconds: Double?,
    val focalLengthMm: Double?,
    val durationMillis: Long?,
    val latitude: Double?,
    val longitude: Double?,
    val capturedAtInstant: Instant?,
    val capturedAtLocal: LocalDateTime?,
    /** A sort key for gallery date ordering: the instant if known, otherwise the local time treated as UTC (documented approximation). */
    val sortKey: Instant?,
    val originalWidth: Int?,
    val originalHeight: Int?,
    val displayWidth: Int?,
    val displayHeight: Int?,
    val rotationDegrees: Int?,
    val mirrored: Boolean?,
    val warning: String?,
)

object ShashinAdapter {
    fun indexedFields(result: MetadataResult): ShashinIndexedFields {
        val g = result.gallery
        val captured = g.capturedAt
        return ShashinIndexedFields(
            mediaKind = if (g.mediaKind == MediaKind.RAW_IMAGE) "IMAGE" else g.mediaKind.name,
            cameraMake = g.camera?.make,
            cameraModel = g.camera?.model,
            lensModel = g.camera?.lensModel,
            iso = g.exposure?.iso,
            aperture = g.exposure?.apertureFNumber,
            exposureTimeSeconds = g.exposure?.exposureTimeSeconds,
            focalLengthMm = g.exposure?.focalLengthMm,
            durationMillis = (g.video?.duration ?: g.audio?.duration)?.toMillis(),
            latitude = g.location?.latitude,
            longitude = g.location?.longitude,
            capturedAtInstant = captured?.instant,
            capturedAtLocal = captured?.localDateTime,
            sortKey = captured?.instant ?: captured?.localDateTime?.toInstant(java.time.ZoneOffset.UTC),
            originalWidth = g.dimensions?.storedWidth,
            originalHeight = g.dimensions?.storedHeight,
            displayWidth = g.dimensions?.displayWidth,
            displayHeight = g.dimensions?.displayHeight,
            rotationDegrees = g.orientation?.rotationDegrees,
            mirrored = g.orientation?.mirrored,
            warning =
            when (result.status) {
                ExtractionStatus.FAILED, ExtractionStatus.UNSUPPORTED -> result.diagnostics.firstOrNull()?.message ?: result.status.name
                else -> null
            },
        )
    }

    /** Applies the result to a Shashin entity wrapper. The full document omits inline bytes to keep the database small. */
    fun apply(result: MetadataResult, target: ShashinMetadataTarget, storeFullDocument: Boolean = true) {
        val f = indexedFields(result)
        target.setMediaKind(f.mediaKind)
        target.setCameraMake(f.cameraMake)
        target.setCameraModel(f.cameraModel)
        target.setLensModel(f.lensModel)
        target.setIso(f.iso)
        target.setAperture(f.aperture)
        target.setExposureTimeSeconds(f.exposureTimeSeconds)
        target.setFocalLengthMm(f.focalLengthMm)
        target.setDurationMillis(f.durationMillis)
        target.setLatitude(f.latitude)
        target.setLongitude(f.longitude)
        target.setCapturedAtInstant(f.capturedAtInstant)
        target.setCapturedAtLocal(f.capturedAtLocal)
        target.setOriginalWidth(f.originalWidth)
        target.setOriginalHeight(f.originalHeight)
        target.setDisplayWidth(f.displayWidth)
        target.setDisplayHeight(f.displayHeight)
        target.setRotationDegrees(f.rotationDegrees)
        target.setMirrored(f.mirrored)
        target.setMetadataWarning(f.warning)
        target.setMetadataDocument(if (storeFullDocument) KirokuJson.toJson(result, JsonOptions(includeOriginalBytes = false)) else null)
    }
}
