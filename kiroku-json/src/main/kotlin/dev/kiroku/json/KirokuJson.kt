package dev.kiroku.json

import com.fasterxml.jackson.core.JsonEncoding
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import dev.kiroku.Capabilities
import dev.kiroku.MetadataResult
import dev.kiroku.model.AnimationMetadata
import dev.kiroku.model.AudioMetadata
import dev.kiroku.model.BackendRun
import dev.kiroku.model.CameraMetadata
import dev.kiroku.model.CaptureTimestamp
import dev.kiroku.model.ColorMetadata
import dev.kiroku.model.ContainerInfo
import dev.kiroku.model.DescriptionMetadata
import dev.kiroku.model.Dimensions
import dev.kiroku.model.DirectoryInfo
import dev.kiroku.model.EmbeddedImageInfo
import dev.kiroku.model.ExposureMetadata
import dev.kiroku.model.FieldProvenance
import dev.kiroku.model.FileProbe
import dev.kiroku.model.FullMetadata
import dev.kiroku.model.GalleryMetadata
import dev.kiroku.model.GeoMetadata
import dev.kiroku.model.MetadataDiagnostic
import dev.kiroku.model.NativeType
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.Orientation
import dev.kiroku.model.OriginalValue
import dev.kiroku.model.Rational
import dev.kiroku.model.RawObservation
import dev.kiroku.model.StreamInfo
import dev.kiroku.model.TagReference
import dev.kiroku.model.VideoMetadata
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

/** JSON options. */
public data class JsonOptions
@JvmOverloads
constructor(
    val pretty: Boolean = false,
    /** Write `null` for absent fields. If `false` (default), absent fields are omitted, consistently everywhere. */
    val includeNulls: Boolean = false,
    /** Include `full` (observations, directories, provenance). */
    val includeFull: Boolean = true,
    /** Include inline original bytes (base64). If `false`, only offset and length are written. */
    val includeOriginalBytes: Boolean = true,
)

/**
 * Deterministic JSON serialization for Kiroku results, schema `MetadataResult.SCHEMA_VERSION`.
 *
 * The field order is fixed by this writer, not by reflection. [NormalizedValue] is written as
 * `{"type": "<ValueType>", "value": ...}`. Instants and local date-times are ISO-8601 strings,
 * durations are ISO-8601 (`PT12.5S`), rationals are `{"numerator": n, "denominator": d}`, and bytes are base64.
 * The JSON Schema is `docs/schema/kiroku-result.schema.json`.
 */
public object KirokuJson {
    private val factory = JsonFactory()

    @JvmStatic
    @JvmOverloads
    public fun toJson(result: MetadataResult, options: JsonOptions = JsonOptions()): String = render(options) { writeResult(it, result, options) }

    @JvmStatic
    @JvmOverloads
    public fun galleryToJson(gallery: GalleryMetadata, options: JsonOptions = JsonOptions()): String = render(options) { W(it, options).gallery(gallery) }

    @JvmStatic
    @JvmOverloads
    public fun capabilitiesToJson(capabilities: Capabilities, options: JsonOptions = JsonOptions(pretty = true)): String =
        render(options) { W(it, options).capabilities(capabilities) }

    @JvmStatic
    @JvmOverloads
    public fun write(result: MetadataResult, out: OutputStream, options: JsonOptions = JsonOptions()) {
        factory.createGenerator(out, JsonEncoding.UTF8).use { g ->
            if (options.pretty) g.useDefaultPrettyPrinter()
            writeResult(g, result, options)
        }
    }

    private fun render(options: JsonOptions, block: (JsonGenerator) -> Unit): String {
        val bos = ByteArrayOutputStream()
        factory.createGenerator(bos, JsonEncoding.UTF8).use { g ->
            if (options.pretty) g.useDefaultPrettyPrinter()
            block(g)
        }
        return bos.toString(Charsets.UTF_8)
    }

