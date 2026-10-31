# Implementation Plan for Claude Code

## Phase 0 — Repository
Create Gradle Kotlin DSL multi-module repo, JVM toolchain, formatter, static checks, CI, dependency/license inventory, README and architecture notes. Decide group/package name in config. Avoid premature HTTP API.

## Phase 1 — Public models
Implement API from `03-PUBLIC-API.md`, options, diagnostics, typed values, rational numbers, provenance, statuses, immutable objects, Kotlin/Java tests, and serialization snapshots. Ensure no `Any` or polymorphic ambiguity in JSON schema.

## Phase 2 — Detection and baseline
Implement safe file validation, signature probing, extension mismatch warning, basic file info, baseline image metadata reader, TIFF/EXIF orientation and GPS normalization, date parser, capture-time provenance. Support malformed/truncated fixture handling.

## Phase 3 — Rich image/RAW backend
Add optional ExifTool process runner, robust JSON mapping, group-aware duplicate tags, bounded output, MakerNotes/XMP/IPTC/ICC, HEIC/HEIF and proprietary RAW. Merge with baseline without losing original observations.

## Phase 4 — Video/audio backend
Add optional ffprobe process runner, all stream and format tags, chapters, side data, display matrix, HDR/color, codec and audio. Add ExifTool video metadata merge and photo/video camera/device normalization. Validate MOV phone footage with device tags and MP4 stripped of them.

## Phase 5 — Gallery projection
Implement field-specific precedence, timezone handling, orientation/display dimension math, GPS normalization, unit conversions, all camera and exposure fields for both photo and video, privacy redaction, conflict provenance.

## Phase 6 — Production hardening
Batch bounded concurrency, coroutine cancellation, timeout/kill, symlink/root security, output caps, structured logging, stable ordering, large-file tests, resilience and performance measurements.

## Phase 7 — Consumer integration
Provide sample Shashin mapping, Spring optional integration example, JSON example, CLI for inspecting `gallery`, `full`, diagnostics. Do not migrate thumbnailing or Argus features.

## Phase gates
After each phase: compile, unit tests, fixture tests, docs updated, run lint. Report commands and results, remaining unsupported cases and next actions. Never mark a phase complete with failing tests.
