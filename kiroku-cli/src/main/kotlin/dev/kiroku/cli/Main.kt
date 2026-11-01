package dev.kiroku.cli

import dev.kiroku.BackendMode
import dev.kiroku.ExternalToolConfig
import dev.kiroku.ExtractionDepth
import dev.kiroku.ExtractionOptions
import dev.kiroku.GpsPolicy
import dev.kiroku.KirokuInfo
import dev.kiroku.MediaMetadata
import dev.kiroku.MetadataConfig
import dev.kiroku.MetadataResult
import dev.kiroku.json.JsonOptions
import dev.kiroku.json.KirokuJson
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.system.exitProcess

private const val USAGE = """kiroku - media metadata inspector (demo CLI for the Kiroku library)

Usage: kiroku [options] <file-or-directory>...

Output (default: gallery JSON per file, one JSON object per line):
  --full              full result: gallery, observations, provenance, diagnostics
  --summary           one human-readable line per file
  --diagnostics       only status, diagnostics and backend runs
  --capabilities      print the capability report and exit
  --pretty            pretty-print JSON
  --no-bytes          omit inline original bytes from --full output

Extraction:
  --fast | --deep     depth (default STANDARD; --deep enables ExifTool/ffprobe when installed)
  --exiftool PATH     ExifTool executable (default: PATH lookup)
  --ffprobe PATH      ffprobe executable (default: PATH lookup)
  --no-external       never run external tools
  --gps POLICY        INCLUDE | COARSE_GALLERY | EXCLUDE_GALLERY | EXCLUDE_ALL
  --checksum          compute SHA-256
  -r, --recursive     descend into directories
  -j N                parallel extractions (default: CPU count, max 8)
  --version"""

fun main(args: Array<String>) {
    exitProcess(Cli().run(args.toList()))
}

internal class Cli(private val out: java.io.PrintStream = System.out, private val err: java.io.PrintStream = System.err) {
    fun run(args: List<String>): Int {
        var mode = "gallery"
        var pretty = false
        var bytes = true
        var depth = ExtractionDepth.STANDARD
        var exiftool: Path? = null
        var ffprobe: Path? = null
        var external = true
        var gps = GpsPolicy.INCLUDE
        var checksum = false
        var recursive = false
        var parallel = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val inputs = ArrayList<Path>()
        val it = args.iterator()
        while (it.hasNext()) {
            when (val a = it.next()) {
                "-h", "--help" -> {
                    out.println(USAGE)
                    return 0
                }

                "--version" -> {
                    out.println("kiroku ${KirokuInfo.VERSION} (schema ${KirokuInfo.SCHEMA_VERSION})")
                    return 0
                }

                "--full" -> mode = "full"

                "--summary" -> mode = "summary"

                "--diagnostics" -> mode = "diagnostics"

                "--capabilities" -> mode = "capabilities"

                "--pretty" -> pretty = true

                "--no-bytes" -> bytes = false

                "--fast" -> depth = ExtractionDepth.FAST

                "--deep" -> depth = ExtractionDepth.DEEP

                "--exiftool" -> exiftool = Path.of(next(it, a) ?: return 2)

                "--ffprobe" -> ffprobe = Path.of(next(it, a) ?: return 2)

                "--no-external" -> external = false

                "--gps" -> gps = runCatching { GpsPolicy.valueOf(next(it, a) ?: return 2) }.getOrElse { return usage("invalid --gps value") }

                "--checksum" -> checksum = true

                "-r", "--recursive" -> recursive = true

                "-j" -> parallel = (next(it, a) ?: return 2).toIntOrNull()?.coerceAtLeast(1) ?: return usage("invalid -j value")

                else -> if (a.startsWith("-")) return usage("unknown option $a") else inputs.add(Path.of(a))
            }
        }
        val toolMode = if (external) BackendMode.DEEP_ONLY else BackendMode.DISABLED
        val config =
            MetadataConfig(
                exiftool = ExternalToolConfig(toolMode, exiftool),
                ffprobe = ExternalToolConfig(toolMode, ffprobe),
                batchParallelism = parallel,
            )
        val options = ExtractionOptions(depth = depth, gpsPolicy = gps, computeChecksum = checksum)
        val json = JsonOptions(pretty = pretty, includeOriginalBytes = bytes, includeFull = mode == "full")
        MediaMetadata.create(config).use { extractor ->
            if (mode == "capabilities") {
                out.println(KirokuJson.capabilitiesToJson(extractor.capabilities(), JsonOptions(pretty = true)))
                return 0
            }
            if (inputs.isEmpty()) return usage("no input files")
            val files = expand(inputs, recursive)
            var failures = 0
            for (r in extractor.extractBatch(files.asSequence(), options)) {
                if (r.status == dev.kiroku.ExtractionStatus.FAILED) failures++
                when (mode) {
                    "summary" -> out.println(summary(r))
                    "diagnostics" -> out.println(diagnostics(r))
                    "full" -> out.println(KirokuJson.toJson(r, json))
                    else -> out.println(KirokuJson.galleryToJson(r.gallery, json))
                }
            }
            return if (failures > 0) 1 else 0
        }
    }