    private fun writeResult(g: JsonGenerator, r: MetadataResult, o: JsonOptions) {
        val w = W(g, o)
        g.writeStartObject()
        g.writeStringField("schemaVersion", r.schemaVersion)
        g.writeStringField("status", r.status.name)
        g.writeFieldName("gallery")
        w.gallery(r.gallery)
        if (o.includeFull) {
            g.writeFieldName("full")
            w.full(r.full)
        }
        g.writeArrayFieldStart("diagnostics")
        r.diagnostics.forEach { w.diagnostic(it) }
        g.writeEndArray()
        g.writeArrayFieldStart("backendRuns")
        r.backendRuns.forEach { w.backendRun(it) }
        g.writeEndArray()
        g.writeEndObject()
    }

    /** The writer. Each model type has one method with a fixed field order. */
    private class W(val g: JsonGenerator, val o: JsonOptions) {
        fun str(name: String, v: String?) {
            if (v != null) {
                g.writeStringField(name, v)
            } else if (o.includeNulls) {
                g.writeNullField(name)
            }
        }

        fun num(name: String, v: Number?) {
            when (v) {
                null -> if (o.includeNulls) g.writeNullField(name)

                is Int -> g.writeNumberField(name, v)

                is Long -> g.writeNumberField(name, v)

                is Double -> if (v.isFinite()) {
                    g.writeNumberField(name, v)
                } else if (o.includeNulls) {
                    g.writeNullField(name)
                }

                else -> g.writeNumberField(name, v.toDouble())
            }
        }

        fun bool(name: String, v: Boolean?) {
            if (v != null) {
                g.writeBooleanField(name, v)
            } else if (o.includeNulls) {
                g.writeNullField(name)
            }
        }

        fun instant(name: String, v: Instant?) = str(name, v?.toString())

        fun local(name: String, v: LocalDateTime?) = str(name, v?.toString())

        fun duration(name: String, v: Duration?) = str(name, v?.toString())

        fun rational(name: String, v: Rational?) {
            if (v == null) {
                if (o.includeNulls) g.writeNullField(name)
                return
            }
            g.writeFieldName(name)
            rationalValue(v)
        }

        fun rationalValue(v: Rational) {
            g.writeStartObject()
            g.writeNumberField("numerator", v.numerator)
            g.writeNumberField("denominator", v.denominator)
            g.writeEndObject()
        }

        fun obj(name: String, present: Boolean, body: () -> Unit) {
            if (!present) {
                if (o.includeNulls) g.writeNullField(name)
                return
            }
            g.writeFieldName(name)
            g.writeStartObject()
            body()
            g.writeEndObject()
        }

        fun strings(name: String, v: List<String>) {
            g.writeArrayFieldStart(name)
            v.forEach { g.writeString(it) }
            g.writeEndArray()
        }

        fun gallery(x: GalleryMetadata) {
            g.writeStartObject()
            str("mediaKind", x.mediaKind.name)
            str("format", x.format)
            str("mimeType", x.mimeType)
            obj("file", true) {
                str("name", x.file.name)
                str("extension", x.file.extension)
                num("sizeBytes", x.file.sizeBytes)
                str("detectedFormat", x.file.detectedFormat)
                str("mimeType", x.file.mimeType)
                instant("createdAtFileSystem", x.file.createdAtFileSystem)
                instant("modifiedAtFileSystem", x.file.modifiedAtFileSystem)
                str("checksum", x.file.checksum)
            }
            capture("capturedAt", x.capturedAt)
            instant("modifiedAt", x.modifiedAt)
            dimensions(x.dimensions)
            orientation(x.orientation)
            camera(x.camera)
            exposure(x.exposure)
            location(x.location)
            description(x.description)
            color(x.color)
            video(x.video)
            audio(x.audio)
            animation(x.animation)
            strings("keywords", x.keywords)
            g.writeObjectFieldStart("extra")
            x.extra.forEach { (k, v) ->
                g.writeFieldName(k)
                value(v)
            }
            g.writeEndObject()
            g.writeEndObject()
        }

        fun capture(name: String, c: CaptureTimestamp?) = obj(name, c != null) {
            c!!
            instant("instant", c.instant)
            local("localDateTime", c.localDateTime)
            str("offset", c.offset?.id)
            bool("timezoneKnown", c.timezoneKnown)
            str("precision", c.precision.name)
            str("source", c.source)
        }

