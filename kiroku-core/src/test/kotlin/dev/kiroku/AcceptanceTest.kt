package dev.kiroku

import dev.kiroku.model.AltitudeReference
import dev.kiroku.model.CameraMetadata
import dev.kiroku.model.DiagnosticCode
import dev.kiroku.model.MediaFormat
import dev.kiroku.model.MediaKind
import dev.kiroku.model.Rational
import dev.kiroku.model.StreamKind
import dev.kiroku.testing.Fixtures
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Acceptance scenarios from 07-TESTING-AND-ACCEPTANCE.md. Expected values come from the values the
 * fixture generator wrote (scripts/generate-fixtures.sh), cross-checked with ExifTool/ffprobe (see the
 * oracle tests), or for third-party samples from independent ExifTool reads recorded in fixtures/manifest.json.
 */
class AcceptanceTest {
    private fun x(path: java.nio.file.Path) = Fixtures.extractor.extract(path)

    @Nested
    inner class Routing {
        /** Every extension listed in the product requirements, with a fixture and the expected detection. */
        private val routes =
            listOf(
                Triple("jpeg_full_exif.jpg", MediaFormat.JPEG, MediaKind.IMAGE),
                Triple("png_xmp_text.png", MediaFormat.PNG, MediaKind.IMAGE),
                Triple("bmp_plain.bmp", MediaFormat.BMP, MediaKind.IMAGE),
                Triple("gif_animated.gif", MediaFormat.GIF, MediaKind.IMAGE),
                Triple("webp_exif_xmp.webp", MediaFormat.WEBP, MediaKind.IMAGE),
                Triple("tiff_basic.tif", MediaFormat.TIFF, MediaKind.IMAGE),
                Triple("video_iphone_style.mov", MediaFormat.MOV, MediaKind.VIDEO),
                Triple("video_android_style.mp4", MediaFormat.MP4, MediaKind.VIDEO),
                Triple("video_basic.avi", MediaFormat.AVI, MediaKind.VIDEO),
                Triple("video_basic.webm", MediaFormat.WEBM, MediaKind.VIDEO),
                Triple("video_raw.hevc", MediaFormat.HEVC, MediaKind.VIDEO),
                Triple("audio_bwf.wav", MediaFormat.WAV, MediaKind.AUDIO),
            )

        @Test
        fun `generated fixtures route by signature`() {
            for ((name, format, kind) in routes) {
                val r = x(Fixtures.gen(name))
                assertEquals(format.id, r.gallery.format, name)
                assertEquals(kind, r.gallery.mediaKind, name)
                assertEquals(ExtractionStatus.COMPLETE, r.status, "$name: ${r.diagnostics}")
                assertTrue(r.full.file.extensionMatches, name)
            }
        }

        @Test
        fun `camera samples route to RAW kinds`() {
            mapOf(
                "Nikon.nef" to MediaFormat.NEF,
                "CanonRaw.cr2" to MediaFormat.CR2,
                "Panasonic.rw2" to MediaFormat.RW2,
                "DNG.dng" to MediaFormat.DNG,
                "QuickTime.heic" to MediaFormat.HEIC,
            )
                .forEach { (name, fmt) ->
                    val r = x(Fixtures.et(name))
                    assertEquals(fmt.id, r.gallery.format, name)
                    assertEquals(if (fmt == MediaFormat.HEIC) MediaKind.IMAGE else MediaKind.RAW_IMAGE, r.gallery.mediaKind, name)
                }
            mapOf(
                "olympus_e3.orf" to MediaFormat.ORF,
                "sony_ilce7s.arw" to MediaFormat.ARW,
                "samsung_nx500.srw" to MediaFormat.SRW,
                "leica_dlux5.rwl" to MediaFormat.RWL,
                "nikon_d2h.nef" to MediaFormat.NEF,
                "canon_eos20d.cr2" to MediaFormat.CR2,
                "panasonic_lx7.rw2" to MediaFormat.RW2,
                "blackmagic_micro.dng" to MediaFormat.DNG,
            ).forEach { (name, fmt) ->
                val r = x(Fixtures.ext(name))
                assertEquals(fmt.id, r.gallery.format, name)
                assertEquals(MediaKind.RAW_IMAGE, r.gallery.mediaKind, name)
                assertEquals(ExtractionStatus.COMPLETE, r.status, "$name ${r.diagnostics}")
            }
        }

        @Test
        fun `all required extensions are known to the format table`() {
            val known = MediaFormat.entries.flatMap { it.extensions }.toSet()
            assertTrue(known.containsAll(MediaFormat.REQUIRED_EXTENSIONS), "missing: ${MediaFormat.REQUIRED_EXTENSIONS - known}")
            for (ext in MediaFormat.REQUIRED_EXTENSIONS) assertNotNull(MediaFormat.fromExtension(ext.uppercase()), ext)
        }

        @Test
        fun `a false extension is detected and reported`() {
            val r = x(Fixtures.gen("false_extension_png.jpg"))
            assertEquals("PNG", r.gallery.format)
            assertEquals(false, r.full.file.extensionMatches)
            assertEquals(MediaFormat.JPEG, r.full.file.extensionFormat)
            assertTrue(r.diagnostics.any { it.code == DiagnosticCode.EXTENSION_MISMATCH })
        }

        @Test
        fun `a JPEG uploaded as dng is detected as JPEG`() {
            val r = x(Fixtures.ext("mislabeled_jpeg_as.dng"))
            assertEquals("JPEG", r.gallery.format)
            assertTrue(r.diagnostics.any { it.code == DiagnosticCode.EXTENSION_MISMATCH })
        }

        @Test
        fun `unknown signature is UNSUPPORTED, not COMPLETE`() {
            val r = x(Fixtures.gen("corrupt_header.mp4"))
            assertEquals(ExtractionStatus.UNSUPPORTED, r.status)
            assertEquals(MediaKind.UNKNOWN, r.gallery.mediaKind)
            assertTrue(r.diagnostics.any { it.code == DiagnosticCode.UNKNOWN_FORMAT })
        }
    }

