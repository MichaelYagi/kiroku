package dev.kiroku.normalize

import dev.kiroku.ExtractionOptions
import dev.kiroku.GpsPolicy
import dev.kiroku.format.isobmff.CICP
import dev.kiroku.format.isobmff.IsoBmffParser
import dev.kiroku.model.AltitudeReference
import dev.kiroku.model.AnimationMetadata
import dev.kiroku.model.AudioMetadata
import dev.kiroku.model.AudioTrackSummary
import dev.kiroku.model.CameraMetadata
import dev.kiroku.model.CaptureTimestamp
import dev.kiroku.model.ColorMetadata
import dev.kiroku.model.ContainerInfo
import dev.kiroku.model.DescriptionMetadata
import dev.kiroku.model.DiagnosticCode
import dev.kiroku.model.Dimensions
import dev.kiroku.model.EmbeddedImageInfo
import dev.kiroku.model.EmbeddedImageKind
import dev.kiroku.model.ExposureMetadata
import dev.kiroku.model.FieldProvenance
import dev.kiroku.model.FileProbe
import dev.kiroku.model.FlashInfo
import dev.kiroku.model.GalleryFile
import dev.kiroku.model.GalleryMetadata
import dev.kiroku.model.GeoMetadata
import dev.kiroku.model.MediaFormat
import dev.kiroku.model.MediaKind
import dev.kiroku.model.MetadataDiagnostic
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.Orientation
import dev.kiroku.model.Rational
import dev.kiroku.model.RawObservation
import dev.kiroku.model.Severity
import dev.kiroku.model.StreamInfo
import dev.kiroku.model.StreamKind
import dev.kiroku.model.TimestampPrecision
import dev.kiroku.model.VideoMetadata
import dev.kiroku.model.VideoTrackSummary
import dev.kiroku.model.asDouble
import dev.kiroku.model.asList
import dev.kiroku.model.asLong
import dev.kiroku.model.asRational
import dev.kiroku.model.asText
import dev.kiroku.spi.MetadataCollector
import dev.kiroku.spi.StreamProps
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

internal class Projection(val gallery: GalleryMetadata, val provenance: Map<String, FieldProvenance>, val diagnostics: List<MetadataDiagnostic>)

/**
 * Builds [GalleryMetadata] from the full observations. Every value comes from an observation (or a
 * stream/container summary built from observations), and each field's choice is recorded in
 * [FieldProvenance]. Display strings are never parsed for numbers.
 */
