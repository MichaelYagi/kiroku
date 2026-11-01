package dev.kiroku.examples

import dev.kiroku.ExtractionDepth
import dev.kiroku.ExtractionOptions
import dev.kiroku.GpsPolicy
import dev.kiroku.MediaMetadata
import dev.kiroku.json.JsonOptions
import dev.kiroku.json.KirokuJson
import dev.kiroku.model.ValueFidelity
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/** Basic use: one extractor, reused; gallery for UI; full for provenance. */
fun basic(file: Path) {
    MediaMetadata.create().use { extractor ->
        val result = extractor.extract(file)
        val g = result.gallery
        println("${result.status} ${g.mediaKind} ${g.format}")
        println("camera: ${g.camera?.make} ${g.camera?.model}, lens: ${g.camera?.lensModel}")
        println("captured: ${g.capturedAt?.instant ?: g.capturedAt?.localDateTime} (tz known: ${g.capturedAt?.timezoneKnown})")
        println("display: ${g.dimensions?.displayWidth}x${g.dimensions?.displayHeight}, rotate ${g.orientation?.rotationDegrees}°")
        g.location?.let { println("gps: ${it.latitude}, ${it.longitude}") }
        println("observations: ${result.full.observations.size}")
    }
}

/** Every original EXIF tag with its numeric ID, TIFF type, decoded value and byte-exact location. */
fun dumpExif(file: Path) {
    MediaMetadata.create().use { extractor ->
        for (o in extractor.extract(file).full.observations) {
            if (o.groupPath.firstOrNull() != "EXIF" && o.groupPath.firstOrNull() != "MakerNotes") continue
            val loc = o.original?.takeIf { it.fidelity == ValueFidelity.BYTE_EXACT }?.let { "@${it.fileOffset}+${it.byteLength}" } ?: ""
            println(
                "${o.qualifiedName} [${o.tagIdHex}] ${o.nativeType?.name}x${o.nativeType?.count} = ${o.value} ${o.displayValue?.let {
                    "($it)"
                } ?: ""} $loc",
            )
        }
    }
}

/** Why a gallery field has its value: the chosen tag, the alternatives, and any conflict. */
fun explain(file: Path, field: String = "capturedAt") {
    MediaMetadata.create().use { extractor ->
        val p = extractor.extract(file).full.fieldProvenance[field] ?: return
        println("$field <- ${p.chosen?.qualifiedName} (${p.reason}); conflict=${p.conflict}")
        p.candidates.forEach { println("  ${if (it.accepted) "*" else " "} ${it.source.qualifiedName} = ${it.normalizedValue} ${it.rejectionReason ?: ""}") }
    }
}

/** Ingest a directory with bounded concurrency, privacy-safe gallery output, and per-file errors. */
fun ingest(files: List<Path>) {
    val options = ExtractionOptions(depth = ExtractionDepth.FAST, gpsPolicy = GpsPolicy.COARSE_GALLERY)
    MediaMetadata.create().use { extractor ->
        extractor.extractBatch(files.asSequence(), options).forEach { r ->
            println(KirokuJson.galleryToJson(r.gallery, JsonOptions()))
        }
    }
}

/** Coroutines: extractAsync honours cancellation. */
fun async(file: Path) = runBlocking {
    MediaMetadata.create().use { extractor -> println(extractor.extractAsync(file).gallery.camera) }
}

fun main(args: Array<String>) {
    val f = Path.of(args.firstOrNull() ?: error("usage: KotlinUsage <file>"))
    basic(f)
    explain(f)
}