        fun dimensions(d: Dimensions?) = obj("dimensions", d != null) {
            d!!
            num("storedWidth", d.storedWidth)
            num("storedHeight", d.storedHeight)
            num("displayWidth", d.displayWidth)
            num("displayHeight", d.displayHeight)
            num("megapixels", d.megapixels)
            rational("pixelAspectRatio", d.pixelAspectRatio)
            rational("aspectRatio", d.aspectRatio)
        }

        fun orientation(x: Orientation?) = obj("orientation", x != null) {
            x!!
            num("exifCode", x.exifCode)
            num("rotationDegrees", x.rotationDegrees)
            bool("mirrored", x.mirrored)
            x.transformMatrix?.let { m ->
                g.writeArrayFieldStart("transformMatrix")
                m.forEach { g.writeNumber(it) }
                g.writeEndArray()
            } ?: run { if (o.includeNulls) g.writeNullField("transformMatrix") }
            str("source", x.source)
        }

        fun camera(c: CameraMetadata?) = obj("camera", c != null) {
            c!!
            str("make", c.make)
            str("model", c.model)
            str("deviceName", c.deviceName)
            str("lensMake", c.lensMake)
            str("lensModel", c.lensModel)
            str("lensSerialNumber", c.lensSerialNumber)
            str("cameraSerialNumber", c.cameraSerialNumber)
            str("firmware", c.firmware)
            str("software", c.software)
            str("owner", c.owner)
            str("cameraType", c.cameraType)
            str("deviceIdentifier", c.deviceIdentifier)
        }

        fun exposure(e: ExposureMetadata?) = obj("exposure", e != null) {
            e!!
            num("iso", e.iso)
            num("exposureTimeSeconds", e.exposureTimeSeconds)
            rational("exposureTime", e.exposureTime)
            num("apertureFNumber", e.apertureFNumber)
            num("focalLengthMm", e.focalLengthMm)
            num("focalLength35mmEquivalentMm", e.focalLength35mmEquivalentMm)
            num("exposureBiasEv", e.exposureBiasEv)
            str("meteringMode", e.meteringMode)
            str("exposureProgram", e.exposureProgram)
            obj("flash", e.flash != null) {
                val f = e.flash!!
                num("code", f.code)
                bool("fired", f.fired)
                str("mode", f.mode)
                bool("redEyeReduction", f.redEyeReduction)
                bool("present", f.present)
            }
            str("whiteBalance", e.whiteBalance)
            str("focusMode", e.focusMode)
            num("digitalZoomRatio", e.digitalZoomRatio)
            num("subjectDistanceMeters", e.subjectDistanceMeters)
            num("shutterSpeedApex", e.shutterSpeedApex)
            num("brightnessValue", e.brightnessValue)
            str("sceneType", e.sceneType)
            str("stabilization", e.stabilization)
            str("hdrMode", e.hdrMode)
            str("shootingMode", e.shootingMode)
        }

        fun location(l: GeoMetadata?) = obj("location", l != null) {
            l!!
            num("latitude", l.latitude)
            num("longitude", l.longitude)
            num("altitudeMeters", l.altitudeMeters)
            str("altitudeReference", l.altitudeReference?.name)
            num("accuracyMeters", l.accuracyMeters)
            num("headingDegrees", l.headingDegrees)
            num("speedMetersPerSecond", l.speedMetersPerSecond)
            num("directionDegrees", l.directionDegrees)
            instant("locationTimestamp", l.locationTimestamp)
            str("gpsDatum", l.gpsDatum)
            str("source", l.source)
        }

        fun description(d: DescriptionMetadata?) = obj("description", d != null) {
            d!!
            str("title", d.title)
            str("caption", d.caption)
            str("comment", d.comment)
            str("artist", d.artist)
            str("copyright", d.copyright)
            str("creator", d.creator)
            str("credit", d.credit)
            num("rating", d.rating)
            str("rights", d.rights)
            str("originalFilename", d.originalFilename)
            str("author", d.author)
            str("album", d.album)
        }

