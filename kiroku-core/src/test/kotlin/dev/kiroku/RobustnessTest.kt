package dev.kiroku

import dev.kiroku.model.DiagnosticCode
import dev.kiroku.testing.Fixtures
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Random
import java.util.concurrent.TimeUnit
import kotlin.io.path.name
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RobustnessTest {
    @TempDir
    lateinit var tmp: Path

    /** Small fixtures with distinct container structures. */
    private val structural =
        listOf(
            "jpeg_full_exif.jpg", "png_xmp_text.png", "gif_animated.gif", "webp_exif_xmp.webp", "tiff_basic.tif", "bmp_plain.bmp",
            "video_iphone_style.mov", "video_basic.avi", "video_basic.webm", "video_raw.hevc", "audio_bwf.wav",
        ).map { Fixtures.gen(it) } +
            listOf(
                "Nikon.nef",
                "CanonRaw.cr2",
                "Panasonic.rw2",
                "DNG.dng",
                "QuickTime.heic",
                "Canon.jpg",
                "Apple.jpg",
                "Matroska.mkv",
            ).map {
                Fixtures.et(it)
            }

    @TestFactory
    fun `truncation at many offsets never throws`(): List<DynamicTest> = structural.map { f ->
        DynamicTest.dynamicTest(f.name) {
            val bytes = Files.readAllBytes(f)
            val cuts = (1..40).map { (bytes.size.toLong() * it / 41).toInt() } + listOf(1, 2, 3, 4, 8, 12, 16)
            for (cut in cuts.distinct()) {
                val t = tmp.resolve("cut_${cut}_${f.name}")
                t.writeBytes(bytes.copyOf(cut))
                val r = Fixtures.extractor.extract(t)
                assertNotEquals(ExtractionStatus.COMPLETE, r.status.takeIf { cut < 4 }, "a $cut-byte file cannot be COMPLETE")
                if (r.status == ExtractionStatus.COMPLETE && cut < bytes.size / 2) {
                    // Some formats legitimately complete with the metadata prefix only; then no failure diagnostics may appear.
                    assertTrue(
                        r.diagnostics.none {
                            it.code == DiagnosticCode.INTERNAL_ERROR || it.code == DiagnosticCode.BACKEND_FAILED
                        },
                        "${f.name}@$cut ${r.diagnostics}",
                    )
                }
                assertTrue(
                    r.diagnostics.none {
                        it.code == DiagnosticCode.INTERNAL_ERROR || it.code == DiagnosticCode.BACKEND_FAILED
                    },
                    "${f.name} cut at $cut: ${r.diagnostics}",
                )
                Files.delete(t)
            }
        }
    }

    /**
     * CR3 and RAF samples are downloaded, not committed. Their metadata is not all in the first 4 KiB, so corruption
     * targets the regions the parsers read: the CR3 `moov` box, and the RAF header, JPEG EXIF segment and RAF directory.
     */
    @TestFactory
    fun `CR3 and RAF survive truncation and corruption of their metadata`(): List<DynamicTest> =
        listOf("canon_eos_r6.cr3", "fujifilm_xs10.raf", "fujifilm_s5000.raf").map { name ->
            DynamicTest.dynamicTest(name) {
                val bytes = Files.readAllBytes(Fixtures.ext(name))
                val regions =
                    if (name.endsWith(".raf")) {
                        val dir = java.nio.ByteBuffer.wrap(bytes, 0x5C, 4).int
                        listOf(0 until 0x94, 0x94 until 0x94 + 65_536, dir until minOf(bytes.size, dir + 4096))
                    } else {
                        listOf(0 until minOf(bytes.size, 65_536))
                    }
                fun check(r: dev.kiroku.MetadataResult, what: String) = assertTrue(
                    r.diagnostics.none { it.code == DiagnosticCode.INTERNAL_ERROR || it.code == DiagnosticCode.BACKEND_FAILED },
                    "$name $what: ${r.diagnostics}",
                )
                for (i in 1..30) {
                    val cut = (bytes.size.toLong() * i / 31).toInt()
                    val t = tmp.resolve("cut_${cut}_$name")
                    t.writeBytes(bytes.copyOf(cut))
                    check(Fixtures.extractor.extract(t), "cut at $cut")
                    Files.delete(t)
                }
                val rnd = Random(name.hashCode().toLong())
                repeat(25) { round ->
                    val copy = bytes.copyOf()
                    repeat(1 + rnd.nextInt(32)) {
                        val region = regions[rnd.nextInt(regions.size)]
                        copy[region.first + rnd.nextInt(region.last - region.first + 1)] = rnd.nextInt(256).toByte()
                    }
                    val t = tmp.resolve("fuzz_${round}_$name")
                    t.writeBytes(copy)
                    check(Fixtures.extractor.extract(t, ExtractionOptions(timeout = Duration.ofSeconds(10))), "round $round")
                    Files.delete(t)
                }
            }
        }

    @TestFactory
    fun `seeded random byte corruption never throws or fails a backend`(): List<DynamicTest> = structural.map { f ->
        DynamicTest.dynamicTest(f.name) {
            val bytes = Files.readAllBytes(f)
            val rnd = Random(f.name.hashCode().toLong())
            repeat(30) { round ->
                val copy = bytes.copyOf()
                // Corrupt the metadata-heavy head of the file.
                val span = minOf(copy.size, 4096)
                repeat(1 + rnd.nextInt(24)) { copy[rnd.nextInt(span)] = rnd.nextInt(256).toByte() }
                val t = tmp.resolve("fuzz_${round}_${f.name}")
                t.writeBytes(copy)
                val r = Fixtures.extractor.extract(t, ExtractionOptions(timeout = Duration.ofSeconds(10)))
                assertTrue(
                    r.diagnostics.none { it.code == DiagnosticCode.INTERNAL_ERROR || it.code == DiagnosticCode.BACKEND_FAILED },
                    "${f.name} round $round: ${r.diagnostics.filter {
                        it.code == DiagnosticCode.INTERNAL_ERROR || it.code == DiagnosticCode.BACKEND_FAILED
                    }}",
                )
                Files.delete(t)
            }
        }
    }

    @Test
    fun `IFD loops and absurd counts are bounded`() {
        // A little-endian TIFF whose IFD0 points to itself as the next IFD, and whose single entry declares a huge count.
        val b = java.nio.ByteBuffer.allocate(64).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("II".toByteArray()).putShort(42).putInt(8)
        b.putShort(1)
        b.putShort(0x010E).putShort(2).putInt(0x7FFFFFFF).putInt(40) // ImageDescription, ASCII, absurd count
        b.putInt(8) // next IFD = itself
        val f = tmp.resolve("loop.tif")
        f.writeBytes(b.array())
        val r = Fixtures.extractor.extract(f)
        assertTrue(r.diagnostics.any { it.code == DiagnosticCode.MALFORMED })
        assertTrue(r.diagnostics.none { it.code == DiagnosticCode.INTERNAL_ERROR })
        assertEquals(ExtractionStatus.PARTIAL, r.status)
    }

    @Test
    fun `observation limit truncates and reports PARTIAL`() {
        val r = Fixtures.extractor.extract(Fixtures.et("Canon.jpg"), ExtractionOptions(limits = ResourceLimits(maxObservations = 10)))
        assertEquals(10, r.full.observations.size)
        assertEquals(ExtractionStatus.PARTIAL, r.status)
        assertTrue(r.diagnostics.any { it.code == DiagnosticCode.LIMIT_EXCEEDED })
    }

    @Test
    fun `huge string values are clipped to the limit`() {
        val r = Fixtures.extractor.extract(
            Fixtures.gen("jpeg_full_exif.jpg"),
            ExtractionOptions(limits = ResourceLimits(maxStringLength = 4)),
        )
        assertTrue(
            r.full.observations.all {
                (it.value as? dev.kiroku.model.NormalizedValue.Text)?.value?.length?.let { l -> l <= 4 }
                    ?: true
            },
        )
    }

    @Test
    fun `file size limit fails fast`() {
        val r = Fixtures.extractor.extract(
            Fixtures.gen("jpeg_full_exif.jpg"),
            ExtractionOptions(limits = ResourceLimits(maxFileSizeBytes = 100)),
        )
        assertEquals(ExtractionStatus.FAILED, r.status)
        assertEquals(DiagnosticCode.FILE_TOO_LARGE, r.diagnostics.single().code)
    }

    @Test
    fun `missing file, directory and empty file`() {
        assertEquals(DiagnosticCode.FILE_NOT_FOUND, Fixtures.extractor.extract(tmp.resolve("nope.jpg")).diagnostics.single().code)
        assertEquals(ExtractionStatus.FAILED, Fixtures.extractor.extract(tmp).status)
        val empty = tmp.resolve("empty.jpg").also { Files.createFile(it) }
        assertEquals(ExtractionStatus.UNSUPPORTED, Fixtures.extractor.extract(empty).status)
    }

    @Test
    fun `unicode, spaces and quotes in paths`() {
        // Windows forbids '"' in file names; every other hostile character is valid there too.
        val windows = System.getProperty("os.name").lowercase().contains("win")
        val dir = Files.createDirectories(tmp.resolve(if (windows) "写真 'and' \$dollar; rm -rf" else "写真 \"quoted\" 'and' \$dollar; rm -rf"))
        val f = dir.resolve("Ünïcødé  file;\$(touch pwned).jpg")
        Files.copy(Fixtures.gen("jpeg_full_exif.jpg"), f)
        val r = Fixtures.extractor.extract(f)
        assertEquals(ExtractionStatus.COMPLETE, r.status)
        assertEquals("Ünïcødé  file;\$(touch pwned).jpg", r.gallery.file.name)
        assertTrue(r.diagnostics.none { it.message.contains(dir.toString()) }, "diagnostics must not leak paths")
        assertTrue(Files.notExists(Path.of("pwned")))
    }

    @Test
    fun `symlink policy and allowed roots`() {
        val inside = Files.createDirectories(tmp.resolve("inside"))
        val outside = Files.createDirectories(tmp.resolve("outside"))
        val target = outside.resolve("secret.jpg")
        Files.copy(Fixtures.gen("jpeg_full_exif.jpg"), target)
        val link = inside.resolve("link.jpg")
        try {
            Files.createSymbolicLink(link, target)
        } catch (e: Exception) {
            org.junit.jupiter.api.Assumptions.abort<Unit>("symlinks unsupported on this platform: ${e.javaClass.simpleName}")
        }
        MediaMetadata.create(MetadataConfig(allowedRoots = listOf(inside))).use { ex ->
            val r = ex.extract(link)
            assertEquals(ExtractionStatus.FAILED, r.status)
            assertEquals(
                DiagnosticCode.PATH_NOT_ALLOWED,
                r.diagnostics.single().code,
                "symlink escape outside the allowed root is rejected",
            )
        }
        MediaMetadata.create(MetadataConfig(symlinkPolicy = SymlinkPolicy.REJECT)).use { ex ->
            assertEquals(DiagnosticCode.SYMLINK_REJECTED, ex.extract(link).diagnostics.single().code)
        }
        MediaMetadata.create(MetadataConfig(allowedRoots = listOf(outside))).use { ex ->
            assertEquals(ExtractionStatus.COMPLETE, ex.extract(link).status)
        }
    }

    @Test
    fun `batch keeps order, isolates failures and honours parallelism`() {
        val files = Fixtures.committed() + tmp.resolve("missing.jpg")
        MediaMetadata.create(MetadataConfig(batchParallelism = 3)).use { ex ->
            val results = ex.extractBatch(files.asSequence()).toList()
            assertEquals(files.size, results.size)
            results.zip(files).forEach { (r, f) -> assertEquals(f.name, r.gallery.file.name) }
            assertEquals(ExtractionStatus.FAILED, results.last().status)
        }
    }

    @Test
    fun `batch iterator can be closed early`() {
        MediaMetadata.create(MetadataConfig(batchParallelism = 2)).use { ex ->
            val it = ex.extractBatchIterator(List(50) { Fixtures.gen("jpeg_full_exif.jpg") }, ExtractionOptions.DEFAULT)
            assertTrue(it.hasNext())
            it.next()
            it.close()
        }
    }

    @Test
    fun `async and future APIs`() {
        val f = Fixtures.gen("video_iphone_style.mov")
        runBlocking {
            val a = async { Fixtures.extractor.extractAsync(f) }
            assertEquals("Apple", a.await().gallery.camera!!.make)
        }
        assertEquals("Apple", Fixtures.extractor.extractFuture(f).get(10, TimeUnit.SECONDS).gallery.camera!!.make)
    }

    @Test
    fun `a closed extractor rejects use`() {
        val ex = MediaMetadata.create()
        ex.close()
        ex.close()
        kotlin.test.assertFailsWith<IllegalStateException> { ex.extract(Fixtures.gen("bmp_plain.bmp")) }
    }

    @Test
    fun `performance - 1000 mixed extractions with bounded heap`() {
        val files = Fixtures.committed()
        val rt = Runtime.getRuntime()
        System.gc()
        val before = rt.totalMemory() - rt.freeMemory()
        val t0 = System.nanoTime()
        var obs = 0L
        MediaMetadata.create(MetadataConfig(batchParallelism = 4)).use { ex ->
            ex.extractBatch(generateSequence(0) { it + 1 }.take(1000).map { files[it % files.size] }).forEach {
                obs +=
                    it.full.observations.size
            }
        }
        val seconds = (System.nanoTime() - t0) / 1e9
        val after = rt.totalMemory() - rt.freeMemory()
        val line = "PERF 1000 extractions of ${files.size} committed fixtures: %.2f s (%.1f files/s), %d observations, heap delta %d MiB, max heap %d MiB"
            .format(seconds, 1000 / seconds, obs, (after - before) / (1 shl 20), rt.maxMemory() / (1 shl 20))
        println(line)
        System.getenv("KIROKU_PERF_OUT")?.let { Files.writeString(Path.of(it), line + "\n") }
        assertTrue(seconds < 120, line)
    }

    @Test
    fun `large files are not read in full`() {
        // A 64 MiB MP4 whose mdat is mostly padding: extraction must not touch the media data.
        val src = Files.readAllBytes(Fixtures.gen("video_android_style.mp4"))
        val big = tmp.resolve("big.mp4")
        Files.newOutputStream(big).use { out ->
            out.write(src)
            // An extra top-level 'free' box of 64 MiB.
            val size = 64 * 1024 * 1024
            out.write(java.nio.ByteBuffer.allocate(8).putInt(size).put("free".toByteArray()).array())
            val chunk = ByteArray(1 shl 20)
            repeat(64) { out.write(chunk) }
        }
        val t0 = System.nanoTime()
        val r = Fixtures.extractor.extract(big)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(ExtractionStatus.COMPLETE, r.status, r.diagnostics.toString())
        assertTrue(ms < 5_000, "took $ms ms")
    }
}
