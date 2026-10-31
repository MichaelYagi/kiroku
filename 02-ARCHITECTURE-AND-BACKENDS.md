# Architecture and Backends

## Modules
- `metadata-core`: public API, models, type detection, orchestration, normalization, warnings.
- `metadata-image`: JVM image metadata reader (e.g., Drew Noakes metadata-extractor), native tags and standard EXIF/IPTC/XMP extraction.
- `metadata-exiftool`: optional ExifTool subprocess adapter for broad RAW, MakerNotes, HEIF, video camera tags, and vendor metadata.
- `metadata-ffprobe`: optional FFprobe adapter for containers, video/audio streams, rotations, codec, HDR, durations, GPS/device tags when present.
- `metadata-json`: optional kotlinx.serialization or Jackson integration with stable schema.
- `metadata-cli`: example/demo executable, not required for embedding.

## Extraction pipeline
`Path -> validate -> detect signature -> choose backends -> collect raw tagged observations -> merge without losing collisions -> normalize gallery projection -> return result + diagnostics`.

## Backend responsibilities
1. Built-in JVM: file stats and signature probing; use metadata-extractor for image metadata when supported. No image pixel decoding required.
2. ExifTool: optional rich image/RAW/HEIC and maker-specific metadata. Request structured JSON with group-qualified keys, numeric/raw values where practical, and separate human-readable descriptions if needed. Preserve duplicate tags/groups and embedded paths; ExifTool JSON flags can change naming/typing, so test invocation and avoid assuming all keys are unique. Capture tool version.
3. FFprobe: optional `-show_format -show_streams -show_chapters` JSON; optionally `-show_frames` only in explicitly bounded deep mode. Capture all stream tags and side data, display matrices, color info, audio details. Use `-v error`, `-of json`, `-i <path>` via ProcessBuilder argument array, never a shell. Some tags are absent depending on muxer/device.
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
Verify library/runtime compatibility and licenses before choosing versions. ExifTool, FFmpeg/ffprobe, and codecs are not bundled; include installation instructions and capability checks. Pure-JVM baseline may have substantially less coverage for proprietary RAW and video metadata.