internal class GalleryProjector(
    private val probe: FileProbe,
    private val observations: List<RawObservation>,
    private val streams: List<StreamInfo>,
    private val containers: List<ContainerInfo>,
    private val embedded: List<EmbeddedImageInfo>,
    private val options: ExtractionOptions,
) {
    private val r = Resolver(observations)
    private val diags = ArrayList<MetadataDiagnostic>()
    private val format = probe.detectedFormat
    private val isHeif = format == MediaFormat.HEIC || format == MediaFormat.HEIF || format == MediaFormat.AVIF
    private val isRaw = probe.mediaKind == MediaKind.RAW_IMAGE

    // ---- Groups ----
    private val exif0 = group("EXIF", "IFD0")
    private val exifIfd = group("EXIF", "ExifIFD")
    private val exifAny = { g: List<String> -> g.getOrNull(0) == "EXIF" && g.getOrNull(1) != "IFD1" }
    private val gps = group("EXIF", "GPS")
    private val keys = group("QuickTime", "Keys")
    private val udta = group("QuickTime", "UserData")
    private val ilst = group("QuickTime", "ItemList")
    private val mvhd = group("QuickTime", "MovieHeader", "QuickTime")
    private val qtMeta = group("QuickTime", "Meta")
    private val iptc = group("IPTC")
    private val riffInfo = group("RIFF", "INFO")
    private val mkv = group("Matroska", "Matroska")
    private val mkvTags = group("Matroska", "Tags")
    private val png = { g: List<String> -> g.getOrNull(0) == "PNG" }
    private val maker = { g: List<String> -> g.getOrNull(0) == "MakerNotes" }
    private val composite = group("Composite")
    private val id3v2 = group("ID3", "ID3v2_2", "ID3v2_3", "ID3v2_4")
    private val id3v1 = group("ID3", "ID3v1")

    private fun xmp(ns: String) = group("XMP", "XMP-$ns")

    fun project(): Projection {
        val kind = effectiveKind()
        val video = if (kind == MediaKind.VIDEO ||
            streams.any { it.kind == StreamKind.VIDEO } && probe.mediaKind == MediaKind.VIDEO
        ) {
            video()
        } else {
            null
        }
        val audio = if (streams.any { it.kind == StreamKind.AUDIO }) audio() else null
        val orientation = orientation(kind)
        val gallery =
            GalleryMetadata(
                mediaKind = kind,
                format = format.id,
                mimeType = probe.mimeType,
                file =
                GalleryFile(
                    probe.name,
                    probe.extension,
                    probe.sizeBytes,
                    format.id,
                    probe.mimeType,
                    probe.createdAtFileSystem,
                    probe.modifiedAtFileSystem,
                    probe.checksum,
                ),
                capturedAt = capturedAt(),
                modifiedAt = modifiedAt(),
                dimensions = dimensions(kind, orientation),
                orientation = orientation,
                camera = camera(),
                exposure = exposure(),
                location = location(),
                description = description(),
                color = color(kind),
                video = video,
                audio = audio,
                animation = animation(),
                keywords = keywords(),
                extra = extra(),
            )
        return Projection(gallery, r.provenance, diags + r.diagnostics)
    }

    private fun effectiveKind(): MediaKind {
        val k = probe.mediaKind
        if (k == MediaKind.VIDEO && streams.isNotEmpty() && streams.none { it.kind == StreamKind.VIDEO } &&
            streams.any { it.kind == StreamKind.AUDIO }
        ) {
            return MediaKind.AUDIO
        }
        return k
    }

    // ---- Converters ----

    private fun textOf(v: NormalizedValue): String? = when (v) {
        is NormalizedValue.Text -> v.value
        is NormalizedValue.ListValue -> v.items.firstNotNullOfOrNull { textOf(it)?.takeIf { s -> s.isNotBlank() } }
        is NormalizedValue.ObjectValue -> (v.fields["x-default"] ?: v.fields.values.firstOrNull())?.let { textOf(it) }
        is NormalizedValue.Integer -> v.value.toString()
        else -> null
    }?.replace('\u0000', ' ')?.trim()?.replace(Regex("\\s{2,}"), " ")

    private val str: (RawObservation) -> Conversion<String> = { o ->
        val t = textOf(o.value)
        when {
            t == null -> Conversion.Rejected("not text")
            t.isBlank() -> Conversion.Rejected("blank")
            t.all { !it.isLetterOrDigit() } -> Conversion.Rejected("placeholder punctuation '$t'")
            else -> Conversion.Ok(t)
        }
    }

    /** Text only when the stored value is text, so vendor numeric codes are never shown as names. */
    private val textOnly: (RawObservation) -> Conversion<String> = { o ->
        if (o.value is NormalizedValue.Text || (o.value as? NormalizedValue.ListValue)?.items?.firstOrNull() is NormalizedValue.Text) {
            str(o)
        } else {
            Conversion.Rejected("vendor numeric code, not a name")
        }
    }

    /** Text for descriptions, rejecting known camera placeholder strings. */
    private val desc: (RawObservation) -> Conversion<String> = { o ->
        when (val c = str(o)) {
            is Conversion.Ok -> if (c.value.uppercase() in PLACEHOLDERS ||
                c.value.all { it == '?' || it == ' ' }
            ) {
                Conversion.Rejected("camera placeholder text")
            } else {
                c
            }

            else -> c
        }
    }

    /** A categorical label from a backend display value (for example ExifTool MakerNote enums). */
    private val label: (RawObservation) -> Conversion<String> = { o ->
        val d = o.displayValue?.trim()
        if (!d.isNullOrEmpty() && o.backend != MetadataCollector.NATIVE_BACKEND_ID) Conversion.Ok(d) else str(o)
    }

    private fun dbl(o: RawObservation): Double? = o.value.asDouble()

    private fun positive(o: RawObservation): Conversion<Double> {
        val d = dbl(o) ?: return Conversion.Rejected("not numeric")
        return if (d > 0 && d.isFinite()) Conversion.Ok(d) else Conversion.Rejected("non-positive or undefined ($d)")
    }

    private fun intOf(o: RawObservation): Conversion<Int> {
        val l = o.value.asLong() ?: o.value.asDouble()?.roundToInt()?.toLong() ?: return Conversion.Rejected("not an integer")
        return if (l in Int.MIN_VALUE..Int.MAX_VALUE) Conversion.Ok(l.toInt()) else Conversion.Rejected("out of range")
    }

    private fun eqText(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)

    private fun eqNum(a: Double, b: Double) = abs(a - b) <= 1e-3 * maxOf(1.0, abs(a), abs(b))

    private fun textField(field: String, vararg sources: Source<String>): String? = r.resolve(field, sources.toList(), { NormalizedValue.Text(it) }, ::eqText)

    private fun numField(field: String, vararg sources: Source<Double>): Double? = r.resolve(field, sources.toList(), { NormalizedValue.Decimal(it) }, ::eqNum)

    private fun intField(field: String, vararg sources: Source<Int>): Int? = r.resolve(field, sources.toList(), { NormalizedValue.Integer(it.toLong()) })

    // ---- Camera ----

    private fun camera(): CameraMetadata? {
        val c =
            CameraMetadata(
                make =
                textField(
                    "camera.make",
                    src(5, exif0, "Make", convert = str),
                    src(6, keys, "com.apple.quicktime.make", "Make", convert = str),
                    src(8, udta, "©mak", "Make", convert = str),
                    src(9, ilst, "©mak", "Make", convert = str),
                    src(12, keys, "com.android.manufacturer", convert = str),
                    src(15, xmp("tiff"), "Make", convert = str),
                    src(20, riffInfo, "Make", convert = str),
                    src(22, mkvTags, "MAKE", "make", convert = str),
                    src(30, group("ffprobe", "format"), "com.apple.quicktime.make", "make", convert = str),
                ),
                model =
                textField(
                    "camera.model",
                    src(5, exif0, "Model", convert = str),
                    src(6, keys, "com.apple.quicktime.model", "Model", convert = str),
                    src(8, udta, "©mod", "Model", convert = str),
                    src(9, ilst, "©mod", "Model", convert = str),
                    src(12, keys, "com.android.model", convert = str),
                    src(15, xmp("tiff"), "Model", convert = str),
                    src(18, exif0, "UniqueCameraModel", "LocalizedCameraModel", convert = str),
                    src(20, riffInfo, "Model", convert = str),
                    src(22, mkvTags, "MODEL", "model", convert = str),
                    src(30, group("ffprobe", "format"), "com.apple.quicktime.model", "model", convert = str),
                ),
                deviceName = null,
                lensMake =
                textField(
                    "camera.lensMake",
                    src(5, exifIfd, "LensMake", convert = str),
                    src(15, xmp("exifEX"), "LensMake", convert = str),
                    src(25, maker, "LensTypeMake", convert = str),
                ),
                lensModel =
                textField(
                    "camera.lensModel",
                    src(5, exifIfd, "LensModel", convert = str),
                    src(7, keys, "com.apple.quicktime.camera.lens_model", "LensModel", convert = str),
                    src(12, xmp("exifEX"), "LensModel", convert = str),
                    src(14, xmp("aux"), "Lens", convert = str),
                    src(
                        20,
                        group("MakerNotes", "Canon", "OlympusEquipment", "Panasonic", "Leica", "Samsung"),
                        "LensModel",
                        "LensType",
                        "LensTypeModel",
                        convert = textOnly,
                    ),
                    src(25, composite, "LensID", convert = label),
                ),
                lensSerialNumber =
                textField(
                    "camera.lensSerialNumber",
                    src(5, exifIfd, "LensSerialNumber", convert = str),
                    src(15, xmp("aux"), "LensSerialNumber", convert = str),
                    src(20, group("MakerNotes", "OlympusEquipment", "Panasonic"), "LensSerialNumber", convert = textOnly),
                ),
                cameraSerialNumber =
                textField(
                    "camera.cameraSerialNumber",
                    src(5, exifIfd, "SerialNumber", "BodySerialNumber", convert = str),
                    src(8, exif0, "CameraSerialNumber", convert = str),
                    src(15, xmp("aux"), "SerialNumber", convert = str),
                    src(16, xmp("exifEX"), "BodySerialNumber", convert = str),
                    src(
                        20,
                        group("MakerNotes", "Canon", "Nikon", "OlympusEquipment", "Sony", "Pentax", "Samsung", "Sigma"),
                        "SerialNumber",
                        convert = str,
                    ),
                ),
                firmware =
                textField(
                    "camera.firmware",
                    src(5, exifIfd, "CameraFirmware", convert = str),
                    src(15, xmp("aux"), "Firmware", convert = str),
                    src(20, group("MakerNotes", "Canon"), "CanonFirmwareVersion", convert = textOnly),
                    src(20, group("MakerNotes", "OlympusEquipment"), "BodyFirmwareVersion", convert = textOnly),
                    src(21, group("MakerNotes", "Pentax", "Sigma"), "FirmwareVersion", "Firmware", convert = textOnly),
                ),
                software =
                textField(
                    "camera.software",
                    src(5, exif0, "Software", convert = str),
                    src(6, keys, "com.apple.quicktime.software", "Software", convert = str),
                    src(8, udta, "©swr", "SoftwareVersion", convert = str),
                    src(12, xmp("xmp"), "CreatorTool", convert = str),
                    src(14, keys, "com.android.version", "AndroidVersion", convert = { o ->
                        (str(o) as? Conversion.Ok)?.let { Conversion.Ok("Android ${it.value}") }
                            ?: Conversion.Rejected("not text")
                    }),
                    src(15, riffInfo, "Software", convert = str),
                    src(16, png, "Software", convert = str),
                ),
                owner =
                textField(
                    "camera.owner",
                    src(5, exifIfd, "OwnerName", "CameraOwnerName", convert = str),
                    src(15, xmp("aux"), "OwnerName", convert = str),
                    src(20, group("MakerNotes", "Canon"), "OwnerName", convert = str),
                ),
                cameraType =
                textField(
                    "camera.cameraType",
                    src(5, keys, "com.apple.quicktime.camera.identifier", "CameraIdentifier", convert = str),
                ),
                deviceIdentifier = null,
            )
        return c.takeUnless { it == CameraMetadata() }
    }

    // ---- Exposure ----

    private fun exposure(): ExposureMetadata? {
        val isoCandidate: (RawObservation) -> Conversion<Int> = { o ->
            when (val c = intOf(o)) {
                is Conversion.Ok -> if (c.value <=
                    0
                ) {
                    Conversion.Rejected("non-positive")
                } else if (c.value == 65535 &&
                    o.tagName == "ISO"
                ) {
                    Conversion.Rejected("65535 means 'ISO 65535 or higher'")
                } else {
                    c
                }

                else -> c
            }
        }
        val nikonIso: (RawObservation) -> Conversion<Int> = { o ->
            val l = o.value.asList()
            val v = l.getOrNull(1)?.asLong() ?: l.getOrNull(0)?.asLong()
            if (v != null && v > 0) Conversion.Ok(v.toInt()) else Conversion.Rejected("no ISO value")
        }
        val iso =
            intField(
                "exposure.iso",
                src(5, exifIfd, "ISO", "PhotographicSensitivity", convert = isoCandidate),
                src(6, exifIfd, "StandardOutputSensitivity", "RecommendedExposureIndex", "ISOSpeed", convert = isoCandidate),
                src(12, group("EXIF", "IFD0"), "ISO", convert = isoCandidate),
                src(15, xmp("exif"), "ISOSpeedRatings", convert = isoCandidate),
                src(16, xmp("exifEX"), "PhotographicSensitivity", convert = isoCandidate),
                src(25, group("MakerNotes", "Nikon"), "ISO", convert = nikonIso),
            )
        val exposureTimeRational =
            r.resolve(
                "exposure.exposureTime",
                listOf(
                    src(5, exifIfd, "ExposureTime") { o ->
                        val q = o.value.asRational()
                        if (q != null && q.isDefined &&
                            q.numerator > 0
                        ) {
                            Conversion.Ok(q)
                        } else {
                            Conversion.Rejected("undefined")
                        }
                    },
                    src(15, xmp("exif"), "ExposureTime") { o ->
                        ok(
                            parseRationalText(textOf(o.value))?.takeIf {
                                it.isDefined &&
                                    it.numerator > 0
                            },
                        )
                    },
                ),
                { NormalizedValue.RationalValue(it) },
                { a, b -> eqNum(a.toDouble()!!, b.toDouble()!!) },
            )
        val exposureTime =
            numField(
                "exposure.exposureTimeSeconds",
                src(5, exifIfd, "ExposureTime", convert = ::positive),
                src(15, xmp("exif"), "ExposureTime") { o -> ok(parseRationalText(textOf(o.value))?.toDouble()?.takeIf { it > 0 }) },
            )
        val aperture =
            numField(
                "exposure.apertureFNumber",
                src(5, exifIfd, "FNumber", convert = ::positive),
                src(15, xmp("exif"), "FNumber") { o -> ok(parseRationalText(textOf(o.value))?.toDouble()?.takeIf { it > 0 }) },
                src(25, exifIfd, "ApertureValue") { o ->
                    val av = dbl(o)
                    if (av ==
                        null
                    ) {
                        Conversion.Rejected("not numeric")
                    } else {
                        Conversion.Ok((2.0.pow(av / 2.0) * 10).roundToInt() / 10.0, "derived from APEX ApertureValue")
                    }
                },
            )
        val focal =
            numField(
                "exposure.focalLengthMm",
                src(5, exifIfd, "FocalLength", convert = ::positive),
                src(15, xmp("exif"), "FocalLength") { o -> ok(parseRationalText(textOf(o.value))?.toDouble()?.takeIf { it > 0 }) },
            )
        val focal35 =
            numField(
                "exposure.focalLength35mmEquivalentMm",
                src(5, exifIfd, "FocalLengthIn35mmFormat", "FocalLengthIn35mmFilm", convert = ::positive),
                src(
                    8,
                    keys,
                    "com.apple.quicktime.camera.focal_length.35mm_equivalent",
                    "CameraFocalLength35mmEquivalent",
                    convert = ::positive,
                ),
                src(15, xmp("exif"), "FocalLengthIn35mmFilm", convert = ::positive),
            )
        val bias =
            numField(
                "exposure.exposureBiasEv",
                src(5, exifIfd, "ExposureCompensation", "ExposureBiasValue") { o -> ok(dbl(o)?.takeIf { it.isFinite() && abs(it) < 100 }) },
                src(15, xmp("exif"), "ExposureBiasValue") { o -> ok(parseRationalText(textOf(o.value))?.toDouble()) },
            )
        val metering =
            textField(
                "exposure.meteringMode",
                src(5, exifIfd, "MeteringMode") { o ->
                    ok(o.value.asLong()?.let { METERING[it] }, "unknown/undefined code")
                },
                src(15, xmp("exif"), "MeteringMode") { o -> ok(textOf(o.value)?.toLongOrNull()?.let { METERING[it] }) },
            )
        val program =
            textField(
                "exposure.exposureProgram",
                src(5, exifIfd, "ExposureProgram") { o ->
                    ok(o.value.asLong()?.let { PROGRAM[it] }, "not defined")
                },
                src(15, xmp("exif"), "ExposureProgram") { o -> ok(textOf(o.value)?.toLongOrNull()?.let { PROGRAM[it] }) },
            )
        val flash =
            r.resolve(
                "exposure.flash",
                listOf(
                    src(5, exifIfd, "Flash") { o -> ok(o.value.asLong()?.toInt()?.let(::flashInfo)) },
                    src(15, xmp("exif"), "Flash") { o -> ok(xmpFlash(o.value)) },
                ),
                { NormalizedValue.Text(it.toString()) },
            )
        val wb = textField(
            "exposure.whiteBalance",
            src(5, exifIfd, "WhiteBalance") { o ->
                ok(
                    o.value.asLong()?.let {
                        mapOf(
                            0L to "AUTO",
                            1L to "MANUAL",
                        )[it]
                    },
                )
            },
            src(15, xmp("exif"), "WhiteBalance") { o ->
                ok(textOf(o.value)?.toLongOrNull()?.let { mapOf(0L to "AUTO", 1L to "MANUAL")[it] })
            },
        )
        val focus =
            textField(
                "exposure.focusMode",
                src(20, group("MakerNotes", "Nikon"), "FocusMode", convert = str),
                src(25, maker, "FocusMode", convert = { o ->
                    if (o.backend ==
                        MetadataCollector.NATIVE_BACKEND_ID
                    ) {
                        Conversion.Rejected("vendor code without a decoded label")
                    } else {
                        label(o)
                    }
                }),
            )
        val zoom = numField(
            "exposure.digitalZoomRatio",
            src(5, exifIfd, "DigitalZoomRatio") { o ->
                val d = dbl(o)
                if (d != null &&
                    d > 0
                ) {
                    Conversion.Ok(d)
                } else {
                    Conversion.Rejected("0 or undefined means digital zoom not used/unknown")
                }
            },
        )
        val distance =
            numField(
                "exposure.subjectDistanceMeters",
                src(5, exifIfd, "SubjectDistance") { o ->
                    val q = o.value.asRational()
                    when {
                        q != null && q.numerator == 0xFFFFFFFFL -> Conversion.Rejected("infinity")
                        q != null && q.numerator == 0L -> Conversion.Rejected("0 means unknown")
                        else -> positive(o)
                    }
                },
            )
        val shutterApex =
            numField(
                "exposure.shutterSpeedApex",
                src(5, exifIfd, "ShutterSpeedValue") { o ->
                    ok(
                        dbl(o)?.takeIf {
                            it.isFinite() &&
                                abs(it) < 64
                        },
                    )
                },
            )
        val brightness =
            numField(
                "exposure.brightnessValue",
                src(5, exifIfd, "BrightnessValue") { o ->
                    val q = o.value.asRational()
                    if (q !=
                        null &&
                        q.numerator == 0xFFFFFFFFL
                    ) {
                        Conversion.Rejected("unknown")
                    } else {
                        ok(dbl(o)?.takeIf { it.isFinite() && abs(it) < 100 })
                    }
                },
            )
        val scene = textField("exposure.sceneType", src(5, exifIfd, "SceneCaptureType") { o -> ok(o.value.asLong()?.let { SCENE[it] }) })
        val stabilization = textField(
            "exposure.stabilization",
            src(25, maker, "ImageStabilization", convert = { o ->
                if (o.backend ==
                    MetadataCollector.NATIVE_BACKEND_ID
                ) {
                    Conversion.Rejected("vendor code without a decoded label")
                } else {
                    label(o)
                }
            }),
        )
        val hdr =
            textField(
                "exposure.hdrMode",
                src(5, exifIfd, "CustomRendered") { o ->
                    ok(o.value.asLong()?.let { mapOf(2L to "HDR", 3L to "HDR", 7L to "PORTRAIT_HDR")[it] }, "not an HDR rendering")
                },
                src(10, group("MakerNotes", "Apple"), "HDRImageType") { o ->
                    ok(
                        o.value.asLong()?.let {
                            mapOf(
                                3L to "HDR",
                                4L to "ORIGINAL_FOR_HDR",
                            )[it]
                        },
                    )
                },
                src(25, maker, "HDR", "HDRMode", convert = { o ->
                    if (o.backend ==
                        MetadataCollector.NATIVE_BACKEND_ID
                    ) {
                        Conversion.Rejected("vendor code without a decoded label")
                    } else {
                        label(o)
                    }
                }),
            )
        val shooting =
            textField(
                "exposure.shootingMode",
                src(10, exifIfd, "ExposureMode") { o ->
                    ok(o.value.asLong()?.let { mapOf(0L to "AUTO", 1L to "MANUAL", 2L to "AUTO_BRACKET")[it] })
                },
                src(25, maker, "ShootingMode", convert = { o ->
                    if (o.backend ==
                        MetadataCollector.NATIVE_BACKEND_ID
                    ) {
                        Conversion.Rejected("vendor code without a decoded label")
                    } else {
                        label(o)
                    }
                }),
            )
        val e =
            ExposureMetadata(
                iso, exposureTime, exposureTimeRational, aperture, focal, focal35, bias, metering, program, flash, wb, focus,
                zoom, distance, shutterApex, brightness, scene, stabilization, hdr, shooting,
            )
        return e.takeUnless { it == ExposureMetadata() }
    }

    // ---- Time ----

    private fun exifDate(o: RawObservation, subName: String, offsetName: String): Conversion<CaptureTimestamp> {
        val sub = r.observations(setOf(subName), exifIfd).firstOrNull { it.backend == o.backend }?.let { textOf(it.value) }
        val off = r.observations(setOf(offsetName), exifIfd).firstOrNull { it.backend == o.backend }?.let { textOf(it.value) }
        val p =
            Dates.parse(textOf(o.value), sub, off)
                ?: return Conversion.Rejected("unparseable or implausible date '${textOf(o.value)?.take(40)}'")
        return Conversion.Ok(p.toCapture(o.qualifiedName))
    }

    private fun isoDate(o: RawObservation): Conversion<CaptureTimestamp> {
        val p = Dates.parse(textOf(o.value)) ?: return Conversion.Rejected("unparseable or implausible date")
        return Conversion.Ok(p.toCapture(o.qualifiedName))
    }

    /** QuickTime header times: integer seconds since 1904 (native) or a UTC date string (ExifTool). UTC by definition. */
    private fun qtDate(o: RawObservation): Conversion<CaptureTimestamp> {
        val instant =
            when (val v = o.value) {
                is NormalizedValue.Integer -> IsoBmffParser.qtTime(v.value)
                else -> Dates.parse(textOf(v))?.let { it.offset?.let { _ -> it.instant } ?: it.local.toInstant(ZoneOffset.UTC) }
            } ?: return Conversion.Rejected("unset (0) or unparseable")
        // Some encoders write the Unix epoch instead of 0 when the clock is unset (ExifTool shows 1970:01:01 00:00:00).
        if (instant == Instant.EPOCH) return Conversion.Rejected("unset (Unix epoch)")
        if (!Dates.plausible(instant)) return Conversion.Rejected("implausible")
        return Conversion.Ok(CaptureTimestamp(instant, null, ZoneOffset.UTC, TimestampPrecision.SECOND, o.qualifiedName, true))
    }

    private fun mkvDate(o: RawObservation): Conversion<CaptureTimestamp> {
        val ns = o.value.asLong() ?: return isoDate(o)
        val instant = dev.kiroku.format.ebml.MatroskaParser.mkvDate(ns)
        if (!Dates.plausible(instant) || ns == 0L) return Conversion.Rejected("unset or implausible")
        return Conversion.Ok(CaptureTimestamp(instant, null, ZoneOffset.UTC, TimestampPrecision.SECOND, o.qualifiedName, true))
    }

    private fun iptcDate(o: RawObservation): Conversion<CaptureTimestamp> {
        val d = textOf(o.value) ?: return Conversion.Rejected("not text")
        val t = r.observations(setOf("TimeCreated"), iptc).firstOrNull { it.backend == o.backend }?.let { textOf(it.value) }
        val p = Dates.parse(if (t != null) "${d}T$t" else d) ?: Dates.parse(d) ?: return Conversion.Rejected("unparseable")
        return Conversion.Ok(p.toCapture(o.qualifiedName))
    }

    private fun bwfDate(o: RawObservation): Conversion<CaptureTimestamp> {
        val d = textOf(o.value) ?: return Conversion.Rejected("not text")
        val t = r.observations(setOf("TimeOriginal"), group("RIFF", "BroadcastExtension")).firstOrNull()?.let { textOf(it.value) }
        val p = Dates.parse(if (t != null) "$d $t" else d) ?: return Conversion.Rejected("unparseable")
        return Conversion.Ok(p.toCapture(o.qualifiedName))
    }

    private fun equivalentTimes(a: CaptureTimestamp, b: CaptureTimestamp): Boolean {
        val ai = a.instant
        val bi = b.instant
        if (ai != null && bi != null) return abs(Duration.between(ai, bi).seconds) <= 1
        val al = a.localDateTime
        val bl = b.localDateTime
        if (al != null && bl != null) return abs(Duration.between(al, bl).seconds) <= 1
        // A local time against a UTC instant cannot be compared without a time zone, so it is not a conflict.
        return true
    }

    private fun capturedAt(): CaptureTimestamp? {
        val sources =
            mutableListOf(
                src(5, exifIfd, "DateTimeOriginal") { o -> exifDate(o, "SubSecTimeOriginal", "OffsetTimeOriginal") },
                src(6, keys, "com.apple.quicktime.creationdate", "CreationDate", convert = ::isoDate),
                src(10, xmp("exif"), "DateTimeOriginal", convert = ::isoDate),
                src(12, xmp("photoshop"), "DateCreated", convert = ::isoDate),
                src(15, exifIfd, "CreateDate", "DateTimeDigitized") { o -> exifDate(o, "SubSecTimeDigitized", "OffsetTimeDigitized") },
                src(18, xmp("xmp"), "CreateDate", convert = ::isoDate),
                src(19, udta, "©day", "ContentCreateDate", convert = ::isoDate),
                src(20, group("RIFF", "AVI"), "DateTimeOriginal", convert = ::isoDate),
                src(20, group("RIFF", "BroadcastExtension"), "DateTimeOriginal", convert = ::bwfDate),
                src(22, iptc, "DateCreated", convert = ::iptcDate),
                src(24, ilst, "©day", "ContentCreateDate", convert = ::isoDate),
                src(26, keys, "com.apple.quicktime.creationdate.local", convert = ::isoDate),
                src(28, riffInfo, "DateCreated", convert = ::isoDate),
                src(30, mvhd, "CreateDate", convert = ::qtDate),
                src(30, mkv, "DateTimeOriginal", convert = ::mkvDate),
                src(32, mkvTags, "DATE_RECORDED", "DATE_RELEASED", "creation_time", convert = ::isoDate),
                src(35, groupPrefix("QuickTime", "Track"), "MediaCreateDate", "TrackCreateDate", convert = ::qtDate),
                src(40, png, "Creation_Time", "CreationTime", convert = ::isoDate),
                src(45, group("ffprobe", "format"), "creation_time", convert = ::isoDate),
            )
        // A year- or month-only date (for example an iTunes "©day" of "2010") never beats a full timestamp from a lower-ranked source.
        var ts =
            r.resolve("capturedAt", sources, { NormalizedValue.Text(it.toString()) }, ::equivalentTimes) {
                it.precision == TimestampPrecision.YEAR || it.precision == TimestampPrecision.MONTH
            }
        if (ts == null && options.fileSystemTimeAsCaptureFallback && probe.modifiedAtFileSystem != null) {
            val m = probe.modifiedAtFileSystem
            ts = CaptureTimestamp(m, null, ZoneOffset.UTC, TimestampPrecision.SECOND, "FileSystem:ModifyDate", true)
            diags +=
                MetadataDiagnostic(
                    DiagnosticCode.CAPTURE_TIME_FROM_FILESYSTEM,
                    Severity.WARNING,
                    "No embedded capture time; using the filesystem modification time as requested",
                    field = "capturedAt",
                )
            r.record("capturedAt", null, emptyList(), "filesystem fallback (caller opt-in)")
        }
        if (ts != null && !ts.timezoneKnown) {
            diags +=
                MetadataDiagnostic(
                    DiagnosticCode.TIMEZONE_UNKNOWN,
                    Severity.INFO,
                    "Capture time has no UTC offset; reported as local date-time only",
                    field = "capturedAt",
                )
        }
        return ts
    }

    private fun modifiedAt(): Instant? = r.resolve(
        "modifiedAt",
        listOf(
            src(5, exif0, "ModifyDate") { o ->
                exifDate(o, "SubSecTime", "OffsetTime").let { c ->
                    if (c is Conversion.Ok &&
                        c.value.instant == null
                    ) {
                        Conversion.Rejected("no UTC offset, so no instant")
                    } else {
                        c
                    }
                }
            },
            src(10, xmp("xmp"), "ModifyDate") { o ->
                isoDate(o).let { c ->
                    if (c is Conversion.Ok &&
                        c.value.instant == null
                    ) {
                        Conversion.Rejected("no UTC offset")
                    } else {
                        c
                    }
                }
            },
            src(20, mvhd, "ModifyDate", convert = ::qtDate),
        ),
        { NormalizedValue.Text(it.instant.toString()) },
        { a, b -> a.instant == b.instant },
    )?.instant

    // ---- Location ----

    private fun location(): GeoMetadata? {
        if (options.gpsPolicy == GpsPolicy.EXCLUDE_GALLERY || options.gpsPolicy == GpsPolicy.EXCLUDE_ALL) {
            if (observations.any { isLocationObservation(it) }) {
                diags +=
                    MetadataDiagnostic(
                        DiagnosticCode.REDACTED,
                        Severity.INFO,
                        "Location omitted from gallery output by GPS policy ${options.gpsPolicy}",
                        field = "location",
                    )
            }
            return null
        }
        val geo =
            r.resolve(
                "location",
                listOf(
                    src(5, gps, "GPSLatitude") { o -> exifGps(o) },
                    src(6, keys, "com.apple.quicktime.location.ISO6709", "GPSCoordinates") { o -> iso6709(o) },
                    src(10, udta, "©xyz", "GPSCoordinates") { o -> iso6709(o) },
                    src(11, ilst, "©xyz", "GPSCoordinates") { o -> iso6709(o) },
                    src(15, xmp("exif"), "GPSLatitude") { o -> xmpGps(o) },
                    src(20, udta, "LocationLatitude") { o -> loci(o) },
                    src(30, mkvTags, "LOCATION", "location", "com.apple.quicktime.location.ISO6709") { o -> iso6709(o) },
                    src(35, group("ffprobe", "format"), "location", "location-eng", "com.apple.quicktime.location.ISO6709") { o ->
                        iso6709(o)
                    },
                    src(40, composite, "GPSLatitude") { o -> compositeGps(o) },
                ),
                { NormalizedValue.Text("%.6f,%.6f".format(it.latitude, it.longitude)) },
                { a, b -> abs(a.latitude!! - b.latitude!!) < 1e-4 && abs(a.longitude!! - b.longitude!!) < 1e-4 },
            ) ?: return null
        return if (options.gpsPolicy == GpsPolicy.COARSE_GALLERY) {
            geo.copy(
                latitude = geo.latitude?.let { (it * 100).roundToInt() / 100.0 },
                longitude = geo.longitude?.let { (it * 100).roundToInt() / 100.0 },
                accuracyMeters = maxOf(geo.accuracyMeters ?: 0.0, 1100.0),
                locationTimestamp = null,
            )
        } else {
            geo
        }
    }

    private fun sibling(o: RawObservation, name: String): RawObservation? = r.observations(setOf(name), { it == o.groupPath }).filter {
        it.backend == o.backend &&
            it.sourcePath?.substringBeforeLast('/') == o.sourcePath?.substringBeforeLast('/')
    }.let { same ->
        same.firstOrNull() ?: r.observations(setOf(name), { it == o.groupPath }).firstOrNull { it.backend == o.backend }
    }

    private fun exifGps(lat: RawObservation): Conversion<GeoMetadata> {
        val lon = sibling(lat, "GPSLongitude") ?: return Conversion.Rejected("no matching GPSLongitude")
        val latRef = sibling(lat, "GPSLatitudeRef")?.let { textOf(it.value) }
        val lonRef = sibling(lat, "GPSLongitudeRef")?.let { textOf(it.value) }
        val status = sibling(lat, "GPSStatus")?.let { textOf(it.value) }
        if (status?.uppercase() == "V") return Conversion.Rejected("GPSStatus V (void measurement)")
        val la = Geo.dmsToDecimal(lat.value, latRef) ?: return Conversion.Rejected("invalid latitude")
        val lo = Geo.dmsToDecimal(lon.value, lonRef) ?: return Conversion.Rejected("invalid longitude")
        if (!Geo.valid(la, lo)) return Conversion.Rejected("coordinates out of range")
        val altObs = sibling(lat, "GPSAltitude")
        val altRefCode = sibling(lat, "GPSAltitudeRef")?.value?.asLong()
        val alt = altObs?.value?.asDouble()?.takeIf { it.isFinite() }
        val (altitude, altRef) =
            when {
                alt == null -> null to null
                altRefCode == 1L || altRefCode == 3L -> -abs(alt) to AltitudeReference.BELOW_SEA_LEVEL
                altRefCode == null || altRefCode == 0L || altRefCode == 2L -> alt to AltitudeReference.ABOVE_SEA_LEVEL
                else -> alt to AltitudeReference.UNKNOWN
            }
        val speed = sibling(lat, "GPSSpeed")?.value?.asDouble()?.let {
            Geo.speedToMetersPerSecond(it, sibling(lat, "GPSSpeedRef")?.let { r -> textOf(r.value) })
        }
        val track = sibling(lat, "GPSTrack")?.value?.asDouble()?.takeIf { it in 0.0..360.0 }
        val dir = sibling(lat, "GPSImgDirection")?.value?.asDouble()?.takeIf { it in 0.0..360.0 }
        val err = sibling(lat, "GPSHPositioningError")?.value?.asDouble()?.takeIf { it >= 0 }
        val datum = sibling(lat, "GPSMapDatum")?.let { textOf(it.value) }?.takeIf { it.isNotBlank() }
        val stamp = gpsTimestamp(sibling(lat, "GPSDateStamp")?.let { textOf(it.value) }, sibling(lat, "GPSTimeStamp")?.value)
        return Conversion.Ok(GeoMetadata(la, lo, altitude, altRef, err, track, speed, dir, stamp, datum, lat.qualifiedName))
    }

    private fun gpsTimestamp(date: String?, time: NormalizedValue?): Instant? {
        val d = date?.let { Dates.parse(it) }?.local?.toLocalDate() ?: return null
        val parts = time?.asList()?.mapNotNull { it.asDouble() } ?: return null
        if (parts.size != 3) return null
        val (h, m, s) = parts
        if (h !in 0.0..23.0 || m !in 0.0..59.0 || s < 0 || s >= 61) return null
        val nanos = ((s - s.toInt()) * 1e9).toInt()
        // GPS time is UTC by definition (EXIF 2.3).
        return LocalDate.of(
            d.year,
            d.month,
            d.dayOfMonth,
        ).atTime(LocalTime.of(h.toInt(), m.toInt(), s.toInt().coerceAtMost(59), nanos)).toInstant(ZoneOffset.UTC)
    }

    private fun iso6709(o: RawObservation): Conversion<GeoMetadata> {
        val p = Geo.parseIso6709(textOf(o.value)) ?: return Conversion.Rejected("not a valid ISO 6709 string")
        val acc =
            r.observations(setOf("com.apple.quicktime.location.accuracy.horizontal", "LocationAccuracyHorizontal"), keys)
                .firstOrNull()?.let { it.value.asDouble() ?: textOf(it.value)?.toDoubleOrNull() }
        return Conversion.Ok(
            GeoMetadata(
                p.latitude,
                p.longitude,
                p.altitude,
                p.altitude?.let {
                    AltitudeReference.UNKNOWN
                },
                acc,
                source = o.qualifiedName,
            ),
        )
    }

    private fun xmpGps(o: RawObservation): Conversion<GeoMetadata> {
        val la = Geo.parseXmpCoordinate(textOf(o.value)) ?: return Conversion.Rejected("invalid XMP latitude")
        val lo =
            r.observations(setOf("GPSLongitude"), xmp("exif")).firstOrNull {
                it.backend == o.backend
            }?.let { Geo.parseXmpCoordinate(textOf(it.value)) }
                ?: return Conversion.Rejected("no XMP longitude")
        if (!Geo.valid(la, lo)) return Conversion.Rejected("out of range")
        val alt = r.observations(setOf("GPSAltitude"), xmp("exif")).firstOrNull()?.let { parseRationalText(textOf(it.value))?.toDouble() }
        val altRef = r.observations(setOf("GPSAltitudeRef"), xmp("exif")).firstOrNull()?.let { textOf(it.value) }
        val signed = if (alt != null && altRef == "1") -abs(alt) else alt
        return Conversion.Ok(
            GeoMetadata(
                la,
                lo,
                signed,
                signed?.let {
                    if (altRef ==
                        "1"
                    ) {
                        AltitudeReference.BELOW_SEA_LEVEL
                    } else {
                        AltitudeReference.ABOVE_SEA_LEVEL
                    }
                },
                source = o.qualifiedName,
            ),
        )
    }

    private fun loci(o: RawObservation): Conversion<GeoMetadata> {
        val la = o.value.asDouble() ?: return Conversion.Rejected("invalid")
        val lo =
            r.observations(setOf("LocationLongitude"), udta).firstOrNull { it.backend == o.backend }?.value?.asDouble()
                ?: return Conversion.Rejected("no longitude")
        if (!Geo.valid(la, lo)) return Conversion.Rejected("out of range")
        val alt = r.observations(setOf("LocationAltitude"), udta).firstOrNull()?.value?.asDouble()
        return Conversion.Ok(GeoMetadata(la, lo, alt, alt?.let { AltitudeReference.UNKNOWN }, source = o.qualifiedName))
    }

    private fun compositeGps(o: RawObservation): Conversion<GeoMetadata> {
        val la = o.value.asDouble() ?: return Conversion.Rejected("not numeric")
        val lo =
            r.observations(setOf("GPSLongitude"), composite).firstOrNull()?.value?.asDouble() ?: return Conversion.Rejected("no longitude")
        if (!Geo.valid(la, lo)) return Conversion.Rejected("out of range")
        return Conversion.Ok(
            GeoMetadata(la, lo, r.observations(setOf("GPSAltitude"), composite).firstOrNull()?.value?.asDouble(), source = o.qualifiedName),
        )
    }

    // ---- Orientation and dimensions ----

    private fun orientation(kind: MediaKind): Orientation? {
        if (kind == MediaKind.VIDEO) {
            val v = primaryVideo() ?: return null
            val m = (v.properties[StreamProps.MATRIX] as? NormalizedValue.ListValue)?.items?.mapNotNull { it.asDouble() }
            if (m != null && m.size >= 5) {
                val o = Geo.matrixOrientation(m)
                val trackObs = observations.firstOrNull {
                    it.tagName == "MatrixStructure" && it.streamIndex == v.index &&
                        it.backend == v.backend
                }
                r.record(
                    "orientation",
                    trackObs,
                    listOfNotNull(
                        trackObs?.let {
                            it to it.value
                        },
                    ),
                    "display matrix of primary video stream ${v.index}",
                )
                return o?.copy(source = trackObs?.qualifiedName ?: "stream ${v.index} matrix")
            }
            val rot = v.properties[StreamProps.ROTATION]?.asDouble()
            if (rot != null) {
                val cw = (((-rot).roundToInt() % 360) + 360) % 360
                r.record("orientation", null, emptyList(), "rotation side data of stream ${v.index} (${v.backend})")
                return Orientation(null, cw, false, null, "${v.backend}:stream${v.index}:rotation")
            }
            return null
        }
        if (isHeif) {
            val irot = r.observations(setOf("Rotation"), qtMeta).firstOrNull()
            val imir = r.observations(setOf("MirrorAxis"), qtMeta).firstOrNull()
            val order = r.observations(setOf("TransformOrder"), qtMeta).firstOrNull()?.let { textOf(it.value) }
            val exifObs = r.observations(setOf("Orientation"), exif0)
            val cands = listOfNotNull(irot, imir).map { it to it.value } + exifObs.map { it to it.value }
            r.record(
                "orientation",
                irot ?: imir,
                cands,
                "HEIF transformative properties (irot/imir) define display orientation; EXIF Orientation is informative only and is not applied a second time",
            )
            val o = Geo.heifOrientation(irot?.value?.asLong()?.toInt(), imir?.value?.asLong()?.toInt(), order?.startsWith("imir") == true)
            return o.copy(source = (irot ?: imir)?.qualifiedName ?: "HEIF (no irot/imir: identity)")
        }
        val code =
            intField(
                "orientation",
                src(5, exif0, "Orientation") { o ->
                    val c = o.value.asLong()?.toInt()
                    if (c != null &&
                        c in 1..8
                    ) {
                        Conversion.Ok(c)
                    } else {
                        Conversion.Rejected("invalid orientation code $c")
                    }
                },
                src(15, xmp("tiff"), "Orientation") { o ->
                    val c = textOf(o.value)?.toIntOrNull()
                    if (c != null &&
                        c in 1..8
                    ) {
                        Conversion.Ok(c)
                    } else {
                        Conversion.Rejected("invalid")
                    }
                },
            ) ?: return null
        val src = r.provenance["orientation"]?.chosen?.qualifiedName
        return Geo.exifOrientation(code)?.copy(source = src)
    }

    private fun dimensions(kind: MediaKind, orientation: Orientation?): Dimensions? {
        val (w, h, par) =
            when (kind) {
                MediaKind.VIDEO -> videoDims() ?: return null
                MediaKind.AUDIO, MediaKind.UNKNOWN -> return null
                else -> imageDims() ?: return null
            }
        val rot = orientation?.rotationDegrees ?: 0
        val swap = rot == 90 || rot == 270
        val parW = if (par != null && par.isDefined && par.numerator > 0 &&
            par.denominator > 0
        ) {
            (w * par.numerator.toDouble() / par.denominator).roundToInt()
        } else {
            w
        }
        val dw = if (swap) h else parW
        val dh = if (swap) parW else h
        val mp = (w.toLong() * h.toLong()) / 1_000_000.0
        val aspect = if (dw > 0 && dh > 0) Rational(dw.toLong(), dh.toLong()).reduced() else null
        return Dimensions(w, h, dw, dh, (mp * 100).roundToInt() / 100.0, par, aspect)
    }

    private fun imageDims(): Triple<Int, Int, Rational?>? {
        val pair: (RawObservation, String) -> Conversion<Pair<Int, Int>> = { o, other ->
            val w = o.value.asLong()
            val h = sibling(o, other)?.value?.asLong()
            if (w != null && h != null && w > 0 && h > 0 && w < 1_000_000 &&
                h < 1_000_000
            ) {
                Conversion.Ok(w.toInt() to h.toInt())
            } else {
                Conversion.Rejected("missing or invalid dimension pair")
            }
        }
        val sources = ArrayList<Source<Pair<Int, Int>>>()
        when {
            isRaw -> {
                rafCropped()?.let { return it }
                dngCrop()?.let { return it }
                rawPrimary()?.let { (w, h, grp) ->
                    val obs = observations.firstOrNull { it.groupPath == listOf("EXIF", grp) && it.tagName == "ImageWidth" }
                    r.record(
                        "dimensions",
                        obs,
                        listOfNotNull(
                            obs?.let {
                                it to it.value
                            },
                        ),
                        "largest full-resolution RAW image IFD (EXIF:$grp)",
                    )
                    return Triple(w, h, null)
                }
                sources += src(5, exif0, "SensorRightBorder") { o -> rw2Dims(o) }
                sources += src(20, exifIfd, "ExifImageWidth") { o -> pair(o, "ExifImageHeight") }
                sources += src(25, exif0, "ImageWidth") { o -> pair(o, "ImageHeight") }
            }

            isHeif -> {
                sources +=
                    src(5, qtMeta, "ImageSpatialExtent") { o ->
                        val l = o.value.asList().mapNotNull { it.asLong() }
                        if (l.size == 2 &&
                            l.all { it > 0 }
                        ) {
                            Conversion.Ok(l[0].toInt() to l[1].toInt())
                        } else {
                            Conversion.Rejected("invalid ispe")
                        }
                    }
                sources += src(20, exifIfd, "ExifImageWidth") { o -> pair(o, "ExifImageHeight") }
            }

            else -> {
                sources += src(5, group("File", "File"), "ImageWidth") { o -> pair(o, "ImageHeight") }
                sources += src(5, png, "ImageWidth") { o -> pair(o, "ImageHeight") }
                sources += src(5, group("GIF"), "ImageWidth") { o -> pair(o, "ImageHeight") }
                sources += src(5, group("BMP"), "ImageWidth") { o -> pair(o, "ImageHeight") }
                sources +=
                    src(5, group("RIFF", "WEBP"), "ImageWidth") { o ->
                        if (o.sourcePath?.contains("VP8X") == true ||
                            r.observations(setOf("ImageWidth"), group("RIFF", "WEBP")).none { it.sourcePath?.contains("VP8X") == true }
                        ) {
                            pair(o, "ImageHeight")
                        } else {
                            Conversion.Rejected("VP8X canvas takes precedence")
                        }
                    }
                sources +=
                    src(5, exif0, "ImageWidth") { o ->
                        if (format == MediaFormat.TIFF ||
                            format == MediaFormat.BIGTIFF
                        ) {
                            pair(o, "ImageHeight")
                        } else {
                            Conversion.Rejected("IFD0 size applies to TIFF only")
                        }
                    }
                sources +=
                    src(4, group("Photoshop", "Photoshop"), "ImageWidth") { o ->
                        if (format == MediaFormat.PSD) pair(o, "ImageHeight") else Conversion.Rejected("Photoshop header size applies to PSD only")
                    }
                // EPS: Photoshop's %ImageData gives pixel columns and rows. The BoundingBox is in points, not pixels.
                sources +=
                    src(6, group("PostScript", "PostScript"), "ImageData") { o ->
                        val n = textOf(o.value)?.trim()?.split(Regex("\\s+"))?.take(2)?.mapNotNull { it.toIntOrNull() }
                        if (n != null && n.size == 2 &&
                            n.all { it in 1 until 1_000_000 }
                        ) {
                            Conversion.Ok(n[0] to n[1])
                        } else {
                            Conversion.Rejected("invalid ImageData")
                        }
                    }
                sources += src(20, exifIfd, "ExifImageWidth") { o -> pair(o, "ExifImageHeight") }
                sources += src(30, group("ffprobe", "stream"), "width") { o -> pair(o, "height") }
            }
        }
        val wh = r.resolve("dimensions", sources, { NormalizedValue.Text("${it.first}x${it.second}") }) ?: return null
        val par = r.observations(setOf("PixelAspectRatio"), qtMeta).firstOrNull()?.value?.asRational()
        return Triple(wh.first, wh.second, par)
    }

    /** Fujifilm RAF: `RawImageCroppedSize` (stored as height, width) in the RAF directory, the size ExifTool reports. */
    private fun rafCropped(): Triple<Int, Int, Rational?>? {
        val o = observations.firstOrNull { it.groupPath == listOf("RAF", "RAF") && it.tagName == "RawImageCroppedSize" } ?: return null
        val l = o.value.asList().mapNotNull { it.asLong() }
        if (l.size != 2 || l.any { it <= 0 || it >= 1_000_000 }) return null
        r.record("dimensions", o, listOf(o to o.value), "Fujifilm RAF cropped raw size (RAF:RAF:RawImageCroppedSize)")
        return Triple(l[1].toInt(), l[0].toInt(), null)
    }

    private fun dngCrop(): Triple<Int, Int, Rational?>? {
        if (format != MediaFormat.DNG) return null
        val obs = observations.firstOrNull { it.tagName == "DefaultCropSize" && it.groupPath.getOrNull(0) == "EXIF" } ?: return null
        val l = obs.value.asList().mapNotNull { it.asDouble() }
        if (l.size != 2 || l.any { it <= 0 }) return null
        r.record("dimensions", obs, listOf(obs to obs.value), "DNG DefaultCropSize (final rendered size)")
        return Triple(l[0].roundToInt(), l[1].roundToInt(), null)
    }

    /** The largest full-resolution RAW image IFD (CFA or linear raw), from the embedded-image list. */
    private fun rawPrimary(): Triple<Int, Int, String>? {
        val img =
            embedded
                .filter {
                    it.kind == EmbeddedImageKind.PRIMARY && it.width != null && it.height != null &&
                        it.groupPath.firstOrNull() == "EXIF" &&
                        it.width!! > 0 &&
                        it.height!! > 0
                }
                .maxByOrNull { it.width!!.toLong() * it.height!! }
                ?: return null
        return Triple(img.width!!, img.height!!, img.groupPath[1])
    }

    private fun rw2Dims(o: RawObservation): Conversion<Pair<Int, Int>> {
        val right = o.value.asLong() ?: return Conversion.Rejected("invalid")
        val left = sibling(o, "SensorLeftBorder")?.value?.asLong() ?: return Conversion.Rejected("no left border")
        val top = sibling(o, "SensorTopBorder")?.value?.asLong() ?: return Conversion.Rejected("no top border")
        val bottom = sibling(o, "SensorBottomBorder")?.value?.asLong() ?: return Conversion.Rejected("no bottom border")
        val w = right - left
        val h = bottom - top
        return if (w > 0 &&
            h > 0
        ) {
            Conversion.Ok(w.toInt() to h.toInt(), "Panasonic sensor borders")
        } else {
            Conversion.Rejected("invalid borders")
        }
    }

    private fun videoDims(): Triple<Int, Int, Rational?>? {
        val v = primaryVideo() ?: return null
        val w = v.properties[StreamProps.WIDTH]?.asLong()?.toInt() ?: return null
        val h = v.properties[StreamProps.HEIGHT]?.asLong()?.toInt() ?: return null
        if (w <= 0 || h <= 0) return null
        var par = v.properties[StreamProps.PIXEL_ASPECT_RATIO]?.asRational()?.takeIf { it.isDefined && it.numerator > 0 }
        // Matroska: DisplayWidth/DisplayHeight in pixels define the display aspect.
        if (par == null) {
            val dw = v.properties[StreamProps.DISPLAY_WIDTH]?.asLong()
            val dh = v.properties[StreamProps.DISPLAY_HEIGHT]?.asLong()
            val unit = v.properties["displayUnit"]?.asLong() ?: 0
            if (dw != null && dh != null && dw > 0 && dh > 0 && unit == 0L && v.backend != MetadataCollector.NATIVE_BACKEND_ID ||
                dw != null && dh != null && format.id in setOf("WEBM", "MATROSKA") && unit == 0L
            ) {
                if (dw!! * h != dh!! * w) par = Rational(dw * h, dh * w).reduced()
            }
        }
        r.record("dimensions", null, emptyList(), "primary video stream ${v.index} (${v.backend})")
        return Triple(w, h, par?.takeIf { it != Rational(1, 1) && it.reduced() != Rational(1, 1) })
    }

    // ---- Streams ----

    private fun streamsOf(kind: StreamKind): List<StreamInfo> {
        val native = streams.filter { it.kind == kind && it.backend == MetadataCollector.NATIVE_BACKEND_ID }
        return native.ifEmpty { streams.filter { it.kind == kind } }
    }

    private var primaryVideoCache: StreamInfo? = null
    private var primaryVideoDone = false

    private fun primaryVideo(): StreamInfo? {
        if (primaryVideoDone) return primaryVideoCache
        primaryVideoDone = true
        val vids = streamsOf(StreamKind.VIDEO)
        val enabled = vids.filter { it.properties[StreamProps.ENABLED] != NormalizedValue.Bool(false) }.ifEmpty { vids }
        val chosen = enabled.firstOrNull { it.properties[StreamProps.DEFAULT] == NormalizedValue.Bool(true) } ?: enabled.firstOrNull()
        if (vids.size > 1 && chosen != null) {
            diags +=
                MetadataDiagnostic(
                    DiagnosticCode.PRIMARY_STREAM_SELECTED,
                    Severity.INFO,
                    "${vids.size} video streams; stream ${chosen.index} chosen as primary (first enabled/default)",
                    chosen.backend,
                    "video.primaryStreamIndex",
                )
        }
        primaryVideoCache = chosen
        return chosen
    }

    private fun container(): ContainerInfo? = containers.firstOrNull()

    private fun durOf(s: StreamInfo): Duration? = s.properties[StreamProps.DURATION_SECONDS]?.asDouble()?.takeIf {
        it > 0 && it.isFinite()
    }?.let {
        Duration.ofNanos(
            (
                it *
                    1e9
                ).toLong(),
        )
    }

    private fun video(): VideoMetadata? {
        val v = primaryVideo()
        val vids = streamsOf(StreamKind.VIDEO)
        val auds = streamsOf(StreamKind.AUDIO)
        val c = container()
        val p = v?.properties.orEmpty()
        val rotation = (if (v != null) orientation(MediaKind.VIDEO) else null)?.rotationDegrees
        val encoder =
            textField(
                "video.encoder",
                src(5, ilst, "©too", "Encoder", convert = str),
                src(6, udta, "©too", "©enc", "Encoder", convert = str),
                src(7, keys, "encoder", "Encoder", "com.apple.quicktime.encoder", convert = str),
                src(10, group("Matroska", "Matroska"), "WritingApp", convert = str),
                src(11, mkvTags, "ENCODER", "encoder", convert = str),
                src(12, group("H265", "H265"), "UserDataUnregistered", convert = str),
                src(15, riffInfo, "Software", convert = str),
                src(20, group("ffprobe", "format"), "encoder", convert = str),
            )
        val timescale = v?.let { s ->
            observations.firstOrNull {
                it.tagName == "MediaTimeScale" && it.streamIndex == s.index &&
                    it.backend == s.backend
            }?.value?.asLong()
        }
        val created = r.observations(setOf("CreateDate"), mvhd).firstNotNullOfOrNull { (qtDate(it) as? Conversion.Ok)?.value?.instant }
        val tracks =
            vids.map { s ->
                VideoTrackSummary(
                    s.index, s.codec, s.properties[StreamProps.WIDTH]?.asLong()?.toInt(), s.properties[StreamProps.HEIGHT]?.asLong()?.toInt(),
                    s.properties[StreamProps.FRAME_RATE]?.asRational(), s.properties[StreamProps.BIT_RATE]?.asLong(), durOf(s),
                    s.properties[StreamProps.LANGUAGE]?.asText(),
                    (s.properties[StreamProps.MATRIX] as? NormalizedValue.ListValue)?.items?.mapNotNull {
                        it.asDouble()
                    }?.let { Geo.matrixOrientation(it)?.rotationDegrees },
                    (s.properties[StreamProps.DEFAULT] as? NormalizedValue.Bool)?.value,
                )
            }
        return VideoMetadata(
            duration = c?.duration ?: v?.let { durOf(it) },
            frameRate = p[StreamProps.FRAME_RATE]?.asRational(),
            averageFrameRate = p[StreamProps.AVERAGE_FRAME_RATE]?.asRational(),
            codec = v?.codec,
            codecProfile = p[StreamProps.PROFILE]?.asText(),
            codecLevel = p[StreamProps.LEVEL]?.asText(),
            bitRateBitsPerSecond = p[StreamProps.BIT_RATE]?.asLong() ?: c?.bitRateBitsPerSecond,
            container = if (format == MediaFormat.HEVC) null else format.id,
            streamCount = streams.filter { it.backend == (v?.backend ?: MetadataCollector.NATIVE_BACKEND_ID) }.size.takeIf { it > 0 },
            primaryStreamIndex = v?.index,
            rotationDegrees = rotation,
            variableFrameRate = (p[StreamProps.VARIABLE_FRAME_RATE] as? NormalizedValue.Bool)?.value,
            timeBase = (p[StreamProps.TIME_BASE]?.asRational()) ?: timescale?.takeIf { it > 0 }?.let { Rational(1, it) },
            creationTime = created,
            encoder = encoder,
            audioStreamCount = auds.size,
            videoStreamCount = vids.size,
            tracks = tracks,
        )
    }

    private fun audio(): AudioMetadata? {
        val auds = streamsOf(StreamKind.AUDIO)
        val a = auds.firstOrNull { it.properties[StreamProps.ENABLED] != NormalizedValue.Bool(false) } ?: return null
        val p = a.properties
        fun layout(s: StreamInfo): String? = s.properties[StreamProps.CHANNEL_LAYOUT]?.asText()
            ?: when (s.properties[StreamProps.CHANNELS]?.asLong()) {
                1L -> "mono"
                2L -> "stereo"
                else -> null
            }
        val c = container()
        return AudioMetadata(
            duration = durOf(a) ?: c?.duration,
            codec = a.codec,
            sampleRateHz = p[StreamProps.SAMPLE_RATE]?.asLong()?.toInt(),
            channels = p[StreamProps.CHANNELS]?.asLong()?.toInt(),
            channelLayout = layout(a),
            bitRateBitsPerSecond =
            p[StreamProps.BIT_RATE]?.asLong() ?: (if (probe.mediaKind == MediaKind.AUDIO) c?.bitRateBitsPerSecond else null),
            bitsPerSample = p[StreamProps.BITS_PER_SAMPLE]?.asLong()?.toInt()?.takeIf { it > 0 },
            language = p[StreamProps.LANGUAGE]?.asText(),
            tracks =
            auds.map { s ->
                AudioTrackSummary(
                    s.index, s.codec, s.properties[StreamProps.SAMPLE_RATE]?.asLong()?.toInt(), s.properties[StreamProps.CHANNELS]?.asLong()?.toInt(),
                    layout(s), s.properties[StreamProps.BIT_RATE]?.asLong(),
                    s.properties[StreamProps.BITS_PER_SAMPLE]?.asLong()?.toInt()?.takeIf {
                        it >
                            0
                    },
                    durOf(
                        s,
                    ),
                    s.properties[StreamProps.LANGUAGE]?.asText(), (s.properties[StreamProps.DEFAULT] as? NormalizedValue.Bool)?.value,
                )
            },
        )
    }

    // ---- Description, keywords, colour, animation ----

    private fun description(): DescriptionMetadata? {
        val d =
            DescriptionMetadata(
                title =
                textField(
                    "description.title",
                    src(5, xmp("dc"), "Title", convert = desc), src(8, iptc, "ObjectName", convert = desc),
                    src(9, exifIfd, "ImageTitle", convert = desc), src(10, exif0, "XPTitle", convert = desc),
                    src(10, keys, "com.apple.quicktime.title", "Title", convert = desc), src(11, udta, "©nam", "Title", convert = desc),
                    src(
                        11,
                        ilst,
                        "©nam",
                        "Title",
                        convert = desc,
                    ),
                    src(12, udta, "Title", convert = desc), src(12, mkv, "Title", convert = desc),
                    src(
                        13,
                        mkvTags,
                        "TITLE",
                        convert = desc,
                    ),
                    src(14, riffInfo, "Title", convert = desc), src(15, png, "Title", convert = desc),
                    src(16, id3v2, "Title", convert = desc), src(18, id3v1, "Title", convert = desc),
                    src(19, group("PostScript", "PostScript"), "Title", convert = desc),
                ),
                caption =
                textField(
                    "description.caption",
                    src(5, xmp("dc"), "Description", convert = desc), src(8, iptc, "Caption-Abstract", convert = desc),
                    src(
                        12,
                        exif0,
                        "ImageDescription",
                        convert = desc,
                    ),
                    src(13, keys, "com.apple.quicktime.description", "Description", convert = desc),
                    src(14, udta, "©des", "Description", convert = desc), src(14, ilst, "desc", "Description", convert = desc),
                    src(15, png, "Description", convert = desc), src(16, mkvTags, "DESCRIPTION", convert = desc),
                    src(17, riffInfo, "Subject", convert = desc),
                ),
                comment =
                textField(
                    "description.comment",
                    src(5, exifIfd, "UserComment", convert = desc), src(8, exif0, "XPComment", convert = desc),
                    src(10, group("File", "File"), "Comment", convert = desc), src(10, group("GIF"), "Comment", convert = desc),
                    src(
                        12,
                        keys,
                        "com.apple.quicktime.comment",
                        "Comment",
                        convert = desc,
                    ),
                    src(13, udta, "©cmt", "Comment", convert = desc),
                    src(
                        13,
                        ilst,
                        "©cmt",
                        "Comment",
                        convert = desc,
                    ),
                    src(14, riffInfo, "Comment", convert = desc), src(15, png, "Comment", convert = desc),
                    src(16, mkvTags, "COMMENT", convert = desc),
                    src(17, id3v2, "Comment", convert = desc), src(18, id3v1, "Comment", convert = desc),
                ),
                artist =
                textField(
                    "description.artist",
                    src(5, exif0, "Artist", convert = desc), src(8, exif0, "XPAuthor", convert = desc),
                    src(
                        10,
                        keys,
                        "com.apple.quicktime.artist",
                        "Artist",
                        convert = desc,
                    ),
                    src(11, udta, "©ART", "Artist", convert = desc),
                    src(
                        11,
                        ilst,
                        "©ART",
                        "Artist",
                        convert = desc,
                    ),
                    src(12, riffInfo, "Artist", convert = desc), src(13, mkvTags, "ARTIST", convert = desc),
                    src(14, group("RIFF", "BroadcastExtension"), "Originator", convert = desc),
                    src(15, id3v2, "Artist", convert = desc), src(18, id3v1, "Artist", convert = desc),
                ),
                copyright =
                textField(
                    "description.copyright",
                    src(
                        5,
                        exif0,
                        "Copyright",
                        convert = desc,
                    ),
                    src(6, xmp("dc"), "Rights", convert = desc), src(7, iptc, "CopyrightNotice", convert = desc),
                    src(
                        10,
                        keys,
                        "com.apple.quicktime.copyright",
                        "Copyright",
                        convert = desc,
                    ),
                    src(11, udta, "©cpy", "cprt", "Copyright", convert = desc),
                    src(
                        11,
                        ilst,
                        "cprt",
                        "Copyright",
                        convert = desc,
                    ),
                    src(12, riffInfo, "Copyright", convert = desc), src(13, png, "Copyright", convert = desc),
                    src(14, mkvTags, "COPYRIGHT", convert = desc),
                    src(15, id3v2, "Copyright", convert = desc),
                    src(16, group("PostScript", "PostScript"), "Copyright", convert = desc),
                ),
                creator =
                r.resolve(
                    "description.creator",
                    listOf(
                        src(5, xmp("dc"), "Creator") { o ->
                            ok(
                                o.value.asList().mapNotNull {
                                    textOf(it)?.takeIf { s -> s.isNotBlank() }
                                }.joinToString("; ").ifEmpty { null },
                            )
                        },
                        src(8, iptc, "By-line", convert = desc),
                        src(9, exifIfd, "Photographer", convert = desc),
                    ),
                    { NormalizedValue.Text(it) },
                    ::eqText,
                ),
                credit = textField(
                    "description.credit",
                    src(5, iptc, "Credit", convert = desc),
                    src(6, xmp("photoshop"), "Credit", convert = desc),
                ),
                rating =
                intField(
                    "description.rating",
                    src(5, xmp("xmp"), "Rating") { o ->
                        val v = (textOf(o.value))?.toDoubleOrNull()?.roundToInt()
                        if (v != null &&
                            v in -1..5
                        ) {
                            Conversion.Ok(v)
                        } else {
                            Conversion.Rejected("not in -1..5")
                        }
                    },
                    src(8, exif0, "Rating") { o ->
                        val v = o.value.asLong()?.toInt()
                        if (v != null &&
                            v in -1..5
                        ) {
                            Conversion.Ok(v)
                        } else {
                            Conversion.Rejected("not in -1..5")
                        }
                    },
                    src(20, group("MakerNotes", "Sony", "FujiFilm"), "Rating") { o ->
                        val v = o.value.asLong()?.toInt()
                        if (v != null &&
                            v in 0..5
                        ) {
                            Conversion.Ok(v)
                        } else {
                            Conversion.Rejected("not in 0..5")
                        }
                    },
                ),
                rights = textField(
                    "description.rights",
                    src(5, xmp("xmpRights"), "UsageTerms", convert = desc),
                    src(8, xmp("xmpRights"), "WebStatement", convert = desc),
                ),
                originalFilename =
                textField(
                    "description.originalFilename",
                    src(5, exif0, "OriginalRawFileName", convert = str),
                    src(8, xmp("xmpMM"), "PreservedFileName", convert = str),
                    src(10, xmp("crs"), "RawFileName", convert = str),
                ),
                author =
                textField(
                    "description.author",
                    src(5, png, "Author", convert = desc),
                    src(8, keys, "com.apple.quicktime.author", "Author", convert = desc),
                    src(9, udta, "©aut", "Author", convert = desc),
                    src(10, udta, "auth", convert = desc),
                    src(11, ilst, "©aut", convert = desc),
                ),
                album =
                textField(
                    "description.album",
                    src(5, keys, "com.apple.quicktime.album", "Album", convert = desc),
                    src(6, ilst, "©alb", "Album", convert = desc),
                    src(7, udta, "©alb", "Album", convert = desc),
                    src(8, mkvTags, "ALBUM", convert = desc),
                    src(9, id3v2, "Album", convert = desc),
                    src(10, id3v1, "Album", convert = desc),
                ),
            )
        return d.takeUnless { it == DescriptionMetadata() }
    }

    private fun keywords(): List<String> {
        val out = LinkedHashSet<String>()
        val sources = ArrayList<RawObservation>()
        fun add(obs: List<RawObservation>, split: Boolean) {
            for (o in obs) {
                val items = o.value.asList().mapNotNull { textOf(it) }
                val parts = if (split) items.flatMap { it.split(';', ',') } else items
                var any = false
                for (p in parts) {
                    val t = p.trim()
                    if (t.isNotEmpty() && out.none { it.equals(t, ignoreCase = true) }) {
                        out += t
                        any = true
                    }
                }
                if (any || items.isNotEmpty()) sources += o
            }
        }
        add(r.observations(setOf("Subject"), xmp("dc")), false)
        add(r.observations(setOf("Keywords"), iptc), false)
        add(r.observations(setOf("XPKeywords"), exif0), true)
        add(r.observations(setOf("com.apple.quicktime.keywords", "Keywords"), keys), true)
        add(r.observations(setOf("©key", "kywd", "Keywords"), udta), true)
        add(r.observations(setOf("Keywords"), riffInfo), true)
        add(r.observations(setOf("KEYWORDS"), mkvTags), true)
        if (sources.isNotEmpty()) {
            r.record(
                "keywords",
                sources.first(),
                sources.map {
                    it to it.value
                },
                "union of all keyword sources, de-duplicated case-insensitively, in source order",
            )
        }
        return out.toList()
    }

    private fun color(kind: MediaKind): ColorMetadata? {
        val v = if (kind == MediaKind.VIDEO) primaryVideo() else null
        val vp = v?.properties.orEmpty()
        fun cicp(key: String, names: (Long) -> String?): String? = vp[key]?.let { it.asLong()?.let(names) ?: it.asText() }
        fun heifColr(name: String, names: (Long) -> String?): String? = r.observations(setOf(name), qtMeta).firstOrNull()?.value?.asLong()?.let(names)

        fun pngCicp(name: String, names: (Long) -> String?): String? = r.observations(setOf(name), png).firstOrNull()?.value?.asLong()?.let(names)
        // H.273 code 2 means "unspecified": treated as absent, never as SDR.
        val primaries = (
            cicp(StreamProps.COLOR_PRIMARIES, CICP::primaries) ?: heifColr("ColorPrimaries", CICP::primaries)
                ?: pngCicp("ColorPrimaries", CICP::primaries)
            )?.takeUnless { it == "Unspecified" }
        val transferCode =
            (
                vp[StreamProps.TRANSFER]?.asLong() ?: r.observations(
                    setOf("TransferCharacteristics"),
                    qtMeta,
                ).firstOrNull()?.value?.asLong()
                    ?: r.observations(setOf("TransferCharacteristics"), png).firstOrNull()?.value?.asLong()
                )
                ?.takeUnless { it == 2L }
        val transfer =
            transferCode?.let(CICP::transfer)
                ?: vp[StreamProps.TRANSFER]?.asText()?.takeUnless {
                    it.equals("unknown", true) || it.equals("unspecified", true) ||
                        it.toLongOrNull() == 2L
                }
        val matrix = (cicp(StreamProps.MATRIX_COEFFICIENTS, CICP::matrix) ?: heifColr("MatrixCoefficients", CICP::matrix))?.takeUnless {
            it ==
                "Unspecified"
        }
        val transferText = vp[StreamProps.TRANSFER]?.asText()?.lowercase()
        val hdrGainMap =
            observations.any { it.groupPath.getOrNull(1) in setOf("XMP-hdrgm", "XMP-HDRGainMap") } ||
                observations.any { it.groupPath == listOf("MakerNotes", "Apple") && it.tagName == "HDRHeadroom" }
        val hdr =
            when {
                CICP.isHdrTransfer(transferCode) || transferText in setOf("smpte2084", "arib-std-b67") ||
                    vp.containsKey(StreamProps.DOLBY_VISION_PROFILE) -> true

                hdrGainMap -> true

                transferCode != null || transfer != null -> false

                else -> null
            }
        val colorSpace =
            textField(
                "color.colorSpace",
                src(5, exifIfd, "ColorSpace") { o ->
                    ok(
                        o.value.asLong()?.let {
                            mapOf(
                                1L to "sRGB",
                                2L to "Adobe RGB",
                                0xFFFDL to "Wide Gamut RGB",
                                0xFFFEL to "ICC Profile",
                                0xFFFFL to "Uncalibrated",
                            )[it]
                        },
                    )
                },
                src(8, group("EXIF", "InteropIFD"), "InteropIndex") { o ->
                    ok(
                        textOf(o.value)?.let {
                            mapOf(
                                "R98" to "sRGB",
                                "R03" to "Adobe RGB",
                            )[it.trim()]
                        },
                    )
                },
                src(10, png, "SRGBRendering") { _ -> Conversion.Ok("sRGB") },
                src(12, group("BMP"), "ColorSpace") { o -> ok(o.displayValue) },
            ) ?: primaries
        val iccDesc =
            textField("color.iccProfileDescription", src(5, group("ICC_Profile", "ICC_Profile"), "ProfileDescription", convert = str))
        val iccName =
            textField(
                "color.iccProfileName",
                src(5, png, "ProfileName", convert = str),
                src(10, group("BMP"), "LinkedProfileName", convert = str),
            )
                ?: iccDesc
        val bitDepth =
            vp[StreamProps.BIT_DEPTH_LUMA]?.asLong()?.toInt() ?: intField(
                "color.bitDepth",
                src(5, group("File", "File"), "BitsPerSample", convert = ::intOf),
                src(5, png, "BitDepth", convert = ::intOf),
                src(5, qtMeta, "BitsPerChannel", "BitDepthLuma", convert = ::intOf),
                src(8, exif0, "BitsPerSample", convert = ::intOf),
            )
        val bpp =
            intField(
                "color.bitsPerPixel",
                src(5, group("BMP"), "BitDepth", convert = ::intOf),
                src(5, group("GIF"), "BitsPerPixel", convert = ::intOf),
                src(8, exif0, "BitsPerSample") { o ->
                    val l = o.value.asList().mapNotNull { it.asLong() }
                    if (l.isNotEmpty() &&
                        (format == MediaFormat.TIFF || format == MediaFormat.BIGTIFF)
                    ) {
                        Conversion.Ok(l.sum().toInt())
                    } else {
                        Conversion.Rejected("not a TIFF image")
                    }
                },
            )
        val chroma =
            vp[StreamProps.CHROMA_FORMAT]?.asText()
                ?: r.observations(setOf("ChromaFormat"), qtMeta).firstOrNull()?.displayValue
                ?: r.observations(setOf("YCbCrSubSampling"), group("File", "File")).firstOrNull()?.displayValue?.removePrefix("YCbCr")
        val c =
            ColorMetadata(
                colorSpace, iccName, iccDesc, bitDepth, bpp, hdr, transfer, primaries, matrix,
                vp[StreamProps.PIXEL_FORMAT]?.asText(), chroma,
            )
        return c.takeUnless { it == ColorMetadata() }
    }

    private fun animation(): AnimationMetadata? {
        val g = when (format) {
            MediaFormat.GIF -> group("GIF")
            MediaFormat.WEBP -> group("RIFF", "WEBP")
            MediaFormat.APNG, MediaFormat.PNG -> png
            else -> return null
        }
        val frames =
            r.observations(setOf("FrameCount", "AnimationFrames"), g).firstOrNull()?.value?.asLong()?.toInt()
        val loop = r.observations(
            setOf("AnimationIterations", "AnimationLoopCount", "AnimationPlays"),
            g,
        ).firstOrNull()?.value?.asLong()?.toInt()
        val dur = r.observations(setOf("Duration"), g).firstOrNull()?.value?.asDouble()?.takeIf { it > 0 }?.let {
            Duration.ofMillis(
                (
                    it *
                        1000
                    ).roundToInt().toLong(),
            )
        }
        if ((frames == null || frames <= 1) && loop == null) return null
        return AnimationMetadata(frames, loop, dur)
    }

    private fun extra(): Map<String, NormalizedValue> {
        val out = LinkedHashMap<String, NormalizedValue>()
        fun put(key: String, vararg sources: Source<String>) {
            textField("extra.$key", *sources)?.let { out[key] = NormalizedValue.Text(it) }
        }
        put(
            "livePhotoContentIdentifier",
            src(5, group("MakerNotes", "Apple"), "ContentIdentifier", convert = str),
            src(5, keys, "com.apple.quicktime.content.identifier", "ContentIdentifier", convert = str),
        )
        put("burstId", src(5, group("MakerNotes", "Apple"), "BurstUUID", convert = str))
        put("imageUniqueId", src(5, exifIfd, "ImageUniqueID", convert = str))
        put("documentId", src(5, xmp("xmpMM"), "DocumentID", convert = str))
        return out
    }

    companion object {
        val PLACEHOLDERS =
            setOf(
                "OLYMPUS DIGITAL CAMERA", "SONY DSC", "DIGITAL CAMERA", "DCIM", "SAMSUNG", "KODAK DIGITAL STILL CAMERA",
                "MINOLTA DIGITAL CAMERA", "CREATOR: GD-JPEG V1.0", "DEFAULT", "<NO DESCRIPTION>", "UNTITLED", "SCREENSHOT",
            )
        val METERING = mapOf(
            1L to "AVERAGE",
            2L to "CENTER_WEIGHTED_AVERAGE",
            3L to "SPOT",
            4L to "MULTI_SPOT",
            5L to "PATTERN",
            6L to "PARTIAL",
            255L to "OTHER",
        )
        val PROGRAM =
            mapOf(
                1L to "MANUAL", 2L to "PROGRAM", 3L to "APERTURE_PRIORITY", 4L to "SHUTTER_PRIORITY",
                5L to "CREATIVE", 6L to "ACTION", 7L to "PORTRAIT", 8L to "LANDSCAPE", 9L to "BULB",
            )
        val SCENE = mapOf(0L to "STANDARD", 1L to "LANDSCAPE", 2L to "PORTRAIT", 3L to "NIGHT", 4L to "OTHER")

        fun flashInfo(code: Int): FlashInfo {
            val mode =
                when ((code shr 3) and 3) {
                    1 -> "ON"
                    2 -> "OFF"
                    3 -> "AUTO"
                    else -> null
                }
            return FlashInfo(code, code and 1 == 1, mode, code and 0x40 != 0, code and 0x20 == 0)
        }

        fun xmpFlash(v: NormalizedValue): FlashInfo? {
            val f = (v as? NormalizedValue.ObjectValue)?.fields ?: return null
            fun b(k: String) = (f["exif:$k"] as? NormalizedValue.Text)?.value?.equals("True", ignoreCase = true)
            val modeCode = (f["exif:Mode"] as? NormalizedValue.Text)?.value?.toIntOrNull()
            val mode = when (modeCode) {
                1 -> "ON"
                2 -> "OFF"
                3 -> "AUTO"
                else -> null
            }
            val fired = b("Fired")
            if (fired == null && mode == null) return null
            return FlashInfo(null, fired, mode, b("RedEyeMode"), b("Function")?.not())
        }

        /** Parses "1/250", "0.004" or "28/10". Description strings are never parsed. Use this only for XMP rational-as-text values. */
        fun parseRationalText(s: String?): Rational? {
            val t = s?.trim() ?: return null
            val slash = t.indexOf('/')
            if (slash > 0) {
                val n = t.substring(0, slash).trim().toLongOrNull() ?: return null
                val d = t.substring(slash + 1).trim().toLongOrNull() ?: return null
                return Rational(n, d)
            }
            val d = t.toDoubleOrNull() ?: return null
            if (!d.isFinite()) return null
            val scale = 1_000_000L
            return Rational((d * scale).toLong(), scale).reduced()
        }

        /** Whether an observation carries location data (for GPS redaction). */
        fun isLocationObservation(o: RawObservation): Boolean {
            val g1 = o.groupPath.getOrNull(1)
            val n = o.tagName
            return g1 == "GPS" ||
                (
                    n.startsWith("GPS") &&
                        o.groupPath.getOrNull(0) in setOf("XMP", "Composite", "EXIF", "QuickTime", "Keys", "UserData", "ItemList")
                    ) ||
                n == "©xyz" || n.startsWith("com.apple.quicktime.location") ||
                n.startsWith("Location") && o.groupPath.getOrNull(0) == "QuickTime" ||
                n.equals("location", ignoreCase = true) || n.startsWith("location-") || n == "LOCATION"
        }
    }
}
