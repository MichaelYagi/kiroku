package dev.kiroku.json

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.kiroku.ExtractionOptions
import dev.kiroku.MediaMetadata
import dev.kiroku.MetadataConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KirokuJsonTest {
    private val fixtures = Path.of(System.getProperty("kiroku.fixtures"))
    private val repo = fixtures.parent
    private val extractor = MediaMetadata.create(MetadataConfig(discoverBackends = false))
    private val stable = ExtractionOptions(includeFileSystemTimestamps = false)
    private val mapper = ObjectMapper()

    @AfterAll
    fun close() = extractor.close()

    private val golden =
        mapOf(
            "gallery-jpeg.json" to "media/generated/jpeg_full_exif.jpg",
            "gallery-raw-nef.json" to "media/exiftool/Nikon.nef",
            "gallery-video-mov.json" to "media/generated/video_iphone_style.mov",
            "gallery-heic.json" to "media/exiftool/QuickTime.heic",
            "gallery-audio-wav.json" to "media/generated/audio_bwf.wav",
            "gallery-hevc-elementary.json" to "media/generated/video_raw.hevc",
        )

    @Test
    fun `gallery JSON matches golden snapshots`() {
        val update = System.getProperty("kiroku.updateGolden") == "true" || System.getenv("KIROKU_UPDATE_GOLDEN") == "true"
        val dir = repo.resolve("kiroku-json/src/test/resources/golden")
        val failures = ArrayList<String>()
        for ((name, fixture) in golden) {
            val json = KirokuJson.galleryToJson(extractor.extract(fixtures.resolve(fixture), stable).gallery, JsonOptions(pretty = true)) + "\n"
            val file = dir.resolve(name)
            if (update || !Files.exists(file)) {
                Files.writeString(file, json)
                Files.writeString(repo.resolve("docs/examples").also { Files.createDirectories(it) }.resolve(name), json)
            } else if (Files.readString(file).replace("\r\n", "\n") != json) {
                failures += "$name differs from golden (run with -Dkiroku.updateGolden=true after reviewing the change)"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun `full result JSON is valid, versioned and typed`() {
        val r = extractor.extract(fixtures.resolve("media/generated/jpeg_full_exif.jpg"), stable)
        val node = mapper.readTree(KirokuJson.toJson(r))
        assertEquals("1.0.0", node["schemaVersion"].asText())
        assertEquals("COMPLETE", node["status"].asText())
        val iso = node["full"]["observations"].first { it["tagName"].asText() == "ISO" && it["groupPath"][1].asText() == "ExifIFD" }
        assertEquals("INTEGER", iso["value"]["type"].asText())
        assertEquals(400, iso["value"]["value"].asInt())
        assertEquals("0x8827", iso["tagIdHex"].asText())
        assertEquals("BYTE_EXACT", iso["original"]["fidelity"].asText())
        val nativeIso = r.full.find("EXIF:ExifIFD:ISO").single()
        assertEquals(nativeIso.original!!.bytes!!.base64(), iso["original"]["bytesBase64"].asText())
        assertEquals(nativeIso.original!!.byteOrder!!.name, iso["original"]["byteOrder"].asText())
        assertTrue(node["full"]["fieldProvenance"]["camera.model"]["chosen"]["qualifiedName"].asText() == "EXIF:IFD0:Model")
    }

    @Test
    fun `JSON is deterministic and can omit bytes or include nulls`() {
        val r = extractor.extract(fixtures.resolve("media/exiftool/Canon.jpg"), stable)

        // Everything except per-run timings is deterministic.
        fun stableJson(x: dev.kiroku.MetadataResult) =
            (mapper.readTree(KirokuJson.toJson(x)) as com.fasterxml.jackson.databind.node.ObjectNode).apply { remove("backendRuns") }
        assertEquals(stableJson(r), stableJson(extractor.extract(fixtures.resolve("media/exiftool/Canon.jpg"), stable)))
        assertTrue(!KirokuJson.toJson(r, JsonOptions(includeOriginalBytes = false)).contains("bytesBase64"))
        val withNulls = mapper.readTree(KirokuJson.galleryToJson(r.gallery, JsonOptions(includeNulls = true)))
        assertTrue(withNulls.has("location") && withNulls["location"].isNull)
    }

    @Test
    fun `every field written is declared in the JSON schema`() {
        val schema = mapper.readTree(repo.resolve("docs/schema/kiroku-result.schema.json").toFile())
        val defs = schema["\$defs"]
        val failures = ArrayList<String>()
        fun check(node: JsonNode, schemaNode: JsonNode, path: String) {
            val s = schemaNode["\$ref"]?.asText()?.removePrefix("#/\$defs/")?.let { defs[it] } ?: schemaNode
            if (node.isObject && s.has("properties")) {
                node.properties().forEach { (k, v) ->
                    val prop = s["properties"][k]
                    if (prop == null) failures += "$path.$k not in schema" else check(v, prop, "$path.$k")
                }
            } else if (node.isArray && s.has("items")) {
                node.forEach { check(it, s["items"], "$path[]") }
            }
        }
        for (f in listOf(
            "media/generated/jpeg_full_exif.jpg",
            "media/generated/video_iphone_style.mov",
            "media/exiftool/Nikon.nef",
            "media/generated/gif_animated.gif",
        )) {
            check(mapper.readTree(KirokuJson.toJson(extractor.extract(fixtures.resolve(f), ExtractionOptions(computeChecksum = true)))), schema, f)
        }
        assertTrue(failures.isEmpty(), failures.distinct().joinToString("\n"))
    }

    @Test
    fun `capabilities JSON`() {
        val node = mapper.readTree(KirokuJson.capabilitiesToJson(extractor.capabilities()))
        assertTrue(node["formats"].any { it["format"].asText() == "NEF" })
        assertTrue(node["backends"].any { it["id"].asText() == "native" && it["available"].asBoolean() })
    }
}
