# Architecture and Backends

> Kiroku competes with Drew Noakes' metadata-extractor, so it ships its own native parsers. metadata-extractor and ExifTool are used only as test oracles. See `09-DECISIONS.md` (D1–D4).

## Modules
- `kiroku-core`: public API, models, signature detection, orchestration, **native metadata parsers** (TIFF/EXIF/RAW + MakerNotes, JPEG, PNG, GIF, WebP/RIFF, BMP, ISO-BMFF/QuickTime/HEIF, AVI, WAV, Matroska/WebM, HEVC Annex-B, XMP, IPTC IIM, ICC), normalization, warnings. Only runtime dependency: kotlinx-coroutines.
- `kiroku-exiftool`: optional ExifTool subprocess adapter (deep mode) for extra proprietary MakerNote decoding and vendor metadata.
- `kiroku-ffprobe`: optional FFprobe adapter (deep mode) for extra codec, stream, side-data and HDR detail.
- `kiroku-json`: optional Jackson-streaming JSON serialization with a stable, versioned schema.
- `kiroku-cli`: example/demo executable, not required for embedding.

## Extraction pipeline
`Path -> validate -> detect signature -> choose backends -> collect raw tagged observations -> merge without losing collisions -> normalize gallery projection -> return result + diagnostics`.

## Backend responsibilities
1. Native (Kiroku): file stats, signature probing, and metadata parsing for every supported format, at all depths. Reads only metadata structures through bounded random access; no pixel or frame decoding. It is the only backend allowed to emit `ValueFidelity.BYTE_EXACT` observations (verbatim bytes plus file offset, numeric tag ID, native type code and count).
2. ExifTool (deep mode by default): optional rich image/RAW/HEIC and maker-specific metadata. Request structured JSON with group-qualified keys, numeric/raw values where practical, and separate human-readable descriptions if needed. Preserve duplicate tags/groups and embedded paths; ExifTool JSON flags can change naming/typing, so test invocation and avoid assuming all keys are unique. Capture tool version. ExifTool values are `BACKEND_DECODED`; binary values obtained with `-b` are `BACKEND_RECONSTRUCTED`, never byte-exact.
3. FFprobe (deep mode by default): optional `-show_format -show_streams -show_chapters` JSON; optionally `-show_frames` only in explicitly bounded deep mode. Capture all stream tags and side data, display matrices, color info, audio details. Use `-v error`, `-of json`, `-i <path>` via ProcessBuilder argument array, never a shell. Some tags are absent depending on muxer/device.
4. Merge: retain every observation in `full`; normalized fields use precedence and plausibility rules, not last-writer-wins. Preserve conflicts in diagnostics/provenance.

## Process hardening
- ProcessBuilder argument list; do not invoke shell. Canonicalize/validate input; optional allowed-root enforcement; reject symlink escape if configured.
- Bound file size, timeouts, stdout/stderr bytes, nesting depth, metadata counts, tag string lengths, and concurrent subprocess count. Kill subprocess tree on timeout where possible.
- Do not read entire media into RAM. Support `Path` as primary input; `InputStream` optional only with explicit spooling limits and cleanup.
- ExifTool and FFprobe paths configurable; no implicit network access or remote metadata parsing.
- `ExtractionOptions` controls enabled backends, depth, timeouts, privacy, max output size, and parallelism.
- Report `BackendStatus`: USED, UNAVAILABLE, SKIPPED, FAILED, TIMED_OUT, with version and timing.

## Precedence policy
- Prefer embedded original capture time over container creation time, and both over filesystem modification time. Never substitute filesystem time into `capturedAt` without explicit caller opt-in and provenance.
- Prefer EXIF camera/lens tags for stills; QuickTime/Apple keys and ExifTool vendor tags for video; allow configurable source ranking. Do not assume a camera name from encoder software.
- For video dimensions use primary video stream coded/display dimensions, accounting for sample aspect ratio and display transform; for photos use primary image pixel dimensions and EXIF orientation.
- Never use human-readable tag description strings to parse numeric values if typed raw values exist.

## Dependency caveats
Verify library/runtime compatibility and licenses before choosing versions. ExifTool, FFmpeg/ffprobe, and codecs are not bundled; include installation instructions and capability checks. The native baseline decodes standard EXIF/TIFF/GPS/XMP/IPTC/ICC and container structures fully, and recognised MakerNote IFDs byte-exactly. Human-readable names for many proprietary MakerNote tags may still need ExifTool deep mode. Unrecognised tags are always kept with their numeric ID and raw bytes.

## Backend modes
`BackendMode.DISABLED | DEEP_ONLY (default for ExifTool/ffprobe) | ALWAYS | REQUIRED`. The status policy for unavailable backends is in `09-DECISIONS.md` D2.
