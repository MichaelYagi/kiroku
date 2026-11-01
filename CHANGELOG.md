# Changelog

## Unreleased

### Fixed
- `kiroku-json`: pretty-printed JSON used the platform line separator, so it had CRLF line endings on Windows.
  It now always uses `\n`, as the determinism guarantee requires.

## 0.1.0 (2026-10-31)

First implementation.

### Added
- `kiroku-core`: public API (`MediaMetadata`, `MediaMetadataExtractor` with sync, coroutine, future and batch APIs,
  `Capabilities`), immutable gallery and full models, schema 1.0.0.
- Native, bounded parsers with no third-party parsing library:
  - TIFF/BigTIFF/EXIF with byte-exact original values, numeric tag IDs and native types
  - RAW variants: NEF, CR2, ORF, ARW, RW2, RWL, SRW, DNG, PEF
  - MakerNote IFDs for 12 vendor layouts
  - JPEG (incl. Extended XMP, MPF, multi-segment ICC), PNG/APNG, GIF, BMP, WebP
  - HEIF/HEIC/AVIF items and properties
  - QuickTime/MP4 (incl. Apple `mdta` keys, display matrix, hvcC SPS VUI)
  - AVI, WAV/RF64 with BWF and iXML, Matroska/WebM, raw HEVC Annex-B
  - XMP (hardened XML), IPTC IIM, Photoshop IRB, ICC
- Value fidelity model: `BYTE_EXACT`, `BACKEND_RECONSTRUCTED`, `BACKEND_DECODED`, `DESCRIPTION_ONLY`. Only the
  native backend can claim byte-exact.
- Gallery projection with ranked sources, validity checks, conflict detection and per-field provenance:
  - time-zone-aware capture time
  - orientation from EXIF, HEIF and display matrices, with no double rotation
  - RAW, HEIF and video dimension policies
  - GPS from EXIF, ISO 6709, XMP and 3GPP, with privacy policies
- Hardening:
  - path validation, allowed roots and symlink policy
  - resource limits
  - truncation and corruption tolerance
  - timeouts and cancellation
  - bounded batch concurrency
  - `System.Logger` logging with no paths or coordinates
- `kiroku-exiftool`: optional deep-mode backend with a `-stay_open` process pool. Duplicate tags are preserved
  (`-G0:1:4`). Timeout, output cap, hostile-path safety.
- `kiroku-ffprobe`: optional deep-mode backend for streams, side data, chapters and the first frame's side data.
- `kiroku-json`: deterministic JSON writer, a JSON Schema (`docs/schema`) and golden examples.
- `kiroku-cli`: inspector with summary, gallery, full, diagnostics and capabilities output.
- `examples`: Kotlin and Java usage, and a Shashin adapter sketch with tests.
- Tests:
  - ExifTool and metadata-extractor oracles
  - byte-exact re-read verification
  - acceptance scenarios
  - fuzzing and truncation
  - performance
- Fixtures: generator script, CC0 RAW fetch script with checksums, `fixtures/manifest.json`.
- Docs: updated specs 02–07 and `CLAUDE.md`, plus the decision log `09-DECISIONS.md` (Kiroku as a metadata-extractor
  competitor, native parsers, optional deep-mode tools, fidelity model).

### Changed
- Jars target Java 17 and the Kotlin 2.0 API, and need kotlinx-coroutines 1.8.1 or newer, so Java 17 / Kotlin 2.0
  applications such as Spring Boot 3.4 can use them (`09-DECISIONS.md` D6). Builds and tests run on a JDK 17
  toolchain. Virtual threads were replaced by daemon platform threads.
- Capture time: a year- or month-only date (for example an iTunes `©day` of `2010`) no longer beats a full timestamp
  from a lower-ranked source.
- QuickTime/MP4 header dates of exactly 1970-01-01T00:00:00Z (written by some encoders when the clock is unset) are
  treated as unset, like 0, for `capturedAt`, `modifiedAt` and `video.creationTime`.
