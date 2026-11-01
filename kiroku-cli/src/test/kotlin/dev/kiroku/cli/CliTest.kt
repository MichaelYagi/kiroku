package dev.kiroku.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliTest {
    private val fixtures = Path.of(System.getProperty("kiroku.fixtures"))

    private fun run(vararg args: String): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = Cli(PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8)).run(args.toList())
        return code to out.toString(Charsets.UTF_8) + err.toString(Charsets.UTF_8)
    }

    @Test
    fun `summary, gallery JSON, capabilities and errors`() {
        val (code, text) = run("--no-external", "--summary", fixtures.resolve("media/generated/jpeg_full_exif.jpg").toString())
        assertEquals(0, code)
        assertTrue(text.contains("KT-1000 Mark II") && text.contains("ISO400"), text)
        val (c2, json) = run("--no-external", fixtures.resolve("media/generated/video_iphone_style.mov").toString())
        assertEquals(0, c2)
        assertTrue(json.contains("\"model\":\"iPhone 15 Pro\""), json)
        assertTrue(run("--capabilities", "--no-external").second.contains("\"NEF\""))
        assertEquals(1, run("--no-external", fixtures.resolve("does-not-exist.jpg").toString()).first)
        assertEquals(2, run("--bogus").first)
    }
}
