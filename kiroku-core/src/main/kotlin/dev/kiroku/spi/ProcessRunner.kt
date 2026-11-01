package dev.kiroku.spi

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

/**
 * Hardened subprocess execution for optional external tools.
 *
 * - The command is an argument list passed directly to the OS. No shell is involved, so no interpolation is possible.
 * - stdin is closed, so tools cannot block waiting for input.
 * - stdout is capped at [maxStdoutBytes] (the process is killed past it) and stderr at [maxStderrBytes].
 * - On timeout or cancellation the whole process tree is destroyed forcibly.
 */
public class ProcessRunner(private val maxStdoutBytes: Long, private val maxStderrBytes: Int) {
    public class Result(
        public val exitCode: Int?,
        public val stdout: ByteArray,
        public val stderr: String,
        public val timedOut: Boolean,
        public val outputTruncated: Boolean,
        public val cancelled: Boolean,
    )

    public fun run(command: List<String>, timeout: Duration, cancelled: () -> Boolean = { false }): Result {
        require(command.isNotEmpty())
        require(command.none { it.contains('\u0000') }) { "NUL in argument" }
        val process =
            ProcessBuilder(command)
                .redirectInput(ProcessBuilder.Redirect.PIPE)
                .start()
        process.outputStream.close()
        val out = CappedReader(process.inputStream, maxStdoutBytes)
        val err = CappedReader(process.errorStream, maxStderrBytes.toLong())
        val tOut = pipeThread(out, "kiroku-stdout")
        val tErr = pipeThread(err, "kiroku-stderr")
        val deadline = System.nanoTime() + timeout.toNanos()
        var timedOut = false
        var wasCancelled = false
        while (true) {
            if (process.waitFor(50, TimeUnit.MILLISECONDS)) break
            if (out.overflow) {
                kill(process)
                break
            }
            if (cancelled()) {
                wasCancelled = true
                kill(process)
                break
            }
            if (System.nanoTime() > deadline) {
                timedOut = true
                kill(process)
                break
            }
        }
        tOut.join(2000)
        tErr.join(2000)
        val exit = if (process.isAlive) null else process.exitValue()
        return Result(exit, out.bytes(), err.bytes().toString(Charsets.UTF_8), timedOut, out.overflow, wasCancelled)
    }

    private fun pipeThread(r: Runnable, name: String): Thread = Thread(r, name).apply { isDaemon = true }.also { it.start() }

    private class CappedReader(private val input: InputStream, private val cap: Long) : Runnable {
        private val buf = ByteArrayOutputStream()

        @Volatile var overflow = false

        override fun run() {
            val chunk = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    synchronized(buf) {
                        val room = cap - buf.size()
                        if (room <= 0) {
                            overflow = true
                        } else {
                            buf.write(chunk, 0, minOf(n.toLong(), room).toInt())
                            if (n > room) overflow = true
                        }
                    }
                }
            } catch (_: java.io.IOException) {
                // The stream closes when the process is killed.
            }
        }

        fun bytes(): ByteArray = synchronized(buf) { buf.toByteArray() }
    }

    public companion object {
        /** Destroys a process and all its descendants. */
        @JvmStatic
        public fun kill(process: Process) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            try {
                process.waitFor(2, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        /** Finds [names] on `PATH` (with Windows extensions). Never runs anything. */
        @JvmStatic
        public fun findOnPath(vararg names: String): Path? {
            val path = System.getenv("PATH") ?: return null
            val exts = if (System.getProperty("os.name").lowercase().contains("win")) listOf("", ".exe", ".bat", ".cmd") else listOf("")
            for (dir in path.split(java.io.File.pathSeparatorChar)) {
                if (dir.isBlank()) continue
                for (n in names) {
                    for (e in exts) {
                        val p =
                            try {
                                Path.of(dir, n + e)
                            } catch (_: java.nio.file.InvalidPathException) {
                                continue
                            }
                        if (p.isRegularFile() && p.isExecutable()) return p.toAbsolutePath()
                    }
                }
            }
            return null
        }

        /** Validates a configured executable path without running it. */
        @JvmStatic
        public fun usableExecutable(p: Path?): Path? = p?.takeIf { Files.isRegularFile(it) && Files.isExecutable(it) }?.toAbsolutePath()
    }
}