    private fun next(it: Iterator<String>, opt: String): String? = if (it.hasNext()) {
        it.next()
    } else {
        err.println("missing value for $opt")
        null
    }

    private fun usage(msg: String): Int {
        err.println("kiroku: $msg\n")
        err.println(USAGE)
        return 2
    }

    private fun expand(inputs: List<Path>, recursive: Boolean): List<Path> = inputs.flatMap { p ->
        when {
            p.isDirectory() && recursive -> Files.walk(p).use { s -> s.filter { it.isRegularFile() }.sorted().toList() }
            p.isDirectory() -> Files.list(p).use { s -> s.filter { it.isRegularFile() }.sorted().toList() }
            else -> listOf(p)
        }
    }

    private fun summary(r: MetadataResult): String {
        val g = r.gallery
        val parts =
            listOfNotNull(
                g.file.name,
                r.status.name,
                "${g.mediaKind}/${g.format}",
                g.dimensions?.let { "${it.displayWidth}x${it.displayHeight}" },
                g.camera?.let { listOfNotNull(it.make, it.model).joinToString(" ").ifBlank { null } },
                g.camera?.lensModel,
                g.capturedAt?.let { it.instant?.toString() ?: "${it.localDateTime} (no tz)" },
                g.exposure?.let { e ->
                    listOfNotNull(
                        e.iso?.let {
                            "ISO$it"
                        },
                        e.apertureFNumber?.let {
                            "f/$it"
                        },
                        e.exposureTime?.let { "${it.reduced()}s" },
                        e.focalLengthMm?.let { "${it}mm" },
                    ).joinToString(" ").ifBlank { null }
                },
                g.location?.let { "gps=%.5f,%.5f".format(it.latitude, it.longitude) },
                g.video?.let { v ->
                    listOfNotNull(v.codec, v.duration?.let { "${it.toMillis() / 1000.0}s" }, v.frameRate?.let { "${it.reduced()}fps" }).joinToString(" ")
                },
                "obs=${r.full.observations.size}",
            )
        return parts.joinToString("  |  ")
    }

    private fun diagnostics(r: MetadataResult): String = buildString {
        append(r.gallery.file.name).append(": ").append(r.status)
        r.backendRuns.forEach {
            append("\n  backend ").append(it.backend).append(' ').append(it.status).append(
                it.version?.let { v ->
                    " v$v"
                } ?: "",
            ).append(" ").append(it.duration.toMillis()).append("ms ").append(it.observationCount).append(" obs")
        }
        r.diagnostics.forEach { append("\n  ").append(it.severity).append(' ').append(it.code).append(": ").append(it.message) }
    }
}
