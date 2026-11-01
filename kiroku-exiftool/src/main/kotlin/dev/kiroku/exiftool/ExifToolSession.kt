package dev.kiroku.exiftool

import dev.kiroku.spi.ProcessRunner
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One long-running ExifTool process in `-stay_open` mode. Requests are sent as argument files on stdin
 * (one argument per line, so no shell is involved), and responses end with `{readyN}`.
 *
 * A session is not thread-safe. The pool hands each one to a single caller at a time.
 */
internal class ExifToolSession(executable: Path, private val maxOutputBytes: Long, private val maxStderrBytes: Int) : AutoCloseable {
    private val process: Process =
        ProcessBuilder(executable.toString(), "-stay_open", "True", "-@", "-")
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .start()
    private val stdin = OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8)
    private val stdout: InputStream = process.inputStream
    private val stderrLines = LinkedBlockingQueue<String>(10_000)
    private val counter = AtomicInteger(0)

    @Volatile var broken = false
        private set

    init {
        val reader =
            Thread({
                try {
                    process.errorStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { line -> stderrLines.offer(line.take(4096)) }
                } catch (_: java.io.IOException) {
                }
            }, "kiroku-exiftool-stderr")
        reader.isDaemon = true
        reader.start()
    }

    class Response(val stdout: ByteArray, val stderr: String, val timedOut: Boolean, val truncated: Boolean, val cancelled: Boolean)

    /**
     * Runs one request. Throws nothing on tool errors (they come back in [Response.stderr]). On timeout,
     * cancellation or output overflow the session is killed and marked [broken].
     */
    fun execute(args: List<String>, timeoutNanos: Long, cancelled: () -> Boolean): Response {
        require(args.none { it.contains('\n') || it.contains('\r') || it.contains('\u0000') }) { "argument contains a line break or NUL" }
        val n = counter.incrementAndGet()
        val marker = "{ready$n}"
        stderrLines.clear()
        val request = buildString {
            append("-echo4\n").append(marker).append('\n')
            for (a in args) append(a).append('\n')
            append("-execute").append(n).append('\n')
        }
        try {
            stdin.write(request)
            stdin.flush()
        } catch (e: java.io.IOException) {
            broken = true
            throw e
        }
        val deadline = System.nanoTime() + timeoutNanos
        val out = ByteArrayOutputStream()
        val markerBytes = (marker).toByteArray(StandardCharsets.US_ASCII)
        val buf = ByteArray(16 * 1024)
        var timedOut = false
        var truncated = false
        var wasCancelled = false
        while (true) {
            if (stdout.available() > 0) {
                val r = stdout.read(buf, 0, minOf(buf.size, stdout.available()))
                if (r < 0) {
                    broken = true
                    break
                }
                if (out.size() + r > maxOutputBytes) {
                    truncated = true
                    break
                }
                out.write(buf, 0, r)
                if (endsWithMarker(out, markerBytes)) break
                continue
            }
            if (!process.isAlive) {
                broken = true
                break
            }
            if (cancelled()) {
                wasCancelled = true
                break
            }
            if (System.nanoTime() > deadline) {
                timedOut = true
                break
            }
            Thread.sleep(2)
        }
        if (timedOut || truncated || wasCancelled) {
            broken = true
            ProcessRunner.kill(process)
        }
        // Collect stderr up to the echo marker (short wait: it is written right after processing).
        val err = StringBuilder()
        if (!broken) {
            val until = System.nanoTime() + 500_000_000
            while (System.nanoTime() < until) {
                val line = stderrLines.poll(20, TimeUnit.MILLISECONDS) ?: continue
                if (line.trim() == marker) break
                if (err.length < maxStderrBytes) err.append(line).append('\n')
            }
        } else {
            while (true) {
                val line = stderrLines.poll() ?: break
                if (err.length < maxStderrBytes) err.append(line).append('\n')
            }
        }
        val bytes = out.toByteArray()
        val body = if (!timedOut && !truncated && endsWithMarker(out, markerBytes)) stripMarker(bytes, markerBytes) else bytes
        return Response(body, err.toString(), timedOut, truncated, wasCancelled)
    }

    private fun endsWithMarker(out: ByteArrayOutputStream, marker: ByteArray): Boolean {
        val b = out.toByteArray()
        var end = b.size
        while (end > 0 && (b[end - 1] == '\n'.code.toByte() || b[end - 1] == '\r'.code.toByte())) end--
        if (end < marker.size) return false
        for (i in marker.indices) if (b[end - marker.size + i] != marker[i]) return false
        return true
    }

    private fun stripMarker(b: ByteArray, marker: ByteArray): ByteArray {
        var end = b.size
        while (end > 0 && (b[end - 1] == '\n'.code.toByte() || b[end - 1] == '\r'.code.toByte())) end--
        return b.copyOf(end - marker.size)
    }

    override fun close() {
        try {
            if (process.isAlive && !broken) {
                stdin.write("-stay_open\nFalse\n")
                stdin.flush()
                stdin.close()
                if (!process.waitFor(2, TimeUnit.SECONDS)) ProcessRunner.kill(process)
            } else {
                ProcessRunner.kill(process)
            }
        } catch (_: Exception) {
            ProcessRunner.kill(process)
        }
    }
}
