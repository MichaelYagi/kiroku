package dev.kiroku.normalize

import dev.kiroku.model.DiagnosticCode
import dev.kiroku.model.FieldCandidate
import dev.kiroku.model.FieldProvenance
import dev.kiroku.model.MetadataDiagnostic
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.RawObservation
import dev.kiroku.model.Severity
import dev.kiroku.model.TagReference
import dev.kiroku.model.ValueFidelity
import dev.kiroku.spi.MetadataCollector

/** The outcome of converting one observation for a field: an accepted value, or a rejection reason. */
internal sealed interface Conversion<out T> {
    data class Ok<T>(val value: T, val note: String? = null) : Conversion<T>

    data class Rejected(val reason: String) : Conversion<Nothing>
}

internal fun <T> ok(v: T?, ifNull: String = "not convertible"): Conversion<T> = if (v == null) Conversion.Rejected(ifNull) else Conversion.Ok(v)

/** A source rule: which observations it matches, how it ranks, and how values convert. */
internal class Source<T>(val tagNames: Set<String>, val rank: Int, val group: (List<String>) -> Boolean, val convert: (RawObservation) -> Conversion<T>)

internal fun <T> src(rank: Int, group: (List<String>) -> Boolean, vararg names: String, convert: (RawObservation) -> Conversion<T>): Source<T> =
    Source(names.toSet(), rank, group, convert)

internal fun group(g0: String, vararg g1: String): (List<String>) -> Boolean = { gp -> gp.getOrNull(0) == g0 && (g1.isEmpty() || gp.getOrNull(1) in g1) }

internal fun groupPrefix(g0: String, g1Prefix: String): (List<String>) -> Boolean =
    { gp -> gp.getOrNull(0) == g0 && gp.getOrNull(1)?.startsWith(g1Prefix) == true }

/**
 * Resolves gallery fields from observations with explicit ranking, validity checks and conflict detection.
 * It is not last-writer-wins: the lowest-rank valid candidate wins. On equal rank, a native byte-exact
 * observation beats a backend-decoded one, and then the earlier observation wins.
 */
internal class Resolver(observations: List<RawObservation>) {
    private val byName: Map<String, List<RawObservation>> = observations.groupBy { it.tagName }
    val provenance = LinkedHashMap<String, FieldProvenance>()
    val diagnostics = ArrayList<MetadataDiagnostic>()

    /** Fields where a conflict is worth a diagnostic. */
    private val conflictDiagnosticFields =
        setOf(
            "capturedAt", "camera.make", "camera.model", "camera.lensModel", "location", "dimensions", "orientation",
            "exposure.iso", "exposure.exposureTimeSeconds", "exposure.apertureFNumber",
        )

    fun observations(names: Set<String>, group: (List<String>) -> Boolean): List<RawObservation> =
        names.flatMap { byName[it].orEmpty() }.filter { group(it.groupPath) }

    /**
     * [coarse] marks valid values that are less specific than a field needs (for example a year-only capture date).
     * They are ranked after every valid value that is not coarse, whatever their source rank, but stay listed as candidates.
     */
    fun <T : Any> resolve(
        field: String,
        sources: List<Source<T>>,
        toValue: (T) -> NormalizedValue,
        equivalent: (T, T) -> Boolean = { a, b -> a == b },
        coarse: (T) -> Boolean = { false },
    ): T? {
        data class C(val obs: RawObservation, val rank: Int, val conv: Conversion<T>)
        val cands = ArrayList<C>()
        val seen = HashSet<Int>()
        for (source in sources) {
            for (o in observations(source.tagNames, source.group)) {
                if (!seen.add(o.index)) continue
                val conv =
                    try {
                        source.convert(o)
                    } catch (e: RuntimeException) {
                        Conversion.Rejected("conversion error: ${e.javaClass.simpleName}")
                    }
                cands += C(o, source.rank, conv)
            }
        }
        if (cands.isEmpty()) return null
        val ordered =
            cands.sortedWith(
                compareBy<C> { c -> if ((c.conv as? Conversion.Ok<T>)?.value?.let(coarse) == true) 1 else 0 }
                    .thenBy { it.rank }
                    .thenBy { if (it.obs.backend == MetadataCollector.NATIVE_BACKEND_ID) 0 else 1 }
                    .thenBy { if (it.obs.fidelity == ValueFidelity.BYTE_EXACT) 0 else 1 }
                    .thenBy { it.obs.index },
            )
        val chosen = ordered.firstOrNull { it.conv is Conversion.Ok }
        val chosenValue = (chosen?.conv as? Conversion.Ok<T>)?.value
        val validOthers = ordered.filter { it !== chosen && it.conv is Conversion.Ok }
        val conflicting = chosenValue != null && validOthers.any { !equivalent(chosenValue, (it.conv as Conversion.Ok<T>).value) }
        provenance[field] =
            FieldProvenance(
                field = field,
                chosen = chosen?.let { ref(it.obs) },
                candidates =
                ordered.map { c ->
                    when (val cv = c.conv) {
                        is Conversion.Ok -> FieldCandidate(
                            ref(c.obs),
                            toValue(cv.value),
                            c.rank,
                            c === chosen,
                            if (c ===
                                chosen
                            ) {
                                null
                            } else {
                                "lower precedence"
                            },
                        )

                        is Conversion.Rejected -> FieldCandidate(ref(c.obs), c.obs.value, c.rank, false, cv.reason)
                    }
                },
                reason =
                when {
                    chosen == null -> "no valid candidate"
                    (chosen.conv as Conversion.Ok<T>).note != null -> "rank ${chosen.rank}: ${(chosen.conv as Conversion.Ok<T>).note}"
                    else -> "rank ${chosen.rank} (${chosen.obs.qualifiedName})"
                },
                conflict = conflicting,
            )
        if (conflicting && field in conflictDiagnosticFields) {
            val others = validOthers.filter {
                !equivalent(chosenValue!!, (it.conv as Conversion.Ok<T>).value)
            }.map { it.obs.qualifiedName }.distinct()
            diagnostics +=
                MetadataDiagnostic(
                    DiagnosticCode.FIELD_CONFLICT,
                    Severity.INFO,
                    "Field '$field': chose ${chosen!!.obs.qualifiedName}; non-equivalent alternatives from ${others.take(
                        5,
                    ).joinToString()}",
                    field = field,
                )
        }
        return chosenValue
    }

    /** Records provenance for a field resolved by custom logic. */
    fun record(field: String, chosen: RawObservation?, candidates: List<Pair<RawObservation, NormalizedValue>>, reason: String, conflict: Boolean = false) {
        provenance[field] =
            FieldProvenance(
                field,
                chosen?.let { ref(it) },
                candidates.map { (o, v) -> FieldCandidate(ref(o), v, 0, o === chosen, if (o === chosen) null else "alternative") },
                reason,
                conflict,
            )
    }

    companion object {
        fun ref(o: RawObservation): TagReference = TagReference(o.index, o.backend, o.qualifiedName, o.tagId, o.occurrence, o.streamIndex)
    }
}
