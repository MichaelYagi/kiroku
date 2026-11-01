# Kiroku

Kiroku (記録, "record") is a Kotlin/JVM library that extracts metadata from photos, RAW camera files, video and
audio. One extraction gives you two outputs:

- **`gallery`**: a small, typed, gallery-friendly model. It has the same `camera` / `exposure` / `location` /
  `capturedAt` fields for JPEG, HEIC, RAW, MOV and MP4, and `null` wherever the file has no value. Nothing is guessed.
- **`full`**: every tag Kiroku and the optional backends observed. Each tag keeps its directory path, numeric tag ID,
  native data type and count, decoded value, display value, unit, stream index and occurrence, plus the **original
  bytes with a file offset** and an explicit **fidelity** label. Every gallery field links back to the tags it
  came from, including rejected alternatives and conflicts.

Kiroku is a competitor to Drew Noakes' metadata-extractor. It ships its **own bounded, streaming parsers** for every
format. ExifTool and ffprobe are **optional** deep-mode extras and never a requirement.
See [`09-DECISIONS.md`](09-DECISIONS.md).

```kotlin
MediaMetadata.create().use { extractor ->
    val result = extractor.extract(Path.of("DSC_1001.NEF"))
    println(result.gallery.camera?.model)              // NIKON D2H
    println(result.gallery.capturedAt?.localDateTime)  // 2005-09-24T13:23:28.210 (no time zone in the file: instant is null)
    println(result.full.observations.size)

    val iso = result.full.find("EXIF:ExifIFD:ISO").first()
    println("${iso.tagIdHex} ${iso.nativeType} ${iso.value} ${iso.fidelity} @${iso.original?.fileOffset}")
    // 0x8827 NativeType(system=TIFF, name=SHORT, code=3, count=1) 200 BYTE_EXACT @<file offset of the value bytes>
}
```

## Status

All requested extensions are detected by signature and routed to a native parser. The format matrix below records
what the native parsers decode. Each row is checked on real or generated fixtures, and cross-checked against
ExifTool **and** metadata-extractor in tests.

| Format | Kind | Native coverage | Verified with |
|---|---|---|---|
| JPEG | image | EXIF (byte-exact), MakerNotes (Canon, Nikon, Olympus, Sony, Panasonic, Fujifilm, Pentax, Samsung, Apple, Casio, Sigma, Leica), XMP + Extended XMP, IPTC/Photoshop, ICC, JFIF, MPF, comments | ExifTool samples, generated |
| PNG / APNG | image | IHDR, eXIf, tEXt/zTXt/iTXt, XMP, iCCP, pHYs, tIME, cICP, acTL/fcTL | generated, ExifTool sample |
| BMP | image | all DIB header versions, embedded ICC | generated |
| GIF | image/animation | frames, loop count, delays, comments, XMP | generated |
| WebP | image/animation | VP8/VP8L/VP8X, ANIM/ANMF, EXIF, XMP, ICCP | generated |
| HEIF / HEIC / AVIF | image | ftyp brands, primary item, ispe/irot/imir/pixi/colr/hvcC, Exif and XMP items, thumbnails/aux items | ExifTool sample |
| TIFF / BigTIFF | image or RAW | multi-IFD, SubIFDs, RAW-vs-image classification | generated, ExifTool sample |
| NEF, CR2, ORF, ARW, RW2, RWL, SRW, DNG | RAW | TIFF-variant headers, all IFDs, vendor MakerNote IFDs, DNG tags + DNGPrivateData, RW2 JpgFromRaw | ExifTool samples + 9 CC0 camera files |
| MOV / MP4 / M4A / 3GP | video/audio | mvhd/tkhd (display matrix)/mdhd/hdlr/stsd (avcC, hvcC + SPS VUI, av1C, colr, pasp, btrt, clli, mdcv), stts/stsz, Apple `mdta` keys, iTunes `ilst`, QuickTime `udta` incl. ©xyz, 3GPP `loci`, XMP | generated (iPhone-style, Android-style, stripped, HDR10, VFR, mixed streams), ExifTool sample |
| AVI | video | avih, strh/strf, INFO, IDIT, vendor `strd` EXIF | generated, ExifTool samples |
| WebM / Matroska | video | EBML header, Info, Tracks (incl. Colour), Tags, Chapters, Attachments | generated, ExifTool sample |
| HEVC (raw `.hevc`) | video elementary stream | VPS/SPS/VUI (profile, tier, level, size, bit depth, chroma, colour, SAR, timing), SEI encoder string | generated |
| WAV / RF64 | audio | fmt (incl. extensible), fact, data size and duration, LIST/INFO, BWF `bext`, iXML, XMP | generated, ExifTool sample |

