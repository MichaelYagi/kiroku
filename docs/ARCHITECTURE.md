# Architecture

```
Path → validate (canonical path, allowed roots, symlink policy, size limit)
     → probe (stat, first bytes, signature/structure detection, extension check)
     → backends, in order: native → discovered/explicit (exiftool, ffprobe)
         each writes RawObservations, directories, streams, chapters, embedded images and diagnostics
         into one shared, bounded ExtractionState via its MetadataCollector
     → GalleryProjector (ranked resolution + provenance + conflicts)
     → privacy policy (GPS), includeRawTags
     → MetadataResult(status, gallery, full, diagnostics, backendRuns)
```

## Packages (`kiroku-core`)

| Package | Contents |
|---|---|
| `dev.kiroku` | API: `MediaMetadata`, `MediaMetadataExtractor`, options, config, result, capabilities |
| `dev.kiroku.model` | Immutable models (gallery, full, values, diagnostics) |
| `dev.kiroku.spi` | Backend SPI, `MetadataCollector`, `ProcessRunner`, `StreamProps` |
| `dev.kiroku.detect` | Signature detection and TIFF/RAW classification |
| `dev.kiroku.io` | Bounded random-access `ByteSource` (file, slice, array) with absolute offsets and a 2 MiB block cache |
| `dev.kiroku.format.*` | Native parsers: `tiff` (+ MakerNotes), `jpeg`, `png`, `gif`, `bmp`, `riff` (WebP/AVI/WAV), `isobmff` (MOV/MP4/HEIF), `ebml` (Matroska/WebM), `hevc`, `xmp`, `iptc` (+ Photoshop IRB), `icc` |
| `dev.kiroku.normalize` | `Resolver`, `GalleryProjector`, `Dates`, `Geo` |
| `dev.kiroku.internal` | `Engine`, `NativeBackend`, capability notes |

## Native parser design

- Parsers read through `ByteSource`. They seek to structures and read only the bytes they need. Media payloads
  (JPEG scans, IDAT, mdat, movi, clusters, LZW data) are skipped by size and never decoded. A test checks that a
  64 MiB padded MP4 is parsed in well under a second.
- Every `ByteSource` knows its absolute file offset, or knows it has none (inflated or reassembled data). That is how
  `BYTE_EXACT` claims are kept honest.
- Embedded structures recurse into the shared parsers: EXIF in JPEG, PNG, WebP, HEIF, AVI `strd`, QuickTime vendor
  atoms and Photoshop IRBs; XMP and ICC everywhere; JPEG inside RW2.
- Hostile input is bounded:
  - IFD loops are detected.
  - Counts are checked against the remaining data.
  - There are limits on entries per directory, directories, observations, nesting depth, string length, XMP size, box
    count and the inflate output size.
  - XML is parsed with DTDs and external entities disabled.
  - Structural errors become `TRUNCATED`/`MALFORMED` diagnostics, and the partial results are kept.
- A test fuzzes every structure with seeded byte corruption and truncates each fixture at about 47 cut points.
  Nothing may throw or report `BACKEND_FAILED` or `INTERNAL_ERROR`.

## External tools

- `ProcessRunner` passes an argument list to the OS with no shell. stdin is closed, stdout and stderr are capped,
  and a timeout or cancellation kills the whole process tree.
- **ffprobe** runs with `-i file:<path>`. The `file:` prefix stops a path from being read as a protocol URL.
- **ExifTool** runs in a pool of `-stay_open` processes (at most `maxConcurrentProcesses`).
  - Requests are sent as argfile lines. Paths containing line breaks, or starting with `-`, are refused.
  - A broken, timed-out or overflowing session is killed and replaced.
  - Output is parsed with a streaming JSON reader. `-G0:1:4` gives duplicate tags distinct keys (ExifTool's JSON
    writer otherwise drops them), and `CopyN` becomes Kiroku's `occurrence`.
- Directory names, `SourceFile` and ffprobe's `filename` are not copied into output. Diagnostics replace the path
  with `<file>`.

## Logging

Logging goes through `System.Logger` (logger `dev.kiroku`):
- DEBUG: per-backend runs (backend, status, format, duration, observation count)
- WARNING: failures and timeouts

Paths and coordinates are never logged. Use the JDK's bridge, or SLF4J's `jdk-platform-logging`, to route the output.
