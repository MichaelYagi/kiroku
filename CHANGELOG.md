# Changelog

## Unreleased

### Added
- Canon **CR3**: detected by its `crx` brand and read as RAW. EXIF IFD0, Exif IFD, Canon MakerNote and GPS come from the
  CMT1–CMT4 boxes; the THMB thumbnail is listed. Before, CR3 was misdetected as an MP4 video.
- Fujifilm **RAF**: header, embedded JPEG EXIF with the Fujifilm MakerNote, RAF directory (raw image sizes, crop,
  aspect, layout) and the FujiIFD. Gallery dimensions use `RawImageCroppedSize`, as ExifTool does.
- CC0 CR3 and RAF samples in `scripts/fetch-fixtures.sh`, covered by both oracles, acceptance and robustness tests.
- **MP3**: ID3v2.2/2.3/2.4 and ID3v1 tags, the MPEG frame header and Xing/Info/VBRI durations. ID3 title, artist,
  album, comment and copyright feed the gallery description; the audio stream gives codec, sample rate and channels.
- **PSD/PSB**, **EPS/PostScript**, **ICO/CUR** and **PCX**, verified against ExifTool's values.
- Photoshop image resources are decoded into named values (resolution, print scale, global angle and altitude,
  copyright flag, URL, slices, version info, JPEG quality, pixel aspect ratio, clipping path, thumbnail). This also
  applies to the Photoshop block in JPEG and TIFF files.
- XMP: `XMPToolkit` (including the pre-XMP-3 `x:xaptk` form) and `rdf:about` are reported.
- `DiagnosticCode.ESTIMATED_VALUE` for values that are estimated rather than read.
- Canon MakerNote arrays decoded element by element (groups `MakerNotes:CanonCameraSettings`, `CanonShotInfo`,
  `CanonFocalLength`, `CanonPanorama`, `CanonFileInfo`, `CanonAFInfo`, `CanonAFInfo2`): each element keeps its index as
  tag ID, its native type and its original bytes. Verified element by element against ExifTool with
  `scripts/check-makernote-arrays.py`; model-dependent elements are left unnamed.
- Sony MakerNote 0x9050 deciphered (group `MakerNotes:SonyTag9050`: shutter count, internal serial number, lens mount
  and format, exposure fields) for models using ExifTool's "9050a" layout; other models are not guessed at.
- **GeoTIFF** keys (group `GeoTiff`): version, model and raster type, geographic/projected CS, datum, ellipsoid,
  projection parameters, from the key directory and its double and ASCII parameter tags. The GeoTIFF TIFF tags
  (PixelScale, ModelTiePoint, ModelTransform) are now named.
- **PrintIM** (Epson PRINT Image Matching) blocks in TIFF tag 0xC4A5, MakerNotes and JPEG APP6 `EPPIM` segments.
- **Ducky** (Photoshop "Save for Web") APP12 segments: quality, comment, copyright.
- MP3: VBRBytes and VBRScale from the Xing header.
- The ExifTool oracle test now also compares the GeoTiff, PrintIM, Ducky, PostScript, ID3 and MPEG groups on every
  fixture, and Photoshop resources wherever both decode them.

### Fixed
- XMP packets followed by NUL padding (Fujifilm) were rejected as malformed.
- MakerNote tag names checked against ExifTool by tag ID and directory (new `scripts/check-makernote-names.py`):
  - Casio type 1 (header-less) notes were read with the type 2 table, so every tag had the wrong name.
  - Casio type 2 `WhiteBalance`, `FocusMode`, `Sharpness`, `Contrast`, `Saturation` and Nikon 0x00A0 `SerialNumber`
    had a spurious "2" suffix.
  - Olympus CameraSettings: 0x0527 is `NoiseFilter` (was labelled `PictureModeTone`, which is 0x0526); 0x0502 is
    `WhiteBalanceFineTune` or, with one value, `WhiteBalanceBracket`; 0x0522-0x0526 are now named.
  - FujiFilm 0x1304 was labelled `GEImageSize`, a name that only applies to GE cameras.
- Panasonic `FirmwareVersion` (0x0002) was decoded as text because it shares its ID with `InteropVersion`. Version
  tags are now matched by name, and non-printable version bytes are kept as numbers.
- Casio `FirmwareDate` kept only its first NUL-separated part.
- DNG text tags stored as BYTE (`LocalizedCameraModel`, `OriginalRawFileName`, `ProfileName`, `ProfileCopyright`,
  calibration signatures) were decoded as lists of numbers instead of text.
- PostScript fonts (`%!PS-AdobeFont`) were detected as EPS images.
- `kiroku-json` wrote local times with zero seconds as `HH:mm` (`LocalDateTime.toString()`); seconds are now always written.
- QuickTime/iTunes item-list and user-data atoms had their readable name as the *display value*; it is now the tag name
  (`QuickTime:ItemList:Lyrics`, ExifTool's names), and the four-character code is the tag ID.
- QuickTime user-data text with a classic Mac language code was decoded as Latin-1 instead of MacRoman.
- Ricoh and Sanyo MakerNotes are parsed (8-byte header + IFD), including the PrintIM blocks inside them.
- DJI MakerNotes (aircraft speed and attitude, gimbal attitude) and Leica type 5 MakerNotes (X1/X2/X Vario: original
  file name and directory, film mode, white balance levels). CC0 DJI FC220 and Leica X2 samples added to the fetch script.
- All 76 ExifTool sample files Kiroku reads are now committed fixtures (108 in total), so both oracles check them.

## 0.1.1 (2026-10-31)

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