Limitations are listed per format in [`docs/CAPABILITIES.md`](docs/CAPABILITIES.md) and at runtime through
`extractor.capabilities()`.

## Modules

| Module | Purpose | Runtime dependencies |
|---|---|---|
| `kiroku-core` | API, models, native parsers, normalization | kotlinx-coroutines (Apache-2.0) |
| `kiroku-exiftool` | Optional ExifTool backend (`-stay_open` process pool) | jackson-core (Apache-2.0), external `exiftool` |
| `kiroku-ffprobe` | Optional ffprobe backend | jackson-core, external `ffprobe` |
| `kiroku-json` | Deterministic JSON with a published [JSON Schema](docs/schema/kiroku-result.schema.json) | jackson-core |
| `kiroku-cli` | Demo command-line inspector | all of the above |
| `examples` | Compiled Kotlin/Java examples and the [Shashin adapter](docs/SHASHIN.md) sketch | not published |

Optional backends register themselves through `ServiceLoader`, so adding the jar to the classpath is enough. They run
only at `ExtractionDepth.DEEP` by default (`BackendMode.DEEP_ONLY`). When a tool is missing, the backend run is
reported as `UNAVAILABLE` and the native result is returned. A missing tool makes the status `PARTIAL` only if you set
`BackendMode.REQUIRED`.

## Build

Requirements: JDK 17 or newer. Gradle 9.3.1 is provided by the wrapper. The jars target Java 17 and the Kotlin 2.0
API, so Java 17+ and Kotlin 2.0+ applications (for example Spring Boot 3.x) can use them; see `09-DECISIONS.md` D6.

```bash
./gradlew build                      # compile, test, ktlint, all modules
./gradlew publishToMavenLocal        # dev.kiroku:kiroku-core:0.1.0 etc.
./gradlew :kiroku-cli:installDist    # kiroku-cli/build/install/kiroku/bin/kiroku
scripts/fetch-fixtures.sh            # optional: 61 MB of CC0 RAW samples for the RAW integration tests
```

Optional tools for deep mode and for the oracle and integration tests:

- **ExifTool** 12.x or 13.x (Perl). Use `apt install libimage-exiftool-perl`, `brew install exiftool`, or the Windows
  executable from exiftool.org. Kiroku also finds it through `MetadataConfig.exiftool.executable`, `PATH`,
  `KIROKU_EXIFTOOL`, or `.tools/exiftool-dist/exiftool` (tests only).
- **ffprobe** from FFmpeg 4+. Use `apt install ffmpeg` or `brew install ffmpeg`.

## CLI

```bash
kiroku --summary photos/*.jpg videos/*.mov
kiroku --pretty IMG_0001.HEIC                 # gallery JSON
kiroku --full --no-bytes clip.mp4             # full result without inline bytes
kiroku --deep --diagnostics clip.mov          # native + ExifTool + ffprobe runs
kiroku --capabilities
```

## Documentation

- [docs/API.md](docs/API.md): public API, result semantics, Java interop, `close()` and cancellation
- [docs/FIDELITY.md](docs/FIDELITY.md): original values, tag IDs, native types, byte-exact guarantees
- [docs/NORMALIZATION.md](docs/NORMALIZATION.md): field precedence, time zones, orientation, dimensions, GPS, conflicts
- [docs/CAPABILITIES.md](docs/CAPABILITIES.md): format matrix and limitations
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): pipeline, modules, hardening
- [docs/TESTING.md](docs/TESTING.md): test suites, fixtures, oracles, performance
- [docs/SHASHIN.md](docs/SHASHIN.md): integrating Kiroku into Shashin
- [docs/DEPENDENCIES.md](docs/DEPENDENCIES.md): dependency and fixture licences
- [docs/examples](docs/examples): gallery JSON examples (JPEG, RAW, MOV, HEIC, WAV, HEVC)
- [CHANGELOG.md](CHANGELOG.md)
- Specifications: `01`–`08` and the decision log [`09-DECISIONS.md`](09-DECISIONS.md)

## License

Apache License 2.0. See [LICENSE](LICENSE). All runtime dependencies are Apache-2.0 too; see
[docs/DEPENDENCIES.md](docs/DEPENDENCIES.md).
