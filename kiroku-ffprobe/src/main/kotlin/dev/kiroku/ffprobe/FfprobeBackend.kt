package dev.kiroku.ffprobe

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import dev.kiroku.BackendMode
import dev.kiroku.ExternalToolConfig
import dev.kiroku.ExtractionDepth
import dev.kiroku.MetadataConfig
import dev.kiroku.model.BackendStatus
import dev.kiroku.model.ChapterInfo
import dev.kiroku.model.ContainerInfo
import dev.kiroku.model.DiagnosticCode
import dev.kiroku.model.MediaFormat
import dev.kiroku.model.MediaKind
import dev.kiroku.model.NativeType
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.Rational
import dev.kiroku.model.Severity
import dev.kiroku.model.StreamInfo
import dev.kiroku.model.StreamKind
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
import dev.kiroku.spi.StreamProps
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Optional ffprobe backend (deep mode by default). It runs
 * `ffprobe -v error -of json -show_format -show_streams -show_chapters [-show_frames -read_intervals %+#1] -i <path>`
 * through an argument list (never a shell), with a timeout and capped output.
 *
 * All values are `BACKEND_DECODED`. Streams use the shared [StreamProps] keys, so the gallery projection
 * can use them when Kiroku's native parser has no stream information for a file.
 */
public class FfprobeBackend(
    private val config: ExternalToolConfig,
    private val maxOutputBytes: Long = 64L shl 20,
    private val maxStderrBytes: Int = 64 shl 10,
) : MetadataBackend {
    override val id: String = ID
    override val kind: BackendKind = BackendKind.EXTERNAL_TOOL
    override val metadataFamilies: Set<String> =
        setOf("Container", "Streams", "StreamTags", "SideData", "DisplayMatrix", "HDR", "Chapters", "Frames (first, deep)")

    private val executable: Path? by lazy {
        ProcessRunner.usableExecutable(config.executable)
            ?: if (config.executable == null) ProcessRunner.findOnPath("ffprobe") else null
    }
    private val permits = Semaphore(config.maxConcurrentProcesses.coerceAtLeast(1))

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
                        "configured ffprobe executable is not executable"
                    } else {
                        "ffprobe not found on PATH"
                    },
                )
        return try {
            val r = ProcessRunner(64 * 1024, 4096).run(listOf(exe.toString(), "-v", "error", "-version"), Duration.ofSeconds(15))
            val first = r.stdout.toString(Charsets.UTF_8).lineSequence().firstOrNull().orEmpty()
            val version = Regex("""ffprobe version (\S+)""").find(first)?.groupValues?.get(1)
            if (r.exitCode == 0 &&
                version != null
            ) {
                BackendAvailability(true, version, executable = exe)
            } else {
                BackendAvailability(false, null, "ffprobe -version failed", exe)
            }
        } catch (e: Exception) {
            BackendAvailability(false, null, "cannot run ffprobe: ${e.javaClass.simpleName}", exe)
        }
    }

    /** ffprobe is useful for audio/video containers, and also reads still images and animations. */
    override fun supports(format: MediaFormat): Boolean = format.defaultKind == MediaKind.VIDEO || format.defaultKind == MediaKind.AUDIO || format in STILLS

    override fun extract(context: BackendContext): BackendOutcome {
        val exe = executable ?: return BackendOutcome(BackendStatus.FAILED, null, "executable missing")
        val pathText = context.path.toAbsolutePath().toString()
        val cmd =
            buildList {
                addAll(listOf(exe.toString(), "-v", "error", "-hide_banner", "-of", "json", "-show_format", "-show_streams", "-show_chapters", "-show_error"))
                if (context.options.depth == ExtractionDepth.DEEP) addAll(listOf("-show_frames", "-read_intervals", "%+#1"))
                // "file:" stops ffprobe from interpreting the path as a protocol URL (e.g. "http:" or "concat:").
                addAll(listOf("-i", "file:$pathText"))
            }
        val timeout = Duration.ofNanos(minOf(config.timeout.toNanos(), context.remainingNanos()).coerceAtLeast(1_000_000))
        if (!permits.tryAcquire(timeout.toNanos(), TimeUnit.NANOSECONDS)) return BackendOutcome(BackendStatus.TIMED_OUT, null, "no free ffprobe slot")
        val r =
            try {
                ProcessRunner(maxOutputBytes, maxStderrBytes).run(cmd, timeout) {
                    try {
                        context.checkCancelled()
                        false
                    } catch (_: Exception) {
                        true
                    }
                }
            } finally {
                permits.release()
            }
        if (r.stderr.isNotBlank()) {
            r.stderr.lines().filter {
                it.isNotBlank()
            }.take(20).forEach {
                context.collector.diagnostic(DiagnosticCode.BACKEND_FAILED, Severity.INFO, "ffprobe: ${it.replace(pathText, "<file>").take(300)}")
            }
        }
        if (r.timedOut) return BackendOutcome(BackendStatus.TIMED_OUT, null, "ffprobe timed out")
        if (r.cancelled) return BackendOutcome(BackendStatus.FAILED, null, "cancelled")
        if (r.outputTruncated) {
            context.collector.diagnostic(DiagnosticCode.OUTPUT_TRUNCATED, Severity.WARNING, "ffprobe output exceeded $maxOutputBytes bytes; not parsed")
            context.collector.markTruncated()
            return BackendOutcome(BackendStatus.FAILED, null, "output truncated")
        }
        val tree = parseJson(r.stdout) ?: return BackendOutcome(BackendStatus.FAILED, null, "unparseable ffprobe output (exit ${r.exitCode})")
        tree["error"]?.let { err ->
            val msg = ((err as? NormalizedValue.ObjectValue)?.fields?.get("string") as? NormalizedValue.Text)?.value
            context.collector.diagnostic(DiagnosticCode.BACKEND_FAILED, Severity.INFO, "ffprobe could not read the file: ${msg?.take(200)}")
            if (tree["streams"] == null && tree["format"] == null) return BackendOutcome(BackendStatus.FAILED, null, msg)
        }
        emit(tree, context.collector)
        return BackendOutcome.used()
    }

    /** Parses the top-level JSON object into named sections. Arrays and objects are kept as [NormalizedValue]s. */
    internal fun parseJson(bytes: ByteArray): Map<String, NormalizedValue>? {
        val factory = JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).build()).build()
        return try {
            factory.createParser(bytes).use { p ->
                if (p.nextToken() != JsonToken.START_OBJECT) return null
                val out = LinkedHashMap<String, NormalizedValue>()
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    val k = p.currentName()
                    p.nextToken()
                    out[k] = read(p, 0)
                }
                out
            }
        } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
            null
        }
    }

    private fun read(p: JsonParser, depth: Int): NormalizedValue = when (p.currentToken()) {
        JsonToken.VALUE_STRING -> NormalizedValue.Text(p.text)

        JsonToken.VALUE_NUMBER_INT -> NormalizedValue.Integer(p.longValue)

        JsonToken.VALUE_NUMBER_FLOAT -> NormalizedValue.Decimal(p.doubleValue)

        JsonToken.VALUE_TRUE -> NormalizedValue.Bool(true)

        JsonToken.VALUE_FALSE -> NormalizedValue.Bool(false)

        JsonToken.START_ARRAY -> NormalizedValue.ListValue(
            buildList {
                while (p.nextToken() !=
                    JsonToken.END_ARRAY
                ) {
                    add(if (depth > 32) NormalizedValue.Null.also { p.skipChildren() } else read(p, depth + 1))
                }
            },
        )

        JsonToken.START_OBJECT ->
            NormalizedValue.ObjectValue(
                LinkedHashMap<String, NormalizedValue>().apply {
                    while (p.nextToken() == JsonToken.FIELD_NAME) {
                        val k = p.currentName()
                        p.nextToken()
                        put(k, if (depth > 32) NormalizedValue.Null.also { p.skipChildren() } else read(p, depth + 1))
                    }
                },
            )

        else -> NormalizedValue.Null
    }

    private fun emit(tree: Map<String, NormalizedValue>, c: MetadataCollector) {
        fun obs(group: List<String>, name: String, v: NormalizedValue, stream: Int? = null, ns: String? = null) = c.observe(
            group,
            name,
            v,
            fidelity = ValueFidelity.BACKEND_DECODED,
            nativeType = NativeType(TypeSystem.FFPROBE, v.type.name.lowercase()),
            streamIndex = stream,
            namespace = ns,
        )

        (tree["format"] as? NormalizedValue.ObjectValue)?.fields?.let { f ->
            for ((k, v) in f) {
                if (k == "filename") continue // privacy: no paths in output
                if (k ==
                    "tags"
                ) {
                    (v as? NormalizedValue.ObjectValue)?.fields?.forEach { (tk, tv) -> obs(listOf("ffprobe", "format"), tk, tv, ns = "tag") }
                } else {
                    obs(listOf("ffprobe", "format"), k, v)
                }
            }
            c.container(
                ContainerInfo(
                    format = (f["format_name"] as? NormalizedValue.Text)?.value ?: "unknown",
                    duration = f["duration"]?.let { secs(it) },
                    bitRateBitsPerSecond = (f["bit_rate"] as? NormalizedValue.Text)?.value?.toLongOrNull(),
                    properties = f.filterKeys { it != "tags" && it != "filename" },
                ),
            )
        }
        (tree["streams"] as? NormalizedValue.ListValue)?.items?.forEach { s ->
            val f = (s as? NormalizedValue.ObjectValue)?.fields ?: return@forEach
            val index = (f["index"] as? NormalizedValue.Integer)?.value?.toInt() ?: return@forEach
            for ((k, v) in f) {
                when (k) {
                    "tags" -> (v as? NormalizedValue.ObjectValue)?.fields?.forEach { (tk, tv) -> obs(listOf("ffprobe", "stream"), tk, tv, index, "tag") }

                    "side_data_list" ->
                        (v as? NormalizedValue.ListValue)?.items?.forEach { sd ->
                            val sf = (sd as? NormalizedValue.ObjectValue)?.fields ?: return@forEach
                            val type = (sf["side_data_type"] as? NormalizedValue.Text)?.value ?: "side_data"
                            obs(listOf("ffprobe", "stream:side_data"), type, sd, index, "side_data")
                        }

                    else -> obs(listOf("ffprobe", "stream"), k, v, index)
                }
            }
            c.stream(streamInfo(index, f))
        }
        (tree["chapters"] as? NormalizedValue.ListValue)?.items?.forEachIndexed { i, ch ->
            val f = (ch as? NormalizedValue.ObjectValue)?.fields ?: return@forEachIndexed
            val title = ((f["tags"] as? NormalizedValue.ObjectValue)?.fields?.get("title") as? NormalizedValue.Text)?.value
            c.chapter(ChapterInfo(i, "", f["start_time"]?.let { secs(it) }, f["end_time"]?.let { secs(it) }, title))
        }
        (tree["frames"] as? NormalizedValue.ListValue)?.items?.forEach { fr ->
            val f = (fr as? NormalizedValue.ObjectValue)?.fields ?: return@forEach
            val idx = (f["stream_index"] as? NormalizedValue.Integer)?.value?.toInt()
            (f["side_data_list"] as? NormalizedValue.ListValue)?.items?.forEach { sd ->
                val type = ((sd as? NormalizedValue.ObjectValue)?.fields?.get("side_data_type") as? NormalizedValue.Text)?.value ?: "frame_side_data"
                obs(listOf("ffprobe", "frame:side_data"), type, sd, idx, "frame_side_data")
            }
        }
    }

    private fun streamInfo(index: Int, f: Map<String, NormalizedValue>): StreamInfo {
        fun t(k: String) = (f[k] as? NormalizedValue.Text)?.value
        fun i(k: String) = (f[k] as? NormalizedValue.Integer)?.value ?: t(k)?.toLongOrNull()
        val kind =
            when (t("codec_type")) {
                "video" -> if ((f["disposition"] as? NormalizedValue.ObjectValue)?.fields?.get("attached_pic") ==
                    NormalizedValue.Integer(1)
                ) {
                    StreamKind.IMAGE
                } else {
                    StreamKind.VIDEO
                }

                "audio" -> StreamKind.AUDIO

                "subtitle" -> StreamKind.SUBTITLE

                "data" -> StreamKind.DATA

                else -> StreamKind.OTHER
            }
        val p = LinkedHashMap<String, NormalizedValue>()
        i("width")?.let { p[StreamProps.WIDTH] = NormalizedValue.Integer(it) }
        i("height")?.let { p[StreamProps.HEIGHT] = NormalizedValue.Integer(it) }
        rational(t("r_frame_rate"))?.let { p[StreamProps.FRAME_RATE] = NormalizedValue.RationalValue(it) }
        rational(t("avg_frame_rate"))?.let { p[StreamProps.AVERAGE_FRAME_RATE] = NormalizedValue.RationalValue(it) }
        rational(t("time_base"))?.let { p[StreamProps.TIME_BASE] = NormalizedValue.RationalValue(it) }
        rational(t("sample_aspect_ratio")?.replace(':', '/'))?.let { p[StreamProps.PIXEL_ASPECT_RATIO] = NormalizedValue.RationalValue(it) }
        i("bit_rate")?.let { p[StreamProps.BIT_RATE] = NormalizedValue.Integer(it) }
        t("duration")?.toDoubleOrNull()?.let { p[StreamProps.DURATION_SECONDS] = NormalizedValue.Decimal(it) }
        t("profile")?.let { p[StreamProps.PROFILE] = NormalizedValue.Text(it) }
        i("level")?.takeIf { it > 0 }?.let { p[StreamProps.LEVEL] = NormalizedValue.Text(it.toString()) }
        i("sample_rate")?.let { p[StreamProps.SAMPLE_RATE] = NormalizedValue.Integer(it) }
        i("channels")?.let { p[StreamProps.CHANNELS] = NormalizedValue.Integer(it) }
        t("channel_layout")?.let { p[StreamProps.CHANNEL_LAYOUT] = NormalizedValue.Text(it) }
        (i("bits_per_raw_sample") ?: i("bits_per_sample"))?.takeIf { it > 0 }?.let {
            p[
                if (kind ==
                    StreamKind.AUDIO
                ) {
                    StreamProps.BITS_PER_SAMPLE
                } else {
                    StreamProps.BIT_DEPTH_LUMA
                },
            ] =
                NormalizedValue.Integer(it)
        }
        t("pix_fmt")?.let { p[StreamProps.PIXEL_FORMAT] = NormalizedValue.Text(it) }
        t("color_primaries")?.let { p[StreamProps.COLOR_PRIMARIES] = NormalizedValue.Text(it) }
        t("color_transfer")?.let { p[StreamProps.TRANSFER] = NormalizedValue.Text(it) }
        t("color_space")?.let { p[StreamProps.MATRIX_COEFFICIENTS] = NormalizedValue.Text(it) }
        ((f["tags"] as? NormalizedValue.ObjectValue)?.fields?.get("language") as? NormalizedValue.Text)?.let { p[StreamProps.LANGUAGE] = it }
        ((f["disposition"] as? NormalizedValue.ObjectValue)?.fields?.get("default") as? NormalizedValue.Integer)?.let {
            p[StreamProps.DEFAULT] =
                NormalizedValue.Bool(it.value == 1L)
        }
        (f["side_data_list"] as? NormalizedValue.ListValue)?.items?.forEach { sd ->
            val sf = (sd as? NormalizedValue.ObjectValue)?.fields ?: return@forEach
            when ((sf["side_data_type"] as? NormalizedValue.Text)?.value) {
                // ffprobe reports display-matrix rotation counter-clockwise; StreamProps.ROTATION keeps ffprobe's sign.
                "Display Matrix" -> sf["rotation"]?.let { p[StreamProps.ROTATION] = it }

                "DOVI configuration record" -> sf["dv_profile"]?.let { p[StreamProps.DOLBY_VISION_PROFILE] = it }
            }
        }
        return StreamInfo(index, "", kind, t("codec_name"), t("codec_tag_string"), p)
    }

    private fun rational(s: String?): Rational? {
        val parts = s?.split('/') ?: return null
        if (parts.size != 2) return null
        val n = parts[0].toLongOrNull() ?: return null
        val d = parts[1].toLongOrNull() ?: return null
        return if (n == 0L || d == 0L) null else Rational(n, d)
    }

    private fun secs(v: NormalizedValue): Duration? = when (v) {
        is NormalizedValue.Text -> v.value.toDoubleOrNull()
        is NormalizedValue.Decimal -> v.value
        is NormalizedValue.Integer -> v.value.toDouble()
        else -> null
    }?.takeIf { it.isFinite() && it >= 0 }?.let { Duration.ofNanos((it * 1e9).toLong()) }

    public companion object {
        public const val ID: String = "ffprobe"
        private val STILLS = setOf(MediaFormat.GIF, MediaFormat.WEBP, MediaFormat.APNG)
    }
}

/** `ServiceLoader` provider: adding `kiroku-ffprobe` to the classpath registers the backend. */
public class FfprobeBackendProvider : MetadataBackendProvider {
    override fun create(config: MetadataConfig): MetadataBackend =
        FfprobeBackend(config.ffprobe, config.defaultOptions.limits.maxSubprocessOutputBytes, config.defaultOptions.limits.maxStderrBytes)
}
