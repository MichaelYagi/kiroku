# Implementation Plan for Claude Code

## Phase 0 — Repository
Create Gradle Kotlin DSL multi-module repo, JVM toolchain, formatter, static checks, CI, dependency/license inventory, README and architecture notes. Decide group/package name in config. Avoid premature HTTP API.

## Phase 1 — Public models
Implement API from `03-PUBLIC-API.md`, options, diagnostics, typed values, rational numbers, provenance, statuses, immutable objects, Kotlin/Java tests, and serialization snapshots. Ensure no `Any` or polymorphic ambiguity in JSON schema.

## Phase 2 — Detection and native image/RAW parsers
Implement safe file validation, signature probing, extension mismatch warning, and basic file info. Build Kiroku's native parsers: byte-exact TIFF/EXIF IFD walker (all RAW variants, SubIFDs, GPS, Interop, MakerNote IFDs), JPEG, PNG, GIF, WebP, BMP, HEIF, XMP, IPTC and ICC. Add the date parser and capture-time provenance. Handle malformed/truncated fixtures. No metadata-extractor in main code (`09-DECISIONS.md` D1).

## Phase 3 — Native video/audio parsers
ISO-BMFF/QuickTime (incl. Apple keys, ISO 6709 GPS, display matrix), AVI, WAV (INFO/BWF/iXML), Matroska/WebM, HEVC Annex-B SPS. Validate MOV phone-style footage with device tags and MP4 stripped of them.

## Phase 4 — Optional deep-mode backends
ExifTool process runner (robust JSON mapping with duplicate keys, group-aware, bounded output) and ffprobe runner (streams, format, chapters, side data). Merge with native observations without losing any; mark fidelity honestly.

## Phase 5 — Gallery projection
Implement field-specific precedence, timezone handling, orientation/display dimension math, GPS normalization, unit conversions, all camera and exposure fields for both photo and video, privacy redaction, conflict provenance.

## Phase 6 — Production hardening
Batch bounded concurrency, coroutine cancellation, timeout/kill, symlink/root security, output caps, structured logging, stable ordering, large-file tests, resilience and performance measurements.

## Phase 7 — Consumer integration
Provide sample Shashin mapping, Spring optional integration example, JSON example, CLI for inspecting `gallery`, `full`, diagnostics. Do not migrate thumbnailing or Argus features.

## Phase gates
After each phase: compile, unit tests, fixture tests, docs updated, run lint. Report commands and results, remaining unsupported cases and next actions. Never mark a phase complete with failing tests.
