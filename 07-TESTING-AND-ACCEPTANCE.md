# Testing and Acceptance Criteria

## Test pyramid
- Unit: EXIF orientation 1–8, GPS DMS/signed ISO6709, rational values, null/zero distinctions, date/offset/subsecond parsing, QuickTime date variants, conflicting tags, missing source fields, rotation matrix, aspect ratio.
- Integration: known fixture for each extension and representative vendor; ExifTool/ffprobe optional with availability detection and explicit skipped tests; baseline JVM tests must pass without either tool.
- Contract: Kotlin/Java compile examples, JSON golden snapshots, full observation collision preservation, schema version, deterministic ordering, no fabricated fields.
- Robustness: corrupt headers, truncated files, false extensions, missing binaries, executable failures, timeouts, huge tags, Unicode paths, spaces, quotes, symlinks, resource limits, batch cancellation.
- Performance: baseline parse of 1000 mixed media files with bounded heap; large video should not be fully loaded or decoded. Record performance metrics rather than invent targets.

## Essential fixture scenarios
1. JPEG with EXIF camera/lens/ISO/aperture/shutter/GPS/orientation/time offset.
2. PNG with XMP and text; BMP without camera fields.
3. GIF animated; WebP with EXIF/XMP and animated sample.
4. HEIC/HEIF phone photo with date/device/GPS/orientation.
5. TIFF multi-page and RAW-like TIFF.
6. NEF, CR2, ORF, ARW, RW2, RWL, SRW, DNG representative fixtures with vendor tags.
7. iPhone MOV with make/model, GPS, creation time, rotation, HEVC, audio; Android MP4 with device tags where available.
8. Edited/transcoded MP4 with stripped device metadata: camera must be null, not guessed.
9. AVI, WebM, raw HEVC, WAV with technically accurate kind and null camera when absent.
10. Mixed streams, VFR, HDR, portrait video, absent/ambiguous timestamps, timezone-free EXIF.

## Acceptance assertions
- For every listed extension, detection/routing is tested; parser success depends on valid fixture and installed backend.
- Gallery camera fields have identical names and types for image, RAW, video. A phone MOV with device tags populates them.
- Full metadata retains original grouped tags, stream metadata, and provenance, including conflicts and unknown fields.
- All field units and timestamps are consistent; orientation correct without double application.
- Extraction never mutates source; no pixel/frame decode required for ordinary extraction.
- If optional tools missing, return baseline results and diagnostic with PARTIAL only when an applicable requested backend was expected but unavailable; policy is documented.
- Security tests cover command injection resistance and process/resource limits.
- Tests run on Linux and Windows (plus macOS where available), with platform-specific skips documented.

## Oracle and fidelity tests (see `09-DECISIONS.md` D1, D3)
- metadata-extractor (test scope only) and ExifTool, when installed, are independent oracles. Fixture tests compare Kiroku's decoded tag IDs and values with them, and record any known divergence in the manifest.
- For every `BYTE_EXACT` observation, the test re-reads `fileOffset`/`byteLength` from the fixture and asserts the bytes are identical.
- No non-native backend may emit `BYTE_EXACT`. MakerNotes and unknown tags must appear in `full` with numeric `tagId` and original bytes.

## Fixture acquisition
Use permissively licensed/synthetic media where possible. Track source, license, checksum, expected tags, and known parser limitations in `fixtures/manifest.json`. Do not commit personal GPS or private photos. Never invent expected metadata: inspect fixtures with independent tools and record actual values.
