package dev.kiroku.exiftool

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import dev.kiroku.BackendMode
import dev.kiroku.ExternalToolConfig
import dev.kiroku.MetadataConfig
import dev.kiroku.model.BackendStatus
import dev.kiroku.model.ByteString
import dev.kiroku.model.DiagnosticCode
import dev.kiroku.model.MediaFormat
import dev.kiroku.model.NativeType
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.OriginalValue
import dev.kiroku.model.Severity
import dev.kiroku.model.TypeSystem
import dev.kiroku.model.ValueFidelity
import dev.kiroku.spi.BackendAvailability
import dev.kiroku.spi.BackendContext
import dev.kiroku.spi.BackendKind
import dev.kiroku.spi.BackendOutcome
import dev.kiroku.spi.MetadataBackend
import dev.kiroku.spi.MetadataBackendProvider
import dev.kiroku.spi.MetadataCollector
import dev.kiroku.spi.ProcessRunner
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Optional ExifTool backend (deep mode by default). Adds ExifTool's decoding of proprietary MakerNotes,
 * Composite tags and embedded/timed metadata to Kiroku's native observations.
 *
 * Fidelity: ExifTool values are `BACKEND_DECODED`; binary values fetched with `-b` (when
 * `includeBinaryPayloads` is set) are `BACKEND_RECONSTRUCTED`. ExifTool output is never byte-exact.
 *
 * ExifTool is not bundled. Install it separately and put it on `PATH`, or set [ExternalToolConfig.executable].
 */
