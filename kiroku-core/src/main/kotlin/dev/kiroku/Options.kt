package dev.kiroku

import java.nio.file.Path
import java.time.Duration

public enum class ExtractionDepth {
    /** Native parsers only, structural metadata only. MakerNote IFD decoding, XMP and ICC details are skipped. Intended for ingest. */
    FAST,

    /** Native parsers with full decoding of all supported structures. The default. */
    STANDARD,

    /** STANDARD plus the optional external backends (ExifTool, ffprobe) when installed. */
    DEEP,
}

/** Privacy policy for location data. */
public enum class GpsPolicy {
    /** Include location in the gallery and full output. */
    INCLUDE,

    /** Round gallery coordinates to 2 decimal places (about 1.1 km). Full output is unchanged. */
    COARSE_GALLERY,

    /** `gallery.location` is null. Full output is unchanged. */
    EXCLUDE_GALLERY,

    /**
     * `gallery.location` is null, and GPS observations in full output have their values replaced with
     * `Null` and their original bytes omitted (`OmissionReason.REDACTED`). A REDACTED diagnostic is recorded.
     */
    EXCLUDE_ALL,
}

/**
 * Bounds on how much work and memory one extraction can use. Exceeding a limit truncates that
 * structure, records a `LIMIT_EXCEEDED` diagnostic, and makes the status at best `PARTIAL`.
 */
public data class ResourceLimits
@JvmOverloads
constructor(
    val maxFileSizeBytes: Long = Long.MAX_VALUE,
    val maxEntriesPerDirectory: Int = 8192,
    val maxDirectories: Int = 4096,
    val maxObservations: Int = 250_000,
    val maxNestingDepth: Int = 24,
    val maxStringLength: Int = 1 shl 20,
    val maxXmpBytes: Int = 32 shl 20,
    val maxBoxCount: Int = 200_000,
    val maxSubprocessOutputBytes: Long = 64L shl 20,
    val maxStderrBytes: Int = 64 shl 10,
) {
    public companion object {
        @JvmField public val DEFAULT: ResourceLimits = ResourceLimits()
    }
}

/**
 * Per-call options. The fields listed in `03-PUBLIC-API.md` come first, in the same order.
 * Java callers can use [builder].
 */
public data class ExtractionOptions
@JvmOverloads
constructor(
    val depth: ExtractionDepth = ExtractionDepth.STANDARD,
    /** Keep raw observations in `full`. If `false`, `full.observations` is empty, but gallery output is unaffected. */
    val includeRawTags: Boolean = true,
    /** Raise the inline byte-capture cap from [rawValueByteLimit] to [maxBinaryPayloadBytes] (thumbnails, previews, large MakerNotes). */
    val includeBinaryPayloads: Boolean = false,
    val includeFileSystemTimestamps: Boolean = true,
    val gpsPolicy: GpsPolicy = GpsPolicy.INCLUDE,
    /** The time limit for one file across all backends. Each external tool also has its own timeout. */
    val timeout: Duration = Duration.ofSeconds(30),
    /** Values up to this many bytes have their original bytes captured inline. */
    val rawValueByteLimit: Int = 64 * 1024,
    val maxBinaryPayloadBytes: Int = 16 * 1024 * 1024,
    /** Compute a SHA-256 checksum of the whole file. This reads every byte. */
    val computeChecksum: Boolean = false,
    /** Allow the filesystem modification time as a last-resort `capturedAt`, marked with source `FileSystem:ModifyDate`. */
    val fileSystemTimeAsCaptureFallback: Boolean = false,
    val limits: ResourceLimits = ResourceLimits.DEFAULT,
) {
    /** The effective cap on inline original bytes for a single value. */
    val effectiveByteCaptureLimit: Int get() = if (includeBinaryPayloads) maxBinaryPayloadBytes else rawValueByteLimit

    public fun toBuilder(): Builder = Builder(this)

    /** A Java-friendly builder. */
    public class Builder internal constructor(base: ExtractionOptions) {
        private var depth = base.depth
        private var includeRawTags = base.includeRawTags
        private var includeBinaryPayloads = base.includeBinaryPayloads
        private var includeFileSystemTimestamps = base.includeFileSystemTimestamps
        private var gpsPolicy = base.gpsPolicy
        private var timeout = base.timeout
        private var rawValueByteLimit = base.rawValueByteLimit
        private var maxBinaryPayloadBytes = base.maxBinaryPayloadBytes
        private var computeChecksum = base.computeChecksum
        private var fileSystemTimeAsCaptureFallback = base.fileSystemTimeAsCaptureFallback
        private var limits = base.limits

        public fun depth(value: ExtractionDepth): Builder = apply { depth = value }

        public fun includeRawTags(value: Boolean): Builder = apply { includeRawTags = value }

        public fun includeBinaryPayloads(value: Boolean): Builder = apply { includeBinaryPayloads = value }

        public fun includeFileSystemTimestamps(value: Boolean): Builder = apply { includeFileSystemTimestamps = value }

        public fun gpsPolicy(value: GpsPolicy): Builder = apply { gpsPolicy = value }

        public fun timeout(value: Duration): Builder = apply { timeout = value }

        public fun rawValueByteLimit(value: Int): Builder = apply { rawValueByteLimit = value }

        public fun maxBinaryPayloadBytes(value: Int): Builder = apply { maxBinaryPayloadBytes = value }

        public fun computeChecksum(value: Boolean): Builder = apply { computeChecksum = value }

        public fun fileSystemTimeAsCaptureFallback(value: Boolean): Builder = apply { fileSystemTimeAsCaptureFallback = value }

        public fun limits(value: ResourceLimits): Builder = apply { limits = value }

        public fun build(): ExtractionOptions = ExtractionOptions(
            depth, includeRawTags, includeBinaryPayloads, includeFileSystemTimestamps, gpsPolicy, timeout,
            rawValueByteLimit, maxBinaryPayloadBytes, computeChecksum, fileSystemTimeAsCaptureFallback, limits,
        )
    }

    init {
        require(rawValueByteLimit >= 0) { "rawValueByteLimit must be >= 0" }
        require(maxBinaryPayloadBytes >= 0) { "maxBinaryPayloadBytes must be >= 0" }
        require(!timeout.isNegative && !timeout.isZero) { "timeout must be positive" }
    }

    public companion object {
        @JvmField public val DEFAULT: ExtractionOptions = ExtractionOptions()

        @JvmStatic public fun builder(): Builder = Builder(DEFAULT)
    }
}

