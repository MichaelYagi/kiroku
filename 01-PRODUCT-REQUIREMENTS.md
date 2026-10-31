# Product Requirements

## Goal
A media metadata library for large personal galleries, DAM applications, archives, and batch ingestion. Extract the richest metadata available without modifying original files. Present a compact consistent model for UI, search, indexing, maps, sorting, and camera details.

## Supported extensions (case-insensitive)
Images: `.jpg`, `.jpeg`, `.png`, `.bmp`, `.gif`, `.webm` (actually a video container), `.webp`, `.heif`, `.heic`, `.tif`, `.tiff`.
Video/media: `.mp4`, `.wav` (audio, not video), `.avi`, `.mov`, `.hevc` (raw HEVC elementary stream, not necessarily a container), `.webm`.
RAW: `.nef`, `.cr2`, `.orf`, `.arw`, `.rw2`, `.rwl`, `.srw`, `.dng`, `.tif`, `.tiff` (TIFF may be a RAW container).

## Key behaviors
- File type determined by signature/container probing first; extension is a hint, not ground truth. Distinguish image, video, audio, RAW, and unknown; allow `mediaKind` and `format` to differ.
- Extract basic file info, capture context, camera/device, photographic settings, GPS, dimensions, rotation/orientation, embedded text, copyright, color and ICC, video/audio technical streams, maker notes, XMP/IPTC, and arbitrary vendor tags.
- Support multiple streams, multiple embedded images, multiple EXIF blocks, multi-page TIFF, animated images, live-photo-related metadata if present. Do not silently choose a stream without recording the choice.
- Normalize consistently across image and video sources. Keep capture-device fields under `camera` for both.
- Default fast mode suitable for ingest; optional deep mode to invoke richer tooling and scan more deeply.
- Stream or batch processing with bounded concurrency, cancellation, and per-file errors; no all-or-nothing failure for a directory.
- Stable JSON-friendly types and explicit units; timezone-aware timestamps only where the source provides an offset; timezone-less capture times retain local date-time plus offset uncertainty.
- GPS private by default in logs; allow gallery output redaction policies.

## Out of scope
Editing/writing metadata, image conversion, thumbnail creation, scene analysis, automatic geocoding, AI-derived descriptions, reverse geolocation, extracting every video frame, or relying on filename to infer camera model.

## Quality attributes
Reliable on malformed input; predictable performance; low memory overhead; usable without optional native tools; transparent provenance; versioned output schema; clean Java interoperability; no silent data loss in full output.
