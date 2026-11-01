package dev.kiroku.model

import java.time.Duration
import java.time.Instant

/**
 * One tag/value as a backend observed it. Collisions are never merged: duplicates are separate
 * observations, told apart by [occurrence] and [sourcePath].
 *
 * @property index this observation's position in [FullMetadata.observations]. It is stable for a
 *   given file and Kiroku version, and [TagReference] uses it.
 * @property backend the id of the backend that produced it (`native`, `exiftool`, `ffprobe`).
 * @property groupPath the directory path, for example `["EXIF", "ExifIFD"]` or `["MakerNotes", "Nikon"]`.
 *   Native groups follow ExifTool's family 0/1 names where those exist, so values from different
 *   backends line up.
 * @property tagId the numeric tag ID: TIFF tag, IPTC `record*256+dataset`, FourCC as a big-endian
 *   32-bit integer, or EBML element ID.
 * @property known whether Kiroku (or the backend) knows a name for this tag. Unknown tags are kept.
 * @property occurrence a 0-based counter among observations with the same backend, group path and tag.
 * @property sourcePath the location inside the container, for example `JPEG/APP1[0]/TIFF@12/IFD0`.
 */
public data class RawObservation(
    val index: Int,
    val backend: String,
    val groupPath: List<String>,
    val tagId: Long?,
    val tagName: String,
    val namespace: String? = null,
    val known: Boolean = true,
    val nativeType: NativeType? = null,
    val value: NormalizedValue,
    val displayValue: String? = null,
    val unit: String? = null,
    val streamIndex: Int? = null,
    val occurrence: Int = 0,
    val sourcePath: String? = null,
    val fidelity: ValueFidelity,
    val original: OriginalValue? = null,
) {
    /** The value's type, the same as `value.type`. */
    val valueType: ValueType get() = value.type

    /** [tagId] as `0x%04X` hex, or `null`. */
    val tagIdHex: String? get() = tagId?.let { if (it in 0..0xFFFF) "0x%04X".format(it) else "0x%X".format(it) }

    /** The group path and tag name joined with `:`, for example `EXIF:ExifIFD:DateTimeOriginal`. */
    val qualifiedName: String get() = (groupPath + tagName).joinToString(":")
}

/** A pointer from a normalized field back to the observation it came from. */
public data class TagReference(
    val observationIndex: Int,
    val backend: String,
    val qualifiedName: String,
    val tagId: Long?,
    val occurrence: Int,
    val streamIndex: Int? = null,
)

/** One candidate considered for a normalized gallery field. */
public data class FieldCandidate(
    val source: TagReference,
    /** The value after unit normalization, as it would appear in the gallery field. */
    val normalizedValue: NormalizedValue,
    /** Lower ranks are preferred. */
    val rank: Int,
    val accepted: Boolean,
    val rejectionReason: String? = null,
)

/** How a gallery field was resolved: every candidate, the chosen one, and any conflict. */
public data class FieldProvenance(
    val field: String,
    val chosen: TagReference?,
    val candidates: List<FieldCandidate>,
    val reason: String,
    /** `true` when more than one valid, non-equivalent candidate existed. */
    val conflict: Boolean,
)

public enum class DirectoryKind {
    TIFF_IFD,
    MAKERNOTE,
    XMP_PACKET,
    IPTC_RECORD,
    ICC_PROFILE,
    JPEG_SEGMENT,
    PNG_CHUNK,
    RIFF_CHUNK,
    ISOBMFF_BOX,
    EBML_ELEMENT,
    GIF_BLOCK,
    HEVC_NAL,
    FILE,
    BACKEND_GROUP,
}

/** A structural directory parsed from the file (an IFD, segment, chunk, box, packet or record). */
public data class DirectoryInfo(
    val backend: String,
    val path: List<String>,
    val kind: DirectoryKind,
    val fileOffset: Long? = null,
    val length: Long? = null,
    val byteOrder: ByteOrderMark? = null,
    val entryCount: Int? = null,
    /** `false` when the directory was found but its contents could not be parsed. */
    val parsed: Boolean = true,
    val note: String? = null,
)

public enum class StreamKind { VIDEO, AUDIO, SUBTITLE, DATA, TIMED_METADATA, IMAGE, OTHER }

/** One container stream/track with its decoded technical properties. */
public data class StreamInfo(
    val index: Int,
    val backend: String,
    val kind: StreamKind,
    val codec: String? = null,
    val codecTag: String? = null,
    val properties: Map<String, NormalizedValue> = emptyMap(),
)

public data class ChapterInfo(val index: Int, val backend: String, val start: Duration?, val end: Duration?, val title: String?)

public enum class EmbeddedImageKind { PRIMARY, PAGE, THUMBNAIL, PREVIEW, AUXILIARY, FRAME, OTHER }

