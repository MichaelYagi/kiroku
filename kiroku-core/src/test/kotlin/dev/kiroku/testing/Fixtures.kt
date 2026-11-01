package dev.kiroku.testing

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.kiroku.MediaMetadata
import dev.kiroku.MediaMetadataExtractor
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** Fixture locations and optional external oracles for tests. */
object Fixtures {
    val root: Path = Path.of(System.getProperty("kiroku.fixtures") ?: "fixtures").toAbsolutePath()
    val generated: Path = root.resolve("media/generated")
    val exiftoolSamples: Path = root.resolve("media/exiftool")
    val external: Path = root.resolve("external")

    /** One extractor for the whole test JVM. Extractors are thread-safe. */
    val extractor: MediaMetadataExtractor by lazy { MediaMetadata.create() }

    fun gen(name: String): Path = generated.resolve(name).also { require(it.exists()) { "missing fixture $it" } }

    fun et(name: String): Path = exiftoolSamples.resolve(name).also { require(it.exists()) { "missing fixture $it" } }

    /** An external CC0 sample. The test is skipped when `scripts/fetch-fixtures.sh` has not been run. */
    fun ext(name: String): Path {
        val p = external.resolve(name)
        assumeTrue(p.exists(), "external fixture $name not downloaded (run scripts/fetch-fixtures.sh)")
        return p
    }

    fun committed(): List<Path> =
        listOf(generated, exiftoolSamples).flatMap { dir -> Files.list(dir).use { s -> s.filter { it.isRegularFile() }.sorted().toList() } }

    fun all(): List<Path> = committed() +
        (
            if (Files.isDirectory(external)) {
                Files.list(external).use { s ->
                    s.filter { it.isRegularFile() && !it.name.endsWith(".part") }.sorted().toList()
                }
            } else {
                emptyList()
            }
            )

    /** `KIROKU_NO_EXTERNAL=1` hides all external tools, to prove the baseline passes without them. */
    val noExternal: Boolean = System.getenv("KIROKU_NO_EXTERNAL") == "1"

    /** The ExifTool executable: `-Dkiroku.exiftool`, `KIROKU_EXIFTOOL`, `.tools/exiftool-dist/exiftool`, or `PATH`. */
    val exiftool: Path? by lazy {
        if (noExternal) return@lazy null
        val candidates =
            listOfNotNull(
                System.getProperty("kiroku.exiftool"),
                System.getenv("KIROKU_EXIFTOOL"),
                root.parent?.resolve(".tools/exiftool-dist/exiftool")?.toString(),
            ).map { Path.of(it) } + (System.getenv("PATH") ?: "").split(java.io.File.pathSeparatorChar).map { Path.of(it, "exiftool") }
        candidates.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
    }

    val ffprobe: Path? by lazy {
        if (noExternal) return@lazy null
        val candidates =
            listOfNotNull(System.getProperty("kiroku.ffprobe"), System.getenv("KIROKU_FFPROBE")).map { Path.of(it) } +
                (System.getenv("PATH") ?: "").split(java.io.File.pathSeparatorChar).flatMap {
                    listOf(Path.of(it, "ffprobe"), Path.of(it, "ffprobe.exe"))
                }
        candidates.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
    }

    private val mapper = ObjectMapper()

    /** ExifTool output: `-j -n -G1 -a -u` with numeric values, one JSON object per file. */
    fun exiftoolJson(file: Path): JsonNode {
        val et = exiftool
        assumeTrue(et != null, "ExifTool not available")
        val args = listOf(et.toString(), "-j", "-n", "-G1", "-a", "-u", "-api", "largefilesupport=1", file.toAbsolutePath().toString())
        val p = ProcessBuilder(args).start()
        val out = p.inputStream.readAllBytes()
        p.errorStream.readAllBytes()
        check(p.waitFor(60, TimeUnit.SECONDS)) { "exiftool timed out" }
        return mapper.readTree(out)[0]
    }
}