        fun color(c: ColorMetadata?) = obj("color", c != null) {
            c!!
            str("colorSpace", c.colorSpace)
            str("iccProfileName", c.iccProfileName)
            str("iccProfileDescription", c.iccProfileDescription)
            num("bitDepth", c.bitDepth)
            num("bitsPerPixel", c.bitsPerPixel)
            bool("hdr", c.hdr)
            str("transferCharacteristics", c.transferCharacteristics)
            str("colorPrimaries", c.colorPrimaries)
            str("matrixCoefficients", c.matrixCoefficients)
            str("pixelFormat", c.pixelFormat)
            str("chromaSubsampling", c.chromaSubsampling)
        }

        fun video(v: VideoMetadata?) = obj("video", v != null) {
            v!!
            duration("duration", v.duration)
            rational("frameRate", v.frameRate)
            rational("averageFrameRate", v.averageFrameRate)
            str("codec", v.codec)
            str("codecProfile", v.codecProfile)
            str("codecLevel", v.codecLevel)
            num("bitRateBitsPerSecond", v.bitRateBitsPerSecond)
            str("container", v.container)
            num("streamCount", v.streamCount)
            num("primaryStreamIndex", v.primaryStreamIndex)
            num("rotationDegrees", v.rotationDegrees)
            bool("variableFrameRate", v.variableFrameRate)
            rational("timeBase", v.timeBase)
            instant("creationTime", v.creationTime)
            str("encoder", v.encoder)
            num("audioStreamCount", v.audioStreamCount)
            num("videoStreamCount", v.videoStreamCount)
            g.writeArrayFieldStart("tracks")
            v.tracks.forEach { t ->
                g.writeStartObject()
                num("streamIndex", t.streamIndex)
                str("codec", t.codec)
                num("width", t.width)
                num("height", t.height)
                rational("frameRate", t.frameRate)
                num("bitRateBitsPerSecond", t.bitRateBitsPerSecond)
                duration("duration", t.duration)
                str("language", t.language)
                num("rotationDegrees", t.rotationDegrees)
                bool("isDefault", t.isDefault)
                g.writeEndObject()
            }
            g.writeEndArray()
        }

        fun audio(a: AudioMetadata?) = obj("audio", a != null) {
            a!!
            duration("duration", a.duration)
            str("codec", a.codec)
            num("sampleRateHz", a.sampleRateHz)
            num("channels", a.channels)
            str("channelLayout", a.channelLayout)
            num("bitRateBitsPerSecond", a.bitRateBitsPerSecond)
            num("bitsPerSample", a.bitsPerSample)
            str("language", a.language)
            g.writeArrayFieldStart("tracks")
            a.tracks.forEach { t ->
                g.writeStartObject()
                num("streamIndex", t.streamIndex)
                str("codec", t.codec)
                num("sampleRateHz", t.sampleRateHz)
                num("channels", t.channels)
                str("channelLayout", t.channelLayout)
                num("bitRateBitsPerSecond", t.bitRateBitsPerSecond)
                num("bitsPerSample", t.bitsPerSample)
                duration("duration", t.duration)
                str("language", t.language)
                bool("isDefault", t.isDefault)
                g.writeEndObject()
            }
            g.writeEndArray()
        }

        fun animation(a: AnimationMetadata?) = obj("animation", a != null) {
            a!!
            num("frameCount", a.frameCount)
            num("loopCount", a.loopCount)
            duration("duration", a.duration)
        }

        fun value(v: NormalizedValue) {
            g.writeStartObject()
            g.writeStringField("type", v.type.name)
            when (v) {
                is NormalizedValue.Text -> g.writeStringField("value", v.value)

                is NormalizedValue.Integer -> g.writeNumberField("value", v.value)

                is NormalizedValue.Decimal -> if (v.value.isFinite()) g.writeNumberField("value", v.value) else g.writeStringField("value", v.value.toString())

                is NormalizedValue.RationalValue -> {
                    g.writeFieldName("value")
                    rationalValue(v.value)
                }

                is NormalizedValue.Bool -> g.writeBooleanField("value", v.value)

                is NormalizedValue.Binary -> g.writeNumberField("length", v.length)

                is NormalizedValue.ListValue -> {
                    g.writeArrayFieldStart("value")
                    v.items.forEach { value(it) }
                    g.writeEndArray()
                }

                is NormalizedValue.ObjectValue -> {
                    g.writeObjectFieldStart("value")
                    v.fields.forEach { (k, fv) ->
                        g.writeFieldName(k)
                        value(fv)
                    }
                    g.writeEndObject()
                }

                NormalizedValue.Null -> Unit
            }
            g.writeEndObject()
        }

