package dev.kiroku.internal

import dev.kiroku.BackendCapability
import dev.kiroku.BackendMode
import dev.kiroku.Capabilities
import dev.kiroku.CloseableIterator
import dev.kiroku.ExtractionDepth
import dev.kiroku.ExtractionOptions
import dev.kiroku.ExtractionStatus
import dev.kiroku.FormatCapability
import dev.kiroku.GpsPolicy
import dev.kiroku.MediaMetadataExtractor
import dev.kiroku.MetadataConfig
import dev.kiroku.MetadataResult
import dev.kiroku.SymlinkPolicy
import dev.kiroku.detect.Detection
import dev.kiroku.detect.FormatDetector
import dev.kiroku.io.Bytes
import dev.kiroku.io.FileSource
import dev.kiroku.model.BackendRun
import dev.kiroku.model.BackendStatus
import dev.kiroku.model.ContainerInfo
import dev.kiroku.model.DiagnosticCode
import dev.kiroku.model.FileProbe
import dev.kiroku.model.FullMetadata
import dev.kiroku.model.GalleryFile
import dev.kiroku.model.GalleryMetadata
import dev.kiroku.model.MediaFormat
import dev.kiroku.model.MediaKind
import dev.kiroku.model.MetadataDiagnostic
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.OmissionReason
import dev.kiroku.model.RawObservation
import dev.kiroku.model.Severity
import dev.kiroku.normalize.GalleryProjector
import dev.kiroku.spi.BackendAvailability
import dev.kiroku.spi.BackendContext
import dev.kiroku.spi.ExtractionState
import dev.kiroku.spi.ExtractionTimeoutException
import dev.kiroku.spi.MetadataBackend
import dev.kiroku.spi.MetadataBackendProvider
import dev.kiroku.spi.MetadataCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Duration
import java.util.ArrayDeque
import java.util.ServiceLoader
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/** Daemon threads, so an extractor that is never closed cannot keep the JVM alive. Java 17 compatible (no virtual threads). */
internal fun daemonThreads(prefix: String): java.util.concurrent.ThreadFactory {
    val n = java.util.concurrent.atomic.AtomicInteger()
    return java.util.concurrent.ThreadFactory { r -> Thread(r, "$prefix-${n.incrementAndGet()}").apply { isDaemon = true } }
}

/** The default [MediaMetadataExtractor]. */
private val LOGGER: System.Logger = System.getLogger("dev.kiroku")

internal class Engine(private val config: MetadataConfig, explicitBackends: List<MetadataBackend>) : MediaMetadataExtractor {
    private val backends: List<MetadataBackend>
    private val availability = ConcurrentHashMap<String, BackendAvailability>()
    private val closed = AtomicBoolean(false)

    @Volatile private var executorRef: ExecutorService? = null
    private val executor: ExecutorService
        get() = executorRef ?: synchronized(this) { executorRef ?: Executors.newCachedThreadPool(daemonThreads("kiroku-extract")).also { executorRef = it } }
    private val allowedRoots: List<Path> = config.allowedRoots.map {
        it.toAbsolutePath().normalize().let { p -> runCatching { p.toRealPath() }.getOrDefault(p) }
    }

    init {
        val list = ArrayList<MetadataBackend>()
        list += NativeBackend()
        list += explicitBackends
        list += config.additionalBackends
        if (config.discoverBackends) {
            try {
                ServiceLoader.load(MetadataBackendProvider::class.java, javaClass.classLoader).forEach { provider ->
                    val b = provider.create(config)
                    if (list.none { it.id == b.id }) list += b else b.close()
                }
            } catch (e: java.util.ServiceConfigurationError) {
                // A broken provider must not stop the extractor; it is reported in capabilities.
                availability["serviceloader"] = BackendAvailability(false, null, "ServiceLoader error: ${e.message}")
            }
        }
        backends = list.distinctBy { it.id }
    }

