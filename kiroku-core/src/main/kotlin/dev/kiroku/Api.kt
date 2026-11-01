package dev.kiroku

import dev.kiroku.internal.Engine
import dev.kiroku.model.BackendRun
import dev.kiroku.model.FullMetadata
import dev.kiroku.model.GalleryMetadata
import dev.kiroku.model.MediaFormat
import dev.kiroku.model.MediaKind
import dev.kiroku.model.MetadataDiagnostic
import dev.kiroku.spi.BackendKind
import dev.kiroku.spi.MetadataBackend
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * - `COMPLETE`: every applicable enabled backend succeeded and nothing was truncated.
 * - `PARTIAL`: usable data, but some backend failed or timed out, a `REQUIRED` backend was unavailable,
 *   or input was malformed or truncated.
 * - `UNSUPPORTED`: no parser for the detected format (for example an unknown signature).
 * - `FAILED`: no meaningful extraction (missing file, I/O error, rejected path).
 */
public enum class ExtractionStatus { COMPLETE, PARTIAL, UNSUPPORTED, FAILED }

/** The output of one extraction: the [gallery] projection and the [full] observations come from the same parse. */
public data class MetadataResult(
    val schemaVersion: String,
    val status: ExtractionStatus,
    val gallery: GalleryMetadata,
    val full: FullMetadata,
    val diagnostics: List<MetadataDiagnostic>,
    val backendRuns: List<BackendRun>,
) {
    public companion object {
        /** The version of the output schema (gallery + full + diagnostics). It changes on any incompatible change. */
        public const val SCHEMA_VERSION: String = "1.0.0"
    }
}

/**
 * Extracts metadata. Instances are thread-safe and should be reused. They cache backend availability
 * and may keep external tool processes running.
 *
 * [close] stops any external tool processes and releases batch thread pools. Extractions still
 * running at that point may fail with `BACKEND_FAILED`. Do not use the extractor after closing it.
 *
 * Cancellation: [extractAsync] honours coroutine cancellation. [extractFuture] honours
 * `CompletableFuture.cancel(true)`. A cancelled batch stops starting new files.
 */
public interface MediaMetadataExtractor : AutoCloseable {
    public fun extract(path: Path): MetadataResult = extract(path, ExtractionOptions.DEFAULT)

    public fun extract(path: Path, options: ExtractionOptions): MetadataResult

    public suspend fun extractAsync(path: Path, options: ExtractionOptions = ExtractionOptions.DEFAULT): MetadataResult

    /** For Java: an asynchronous extraction on the extractor's I/O pool. */
    public fun extractFuture(path: Path): CompletableFuture<MetadataResult> = extractFuture(path, ExtractionOptions.DEFAULT)

    public fun extractFuture(path: Path, options: ExtractionOptions): CompletableFuture<MetadataResult>

    /**
     * A lazy batch extraction with bounded concurrency (`MetadataConfig.batchParallelism`).
     * Results come back in input order. One file's failure never stops the batch: it gets a `FAILED` result.
     */
    public fun extractBatch(paths: Sequence<Path>): Sequence<MetadataResult> = extractBatch(paths, ExtractionOptions.DEFAULT)

    public fun extractBatch(paths: Sequence<Path>, options: ExtractionOptions): Sequence<MetadataResult>

    /** For Java: a batch iterator. Close it to stop early and release in-flight work. */
    public fun extractBatchIterator(paths: Iterable<Path>, options: ExtractionOptions): CloseableIterator<MetadataResult>

    /** Which backends, formats and metadata families are available here. */
    public fun capabilities(): Capabilities
}

public interface CloseableIterator<T> :
    Iterator<T>,
    AutoCloseable

/** The capability report: what can actually run here, not what an extension promises. */
public data class Capabilities(val schemaVersion: String, val backends: List<BackendCapability>, val formats: List<FormatCapability>)

public data class BackendCapability(
    val id: String,
    val kind: BackendKind,
    val mode: BackendMode,
    val available: Boolean,
    val version: String?,
    val message: String?,
    val metadataFamilies: Set<String>,
)

public data class FormatCapability(
    val format: MediaFormat,
    val mediaKind: MediaKind,
    val extensions: Set<String>,
    val mimeType: String?,
    /** Available backends that support this format, in run order. */
    val backends: List<String>,
    /** The metadata families the native parser decodes for this format. */
    val nativeFamilies: Set<String>,
    val limitations: List<String>,
)

/** The factory. */
public object MediaMetadata {
    @JvmStatic
    @JvmOverloads
    public fun create(config: MetadataConfig = MetadataConfig()): MediaMetadataExtractor = Engine(config, emptyList())

    /** Creates an extractor with an explicit backend list (no `ServiceLoader` discovery). Mainly for tests. */
    @JvmStatic
    public fun create(config: MetadataConfig, backends: List<MetadataBackend>): MediaMetadataExtractor = Engine(config.copy(discoverBackends = false), backends)
}