/** When an optional backend runs. See `09-DECISIONS.md` D2. */
public enum class BackendMode {
    /** Never run. */
    DISABLED,

    /** Run only at [ExtractionDepth.DEEP], when available. The default for external tools. */
    DEEP_ONLY,

    /** Run at every depth when available. */
    ALWAYS,

    /** Run at every depth. If it is unavailable or fails, the status becomes `PARTIAL`. */
    REQUIRED,
}

/** Configuration for an optional external executable (ExifTool or ffprobe). */
public data class ExternalToolConfig
@JvmOverloads
constructor(
    val mode: BackendMode = BackendMode.DEEP_ONLY,
    /** An explicit executable path. If `null`, the executable is looked up on `PATH`. */
    val executable: Path? = null,
    val timeout: Duration = Duration.ofSeconds(20),
    /** The maximum number of concurrent subprocesses for this tool. */
    val maxConcurrentProcesses: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
)

public enum class SymlinkPolicy {
    /** Follow symlinks, but enforce [MetadataConfig.allowedRoots] on the resolved target. */
    FOLLOW,

    /** Reject any input that is a symlink. */
    REJECT,
}

/**
 * Process-wide configuration for an extractor instance. Java callers can use [builder].
 */
public data class MetadataConfig
@JvmOverloads
constructor(
    val defaultOptions: ExtractionOptions = ExtractionOptions.DEFAULT,
    val exiftool: ExternalToolConfig = ExternalToolConfig(),
    val ffprobe: ExternalToolConfig = ExternalToolConfig(),
    /** When non-empty, inputs (after resolving symlinks) must be inside one of these directories. */
    val allowedRoots: List<Path> = emptyList(),
    val symlinkPolicy: SymlinkPolicy = SymlinkPolicy.FOLLOW,
    /** The maximum number of files extracted concurrently by `extractBatch`. */
    val batchParallelism: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8),
    /** Discover optional backends (`kiroku-exiftool`, `kiroku-ffprobe`) on the classpath with `ServiceLoader`. */
    val discoverBackends: Boolean = true,
    /** Additional backends registered explicitly. */
    val additionalBackends: List<dev.kiroku.spi.MetadataBackend> = emptyList(),
) {
    init {
        require(batchParallelism >= 1) { "batchParallelism must be >= 1" }
    }

    public fun toBuilder(): Builder = Builder(this)

    public class Builder internal constructor(base: MetadataConfig) {
        private var defaultOptions = base.defaultOptions
        private var exiftool = base.exiftool
        private var ffprobe = base.ffprobe
        private var allowedRoots = base.allowedRoots
        private var symlinkPolicy = base.symlinkPolicy
        private var batchParallelism = base.batchParallelism
        private var discoverBackends = base.discoverBackends
        private var additionalBackends = base.additionalBackends

        public fun defaultOptions(value: ExtractionOptions): Builder = apply { defaultOptions = value }

        public fun exiftool(value: ExternalToolConfig): Builder = apply { exiftool = value }

        public fun ffprobe(value: ExternalToolConfig): Builder = apply { ffprobe = value }

        public fun allowedRoots(value: List<Path>): Builder = apply { allowedRoots = value.toList() }

        public fun symlinkPolicy(value: SymlinkPolicy): Builder = apply { symlinkPolicy = value }

        public fun batchParallelism(value: Int): Builder = apply { batchParallelism = value }

        public fun discoverBackends(value: Boolean): Builder = apply { discoverBackends = value }

        public fun addBackend(value: dev.kiroku.spi.MetadataBackend): Builder = apply {
            additionalBackends = additionalBackends + value
        }

        public fun build(): MetadataConfig = MetadataConfig(
            defaultOptions,
            exiftool,
            ffprobe,
            allowedRoots,
            symlinkPolicy,
            batchParallelism,
            discoverBackends,
            additionalBackends,
        )
    }

    public companion object {
        @JvmStatic public fun builder(): Builder = Builder(MetadataConfig())
    }
}