    @Nested
    inner class Images {
        @Test
        fun `scenario 1 - JPEG with full EXIF`() {
            val g = x(Fixtures.gen("jpeg_full_exif.jpg")).gallery
            assertEquals(
                CameraMetadata(
                    make = "Kiroku Test Optics",
                    model = "KT-1000 Mark II",
                    lensMake = "Kiroku",
                    lensModel = "KT 35mm F1.8",
                    lensSerialNumber = "LS-0042",
                    cameraSerialNumber = "BODY-12345",
                    software = "KT Firmware 1.2.3",
                ),
                g.camera,
            )
            val e = g.exposure!!
            assertEquals(400, e.iso)
            assertEquals(2.8, e.apertureFNumber!!, 1e-9)
            assertEquals(0.004, e.exposureTimeSeconds!!, 1e-9)
            assertEquals(Rational(1, 250), e.exposureTime!!.reduced())
            assertEquals(35.0, e.focalLengthMm!!, 1e-9)
            assertEquals(52.0, e.focalLength35mmEquivalentMm!!, 1e-9)
            assertEquals(-0.7, e.exposureBiasEv!!, 0.01)
            assertEquals("PATTERN", e.meteringMode)
            assertEquals("APERTURE_PRIORITY", e.exposureProgram)
            assertEquals(false, e.flash!!.fired)
            assertEquals("OFF", e.flash!!.mode)
            assertEquals("AUTO", e.whiteBalance)
            val t = g.capturedAt!!
            assertEquals(Instant.parse("2023-06-17T12:32:10.123Z"), t.instant)
            assertEquals(LocalDateTime.of(2023, 6, 17, 14, 32, 10, 123_000_000), t.localDateTime)
            assertEquals(ZoneOffset.ofHours(2), t.offset)
            assertEquals(true, t.timezoneKnown)
            assertEquals("EXIF:ExifIFD:DateTimeOriginal", t.source)
            assertEquals(Instant.parse("2023-06-18T07:00:00Z"), g.modifiedAt)
            val loc = g.location!!
            assertEquals(48.858222, loc.latitude!!, 1e-6)
            assertEquals(2.2945, loc.longitude!!, 1e-6)
            assertEquals(35.5, loc.altitudeMeters!!, 1e-6)
            assertEquals(AltitudeReference.ABOVE_SEA_LEVEL, loc.altitudeReference)
            assertEquals(123.4, loc.directionDegrees!!, 1e-6)
            assertEquals(Instant.parse("2023-06-17T12:32:09Z"), loc.locationTimestamp)
            assertEquals("WGS-84", loc.gpsDatum)
            assertEquals(6, g.orientation!!.exifCode)
            assertEquals(90, g.orientation!!.rotationDegrees)
            val d = g.dimensions!!
            assertEquals(640 to 480, d.storedWidth to d.storedHeight)
            assertEquals(480 to 640, d.displayWidth to d.displayHeight, "display dimensions apply orientation once")
            assertEquals("Eiffel Test", g.description!!.title)
            assertEquals(4, g.description!!.rating)
            assertEquals("Kiroku", g.description!!.credit)
            assertEquals(listOf("tower", "paris", "travel"), g.keywords)
            assertEquals("sRGB", g.color!!.colorSpace)
        }

        @Test
        fun `scenario 10 - timezone-free EXIF and a conflicting XMP model`() {
            val r = x(Fixtures.gen("jpeg_no_timezone.jpg"))
            val t = r.gallery.capturedAt!!
            assertNull(t.instant)
            assertNull(t.offset)
            assertEquals(false, t.timezoneKnown)
            assertEquals(LocalDateTime.of(2021, 12, 24, 18, 5), t.localDateTime)
            assertTrue(r.diagnostics.any { it.code == DiagnosticCode.TIMEZONE_UNKNOWN })
            assertEquals("KT-200", r.gallery.camera!!.model)
            val prov = r.full.fieldProvenance.getValue("camera.model")
            assertTrue(prov.conflict, "EXIF and XMP models disagree, so the conflict must be recorded")
            assertEquals("EXIF:IFD0:Model", prov.chosen!!.qualifiedName)
            assertTrue(prov.candidates.any { it.source.qualifiedName == "XMP:XMP-tiff:Model" && !it.accepted })
            assertTrue(r.diagnostics.any { it.code == DiagnosticCode.FIELD_CONFLICT && it.field == "camera.model" })
            assertTrue(r.full.find("XMP:XMP-tiff:Model").isNotEmpty(), "the losing value stays in full output")
        }

        @Test
        fun `a Unix-epoch QuickTime date is treated as unset`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
            // Some encoders write 1970-01-01T00:00:00Z when the clock is unset. Patch every mvhd/tkhd/mdhd date to it.
            val bytes = java.nio.file.Files.readAllBytes(Fixtures.gen("video_android_style.mp4"))
            val unixEpochInQtSeconds = 2_082_844_800L
            var patched = 0
            for (i in 4 until bytes.size - 16) {
                val type = String(bytes, i, 4, Charsets.ISO_8859_1)
                if ((type == "mvhd" || type == "tkhd" || type == "mdhd") && bytes[i + 4].toInt() == 0) {
                    for (field in 0..1) {
                        val at = i + 8 + field * 4
                        for (b in 0..3) bytes[at + b] = (unixEpochInQtSeconds shr (24 - 8 * b)).toByte()
                    }
                    patched++
                }
            }
            assertTrue(patched >= 3, "expected mvhd, tkhd and mdhd boxes")
            val file = dir.resolve("epoch.mp4")
            java.nio.file.Files.write(file, bytes)

            val r = x(file)
            assertNull(r.gallery.capturedAt)
            assertNull(r.gallery.modifiedAt)
            assertNull(r.gallery.video!!.creationTime)
            assertTrue(r.full.fieldProvenance.getValue("capturedAt").candidates.none { it.accepted })
        }

