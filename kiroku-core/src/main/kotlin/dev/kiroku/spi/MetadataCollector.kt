package dev.kiroku.spi

import dev.kiroku.model.ByteOrderMark
import dev.kiroku.model.ChapterInfo
import dev.kiroku.model.ContainerInfo
import dev.kiroku.model.DiagnosticCode
import dev.kiroku.model.DirectoryInfo
import dev.kiroku.model.DirectoryKind
import dev.kiroku.model.EmbeddedImageInfo
import dev.kiroku.model.MetadataDiagnostic
import dev.kiroku.model.NativeType
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.OriginalValue
import dev.kiroku.model.RawObservation
import dev.kiroku.model.Severity
import dev.kiroku.model.StreamInfo
import dev.kiroku.model.ValueFidelity

/** Shared, single-file accumulation state. It is used by one extraction at a time. */
internal class ExtractionState(val maxObservations: Int, val maxDirectories: Int) {
    val observations = ArrayList<RawObservation>()
    val directories = ArrayList<DirectoryInfo>()
    val streams = ArrayList<StreamInfo>()
    val chapters = ArrayList<ChapterInfo>()
    val embeddedImages = ArrayList<EmbeddedImageInfo>()
    val diagnostics = ArrayList<MetadataDiagnostic>()
    val containers = ArrayList<ContainerInfo>()
    val occurrences = HashMap<String, Int>()
    var truncated = false
    private var observationLimitReported = false
    private var directoryLimitReported = false

    fun canAddObservation(backend: String): Boolean {
        if (observations.size < maxObservations) return true
        truncated = true
        if (!observationLimitReported) {
            observationLimitReported = true
            diagnostics +=
                MetadataDiagnostic(
                    DiagnosticCode.LIMIT_EXCEEDED,
                    Severity.WARNING,
                    "Observation limit ($maxObservations) reached; further observations dropped",
                    backend,
                )
        }
        return false
    }

    fun canAddDirectory(backend: String): Boolean {
        if (directories.size < maxDirectories) return true
        truncated = true
        if (!directoryLimitReported) {
            directoryLimitReported = true
            diagnostics +=
                MetadataDiagnostic(
                    DiagnosticCode.LIMIT_EXCEEDED,
                    Severity.WARNING,
                    "Directory limit ($maxDirectories) reached",
                    backend,
                )
        }
        return false
    }
}

/**
 * The sink a backend writes into. It assigns [RawObservation.occurrence] and enforces limits.
 * Only the `native` backend may emit [ValueFidelity.BYTE_EXACT].
 */
public class MetadataCollector internal constructor(public val backend: String, private val state: ExtractionState) {
    private val mayClaimByteExact = backend == NATIVE_BACKEND_ID
    private var byteExactDowngradeReported = false

    /** `true` once a limit truncated output for this file. */
    public val truncated: Boolean get() = state.truncated

    /** The number of observations so far (all backends). */
    public val observationCount: Int get() = state.observations.size

    internal var ownObservationCount: Int = 0
        private set

    /**
     * Records an observation. Returns `null` when the observation limit was reached.
     *
     * [occurrence] is computed from backend, group path and tag (ID if present, otherwise name).
     */
    @JvmOverloads
    public fun observe(
        groupPath: List<String>,
        tagName: String,
        value: NormalizedValue,
        tagId: Long? = null,
        fidelity: ValueFidelity = ValueFidelity.BACKEND_DECODED,
        original: OriginalValue? = null,
        nativeType: NativeType? = null,
        displayValue: String? = null,
        unit: String? = null,
        namespace: String? = null,
        known: Boolean = true,
        streamIndex: Int? = null,
        sourcePath: String? = null,
    ): RawObservation? {
        if (!state.canAddObservation(backend)) return null
        var fid = fidelity
        var orig = original
        if (!mayClaimByteExact && (fid == ValueFidelity.BYTE_EXACT || orig?.fidelity == ValueFidelity.BYTE_EXACT)) {
            fid = ValueFidelity.BACKEND_RECONSTRUCTED
            orig = orig?.copy(fidelity = ValueFidelity.BACKEND_RECONSTRUCTED)
            if (!byteExactDowngradeReported) {
                byteExactDowngradeReported = true
                diagnostic(
                    DiagnosticCode.INVALID_VALUE,
                    Severity.INFO,
                    "Backend '$backend' claimed BYTE_EXACT fidelity; downgraded to BACKEND_RECONSTRUCTED",
                )
            }
        }
        val key = buildString {
            append(backend).append('|').append(streamIndex ?: -1).append('|')
            groupPath.joinTo(this, ":")
            append('|').append(tagId?.toString() ?: tagName)
        }
        val occ = state.occurrences.merge(key, 1, Int::plus)!! - 1
        val obs =
            RawObservation(
                index = state.observations.size,
                backend = backend,
                groupPath = groupPath,
                tagId = tagId,
                tagName = tagName,
                namespace = namespace,
                known = known,
                nativeType = nativeType,
                value = value,
                displayValue = displayValue,
                unit = unit,
                streamIndex = streamIndex,
                occurrence = occ,
                sourcePath = sourcePath,
                fidelity = fid,
                original = orig,
            )
        state.observations += obs
        ownObservationCount++
        return obs
    }

    @JvmOverloads
    public fun directory(
        path: List<String>,
        kind: DirectoryKind,
        fileOffset: Long? = null,
        length: Long? = null,
        byteOrder: ByteOrderMark? = null,
        entryCount: Int? = null,
        parsed: Boolean = true,
        note: String? = null,
    ) {
        if (!state.canAddDirectory(backend)) return
        state.directories += DirectoryInfo(backend, path, kind, fileOffset, length, byteOrder, entryCount, parsed, note)
    }

    public fun stream(stream: StreamInfo) {
        state.streams += stream.copy(backend = backend)
    }

    public fun chapter(chapter: ChapterInfo) {
        state.chapters += chapter.copy(backend = backend)
    }

    public fun embeddedImage(image: EmbeddedImageInfo) {
        state.embeddedImages += image.copy(index = state.embeddedImages.size, backend = backend)
    }

    public fun container(info: ContainerInfo) {
        state.containers += info
    }

    @JvmOverloads
    public fun diagnostic(code: DiagnosticCode, severity: Severity, message: String, field: String? = null, cause: Throwable? = null) {
        if (state.diagnostics.size >= MAX_DIAGNOSTICS) return
        state.diagnostics += MetadataDiagnostic(code, severity, message, backend, field, cause?.javaClass?.name)
    }

    /** Marks the output as truncated (for example after a parser hit a structural limit). The result can be at best `PARTIAL`. */
    public fun markTruncated() {
        state.truncated = true
    }

    public companion object {
        public const val NATIVE_BACKEND_ID: String = "native"
        private const val MAX_DIAGNOSTICS = 2000
    }
}