    private fun availabilityOf(b: MetadataBackend): BackendAvailability = availability.computeIfAbsent(b.id) {
        try {
            b.probeAvailability()
        } catch (e: Exception) {
            BackendAvailability(false, null, "availability check failed: ${e.javaClass.simpleName}")
        }
    }

    // ---- Public API ----

    override fun extract(path: Path, options: ExtractionOptions): MetadataResult = run(path, options) { false }

    override suspend fun extractAsync(path: Path, options: ExtractionOptions): MetadataResult {
        val job = currentCoroutineContext()[Job]
        return withContext(Dispatchers.IO) {
            val result = run(path, options) { job?.isCancelled == true }
            if (job?.isCancelled == true) throw kotlinx.coroutines.CancellationException("extraction cancelled")
            result
        }
    }

    override fun extractFuture(path: Path, options: ExtractionOptions): CompletableFuture<MetadataResult> {
        checkOpen()
        val future = CompletableFuture<MetadataResult>()
        executor.submit {
            try {
                future.complete(run(path, options) { future.isCancelled })
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }
        return future
    }

    override fun extractBatch(paths: Sequence<Path>, options: ExtractionOptions): Sequence<MetadataResult> =
        Sequence { BatchIterator(paths.iterator(), options) }

    override fun extractBatchIterator(paths: Iterable<Path>, options: ExtractionOptions): CloseableIterator<MetadataResult> =
        BatchIterator(paths.iterator(), options)

    override fun capabilities(): Capabilities {
        val backendCaps =
            backends.map { b ->
                val a = availabilityOf(b)
                BackendCapability(b.id, b.kind, b.mode(config), a.available, a.version, a.message, b.metadataFamilies)
            }
        val formats =
            MediaFormat.entries.filter { it != MediaFormat.UNKNOWN }.map { f ->
                FormatCapability(
                    f,
                    f.defaultKind,
                    f.extensions,
                    f.mimeType,
                    backends.filter {
                        it.supports(f) && availabilityOf(it).available && it.mode(config) != BackendMode.DISABLED
                    }.map { it.id },
                    FormatNotes.nativeFamilies(f),
                    FormatNotes.limitations(f),
                )
            }
        return Capabilities(MetadataResult.SCHEMA_VERSION, backendCaps, formats)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        backends.forEach { runCatching { it.close() } }
        executorRef?.shutdownNow()
    }

    private fun checkOpen() = check(!closed.get()) { "extractor is closed" }

    // ---- Batch ----

    private inner class BatchIterator(private val source: Iterator<Path>, private val options: ExtractionOptions) : CloseableIterator<MetadataResult> {
        private val window = ArrayDeque<Pair<Path, Future<MetadataResult>>>()
        private val stop = AtomicBoolean(false)

        private fun fill() {
            while (!stop.get() && window.size < config.batchParallelism && source.hasNext()) {
                val p = source.next()
                window += p to executor.submit<MetadataResult> { run(p, options) { stop.get() } }
            }
        }

        override fun hasNext(): Boolean {
            fill()
            return window.isNotEmpty()
        }

        override fun next(): MetadataResult {
            fill()
            val (p, f) = window.pollFirst() ?: throw NoSuchElementException()
            return try {
                f.get()
            } catch (e: java.util.concurrent.ExecutionException) {
                failed(p, options, DiagnosticCode.INTERNAL_ERROR, "Extraction failed: ${e.cause?.javaClass?.simpleName}", e.cause)
            } catch (e: CancellationException) {
                failed(p, options, DiagnosticCode.CANCELLED, "Batch cancelled", e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                failed(p, options, DiagnosticCode.CANCELLED, "Interrupted", e)
            }
        }

        override fun close() {
            stop.set(true)
            window.forEach { it.second.cancel(true) }
            window.clear()
        }
    }

    // ---- Single-file pipeline ----

    private fun run(path: Path, options: ExtractionOptions, cancelled: () -> Boolean): MetadataResult {
        checkOpen()
        val started = System.nanoTime()
        val deadline = started + options.timeout.toNanos()
        val pre = ArrayList<MetadataDiagnostic>()
        val file = validate(path, pre) ?: return failed(path, options, pre)
        val attrs =
            try {
                Files.readAttributes(file, BasicFileAttributes::class.java)
            } catch (e: IOException) {
                return failed(path, options, DiagnosticCode.IO_ERROR, "Cannot read file attributes", e)
            }
        if (attrs.size() > options.limits.maxFileSizeBytes) {
            return failed(
                path,
                options,
                DiagnosticCode.FILE_TOO_LARGE,
                "File exceeds maxFileSizeBytes (${options.limits.maxFileSizeBytes})",
            )
        }
        val name = path.fileName?.toString() ?: file.fileName.toString()
        val ext = name.substringAfterLast('.', "").lowercase().ifEmpty { null }
        val detection: Detection
        val signature: String
        try {
            FileSource.open(file).use { src ->
                signature = Bytes.run { src.readAtMost(0, 32) }.joinToString("") { "%02x".format(it) }
                detection =
                    try {
                        FormatDetector.detect(src, ext)
                    } catch (e: IOException) {
                        throw e
                    } catch (e: Exception) {
                        // Detection is best-effort on hostile input. A failure there must not escape extract().
                        Detection(
                            MediaFormat.UNKNOWN,
                            MediaKind.UNKNOWN,
                            diagnostics = listOf(
                                MetadataDiagnostic(
                                    DiagnosticCode.MALFORMED,
                                    Severity.WARNING,
                                    "Signature probing failed: ${e.javaClass.simpleName}",
                                    "native",
                                    causeClass = e.javaClass.name,
                                ),
                            ),
                        )
                    }
            }
        } catch (e: IOException) {
            return failed(path, options, DiagnosticCode.IO_ERROR, "Cannot open file", e)
        }
        pre += detection.diagnostics
        val extFormat = MediaFormat.fromExtension(ext)
        val matches = ext == null || extFormat == null && detection.format == MediaFormat.UNKNOWN || ext in detection.format.extensions
        if (!matches && detection.format != MediaFormat.UNKNOWN) {
            pre +=
                MetadataDiagnostic(
                    DiagnosticCode.EXTENSION_MISMATCH,
                    Severity.WARNING,
                    "Extension '.$ext' does not match detected format ${detection.format.id}",
                    "native",
                )
        }
        if (detection.format == MediaFormat.UNKNOWN) {
            pre +=
                MetadataDiagnostic(
                    DiagnosticCode.UNKNOWN_FORMAT,
                    Severity.WARNING,
                    "File signature not recognised (extension '.${ext ?: ""}' is only a hint)",
                    "native",
                )
        }
        val checksum = if (options.computeChecksum) checksum(file, pre) else null
        val probe =
            FileProbe(
                name = name, extension = ext, sizeBytes = attrs.size(), detectedFormat = detection.format,
                mediaKind = detection.kind, mimeType = detection.format.mimeType, extensionFormat = extFormat,
                extensionMatches = matches, signatureHex = signature, brands = detection.brands,
                createdAtFileSystem = if (options.includeFileSystemTimestamps) attrs.creationTime()?.toInstant() else null,
                modifiedAtFileSystem = if (options.includeFileSystemTimestamps ||
                    options.fileSystemTimeAsCaptureFallback
                ) {
                    attrs.lastModifiedTime()?.toInstant()
                } else {
                    null
                },
                checksum = checksum,
            )

        val state = ExtractionState(options.limits.maxObservations, options.limits.maxDirectories)
        state.diagnostics += pre
        val runs = ArrayList<BackendRun>()
        var requiredProblem = false
        var anyFailure = false
        var usedAny = false
        for (b in backends) {
            val mode = b.mode(config)
            val applicable = b.supports(detection.format)
            val wanted =
                when (mode) {
                    BackendMode.DISABLED -> false
                    BackendMode.DEEP_ONLY -> options.depth == ExtractionDepth.DEEP
                    BackendMode.ALWAYS, BackendMode.REQUIRED -> true
                }
            if (!applicable || !wanted) {
                if (b.kind != dev.kiroku.spi.BackendKind.NATIVE && applicable && mode != BackendMode.DISABLED) {
                    runs += BackendRun(b.id, BackendStatus.SKIPPED, null, Duration.ZERO, 0, "mode $mode at depth ${options.depth}")
                }
                if (b.kind == dev.kiroku.spi.BackendKind.NATIVE && !applicable) {
                    runs += BackendRun(b.id, BackendStatus.SKIPPED, null, Duration.ZERO, 0, "no native parser for ${detection.format.id}")
                }
                continue
            }
            val avail = availabilityOf(b)
            if (!avail.available) {
                runs += BackendRun(b.id, BackendStatus.UNAVAILABLE, avail.version, Duration.ZERO, 0, avail.message)
                state.diagnostics +=
                    MetadataDiagnostic(
                        DiagnosticCode.BACKEND_UNAVAILABLE,
                        if (mode == BackendMode.REQUIRED) Severity.ERROR else Severity.INFO,
                        "Backend '${b.id}' unavailable: ${avail.message ?: "not installed"}",
                        b.id,
                    )
                if (mode == BackendMode.REQUIRED) requiredProblem = true
                continue
            }
            val collector = MetadataCollector(b.id, state)
            val t0 = System.nanoTime()
            val outcome =
                try {
                    if (cancelled()) throw CancellationException("cancelled")
                    b.extract(BackendContext(file, probe, options, collector, deadline, cancelled))
                } catch (e: ExtractionTimeoutException) {
                    collector.diagnostic(
                        DiagnosticCode.BACKEND_TIMEOUT,
                        Severity.WARNING,
                        "Backend '${b.id}' exceeded the per-file timeout",
                        cause = e,
                    )
                    dev.kiroku.spi.BackendOutcome(BackendStatus.TIMED_OUT, null, "timeout")
                } catch (e: CancellationException) {
                    collector.diagnostic(
                        DiagnosticCode.CANCELLED,
                        Severity.WARNING,
                        "Extraction cancelled during backend '${b.id}'",
                        cause = e,
                    )
                    runs +=
                        BackendRun(
                            b.id,
                            BackendStatus.FAILED,
                            null,
                            Duration.ofNanos(System.nanoTime() - t0),
                            collector.ownObservationCount,
                            "cancelled",
                        )
                    return assemble(probe, state, runs, options, ExtractionStatus.FAILED)
                } catch (e: IOException) {
                    collector.diagnostic(
                        DiagnosticCode.IO_ERROR,
                        Severity.ERROR,
                        "I/O error in backend '${b.id}': ${e.javaClass.simpleName}",
                        cause = e,
                    )
                    dev.kiroku.spi.BackendOutcome(BackendStatus.FAILED, null, e.javaClass.simpleName)
                } catch (e: Exception) {
                    collector.diagnostic(
                        DiagnosticCode.BACKEND_FAILED,
                        Severity.ERROR,
                        "Backend '${b.id}' failed: ${e.javaClass.simpleName}",
                        cause = e,
                    )
                    dev.kiroku.spi.BackendOutcome(BackendStatus.FAILED, null, e.javaClass.simpleName)
                } catch (e: StackOverflowError) {
                    collector.diagnostic(DiagnosticCode.BACKEND_FAILED, Severity.ERROR, "Backend '${b.id}' exceeded stack depth", cause = e)
                    dev.kiroku.spi.BackendOutcome(BackendStatus.FAILED, null, "StackOverflowError")
                }
            runs +=
                BackendRun(
                    b.id,
                    outcome.status,
                    outcome.version ?: avail.version,
                    Duration.ofNanos(System.nanoTime() - t0),
                    collector.ownObservationCount,
                    outcome.message,
                )
            log(b.id, outcome.status, System.nanoTime() - t0, collector.ownObservationCount, detection.format)
            when (outcome.status) {
                BackendStatus.USED -> usedAny = true

                BackendStatus.FAILED, BackendStatus.TIMED_OUT -> {
                    anyFailure = true
                    if (mode == BackendMode.REQUIRED) requiredProblem = true
                }

                else -> Unit
            }
        }
        val status =
            when {
                detection.format == MediaFormat.UNKNOWN && state.observations.isEmpty() -> ExtractionStatus.UNSUPPORTED
                !usedAny && state.observations.isEmpty() -> if (anyFailure) ExtractionStatus.FAILED else ExtractionStatus.UNSUPPORTED
                state.observations.isEmpty() && state.streams.isEmpty() -> ExtractionStatus.FAILED
                anyFailure || requiredProblem || state.truncated -> ExtractionStatus.PARTIAL
                else -> ExtractionStatus.COMPLETE
            }
        return assemble(probe, state, runs, options, status)
    }

    /**
     * Structured logging through `System.Logger` (bridged to SLF4J/JUL/Log4j by the host application).
     * Paths and coordinates are never logged.
     */
    private fun log(backend: String, status: BackendStatus, nanos: Long, observations: Int, format: MediaFormat) {
        val level = if (status == BackendStatus.FAILED || status == BackendStatus.TIMED_OUT) System.Logger.Level.WARNING else System.Logger.Level.DEBUG
        if (LOGGER.isLoggable(level)) {
            LOGGER.log(
                level,
                "kiroku backend={0} status={1} format={2} durationMs={3} observations={4}",
                backend,
                status,
                format.id,
                nanos / 1_000_000,
                observations,
            )
        }
    }

    private fun assemble(
        probe: FileProbe,
        state: ExtractionState,
        runs: List<BackendRun>,
        options: ExtractionOptions,
        status: ExtractionStatus,
    ): MetadataResult {
        val observations = state.observations.toList()
        val projection =
            try {
                GalleryProjector(probe, observations, state.streams, state.containers, state.embeddedImages, options).project()
            } catch (e: Exception) {
                // The projection must never lose the full output. Report the failure and return an empty gallery.
                state.diagnostics +=
                    MetadataDiagnostic(
                        DiagnosticCode.INTERNAL_ERROR,
                        Severity.ERROR,
                        "Gallery projection failed: ${e.javaClass.simpleName}",
                        causeClass = e.javaClass.name,
                    )
                null
            }
        val finalObservations =
            when {
                !options.includeRawTags -> emptyList()
                options.gpsPolicy == GpsPolicy.EXCLUDE_ALL -> redact(observations, state)
                else -> observations
            }
        val full =
            FullMetadata(
                file = probe,
                container =
                state.containers.firstOrNull()
                    ?: ContainerInfo(probe.detectedFormat.id, probe.brands).takeIf {
                        probe.mediaKind == MediaKind.VIDEO ||
                            probe.mediaKind == MediaKind.AUDIO
                    },
                directories = state.directories.toList(),
                streams = state.streams.toList(),
                chapters = state.chapters.toList(),
                embeddedImages = state.embeddedImages.toList(),
                observations = finalObservations,
                fieldProvenance = if (options.includeRawTags) projection?.provenance.orEmpty() else emptyMap(),
            )
        val diagnostics = (state.diagnostics + projection?.diagnostics.orEmpty()).distinct()
        val finalStatus = if (projection == null && status == ExtractionStatus.COMPLETE) ExtractionStatus.PARTIAL else status
        return MetadataResult(
            MetadataResult.SCHEMA_VERSION,
            finalStatus,
            projection?.gallery ?: emptyGallery(probe),
            full,
            diagnostics,
            runs,
        )
    }

    private fun redact(obs: List<RawObservation>, state: ExtractionState): List<RawObservation> {
        var n = 0
        val out =
            obs.map { o ->
                if (GalleryProjector.isLocationObservation(o)) {
                    n++
                    o.copy(
                        value = NormalizedValue.Null,
                        displayValue = null,
                        original = o.original?.copy(bytes = null, omittedReason = OmissionReason.REDACTED),
                    )
                } else {
                    o
                }
            }
        if (n >
            0
        ) {
            state.diagnostics +=
                MetadataDiagnostic(DiagnosticCode.REDACTED, Severity.INFO, "$n location observations redacted by GPS policy EXCLUDE_ALL")
        }
        return out
    }

    private fun validate(path: Path, diags: MutableList<MetadataDiagnostic>): Path? {
        val abs = path.toAbsolutePath().normalize()
        if (abs.toString().any { it == '\u0000' }) {
            diags += MetadataDiagnostic(DiagnosticCode.PATH_NOT_ALLOWED, Severity.ERROR, "Path contains a NUL character")
            return null
        }
        if (config.symlinkPolicy == SymlinkPolicy.REJECT && Files.isSymbolicLink(abs)) {
            diags +=
                MetadataDiagnostic(DiagnosticCode.SYMLINK_REJECTED, Severity.ERROR, "Input is a symbolic link and symlinkPolicy is REJECT")
            return null
        }
        val real =
            try {
                abs.toRealPath()
            } catch (_: NoSuchFileException) {
                diags += MetadataDiagnostic(DiagnosticCode.FILE_NOT_FOUND, Severity.ERROR, "File not found")
                return null
            } catch (e: IOException) {
                diags +=
                    MetadataDiagnostic(
                        DiagnosticCode.IO_ERROR,
                        Severity.ERROR,
                        "Cannot resolve path: ${e.javaClass.simpleName}",
                        causeClass = e.javaClass.name,
                    )
                return null
            }
        if (allowedRoots.isNotEmpty() && allowedRoots.none { real.startsWith(it) }) {
            diags += MetadataDiagnostic(DiagnosticCode.PATH_NOT_ALLOWED, Severity.ERROR, "Resolved path is outside the allowed roots")
            return null
        }
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            diags += MetadataDiagnostic(DiagnosticCode.NOT_A_REGULAR_FILE, Severity.ERROR, "Not a regular file")
            return null
        }
        return real
    }

    private fun checksum(file: Path, diags: MutableList<MetadataDiagnostic>): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        "sha256:" + md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: IOException) {
        diags += MetadataDiagnostic(DiagnosticCode.IO_ERROR, Severity.WARNING, "Checksum failed: ${e.javaClass.simpleName}")
        null
    }