        @Test
        fun `a year-only date does not beat a full timestamp`() {
            // ExifTool's QuickTime.mov has an iTunes ©day of "2010" and an exact movie-header creation time.
            val r = x(Fixtures.et("QuickTime.mov"))
            val t = r.gallery.capturedAt!!
            assertEquals(Instant.parse("2005-08-11T14:03:54Z"), t.instant)
            assertEquals("QuickTime:MovieHeader:CreateDate", t.source)
            val prov = r.full.fieldProvenance.getValue("capturedAt")
            assertTrue(prov.candidates.any { it.source.qualifiedName == "QuickTime:ItemList:©day" }, "the year-only value stays a candidate")
        }

        @Test
        fun `scenario 2 - PNG with XMP and text, BMP without camera`() {
            val png = x(Fixtures.gen("png_xmp_text.png")).gallery
            assertEquals("PNG Title", png.description!!.title)
            assertEquals("PNG with XMP", png.description!!.caption)
            assertEquals("PNG Author", png.description!!.author)
            assertEquals("Generated by Kiroku", png.description!!.comment)
            assertEquals("PNG-Cam", png.camera!!.model)
            assertEquals(listOf("png"), png.keywords)
            val bmp = x(Fixtures.gen("bmp_plain.bmp")).gallery
            assertNull(bmp.camera)
            assertNull(bmp.capturedAt)
            assertNull(bmp.location)
            assertEquals(64 to 48, bmp.dimensions!!.storedWidth to bmp.dimensions!!.storedHeight)
            assertEquals(24, bmp.color!!.bitsPerPixel)
        }

