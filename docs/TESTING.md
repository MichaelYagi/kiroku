# Testing

```bash
./gradlew build                                  # all modules: compile, tests, ktlint
./gradlew :kiroku-core:test                      # core only
scripts/fetch-fixtures.sh                        # CC0 RAW samples (tests that need them skip otherwise)
KIROKU_NO_EXTERNAL=1 ./gradlew test              # prove the baseline without ExifTool/ffprobe
KIROKU_EXIFTOOL=/path/to/exiftool ./gradlew test # point tests at a specific ExifTool
KIROKU_UPDATE_GOLDEN=true ./gradlew :kiroku-json:test   # regenerate JSON golden files (review the diff)
```

## Suites

| Suite | What it proves |
|---|---|
| `normalize.*UnitTest` | Date/offset/sub-second parsing, QuickTime/ISO/IPTC/ctime variants, implausible dates, DMS/ISO 6709/XMP GPS, speed units, EXIF orientations 1–8, display matrices (incl. mirror), HEIF irot/imir, rationals, null vs zero, ByteString immutability |
| `AcceptanceTest` | Routing for every required extension, the 10 essential fixture scenarios of `07-TESTING-AND-ACCEPTANCE.md`, identical camera fields across kinds, determinism, no source mutation, GPS policies, filesystem-time opt-in, FAST depth, checksum |
| `FidelityTest` | Every `BYTE_EXACT` value is re-read from disk and compared. Tag IDs, native types and counts are exposed. MakerNotes are kept as blobs and as vendor entries. Unknown tags are kept. Size-limit omission. Reconstructed data is not byte-exact. Non-native backends cannot claim byte-exact. |
| `oracle.ExifToolOracleTest` | For every fixture, every EXIF IFD0/ExifIFD/GPS/Interop tag ExifTool reports exists natively with an equal value. Gallery make/model/ISO/f-number/exposure/focal length/DateTimeOriginal/GPS/orientation/video duration agree. Skipped without ExifTool. |
| `oracle.MetadataExtractorOracleTest` | Every EXIF tag ID that metadata-extractor (test-only dependency) reports in IFD0/ExifIFD/GPS/Interop exists in Kiroku with an equal decoded value |
| `RobustnessTest` | Truncation at ~47 offsets per structure, seeded random corruption (30 rounds × 19 structures), IFD loops and absurd counts, observation/string/file-size limits, missing/empty/directory inputs, Unicode/space/quote/`$(…)` paths, symlink escape and allowed roots, batch order/isolation/early close, async and future APIs, a closed extractor, 1000-file performance, large files not read in full |
| `spi.ProcessRunnerTest` | Timeout kills the process tree, output caps, no shell interpretation, cancellation (POSIX only; skipped on Windows) |
| `JavaInteropTest` (Java) | Builders, factory, getters, futures, iterators and static value factories from Java |
| `kiroku-exiftool` tests | ServiceLoader discovery and version, decoded MakerNotes, display vs raw, duplicates preserved, `-b` reconstructed fidelity, DEEP_ONLY skip, missing executable degradation and REQUIRED → PARTIAL, timeout with process replacement, hostile file names, pooled concurrency |
| `kiroku-ffprobe` tests | Streams/side data/container, ffprobe rotation vs native, native/ffprobe agreement on stream counts, size, codec, sample rate and channels for MP4/WebM/AVI/HEVC MP4/WAV/HEVC |
| `kiroku-json` tests | Golden gallery snapshots, typed and versioned full JSON, determinism, omitting bytes, null policy, every emitted field declared in the JSON Schema |
| `examples` tests | Shashin adapter mapping for photo, RAW and video, null mapping for stripped files, warnings |

## MakerNote name check

`scripts/check-makernote-names.py <kiroku-cli> <exiftool> <files...>` compares, per MakerNote directory and tag ID, the
name ExifTool gives a tag with the name Kiroku gives it. Run it after editing `MakerNoteTags.kt`. Expected differences
are ExifTool's model or version suffixes (`ShotInfo02xx`, `ColorData4`), its `…IFD` names for Olympus sub-directory
pointers, and tags ExifTool decodes only for some models.

`scripts/check-makernote-arrays.py` does the same for binary arrays (Canon CameraSettings, ShotInfo, ...): index,
name and stored value per element. Expected differences: elements Kiroku leaves unnamed because their meaning depends on
the camera model, and elements ExifTool converts or hides (for example `FocusDistanceLower` when the upper bound is 0).

## Fixtures

`fixtures/manifest.json` records path, source, licence, SHA-256, known limitations and **independent ExifTool reads**
of the key tags for every fixture. Regenerate it with `python3 -I scripts/record-manifest.py`.

- `fixtures/media/generated`: made by `scripts/generate-fixtures.sh` with ffmpeg test patterns. Tags are written with
  ExifTool or ffmpeg. CC0. The GPS points are public landmarks.
- `fixtures/media/exiftool`: real camera test files from the ExifTool distribution. Same licence as Perl.
- `fixtures/external`: CC0 full-size RAW files from raw.pixls.us (NEF, CR2, CR3, ORF, ARW, RW2, RWL, SRW, RAF, DNG). They are downloaded and checksum-verified, not
  committed. One is a JPEG uploaded with a `.dng` name, and is kept as a mislabel test.

## Measured results (Linux, WSL2 on NTFS, JDK 17 toolchain, 8 cores, 2026-10-31)

| Run | Result |
|---|---|
| `./gradlew clean build` with ExifTool 13.60, ffprobe 7.1.5 and the CC0 RAW fixtures | 332 tests, 0 failures, 0 skipped |
| `KIROKU_NO_EXTERNAL=1`, no ExifTool or ffprobe on `PATH` | 271 tests: 258 passed, 13 skipped (the ExifTool oracle and the ExifTool/ffprobe integration tests), 0 failures |
| 1000 mixed extractions (52 committed fixtures, round-robin, parallelism 4) | 3.02 s (~331 files/s), heap growth 9 MiB, 1 GiB max heap |

Performance depends heavily on the filesystem. These numbers come from a 9p-mounted NTFS volume. Re-run
`RobustnessTest.performance*` with `KIROKU_PERF_OUT=<file>` to record your own.

Test results are cached by Gradle. The visible tool environment (`PATH`, `KIROKU_*`, `.tools`, `fixtures/external`)
is declared as a test input, so a cached "tools absent" result is never reused when tools are present.

## Platforms

CI runs on Linux (with ExifTool and ffmpeg), Windows and macOS (without them, which exercises the skip paths); see
`.github/workflows/ci.yml`. The symlink test aborts when the platform cannot create symlinks (Windows without the
privilege), and the ProcessRunner tests need POSIX tools. Both cases are reported as skipped.