        fun full(f: FullMetadata) {
            g.writeStartObject()
            obj("file", true) { probe(f.file) }
            obj("container", f.container != null) { container(f.container!!) }
            g.writeArrayFieldStart("directories")
            f.directories.forEach { directory(it) }
            g.writeEndArray()
            g.writeArrayFieldStart("streams")
            f.streams.forEach { stream(it) }
            g.writeEndArray()
            g.writeArrayFieldStart("chapters")
            f.chapters.forEach { c ->
                g.writeStartObject()
                num("index", c.index)
                str("backend", c.backend)
                duration("start", c.start)
                duration("end", c.end)
                str("title", c.title)
                g.writeEndObject()
            }
            g.writeEndArray()
            g.writeArrayFieldStart("embeddedImages")
            f.embeddedImages.forEach { embedded(it) }
            g.writeEndArray()
            g.writeArrayFieldStart("observations")
            f.observations.forEach { observation(it) }
            g.writeEndArray()
            g.writeObjectFieldStart("fieldProvenance")
            f.fieldProvenance.forEach { (k, p) ->
                g.writeFieldName(k)
                provenance(p)
            }
            g.writeEndObject()
            g.writeEndObject()
        }

        fun probe(p: FileProbe) {
            str("name", p.name)
            str("extension", p.extension)
            num("sizeBytes", p.sizeBytes)
            str("detectedFormat", p.detectedFormat.id)
            str("mediaKind", p.mediaKind.name)
            str("mimeType", p.mimeType)
            str("extensionFormat", p.extensionFormat?.id)
            bool("extensionMatches", p.extensionMatches)
            str("signatureHex", p.signatureHex)
            strings("brands", p.brands)
            instant("createdAtFileSystem", p.createdAtFileSystem)
            instant("modifiedAtFileSystem", p.modifiedAtFileSystem)
            str("checksum", p.checksum)
        }

        fun container(c: ContainerInfo) {
            str("format", c.format)
            strings("brands", c.brands)
            duration("duration", c.duration)
            num("bitRateBitsPerSecond", c.bitRateBitsPerSecond)
            props("properties", c.properties)
        }

        fun props(name: String, m: Map<String, NormalizedValue>) {
            g.writeObjectFieldStart(name)
            m.forEach { (k, v) ->
                g.writeFieldName(k)
                value(v)
            }
            g.writeEndObject()
        }

        fun directory(d: DirectoryInfo) {
            g.writeStartObject()
            str("backend", d.backend)
            strings("path", d.path)
            str("kind", d.kind.name)
            num("fileOffset", d.fileOffset)
            num("length", d.length)
            str("byteOrder", d.byteOrder?.name)
            num("entryCount", d.entryCount)
            bool("parsed", d.parsed)
            str("note", d.note)
            g.writeEndObject()
        }

        fun stream(s: StreamInfo) {
            g.writeStartObject()
            num("index", s.index)
            str("backend", s.backend)
            str("kind", s.kind.name)
            str("codec", s.codec)
            str("codecTag", s.codecTag)
            props("properties", s.properties)
            g.writeEndObject()
        }

        fun embedded(e: EmbeddedImageInfo) {
            g.writeStartObject()
            num("index", e.index)
            str("backend", e.backend)
            str("kind", e.kind.name)
            strings("groupPath", e.groupPath)
            num("width", e.width)
            num("height", e.height)
            str("compression", e.compression)
            str("mimeType", e.mimeType)
            num("fileOffset", e.fileOffset)
            num("length", e.length)
            g.writeEndObject()
        }

