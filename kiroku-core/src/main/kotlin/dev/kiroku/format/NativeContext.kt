package dev.kiroku.format

import dev.kiroku.ExtractionDepth
import dev.kiroku.ExtractionOptions
import dev.kiroku.io.ByteSource
import dev.kiroku.model.ByteOrderMark
import dev.kiroku.model.ByteString
import dev.kiroku.model.DiagnosticCode
import dev.kiroku.model.NativeType
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.OmissionReason
import dev.kiroku.model.OriginalValue
import dev.kiroku.model.RawObservation
import dev.kiroku.model.Severity
import dev.kiroku.model.ValueFidelity
import dev.kiroku.spi.BackendContext
import dev.kiroku.spi.MetadataCollector

/** Raised by native parsers when a structural limit is exceeded. The parser stops descending. */
internal class LimitExceededException(message: String) : RuntimeException(message)

/**
 * Per-file state shared by Kiroku's native parsers: the collector, the options, limit counters and
 * original-value capture.
 */
internal class NativeContext(val collector: MetadataCollector, val options: ExtractionOptions, private val backendContext: BackendContext?) {
    val limits = options.limits
    val depth = options.depth
    val deep get() = depth == ExtractionDepth.DEEP
    val fast get() = depth == ExtractionDepth.FAST

    private var boxCounter = 0
    private val reportedOnce = HashSet<String>()

    /** Facts found by one parser and used by another (for example `Make`, used to detect the MakerNote vendor). */
    val facts = HashMap<String, String>()

    fun checkCancelled() {
        backendContext?.checkCancelled()
    }

    fun countBox() {
        if (++boxCounter > limits.maxBoxCount) throw LimitExceededException("box/chunk/element count limit ${limits.maxBoxCount} exceeded")
        if (boxCounter and 0xFF == 0) checkCancelled()
    }

    /**
     * Builds the [OriginalValue] for [length] bytes at [offset] in [source].
     * The result is `BYTE_EXACT` only when [source] is file-backed.
     */
    fun original(source: ByteSource, offset: Long, length: Long, order: ByteOrderMark? = null): OriginalValue {
        val abs = source.absolute(offset)
        val fidelity = if (abs != null) ValueFidelity.BYTE_EXACT else ValueFidelity.BACKEND_RECONSTRUCTED
        if (!source.contains(offset, length)) {
            return OriginalValue(fidelity, null, abs, length, order, OmissionReason.OUT_OF_BOUNDS)
        }
        val cap = options.effectiveByteCaptureLimit
        if (length > cap) return OriginalValue(fidelity, null, abs, length, order, OmissionReason.SIZE_LIMIT)
        val bytes = source.read(offset, length.toInt())
        return OriginalValue(fidelity, ByteString.wrap(bytes), abs, length, order, null)
    }

    /** Like [original], for bytes the caller already holds (they must equal the source bytes at [offset]). */
    fun originalOf(bytes: ByteArray, source: ByteSource, offset: Long, order: ByteOrderMark? = null): OriginalValue {
        val abs = source.absolute(offset)
        val fidelity = if (abs != null) ValueFidelity.BYTE_EXACT else ValueFidelity.BACKEND_RECONSTRUCTED
        val cap = options.effectiveByteCaptureLimit
        return if (bytes.size > cap) {
            OriginalValue(fidelity, null, abs, bytes.size.toLong(), order, OmissionReason.SIZE_LIMIT)
        } else {
            OriginalValue(fidelity, ByteString.copyOf(bytes), abs, bytes.size.toLong(), order, null)
        }
    }

    fun observe(
        groupPath: List<String>,
        tagName: String,
        value: NormalizedValue,
        tagId: Long? = null,
        original: OriginalValue? = null,
        nativeType: NativeType? = null,
        displayValue: String? = null,
        unit: String? = null,
        namespace: String? = null,
        known: Boolean = true,
        streamIndex: Int? = null,
        sourcePath: String? = null,
    ): RawObservation? = collector.observe(
        groupPath = groupPath,
        tagName = tagName,
        value = value,
        tagId = tagId,
        fidelity = original?.fidelity ?: ValueFidelity.BACKEND_DECODED,
        original = original,
        nativeType = nativeType,
        displayValue = displayValue ?: readable(groupPath, tagName, value),
        unit = unit,
        namespace = namespace,
        known = known,
        streamIndex = streamIndex,
        sourcePath = sourcePath,
    )

    /** Readable forms of scaled vendor MakerNote values; the stored value is kept as is. */
    private fun readable(groupPath: List<String>, tagName: String, value: NormalizedValue): String? = if (groupPath.size >= 2 && groupPath[0] == "MakerNotes") {
        dev.kiroku.format.tiff.MakerNoteConv.display(groupPath[1], tagName, value, facts["model"])
    } else {
        null
    }

    fun warn(code: DiagnosticCode, message: String, cause: Throwable? = null, severity: Severity = Severity.WARNING) {
        collector.diagnostic(code, severity, message, cause = cause)
        // Informational structure notes do not make the result PARTIAL; real truncation or corruption does.
        if (severity != Severity.INFO &&
            (code == DiagnosticCode.TRUNCATED || code == DiagnosticCode.MALFORMED || code == DiagnosticCode.LIMIT_EXCEEDED)
        ) {
            collector.markTruncated()
        }
    }

    /** Like [warn], but reported at most once per [key]. */
    fun warnOnce(key: String, code: DiagnosticCode, message: String, cause: Throwable? = null, severity: Severity = Severity.WARNING) {
        if (reportedOnce.add(key)) warn(code, message, cause, severity)
    }

    /** Truncates a string to the configured maximum length. */
    fun clip(s: String): String {
        if (s.length <= limits.maxStringLength) return s
        warnOnce("clip", DiagnosticCode.LIMIT_EXCEEDED, "String value truncated to ${limits.maxStringLength} characters")
        return s.substring(0, limits.maxStringLength)
    }
}
