# Public API

Package `dev.kiroku` (module `kiroku-core`).

```kotlin
interface MediaMetadataExtractor : AutoCloseable {
    fun extract(path: Path, options: ExtractionOptions = DEFAULT): MetadataResult
    suspend fun extractAsync(path: Path, options: ExtractionOptions = DEFAULT): MetadataResult
    fun extractFuture(path: Path, options: ExtractionOptions = DEFAULT): CompletableFuture<MetadataResult>   // Java
    fun extractBatch(paths: Sequence<Path>, options: ExtractionOptions = DEFAULT): Sequence<MetadataResult>
    fun extractBatchIterator(paths: Iterable<Path>, options: ExtractionOptions): CloseableIterator<MetadataResult> // Java
    fun capabilities(): Capabilities
}

object MediaMetadata {
    @JvmStatic fun create(config: MetadataConfig = MetadataConfig()): MediaMetadataExtractor
    @JvmStatic fun create(config: MetadataConfig, backends: List<MetadataBackend>): MediaMetadataExtractor
}

data class MetadataResult(schemaVersion, status, gallery: GalleryMetadata, full: FullMetadata,
                          diagnostics: List<MetadataDiagnostic>, backendRuns: List<BackendRun>)
```

The models are in `dev.kiroku.model`: `GalleryMetadata` and its nested types, `FullMetadata`, `RawObservation`,
`NormalizedValue`, `Rational`, `ByteString`, `OriginalValue`, `FieldProvenance`, `MetadataDiagnostic` and
`BackendRun`. Every model is an immutable data class, and the field definitions match `03-PUBLIC-API.md`. The
additions are documented in `09-DECISIONS.md`.

## Options

`ExtractionOptions` (per call; use `ExtractionOptions.builder()` from Java):

| Field | Default | Meaning |
|---|---|---|
| `depth` | `STANDARD` | `FAST` skips MakerNote IFD decoding and ICC tag tables. `DEEP` also runs the optional backends. |
| `includeRawTags` | `true` | When `false`, `full.observations` and `fieldProvenance` are empty. The gallery is unaffected. |
| `includeBinaryPayloads` | `false` | Raises the inline byte cap to `maxBinaryPayloadBytes`. ExifTool then also runs with `-b`. |
| `includeFileSystemTimestamps` | `true` | Report filesystem creation and modification times in `file`. |
| `gpsPolicy` | `INCLUDE` | `COARSE_GALLERY`, `EXCLUDE_GALLERY` or `EXCLUDE_ALL` (also redacts `full`). |
| `timeout` | 30 s | The per-file deadline across all backends. |
| `rawValueByteLimit` | 64 KiB | The inline original-bytes cap per value. |
| `maxBinaryPayloadBytes` | 16 MiB | The cap used with `includeBinaryPayloads`. |
| `computeChecksum` | `false` | SHA-256 of the whole file (reads every byte). |
| `fileSystemTimeAsCaptureFallback` | `false` | Opt-in. Uses the mtime as `capturedAt` with source `FileSystem:ModifyDate`. |
| `limits` | `ResourceLimits()` | File size, entries per IFD, directories, observations, nesting depth, string length, XMP size, box count, subprocess output. |

`MetadataConfig` (per extractor; use `MetadataConfig.builder()`):
- `exiftool` and `ffprobe`: `ExternalToolConfig(mode, executable, timeout, maxConcurrentProcesses)`
- `allowedRoots`, `symlinkPolicy`
- `batchParallelism`, `discoverBackends`, `additionalBackends`, `defaultOptions`

`BackendMode` controls when an optional backend runs:
- `DISABLED`: never.
- `DEEP_ONLY` (default): at `DEEP` depth only.
- `ALWAYS`: at every depth.
- `REQUIRED`: at every depth; if the backend is unavailable or fails, the status becomes `PARTIAL`.

## Result semantics

| Status | Meaning |
|---|---|
| `COMPLETE` | Every applicable enabled backend succeeded and nothing was truncated. It does not mean every possible tag exists. |
| `PARTIAL` | Usable data, but something went wrong. Either a backend failed or timed out, a `REQUIRED` backend was unavailable, the input was truncated or malformed, or a resource limit was hit. |
| `UNSUPPORTED` | No parser for the detected format, for example an unrecognised signature or an empty file. |
| `FAILED` | No meaningful extraction: missing file, I/O error, rejected path, or file too large. |

A missing optional tool is reported as `BackendRun(status = UNAVAILABLE)` with an `INFO` `BACKEND_UNAVAILABLE`
diagnostic. It does not downgrade the result unless that backend's mode is `REQUIRED`.

Diagnostics carry `code`, `severity`, `message`, `backend`, `field` and `causeClass`. Messages never contain file
paths or coordinates. Codes are a stable enum (`DiagnosticCode`).

## Detection

The format comes from the file signature, then from structure. For TIFF, structure decides between RAW vendor
types, DNG, CFA TIFF and image TIFF. The extension is only a tie-breaker hint. The mismatch case is spelled out
below.

- **`full.file`:** shows the outcome as `detectedFormat`, `mediaKind`, `extensionFormat`, `extensionMatches`,
  `signatureHex` and `brands`.
- **Mismatch:** when the extension disagrees with the signature, Kiroku records an `EXTENSION_MISMATCH` warning and
  uses the format detected from the signature.

## Concurrency, `close()` and cancellation

- An extractor is thread-safe and should be reused. It caches backend availability and keeps a pool of ExifTool
  `-stay_open` processes.
- `close()` stops the ExifTool processes and the batch executor. It is idempotent. Using an extractor after close
  throws `IllegalStateException`.
- `extractAsync` runs on `Dispatchers.IO` and checks coroutine cancellation between structures and while external
  tools run. External processes are killed on cancellation.
- `extractFuture(...).cancel(true)` has the same effect.
- `extractBatch` keeps at most `batchParallelism` files in flight and returns results in input order. A failure in one
  file yields a `FAILED` result for that file, and the batch continues. Closing a `CloseableIterator` stops scheduling
  and cancels files in flight.

## Extending

Implement `dev.kiroku.spi.MetadataBackend` and register it with `MetadataConfig.additionalBackends`, or through a
`MetadataBackendProvider` in `META-INF/services/dev.kiroku.spi.MetadataBackendProvider`. Backends write to
`MetadataCollector`. Only the native backend may claim `BYTE_EXACT`. `ProcessRunner` provides hardened subprocess
execution, and `StreamProps` holds the shared stream property keys.

## JSON

The `kiroku-json` module provides these entry points:
- `KirokuJson.toJson(result, JsonOptions(pretty, includeNulls, includeFull, includeOriginalBytes))`
- `galleryToJson(...)`
- `capabilitiesToJson(...)`
- `write(result, outputStream)`

The output format is defined by the schema in `docs/schema/kiroku-result.schema.json`, and examples are in
`docs/examples`. A schema-version change means an incompatible change.