        @Test
        fun `scenario 3 - animated GIF and WebP`() {
            val gif = x(Fixtures.gen("gif_animated.gif")).gallery
            assertEquals(10, gif.animation!!.frameCount)
            assertEquals(0, gif.animation!!.loopCount, "NETSCAPE loop 0 = forever")
            assertEquals(java.time.Duration.ofSeconds(1), gif.animation!!.duration)
            val webp = x(Fixtures.gen("webp_animated.webp")).gallery
            assertEquals(5, webp.animation!!.frameCount)
            assertEquals(3, webp.animation!!.loopCount)
            assertEquals(96 to 64, webp.dimensions!!.storedWidth to webp.dimensions!!.storedHeight)
            val still = x(Fixtures.gen("webp_exif_xmp.webp")).gallery
            assertEquals("WebP-Cam", still.camera!!.model)
            assertEquals(Instant.parse("2020-02-29T17:00:00Z"), still.capturedAt!!.instant)
            assertEquals("WebP Title", still.description!!.title)
            assertNull(still.animation)
        }

        @Test
        fun `scenario 4 - HEIC dimensions from the primary item`() {
            val g = x(Fixtures.et("QuickTime.heic")).gallery
            assertEquals("HEIC", g.format)
            assertEquals(1596 to 1064, g.dimensions!!.storedWidth to g.dimensions!!.storedHeight)
            assertEquals(0, g.orientation!!.rotationDegrees)
            assertEquals("4:2:0", g.color!!.chromaSubsampling)
        }

