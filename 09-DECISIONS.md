# Decision Log

Decisions made while reviewing specs 01–08. Each entry: context, decision, consequence. Later entries override earlier spec text where they conflict.

## D1 — Kiroku is a metadata-extractor competitor: native parsers
**Context.** `02` named Drew Noakes' metadata-extractor as the `metadata-image` backend. Kiroku is positioned as a competitor to that library, and the product owner requires byte-exact original values, numeric tag IDs and native data types, which metadata-extractor does not expose.
**Decision.** Kiroku implements its own bounded, streaming parsers for every supported container (TIFF/EXIF incl. RAW variants and MakerNotes, JPEG, PNG, GIF, WebP/RIFF, BMP, ISO-BMFF/QuickTime/HEIF, AVI, WAV, Matroska/WebM, HEVC Annex-B, XMP, IPTC IIM, ICC). metadata-extractor and ExifTool are used **only in tests**, as independent oracles. No main source set depends on metadata-extractor.
**Consequence.** The "use established libraries" guidance is applied to *external tools* (ExifTool, ffprobe) and to the test oracle, not to the core parsers. Parser scope is kept to metadata structures; pixel/frame data is never decoded.

## D2 — ExifTool and ffprobe are optional deep-mode backends
**Decision.** Native parsers are the default for every format at `FAST` and `STANDARD` depth. ExifTool and ffprobe run at `DEEP` depth when installed (`BackendMode.DEEP_ONLY`, the default), or at every depth with `BackendMode.ALWAYS`, or must succeed with `BackendMode.REQUIRED`. They are never bundled.
**Status policy.** A backend in `DEEP_ONLY`/`ALWAYS` that is unavailable produces an `INFO` diagnostic `BACKEND_UNAVAILABLE` and a `BackendRun(status = UNAVAILABLE)` but does not downgrade `COMPLETE`. A `REQUIRED` backend that is unavailable, fails, or times out downgrades to `PARTIAL`. Truncated/malformed input that still yields data is `PARTIAL`.

## D3 — Value fidelity and original bytes
**Context.** The product owner requires original EXIF directories, numeric tag IDs, data types, decoded values and original binary values, with MakerNotes and unknown tags preserved, and a strict distinction between byte-exact and reconstructed values.
**Decision.** Every `RawObservation` carries:
- `groupPath` (directory path such as `["EXIF","IFD0","ExifIFD","MakerNotes:Nikon"]`), `tagId: Long?` (numeric), `tagName`, `nativeType` (type system, name, numeric type code, component count), `value` (decoded `NormalizedValue`), `displayValue?`.
- `original: OriginalValue?` — `fidelity`, `bytes?`, `fileOffset?`, `byteLength?`, `byteOrder?`, `omittedReason?`.
- `fidelity: ValueFidelity`:
  - `BYTE_EXACT` — Kiroku's native parser copied the bytes verbatim from a known file offset. Only native parsers may emit this.
  - `BACKEND_RECONSTRUCTED` — bytes supplied by an external backend (e.g. ExifTool `-b` base64). Probably original, not guaranteed (ExifTool may rebuild MakerNote offsets, byte order, padding).
  - `BACKEND_DECODED` — typed value only, no original bytes.
  - `DESCRIPTION_ONLY` — only a human-readable rendering is available.
- Byte capture limits: values up to `ExtractionOptions.rawValueByteLimit` (default 64 KiB) are captured inline. Larger values (thumbnails, previews, big MakerNotes) keep `fileOffset`/`byteLength` with `omittedReason = SIZE_LIMIT`, so callers can read them byte-exactly themselves; `includeBinaryPayloads = true` raises the cap to `maxBinaryPayloadBytes`.
- MakerNotes: the whole MakerNote is always recorded as one byte-exact observation (subject to the cap), and when the vendor layout is recognised (Nikon type 3, Canon, Olympus old/new, Sony, Panasonic, Fujifilm, Pentax, Samsung, Apple), its IFD entries are parsed as individual byte-exact observations under `MakerNotes:<Vendor>`. Structural validation failure abandons IFD parsing with a `MAKERNOTE_UNPARSED` warning, while keeping the blob. Unknown tags keep `tagName = "0x%04X"` with `known = false`.

## D4 — Module layout
`kiroku-core` (API, models, detection, native parsers, normalization; only runtime dependency: kotlinx-coroutines), `kiroku-exiftool`, `kiroku-ffprobe` (jackson-core streaming parser, which preserves duplicate JSON keys), `kiroku-json` (jackson-core generator, deterministic schema), `kiroku-cli`. Optional backends are registered explicitly in `MetadataConfig` or discovered via `java.util.ServiceLoader`. Group `dev.kiroku`, base package `dev.kiroku`.

## D5 — Spec inconsistencies resolved
- `.webm` listed under images in `01`: routed as video (Matroska/WebM). `.wav` listed under video: `MediaKind.AUDIO`. `.hevc`: elementary stream, `container = null`, `codec = "hevc"`.
- `.tif/.tiff` in both image and RAW lists: classified `RAW_IMAGE` when DNG tags, CFA photometric interpretation (32803/34892), or known RAW maker structure is present; otherwise `IMAGE` with `TIFF_CLASSIFICATION_UNCERTAIN` info diagnostic when ambiguous.
- `suspend fun extractAsync` is unusable from Java: added `extractFuture(path, options): CompletableFuture<MetadataResult>`; `extractBatch` returns a lazily evaluated `Sequence`, plus `extractBatchIterator` returning a closeable `Iterator` for Java.
- `ExtractionOptions` in `03` lacks fields that `02` requires (backends, limits, privacy, parallelism). The `03` fields are kept with their defaults; additional fields are appended with defaults, and process-wide settings live in `MetadataConfig`.
- `gallery.format` and `gallery.file.detectedFormat` both exist; they hold the same detected format identifier.
- `modifiedAt` in `GalleryMetadata` is the *embedded* modification time (EXIF `DateTime`, XMP `ModifyDate`, QuickTime modification time). Filesystem times stay in `GalleryFile`.
- `NormalizedValue` is a sealed hierarchy with an explicit `type` discriminator in JSON — no `Any`.
- Filesystem capture-time fallback requires `ExtractionOptions.fileSystemTimeAsCaptureFallback = true` and is reported with source `FileSystem:ModifyDate`.

## D6 — Java 17 and Kotlin 2.0 for consumers
`CLAUDE.md` sets a Java 21 baseline "unless project constraints require another supported LTS". The first consumer,
Shashin, is a Spring Boot 3.4 application on Java 17 and Kotlin 2.0, and Spring Boot's dependency management pins
`kotlin-stdlib` 2.0.x and `kotlinx-coroutines` 1.8.x. Jars built for Java 21 and Kotlin 2.4 cannot be loaded there.

Decision:
- All modules compile and run their tests on a **JDK 17 toolchain** with `jvmTarget = 17`. Compiling against JDK 17
  also catches use of newer JDK APIs.
- The Kotlin compiler stays at 2.4.x, but `apiVersion` and `languageVersion` are **2.0** and `coreLibrariesVersion`
  is 2.0.21. Applications on Kotlin 2.0 or later can read the metadata, and the published POM asks for stdlib 2.0.21.
- `kotlinx-coroutines` is **1.8.1**, the oldest version Spring Boot 3.4 manages.
- Virtual threads (Java 21) are replaced by daemon platform threads. Batch concurrency was already capped by
  `batchParallelism`, and `ProcessRunner` uses two short-lived pipe threads per subprocess.

Applications on Java 21 and newer Kotlin are unaffected. Raising the baseline again is a breaking change for consumers.

