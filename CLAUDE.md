# Kotlin Media Metadata — Claude Code Project Instructions

## Mission
Build a production-quality, standalone Kotlin/JVM library that extracts the maximum practically available metadata from photographs, RAW images, video, and audio-bearing media, while providing a consistent, ergonomic gallery-oriented projection. This is an implementation project, not just a design exercise. Work incrementally, compile, test, and document results.

## Read in order
1. `01-PRODUCT-REQUIREMENTS.md`
2. `02-ARCHITECTURE-AND-BACKENDS.md`
3. `03-PUBLIC-API.md`
4. `04-METADATA-SCHEMA-AND-NORMALIZATION.md`
5. `05-FORMAT-MATRIX.md`
6. `06-IMPLEMENTATION-PLAN.md`
7. `07-TESTING-AND-ACCEPTANCE.md`
8. `08-SHASHIN-INTEGRATION.md`
9. `09-DECISIONS.md` — decision log; overrides earlier specs where they conflict.

## Positioning
Kiroku competes with Drew Noakes' metadata-extractor. It ships its own native parsers. metadata-extractor and ExifTool are test oracles only, and ExifTool/ffprobe are optional deep-mode backends. Never add metadata-extractor to a main source set.

## Non-negotiable requirements
- Kotlin/JVM, Java 21 baseline unless project constraints require another supported LTS; Gradle Kotlin DSL; library-first, no mandatory server or framework.
- Two outputs from a single extraction: exhaustive structured `full` metadata and stable, typed `gallery` metadata. Do not re-parse to obtain the gallery projection.
- Consistent photo/video device and camera fields, with null for unavailable information. Never invent a camera, lens, GPS coordinate, or capture timestamp.
- Preserve all original tags where feasible, including unknown/vendor tags, namespaces, stream indices, original types, units, and source provenance. Distinguish raw values from human-readable descriptions.
- Expose original directories, numeric tag IDs, native data types, decoded values, and original bytes where available. Keep MakerNotes and unknown tags. Mark each value's fidelity (`BYTE_EXACT` / `BACKEND_RECONSTRUCTED` / `BACKEND_DECODED` / `DESCRIPTION_ONLY`), and never claim byte-exact when a backend can't guarantee it.
- Support all requested extensions, with capability limitations clearly reported; never equate extension recognition with guaranteed full decoding or metadata coverage.
- Metadata extraction only: no thumbnail rendering, transcoding, pixel decoding, image transformations, face recognition, database, HTTP service, or filesystem mutation.
- Security: untrusted files, bounded processing, no shell interpolation, subprocess timeout, capped output, restricted paths, graceful partial results.
- Explicit `ExtractionStatus` and per-backend warnings; do not silently swallow failures or report unsupported as successful.
- Comprehensive deterministic tests, fixtures, and documented limitations. Prefer correctness and traceability to speculative tag mapping.
- APIs must be straightforward from Kotlin and Java; JSON serialization is optional and must be in a separate module.

## Workflow
- Before coding, inspect repository and available tooling. Do not assume an executable exists or a library supports a format; verify at runtime or via tests.
- Create modules and data classes first, then native image/RAW parsers, native video/audio parsers, ExifTool adapter, ffprobe adapter, normalization, public facade, tests, documentation.
- Favor Apache-2.0/MIT-compatible dependencies; document licenses and any runtime requirements. ExifTool and FFmpeg/ffprobe are optional external executables, not bundled by default.
- Implement graceful degradation if optional backends are unavailable.
- After each phase run `./gradlew test` (and integration tests where tools exist), fix errors, and report actual outcomes.
- Do not remove unknown tags, collapse conflicting timestamps without evidence, or assume file timestamps are capture timestamps.
- Do not claim complete format support until acceptance tests demonstrate the specified tier.
- Maintain `README.md`, examples, capability matrix, and changelog.

## Definition of done
All public APIs and schema documented; supported file signatures recognized; full and gallery outputs work for image, video, and RAW fixtures; provenance and conflicts retained; predictable partial failure; tests passing; CLI example and Shashin adapter documented; no hidden mandatory dependency on ExifTool/ffprobe.