        @Test
        fun `scenario 5 - TIFF orientation and ICC`() {
            val g = x(Fixtures.gen("tiff_basic.tif")).gallery
            assertEquals(8, g.orientation!!.exifCode)
            assertEquals(270, g.orientation!!.rotationDegrees)
            assertEquals(90 to 120, g.dimensions!!.displayWidth to g.dimensions!!.displayHeight)
            val et = x(Fixtures.et("ExifTool.tif")).gallery
            assertEquals("sRGB IEC61966-2.1", et.color!!.iccProfileDescription)
        }
    }

    @Nested
    inner class Raw {
        @Test
        fun `scenario 6 - representative RAW samples with vendor tags`() {
            data class E(val file: String, val make: String, val model: String, val iso: Int?, val w: Int, val h: Int, val vendor: String)
            val expected =
                listOf(
                    E("nikon_d2h.nef", "NIKON CORPORATION", "NIKON D2H", 200, 2496, 1648, "Nikon"),
                    E("canon_eos20d.cr2", "Canon", "Canon EOS 20D", 800, 3504, 2336, "Canon"),
                    E("olympus_e3.orf", "OLYMPUS IMAGING CORP.", "E-3", 100, 3720, 2800, "Olympus"),
                    E("sony_ilce7s.arw", "SONY", "ILCE-7S", 400, 2816, 1872, "Sony"),
                    E("panasonic_lx7.rw2", "Panasonic", "DMC-LX7", 80, 1888, 1260, "Panasonic"),
                    E("leica_dlux5.rwl", "LEICA", "D-LUX 5", 80, 2736, 2736, "Leica"),
                    E("samsung_nx500.srw", "SAMSUNG", "NX500", 100, 6496, 4336, "Samsung"),
                )
            for (e in expected) {
                val r = x(Fixtures.ext(e.file))
                val g = r.gallery
                assertEquals(e.make, g.camera?.make, e.file)
                assertEquals(e.model, g.camera?.model, e.file)
                if (e.iso != null) assertEquals(e.iso, g.exposure?.iso, e.file)
                assertEquals(e.w to e.h, g.dimensions!!.storedWidth to g.dimensions!!.storedHeight, e.file)
                assertNotNull(g.capturedAt, e.file)
                assertTrue(
                    r.full.observations.count {
                        it.groupPath.getOrNull(0) == "MakerNotes" &&
                            it.groupPath.getOrNull(1)?.startsWith(e.vendor) == true
                    } >
                        5,
                    "${e.file}: MakerNotes:${e.vendor}",
                )
            }
        }

        @Test
        fun `DNG uses DefaultCropSize and keeps DNG tags`() {
            val r = x(Fixtures.et("DNG.dng"))
            assertEquals("Canon EOS 350D DIGITAL", r.gallery.camera!!.model)
            assertEquals("Canon350D.CR2", r.gallery.description!!.originalFilename)
            assertTrue(r.full.find("EXIF:IFD0:DNGVersion").isNotEmpty())
            assertEquals("dimensions", r.full.fieldProvenance["dimensions"]!!.field)
        }
    }

    @Nested
    inner class Video {
        @Test
        fun `scenario 7 - iPhone-style MOV populates the same camera fields as a photo`() {
            val r = x(Fixtures.gen("video_iphone_style.mov"))
            val g = r.gallery
            assertEquals(MediaKind.VIDEO, g.mediaKind)
            assertEquals("Apple", g.camera!!.make)
            assertEquals("iPhone 15 Pro", g.camera!!.model)
            assertEquals("17.4.1", g.camera!!.software)
            assertEquals(Instant.parse("2024-05-03T01:15:30Z"), g.capturedAt!!.instant)
            assertEquals(ZoneOffset.ofHours(9), g.capturedAt!!.offset)
            assertEquals("QuickTime:Keys:com.apple.quicktime.creationdate", g.capturedAt!!.source)
            assertEquals(35.6895, g.location!!.latitude!!, 1e-9)
            assertEquals(139.6917, g.location!!.longitude!!, 1e-9)
            assertEquals(40.0, g.location!!.altitudeMeters!!, 1e-9)
            assertEquals(90, g.orientation!!.rotationDegrees)
            assertEquals(false, g.orientation!!.mirrored)
            assertEquals(320 to 240, g.dimensions!!.storedWidth to g.dimensions!!.storedHeight)
            assertEquals(240 to 320, g.dimensions!!.displayWidth to g.dimensions!!.displayHeight)
            assertEquals("hevc", g.video!!.codec)
            assertEquals("MOV", g.video!!.container)
            assertEquals(Rational(30, 1), g.video!!.frameRate)
            assertEquals(90, g.video!!.rotationDegrees)
            assertEquals("aac", g.audio!!.codec)
            assertEquals(1, g.audio!!.channels)
            assertEquals(48000, g.audio!!.sampleRateHz)
            // The container creation time is kept as an alternative, not mixed into capturedAt.
            assertEquals(Instant.parse("2024-05-03T01:15:31Z"), g.video!!.creationTime)
            assertTrue(
                r.full.fieldProvenance.getValue("capturedAt").candidates.any {
                    it.source.qualifiedName ==
                        "QuickTime:MovieHeader:CreateDate"
                },
            )
        }

        @Test
        fun `Android-style MP4 with udta location and device tags`() {
            val g = x(Fixtures.gen("video_android_style.mp4")).gallery
            assertEquals(37.4220, g.location!!.latitude!!, 1e-3)
            assertEquals(-122.0841, g.location!!.longitude!!, 1e-3)
            assertEquals("h264", g.video!!.codec)
            assertEquals(Instant.parse("2023-09-01T08:00:00Z"), g.capturedAt!!.instant)
        }

        @Test
        fun `scenario 8 - stripped MP4 has null camera, time and location`() {
            for (name in listOf("video_stripped.mp4", "video_no_timestamps.mp4")) {
                val g = x(Fixtures.gen(name)).gallery
                assertNull(g.camera?.make, name)
                assertNull(g.camera?.model, name)
                assertNull(g.capturedAt, "$name: no capture time may be invented")
                assertNull(g.location, name)
                assertNotNull(g.video?.duration, name)
            }
        }

        @Test
        fun `scenario 9 - AVI, WebM, raw HEVC and WAV`() {
            val avi = x(Fixtures.gen("video_basic.avi")).gallery
            assertEquals("mpeg4", avi.video!!.codec)
            assertEquals(Rational(30, 1), avi.video!!.frameRate)
            assertEquals("AVI Title", avi.description!!.title)
            assertEquals("pcm", avi.audio!!.codec)
            assertEquals(48000, avi.audio!!.sampleRateHz)
            val webm = x(Fixtures.gen("video_basic.webm")).gallery
            assertEquals("vp9", webm.video!!.codec)
            assertEquals("opus", webm.audio!!.codec)
            assertEquals("WebM Title", webm.description!!.title)
            assertNull(webm.camera?.make)
            val hevc = x(Fixtures.gen("video_raw.hevc")).gallery
            assertEquals("hevc", hevc.video!!.codec)
            assertNull(hevc.video!!.container, "an elementary stream has no container")
            assertEquals("Main", hevc.video!!.codecProfile)
            assertEquals(320 to 240, hevc.dimensions!!.storedWidth to hevc.dimensions!!.storedHeight)
            assertNull(hevc.capturedAt)
            assertNull(hevc.video!!.duration)
            val wav = x(Fixtures.gen("audio_bwf.wav")).gallery
            assertEquals(MediaKind.AUDIO, wav.mediaKind)
            assertNull(wav.video)
            assertNull(wav.dimensions)
            assertEquals(1, wav.audio!!.channels)
            assertEquals(48000, wav.audio!!.sampleRateHz)
            assertEquals(16, wav.audio!!.bitsPerSample)
            assertEquals(java.time.Duration.ofSeconds(1), wav.audio!!.duration)
            assertEquals("WAV Title", wav.description!!.title)
            assertEquals(LocalDateTime.of(2022, 3, 4, 5, 6, 7), wav.capturedAt!!.localDateTime)
        }

        @Test
        fun `scenario 10 - HDR, VFR, mixed streams`() {
            val hdr = x(Fixtures.gen("video_hdr10.mp4")).gallery
            assertEquals(true, hdr.color!!.hdr)
            assertEquals("SMPTE ST 2084 (PQ)", hdr.color!!.transferCharacteristics)
            assertEquals("BT.2020", hdr.color!!.colorPrimaries)
            assertEquals(10, hdr.color!!.bitDepth)
            assertEquals("Main 10", hdr.video!!.codecProfile)
            val vfr = x(Fixtures.gen("video_vfr.mp4")).gallery
            assertEquals(true, vfr.video!!.variableFrameRate)
            val cfr = x(Fixtures.gen("video_android_style.mp4")).gallery
            assertEquals(false, cfr.video!!.variableFrameRate)
            val mixed = x(Fixtures.gen("video_mixed_streams.mp4"))
            assertEquals(2, mixed.gallery.video!!.videoStreamCount)
            assertEquals(2, mixed.gallery.video!!.audioStreamCount)
            assertEquals(listOf("eng", "jpn"), mixed.gallery.audio!!.tracks.map { it.language })
            assertTrue(mixed.full.streams.any { it.kind == StreamKind.SUBTITLE })
            assertTrue(mixed.diagnostics.any { it.code == DiagnosticCode.PRIMARY_STREAM_SELECTED })
            assertEquals(0, mixed.gallery.video!!.primaryStreamIndex)
        }

        @Test
        fun `real camera MOV keeps UTF-8 text and the container date`() {
            val g = x(Fixtures.et("QuickTime.mov")).gallery
            assertEquals("çømménts", g.description!!.comment)
            assertEquals(Instant.parse("2005-08-11T14:03:54Z"), g.video!!.creationTime)
        }
    }

    @Nested
    inner class Contract {
        @Test
        fun `camera fields are one shared type for image, RAW and video`() {
            val names = CameraMetadata::class.java.declaredFields.filter {
                !java.lang.reflect.Modifier.isStatic(it.modifiers)
            }.map { it.name }.toSet()
            assertEquals(
                setOf(
                    "make", "model", "deviceName", "lensMake", "lensModel", "lensSerialNumber", "cameraSerialNumber",
                    "firmware", "software", "owner", "cameraType", "deviceIdentifier",
                ),
                names,
            )
            val photo = x(Fixtures.gen("jpeg_full_exif.jpg")).gallery.camera!!
            val video = x(Fixtures.gen("video_iphone_style.mov")).gallery.camera!!
            assertEquals(photo::class, video::class)
        }

        @Test
        fun `results are deterministic`() {
            for (f in Fixtures.committed()) {
                val a = x(f)
                val b = x(f)
                assertEquals(a.gallery, b.gallery, f.toString())
                assertEquals(a.full.observations, b.full.observations, f.toString())
                assertEquals(a.full.fieldProvenance, b.full.fieldProvenance, f.toString())
            }
        }

        @Test
        fun `schema version is set`() {
            assertEquals(MetadataResult.SCHEMA_VERSION, x(Fixtures.gen("bmp_plain.bmp")).schemaVersion)
        }

        @Test
        fun `extraction never modifies the source file`() {
            for (f in Fixtures.committed()) {
                val before = java.nio.file.Files.readAllBytes(f)
                val mtime = java.nio.file.Files.getLastModifiedTime(f)
                x(f)
                assertTrue(before.contentEquals(java.nio.file.Files.readAllBytes(f)), f.toString())
                assertEquals(mtime, java.nio.file.Files.getLastModifiedTime(f))
            }
        }

        @Test
        fun `filesystem time is used for capturedAt only on opt-in`() {
            val f = Fixtures.gen("bmp_plain.bmp")
            assertNull(Fixtures.extractor.extract(f).gallery.capturedAt)
            val r = Fixtures.extractor.extract(f, ExtractionOptions(fileSystemTimeAsCaptureFallback = true))
            assertEquals("FileSystem:ModifyDate", r.gallery.capturedAt!!.source)
            assertTrue(r.diagnostics.any { it.code == DiagnosticCode.CAPTURE_TIME_FROM_FILESYSTEM })
        }

        @Test
        fun `GPS policies`() {
            val f = Fixtures.gen("jpeg_full_exif.jpg")
            val coarse = Fixtures.extractor.extract(f, ExtractionOptions(gpsPolicy = GpsPolicy.COARSE_GALLERY)).gallery.location!!
            assertEquals(48.86, coarse.latitude!!, 1e-9)
            assertNull(coarse.locationTimestamp)
            val excluded = Fixtures.extractor.extract(f, ExtractionOptions(gpsPolicy = GpsPolicy.EXCLUDE_GALLERY))
            assertNull(excluded.gallery.location)
            assertTrue(excluded.full.find("EXIF:GPS:GPSLatitude").single().original!!.bytes != null, "full output untouched")
            val all = Fixtures.extractor.extract(f, ExtractionOptions(gpsPolicy = GpsPolicy.EXCLUDE_ALL))
            assertNull(all.gallery.location)
            val lat = all.full.find("EXIF:GPS:GPSLatitude").single()
            assertEquals(dev.kiroku.model.NormalizedValue.Null, lat.value)
            assertNull(lat.original!!.bytes)
            assertEquals(dev.kiroku.model.OmissionReason.REDACTED, lat.original!!.omittedReason)
            assertTrue(all.diagnostics.none { d -> d.message.contains("48.85") }, "coordinates never appear in diagnostics")
            val mov = Fixtures.extractor.extract(
                Fixtures.gen("video_iphone_style.mov"),
                ExtractionOptions(gpsPolicy = GpsPolicy.EXCLUDE_ALL),
            )
            assertTrue(
                mov.full.observations.filter { it.tagName.contains("location") }.all {
                    it.value ==
                        dev.kiroku.model.NormalizedValue.Null
                },
            )
        }

        @Test
        fun `includeRawTags false drops observations but not the gallery`() {
            val r = Fixtures.extractor.extract(Fixtures.gen("jpeg_full_exif.jpg"), ExtractionOptions(includeRawTags = false))
            assertTrue(r.full.observations.isEmpty())
            assertEquals("KT-1000 Mark II", r.gallery.camera!!.model)
        }

        @Test
        fun `FAST depth skips MakerNote decoding but keeps the blob`() {
            val r = Fixtures.extractor.extract(Fixtures.et("Canon.jpg"), ExtractionOptions(depth = ExtractionDepth.FAST))
            assertTrue(r.full.observations.none { it.groupPath.firstOrNull() == "MakerNotes" })
            assertTrue(r.full.find(listOf("EXIF", "ExifIFD"), 0x927C).isNotEmpty())
            assertEquals("Canon", r.gallery.camera!!.make)
        }

        @Test
        fun `checksum only when requested`() {
            val f = Fixtures.gen("bmp_plain.bmp")
            assertNull(Fixtures.extractor.extract(f).gallery.file.checksum)
            val c = Fixtures.extractor.extract(f, ExtractionOptions(computeChecksum = true)).gallery.file.checksum!!
            val expected = java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(f)).joinToString("") {
                "%02x".format(it)
            }
            assertEquals("sha256:$expected", c)
        }
    }
}