        fun observation(x: RawObservation) {
            g.writeStartObject()
            num("index", x.index)
            str("backend", x.backend)
            strings("groupPath", x.groupPath)
            num("tagId", x.tagId)
            str("tagIdHex", x.tagIdHex)
            str("tagName", x.tagName)
            str("namespace", x.namespace)
            bool("known", x.known)
            str("valueType", x.valueType.name)
            x.nativeType?.let { nativeType(it) } ?: run { if (o.includeNulls) g.writeNullField("nativeType") }
            g.writeFieldName("value")
            value(x.value)
            str("displayValue", x.displayValue)
            str("unit", x.unit)
            num("streamIndex", x.streamIndex)
            num("occurrence", x.occurrence)
            str("sourcePath", x.sourcePath)
            str("fidelity", x.fidelity.name)
            x.original?.let { original(it) } ?: run { if (o.includeNulls) g.writeNullField("original") }
            g.writeEndObject()
        }

        fun nativeType(t: NativeType) = obj("nativeType", true) {
            str("system", t.system.name)
            str("name", t.name)
            num("code", t.code)
            num("count", t.count)
        }

        fun original(v: OriginalValue) = obj("original", true) {
            str("fidelity", v.fidelity.name)
            if (o.includeOriginalBytes) {
                str("bytesBase64", v.bytes?.base64())
            } else if (o.includeNulls) {
                g.writeNullField("bytesBase64")
            }
            num("fileOffset", v.fileOffset)
            num("byteLength", v.byteLength)
            str("byteOrder", v.byteOrder?.name)
            str("omittedReason", v.omittedReason?.name)
        }

        fun ref(name: String?, r: TagReference?) {
            if (name != null) {
                if (r == null) {
                    if (o.includeNulls) g.writeNullField(name)
                    return
                }
                g.writeFieldName(name)
            }
            r!!
            g.writeStartObject()
            num("observationIndex", r.observationIndex)
            str("backend", r.backend)
            str("qualifiedName", r.qualifiedName)
            num("tagId", r.tagId)
            num("occurrence", r.occurrence)
            num("streamIndex", r.streamIndex)
            g.writeEndObject()
        }

        fun provenance(p: FieldProvenance) {
            g.writeStartObject()
            str("field", p.field)
            ref("chosen", p.chosen)
            str("reason", p.reason)
            bool("conflict", p.conflict)
            g.writeArrayFieldStart("candidates")
            p.candidates.forEach { c ->
                g.writeStartObject()
                ref("source", c.source)
                g.writeFieldName("normalizedValue")
                value(c.normalizedValue)
                num("rank", c.rank)
                bool("accepted", c.accepted)
                str("rejectionReason", c.rejectionReason)
                g.writeEndObject()
            }
            g.writeEndArray()
            g.writeEndObject()
        }

        fun diagnostic(d: MetadataDiagnostic) {
            g.writeStartObject()
            str("code", d.code.name)
            str("severity", d.severity.name)
            str("message", d.message)
            str("backend", d.backend)
            str("field", d.field)
            str("causeClass", d.causeClass)
            g.writeEndObject()
        }

        fun backendRun(b: BackendRun) {
            g.writeStartObject()
            str("backend", b.backend)
            str("status", b.status.name)
            str("version", b.version)
            duration("duration", b.duration)
            num("observationCount", b.observationCount)
            str("message", b.message)
            g.writeEndObject()
        }

        fun capabilities(c: Capabilities) {
            g.writeStartObject()
            str("schemaVersion", c.schemaVersion)
            g.writeArrayFieldStart("backends")
            c.backends.forEach { b ->
                g.writeStartObject()
                str("id", b.id)
                str("kind", b.kind.name)
                str("mode", b.mode.name)
                bool("available", b.available)
                str("version", b.version)
                str("message", b.message)
                strings("metadataFamilies", b.metadataFamilies.sorted())
                g.writeEndObject()
            }
            g.writeEndArray()
            g.writeArrayFieldStart("formats")
            c.formats.forEach { f ->
                g.writeStartObject()
                str("format", f.format.id)
                str("mediaKind", f.mediaKind.name)
                strings("extensions", f.extensions.sorted())
                str("mimeType", f.mimeType)
                strings("backends", f.backends)
                strings("nativeFamilies", f.nativeFamilies.sorted())
                strings("limitations", f.limitations)
                g.writeEndObject()
            }
            g.writeEndArray()
            g.writeEndObject()
        }
    }
}