/** An embedded or additional image (TIFF page, EXIF thumbnail, RAW preview, HEIF item, MPF image). Metadata only, never decoded. */
public data class EmbeddedImageInfo(
    val index: Int,
    val backend: String,
    val kind: EmbeddedImageKind,
    val groupPath: List<String>,
    val width: Int? = null,
    val height: Int? = null,
    val compression: String? = null,
    val mimeType: String? = null,
    val fileOffset: Long? = null,
    val length: Long? = null,
)

/** Information from signature probing and the filesystem. */
public data class FileProbe(
    val name: String,
    val extension: String?,
    val sizeBytes: Long,
    val detectedFormat: MediaFormat,
    val mediaKind: MediaKind,
    val mimeType: String?,
    /** The format the extension suggests, which can differ from [detectedFormat]. */
    val extensionFormat: MediaFormat?,
    val extensionMatches: Boolean,
    /** Up to the first 32 bytes, as lowercase hex. */
    val signatureHex: String,
    /** `ftyp` major and compatible brands for ISO-BMFF files. */
    val brands: List<String> = emptyList(),
    val createdAtFileSystem: Instant? = null,
    val modifiedAtFileSystem: Instant? = null,
    /** `sha256:<hex>`, present only when `computeChecksum` is set. */
    val checksum: String? = null,
)

/** Container-level technical information. */
public data class ContainerInfo(
    val format: String,
    val brands: List<String> = emptyList(),
    val duration: Duration? = null,
    val bitRateBitsPerSecond: Long? = null,
    val properties: Map<String, NormalizedValue> = emptyMap(),
)

/**
 * The exhaustive output. Every observation from every backend is kept, with provenance.
 */
public data class FullMetadata(
    val file: FileProbe,
    val container: ContainerInfo?,
    val directories: List<DirectoryInfo>,
    val streams: List<StreamInfo>,
    val chapters: List<ChapterInfo>,
    val embeddedImages: List<EmbeddedImageInfo>,
    val observations: List<RawObservation>,
    /** How each gallery field was resolved, keyed by a dotted field path such as `camera.model`. */
    val fieldProvenance: Map<String, FieldProvenance>,
) {
    /**
     * For each field, the chosen source first, then the alternatives. This is the
     * `fieldSources: Map<String, List<TagReference>>` view from the spec.
     */
    val fieldSources: Map<String, List<TagReference>>
        get() =
            fieldProvenance.mapValues { (_, p) ->
                val chosen = listOfNotNull(p.chosen)
                chosen + p.candidates.map { it.source }.filter { it !in chosen }
            }

    /** Observations whose qualified name equals [qualifiedName], in order. */
    public fun find(qualifiedName: String): List<RawObservation> = observations.filter { it.qualifiedName == qualifiedName }

    /** Observations in group [groupPath] with numeric [tagId]. */
    public fun find(groupPath: List<String>, tagId: Long): List<RawObservation> = observations.filter { it.groupPath == groupPath && it.tagId == tagId }

    /** Observations from [backend]. */
    public fun byBackend(backend: String): List<RawObservation> = observations.filter { it.backend == backend }
}

public enum class Severity { INFO, WARNING, ERROR }

/** Stable diagnostic codes. */
public enum class DiagnosticCode {
    FILE_NOT_FOUND,
    NOT_A_REGULAR_FILE,
    PATH_NOT_ALLOWED,
    SYMLINK_REJECTED,
    FILE_TOO_LARGE,
    IO_ERROR,
    UNKNOWN_FORMAT,
    UNSUPPORTED_FORMAT,
    EXTENSION_MISMATCH,
    TIFF_CLASSIFICATION_UNCERTAIN,
    TRUNCATED,
    MALFORMED,
    LIMIT_EXCEEDED,
    MAKERNOTE_UNPARSED,
    BACKEND_UNAVAILABLE,
    BACKEND_SKIPPED,
    BACKEND_FAILED,
    BACKEND_TIMEOUT,
    OUTPUT_TRUNCATED,
    FIELD_CONFLICT,
    INVALID_VALUE,
    REDACTED,
    TIMEZONE_UNKNOWN,
    CAPTURE_TIME_FROM_FILESYSTEM,
    PRIMARY_STREAM_SELECTED,

    /** A value was estimated rather than read (for example an MP3 duration from the bit rate). */
    ESTIMATED_VALUE,
    CANCELLED,
    INTERNAL_ERROR,
}

/**
 * A structured diagnostic. [message] never contains the file path, so diagnostics are safe to log.
 */
public data class MetadataDiagnostic(
    val code: DiagnosticCode,
    val severity: Severity,
    val message: String,
    val backend: String? = null,
    val field: String? = null,
    val causeClass: String? = null,
)

public enum class BackendStatus { USED, UNAVAILABLE, SKIPPED, FAILED, TIMED_OUT }

public data class BackendRun(
    val backend: String,
    val status: BackendStatus,
    val version: String?,
    val duration: Duration,
    val observationCount: Int,
    val message: String? = null,
)
