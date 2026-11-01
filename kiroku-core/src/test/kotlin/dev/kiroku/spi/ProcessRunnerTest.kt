package dev.kiroku.spi

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessRunnerTest {
    @TempDir
    lateinit var tmp: Path

    private fun unix() = assumeTrue(!System.getProperty("os.name").lowercase().contains("win"), "POSIX tools required")

    @Test
    fun `timeout kills the process tree`() {
        unix()
        val t0 = System.nanoTime()
        val r = ProcessRunner(1024, 1024).run(listOf("sh", "-c", "sleep 30 & sleep 30"), Duration.ofMillis(300))
        assertTrue(r.timedOut)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 5_000)
    }

    @Test
    fun `stdout is capped`() {
        unix()
        val r = ProcessRunner(10_000, 1024).run(listOf("yes"), Duration.ofSeconds(10))
        assertTrue(r.outputTruncated)
        assertTrue(r.stdout.size <= 10_000)
    }

    @Test
    fun `arguments are never interpreted by a shell`() {
        unix()
        val marker = tmp.resolve("pwned")
        val r = ProcessRunner(1024, 1024).run(listOf("echo", "\$(touch $marker)", ";", "touch", marker.toString()), Duration.ofSeconds(5))
        assertEquals(0, r.exitCode)
        assertTrue(Files.notExists(marker))
        assertTrue(r.stdout.toString(Charsets.UTF_8).contains("\$(touch"))
    }

    @Test
    fun `cancellation stops the process`() {
        unix()
        val r = ProcessRunner(1024, 1024).run(listOf("sleep", "30"), Duration.ofSeconds(30)) { true }
        assertTrue(r.cancelled)
    }
}