public class ExifToolBackend(
    private val config: ExternalToolConfig,
    private val maxOutputBytes: Long = 64L shl 20,
    private val maxStderrBytes: Int = 64 shl 10,
) : MetadataBackend {
    override val id: String = ID
    override val kind: BackendKind = BackendKind.EXTERNAL_TOOL
    override val metadataFamilies: Set<String> =
        setOf("EXIF", "MakerNotes (decoded)", "XMP", "IPTC", "ICC_Profile", "QuickTime", "Composite", "File", "Embedded/timed metadata (-ee)")

    private val executable: Path? by lazy {
        ProcessRunner.usableExecutable(config.executable)
            ?: if (config.executable == null) ProcessRunner.findOnPath("exiftool") else null
    }
    private val permits = Semaphore(config.maxConcurrentProcesses.coerceAtLeast(1))
    private val idle = ConcurrentLinkedQueue<ExifToolSession>()
    private val all = ConcurrentLinkedQueue<ExifToolSession>()

    @Volatile private var closed = false

    override fun mode(config: MetadataConfig): BackendMode = this.config.mode

    override fun probeAvailability(): BackendAvailability {
        val exe =
            executable
                ?: return BackendAvailability(
                    false,
                    null,
                    if (config.executable !=
                        null
                    ) {
                        "configured ExifTool executable is not executable"
                    } else {
                        "exiftool not found on PATH"
                    },
                )
        return try {
            val r = ProcessRunner(4096, 4096).run(listOf(exe.toString(), "-ver"), Duration.ofSeconds(15))
            val v = r.stdout.toString(Charsets.UTF_8).trim()
            if (r.exitCode == 0 && v.matches(Regex("""\d+\.\d+.*"""))) {
                BackendAvailability(true, v, executable = exe)
            } else {
                BackendAvailability(false, null, "exiftool -ver failed (exit ${r.exitCode})", exe)
            }
        } catch (e: Exception) {
            BackendAvailability(false, null, "cannot run exiftool: ${e.javaClass.simpleName}", exe)
        }
    }

    override fun supports(format: MediaFormat): Boolean = format != MediaFormat.UNKNOWN

    override fun extract(context: BackendContext): BackendOutcome {
        val exe = executable ?: return BackendOutcome(BackendStatus.FAILED, null, "executable missing")
        val pathText = context.path.toAbsolutePath().toString()
        if (pathText.any { it == '\n' || it == '\r' } || pathText.startsWith("-")) {
            context.collector.diagnostic(DiagnosticCode.PATH_NOT_ALLOWED, Severity.WARNING, "Path cannot be passed safely to ExifTool")
            return BackendOutcome(BackendStatus.SKIPPED, null, "unsafe path")
        }
        val args =
            buildList {
                // -G0:1:4: ExifTool's JSON writer drops duplicate tags unless family-4 "CopyN" groups make the keys unique.
                addAll(listOf("-j", "-l", "-G0:1:4", "-a", "-u", "-D", "-struct", "-charset", "filename=utf8", "-api", "LargeFileSupport=1"))
                if (context.options.depth == dev.kiroku.ExtractionDepth.DEEP) add("-ee")
                if (context.options.includeBinaryPayloads) add("-b")
                // No "--": in -stay_open argfiles it would turn the following -execute into a file name. The path is
                // canonical and absolute, and paths starting with '-' are rejected above, so it cannot be read as an option.
                add(pathText)
            }
        val timeoutNanos = minOf(config.timeout.toNanos(), context.remainingNanos()).coerceAtLeast(1_000_000)
        if (!permits.tryAcquire(timeoutNanos, TimeUnit.NANOSECONDS)) return BackendOutcome(BackendStatus.TIMED_OUT, null, "no free ExifTool process")
        try {
            check(!closed) { "backend closed" }
            val session = idle.poll() ?: ExifToolSession(exe, maxOutputBytes, maxStderrBytes).also { all += it }
            val response =
                try {
                    session.execute(args, timeoutNanos) {
                        try {
                            context.checkCancelled()
                            false
                        } catch (_: Exception) {
                            true
                        }
                    }
                } finally {
                    if (session.broken) {
                        all.remove(session)
                        session.close()
                    } else {
                        idle.offer(session)
                    }
                }
            if (response.stderr.isNotBlank()) {
                for (line in response.stderr.lines().filter { it.isNotBlank() }.take(20)) {
                    context.collector.diagnostic(
                        DiagnosticCode.BACKEND_FAILED,
                        if (line.startsWith("Warning")) Severity.INFO else Severity.WARNING,
                        "exiftool: ${sanitize(line, pathText)}",
                    )
                }
            }
            if (response.timedOut) return BackendOutcome(BackendStatus.TIMED_OUT, null, "ExifTool timed out")
            if (response.cancelled) return BackendOutcome(BackendStatus.FAILED, null, "cancelled")
            if (response.truncated) {
                context.collector.diagnostic(DiagnosticCode.OUTPUT_TRUNCATED, Severity.WARNING, "ExifTool output exceeded $maxOutputBytes bytes; not parsed")
                context.collector.markTruncated()
                return BackendOutcome(BackendStatus.FAILED, null, "output truncated")
            }
            val version = parse(response.stdout, context.collector)
            return BackendOutcome.used(version)
        } finally {
            permits.release()
        }
    }

    private fun sanitize(line: String, path: String): String = line.replace(path, "<file>").take(300)

    /** Parses `-j -l -G0:1` output with a streaming parser, which keeps duplicate keys (`-a`). Returns the ExifTool version. */
    internal fun parse(json: ByteArray, collector: MetadataCollector): String? {
        val factory =
            JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(64 shl 20).build())
                .build()
        var version: String? = null
        factory.createParser(json).use { p ->
            if (p.nextToken() != JsonToken.START_ARRAY) return null
            if (p.nextToken() != JsonToken.START_OBJECT) return null
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                val key = p.currentName()
                p.nextToken()
                if (key == "SourceFile") {
                    p.skipChildren()
                    continue
                }
                // Family-4 "CopyN" marks the Nth duplicate. It is folded into Kiroku's occurrence counter, so the
                // group path stays ExifTool family 0:1 (and lines up with native groups).
                val parts = key.split(':').filterNot { COPY.matches(it) }
                val tagName = parts.last()
                val groupPath = parts.dropLast(1).ifEmpty { listOf("ExifTool") }
                // Never echo directory names (privacy); the file name is enough.
                if (groupPath.getOrNull(1) == "System" && tagName == "Directory") {
                    p.skipChildren()
                    continue
                }
                if (p.currentToken() != JsonToken.START_OBJECT) {
                    // Without -l a value is bare. Tolerate it.
                    collector.observe(
                        groupPath,
                        tagName,
                        readValue(p, 0),
                        fidelity = ValueFidelity.BACKEND_DECODED,
                        nativeType = NativeType(TypeSystem.EXIFTOOL, "value"),
                    )
                    continue
                }
                var id: Long? = null
                var desc: String? = null
                var value: NormalizedValue? = null
                var num: NormalizedValue? = null
                var rawBinary: ByteString? = null
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    val f = p.currentName()
                    p.nextToken()
                    when (f) {
                        "id" -> id = if (p.currentToken() == JsonToken.VALUE_NUMBER_INT) p.longValue else p.text.toLongOrNull() ?: p.text.let { fourCC(it) }

                        "desc" -> desc = p.text

                        "val" -> {
                            if (p.currentToken() == JsonToken.VALUE_STRING && p.text.startsWith("base64:")) {
                                rawBinary = ByteString.fromBase64(p.text.removePrefix("base64:"))
                                value = NormalizedValue.Binary(rawBinary.size.toLong())
                            } else {
                                value = readValue(p, 0)
                            }
                        }

                        "num" -> num = readValue(p, 0)

                        else -> p.skipChildren()
                    }
                }
                val display = (value as? NormalizedValue.Text)?.value?.takeIf { num != null }
                val v = num ?: value ?: NormalizedValue.Null
                val binaryPlaceholder = (value as? NormalizedValue.Text)?.value?.let { BINARY.matchEntire(it) }?.groupValues?.get(1)?.toLongOrNull()
                val finalValue = if (binaryPlaceholder != null) NormalizedValue.Binary(binaryPlaceholder) else v
                if (groupPath == listOf("ExifTool", "ExifTool") && tagName == "ExifToolVersion") version = finalValue.toString()
                if (tagName == "Warning" || tagName == "Error") {
                    collector.diagnostic(DiagnosticCode.MALFORMED, Severity.INFO, "exiftool ${tagName.lowercase()}: ${finalValue.toString().take(200)}")
                }
                val track = groupPath.getOrNull(1)?.let { TRACK.matchEntire(it) }?.groupValues?.get(1)?.toIntOrNull()
                collector.observe(
                    groupPath = groupPath,
                    tagName = tagName,
                    value = finalValue,
                    tagId = id,
                    fidelity = if (rawBinary != null) ValueFidelity.BACKEND_RECONSTRUCTED else ValueFidelity.BACKEND_DECODED,
                    original = rawBinary?.let { OriginalValue(ValueFidelity.BACKEND_RECONSTRUCTED, it, null, it.size.toLong()) },
                    nativeType = NativeType(TypeSystem.EXIFTOOL, if (num != null) "num" else "val"),
                    displayValue = display ?: if (binaryPlaceholder != null) (value as NormalizedValue.Text).value else null,
                    namespace = desc,
                    known = !tagName.contains("_0x") && !tagName.startsWith("Unknown"),
                    streamIndex = track?.minus(1),
                )
            }
        }
        return version
    }

    private fun readValue(p: JsonParser, depth: Int): NormalizedValue = when (p.currentToken()) {
        JsonToken.VALUE_STRING -> NormalizedValue.Text(p.text)

        JsonToken.VALUE_NUMBER_INT -> if (p.numberType ==
            JsonParser.NumberType.BIG_INTEGER
        ) {
            NormalizedValue.Text(p.text)
        } else {
            NormalizedValue.Integer(p.longValue)
        }

        JsonToken.VALUE_NUMBER_FLOAT -> NormalizedValue.Decimal(p.doubleValue)

        JsonToken.VALUE_TRUE -> NormalizedValue.Bool(true)

        JsonToken.VALUE_FALSE -> NormalizedValue.Bool(false)

        JsonToken.VALUE_NULL -> NormalizedValue.Null

        JsonToken.START_ARRAY -> {
            val items = ArrayList<NormalizedValue>()
            while (p.nextToken() !=
                JsonToken.END_ARRAY
            ) {
                items += if (depth > 32) NormalizedValue.Null.also { p.skipChildren() } else readValue(p, depth + 1)
            }
            NormalizedValue.ListValue(items)
        }

        JsonToken.START_OBJECT -> {
            val fields = LinkedHashMap<String, NormalizedValue>()
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                val k = p.currentName()
                p.nextToken()
                fields[k] = if (depth > 32) NormalizedValue.Null.also { p.skipChildren() } else readValue(p, depth + 1)
            }
            NormalizedValue.ObjectValue(fields)
        }

        else -> {
            p.skipChildren()
            NormalizedValue.Null
        }
    }

    private fun fourCC(s: String): Long? {
        if (s.length != 4) return null
        var v = 0L
        for (c in s) {
            if (c.code > 0xFF) return null
            v = (v shl 8) or c.code.toLong()
        }
        return v
    }

    override fun close() {
        closed = true
        while (true) {
            val s = all.poll() ?: break
            s.close()
        }
        idle.clear()
    }

    public companion object {
        public const val ID: String = "exiftool"
        private val BINARY = Regex("""\(Binary data (\d+) bytes.*\)""")
        private val TRACK = Regex("""Track(\d+)""")
        private val COPY = Regex("""Copy\d+""")
    }
}

/** `ServiceLoader` provider: adding `kiroku-exiftool` to the classpath registers the backend. */
public class ExifToolBackendProvider : MetadataBackendProvider {
    override fun create(config: MetadataConfig): MetadataBackend =
        ExifToolBackend(config.exiftool, config.defaultOptions.limits.maxSubprocessOutputBytes, config.defaultOptions.limits.maxStderrBytes)
}
