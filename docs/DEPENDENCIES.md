# Dependencies and licences

Kiroku itself is licensed under the Apache License 2.0 ([LICENSE](../LICENSE)).

## Runtime

| Module | Dependency | Version | Licence |
|---|---|---|---|
| all | Kotlin standard library | 2.0.21 or newer (compiled with Kotlin 2.4.21, API level 2.0) | Apache-2.0 |
| kiroku-core | kotlinx-coroutines-core | 1.8.1 or newer | Apache-2.0 |
| kiroku-exiftool, kiroku-ffprobe, kiroku-json | jackson-core (streaming only) | 2.22.4 | Apache-2.0 |

No other runtime dependencies. The XML (XMP) parser, zlib inflater and SHA-256 come from the JDK (17+).

## Optional external executables (not bundled)

| Tool | Used by | Licence | Notes |
|---|---|---|---|
| ExifTool 12.x/13.x | `kiroku-exiftool` | Perl (Artistic/GPL) | Runs as a separate process only. Not linked or distributed. |
| ffprobe (FFmpeg 4+) | `kiroku-ffprobe` | LGPL/GPL, depending on the build | Runs as a separate process only. Not linked or distributed. |

## Test and build only

| Dependency | Version | Licence | Purpose |
|---|---|---|---|
| JUnit Jupiter (+ params) | 6.1.3 | EPL-2.0 | Tests |
| kotlin-test-junit5 | 2.4.21 | Apache-2.0 | Tests |
| kotlinx-coroutines-test | 1.8.1 | Apache-2.0 | Tests |
| metadata-extractor | 2.22.0 | Apache-2.0 | Independent oracle (09-DECISIONS D1). Never on the runtime classpath. |
| xmpcore (transitive) | 6.1.11 | BSD-3-Clause | metadata-extractor dependency |
| jackson-databind | 2.22.4 | Apache-2.0 | Reading oracle and JSON in tests |
| ktlint gradle plugin / ktlint | 14.2.0 / 1.8.0 | MIT | Formatting checks |
| Gradle | 9.3.1 | Apache-2.0 | Build |

## Fixtures

| Location | Source | Licence |
|---|---|---|
| `fixtures/media/generated` | Generated (`scripts/generate-fixtures.sh`) | CC0-1.0 |
| `fixtures/media/exiftool` | ExifTool 13.60 `t/images` | Same terms as Perl (Artistic-1.0-Perl or GPL-1.0-or-later) |
| `fixtures/external` (not committed) | raw.pixls.us | CC0-1.0 |