    private fun failed(path: Path, options: ExtractionOptions, code: DiagnosticCode, message: String, cause: Throwable? = null): MetadataResult =
        failed(path, options, listOf(MetadataDiagnostic(code, Severity.ERROR, message, causeClass = cause?.javaClass?.name)))

    private fun failed(path: Path, @Suppress("UNUSED_PARAMETER") options: ExtractionOptions, diags: List<MetadataDiagnostic>): MetadataResult {
        val name = path.fileName?.toString() ?: ""
        val ext = name.substringAfterLast('.', "").lowercase().ifEmpty { null }
        val probe = FileProbe(name, ext, 0, MediaFormat.UNKNOWN, MediaKind.UNKNOWN, null, MediaFormat.fromExtension(ext), false, "")
        return MetadataResult(
            MetadataResult.SCHEMA_VERSION,
            ExtractionStatus.FAILED,
            emptyGallery(probe),
            FullMetadata(probe, null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyMap()),
            diags,
            emptyList(),
        )
    }

    private fun emptyGallery(probe: FileProbe): GalleryMetadata = GalleryMetadata(
        probe.mediaKind, probe.detectedFormat.id.takeIf { probe.detectedFormat != MediaFormat.UNKNOWN }, probe.mimeType,
        GalleryFile(
            probe.name,
            probe.extension,
            probe.sizeBytes,
            probe.detectedFormat.id,
            probe.mimeType,
            probe.createdAtFileSystem,
            probe.modifiedAtFileSystem,
            probe.checksum,
        ),
        null, null, null, null, null, null, null, null, null, null, null, null,
    )
}
