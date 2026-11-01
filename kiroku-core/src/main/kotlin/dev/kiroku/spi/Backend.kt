package dev.kiroku.spi

import dev.kiroku.BackendMode
import dev.kiroku.ExtractionOptions
import dev.kiroku.MetadataConfig
import dev.kiroku.model.BackendStatus
import dev.kiroku.model.FileProbe
import dev.kiroku.model.MediaFormat
import java.nio.file.Path

public enum class BackendKind { NATIVE, EXTERNAL_TOOL, LIBRARY }

/** Whether a backend can run in this environment. External tools are checked by running them. */
public data class BackendAvailability(val available: Boolean, val version: String?, val message: String? = null, val executable: Path? = null)

/**
 * A metadata source. Kiroku's native parsers are one backend. ExifTool and ffprobe are optional ones.
 *
 * Implementations must be thread-safe: one instance serves concurrent extractions.
 * Only the built-in native backend may emit `ValueFidelity.BYTE_EXACT`. The collector downgrades
 * byte-exact claims from any other backend to `BACKEND_RECONSTRUCTED`.
 */
public interface MetadataBackend : AutoCloseable {
    /** A stable id used in observations and backend runs, for example `exiftool`. */
    public val id: String
    public val kind: BackendKind

    /** The metadata families this backend can produce, for the capabilities report. */
    public val metadataFamilies: Set<String>

    /** When this backend runs, given the configuration. */
    public fun mode(config: MetadataConfig): BackendMode

    /** Checked once and cached by the engine. Must not throw. */
    public fun probeAvailability(): BackendAvailability

    /** Whether this backend is worth running for a detected format. */
    public fun supports(format: MediaFormat): Boolean

    /** Extracts metadata into [context]'s collector. Must honour [BackendContext.checkCancelled] and the deadline. */
    public fun extract(context: BackendContext): BackendOutcome

    override fun close() {}
}

/** Creates a backend from configuration. It is discovered with `java.util.ServiceLoader`. */
public interface MetadataBackendProvider {
    public fun create(config: MetadataConfig): MetadataBackend
}

/** The result of one backend run. Observations go to the collector, not here. */
public data class BackendOutcome(val status: BackendStatus, val version: String? = null, val message: String? = null) {
    public companion object {
        @JvmStatic public fun used(version: String? = null): BackendOutcome = BackendOutcome(BackendStatus.USED, version)
    }
}

/** Per-file context passed to a backend. */
public class BackendContext internal constructor(
    public val path: Path,
    public val probe: FileProbe,
    public val options: ExtractionOptions,
    public val collector: MetadataCollector,
    private val deadlineNanos: Long,
    private val cancelled: () -> Boolean,
) {
    /** Nanoseconds left before the per-file deadline. Can be negative. */
    public fun remainingNanos(): Long = deadlineNanos - System.nanoTime()

    /** Throws [java.util.concurrent.CancellationException] when the extraction is cancelled, or [ExtractionTimeoutException] at the deadline. */
    public fun checkCancelled() {
        if (cancelled()) throw java.util.concurrent.CancellationException("extraction cancelled")
        if (remainingNanos() <= 0) throw ExtractionTimeoutException("per-file timeout reached")
    }
}

public class ExtractionTimeoutException(message: String) : RuntimeException(message)
