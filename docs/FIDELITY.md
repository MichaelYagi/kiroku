# Original values and fidelity

Kiroku keeps every tag it sees, including unknown and vendor tags, and says exactly how faithfully each value matches
the file. This is decision D3 in [`09-DECISIONS.md`](../09-DECISIONS.md).

## What each observation exposes

`RawObservation` (in `result.full.observations`):

| Field | Meaning |
|---|---|
| `groupPath` | The directory, e.g. `["EXIF","IFD0"]`, `["EXIF","ExifIFD"]`, `["MakerNotes","Nikon"]`, `["QuickTime","Keys"]`. Names follow ExifTool family 0/1, so native and ExifTool values line up. |
| `sourcePath` | The full container path, e.g. `JPEG/APP1[0]/TIFF/IFD0/ExifIFD/MakerNote/Canon`. |
| `tagId` / `tagIdHex` | The numeric ID: TIFF tag, IPTC `record*256+dataset`, FourCC as a big-endian integer, EBML element ID, ICC signature. |
| `tagName`, `known` | The name (unknown tags are `0x%04X`, `known = false`). Unknown tags are **never dropped**. |
| `nativeType` | Type system, type name, numeric type code, component count. For example TIFF `RATIONAL` code 5 count 3. |
| `value` | The decoded typed value (`NormalizedValue`): text, integer, decimal, exact rational, boolean, list, object, binary length, null. |
| `displayValue` | A human-readable rendering, if any. Normalization never parses it. |
| `unit` | A fixed unit (e.g. `mm`, `s`, `APEX`, `m`). |
| `streamIndex`, `occurrence` | Track/stream index, and the n-th duplicate of the same tag in the same group. Duplicates are separate observations. |
| `original` | `OriginalValue`: `fidelity`, `bytes`, `fileOffset`, `byteLength`, `byteOrder`, `omittedReason`. |
| `fidelity` | See below. |

`result.full.directories` lists every IFD, MakerNote, segment, chunk, box, packet and record, with its offset, length,
byte order and entry count. Unparsed MakerNotes are listed with `parsed = false`.

## Fidelity levels

| Level | Who can emit it | Guarantee |
|---|---|---|
| `BYTE_EXACT` | Only the native backend | `fileOffset`/`byteLength` address the value bytes in the file, and `bytes` (if present) is identical to them. Tests re-read every such value from disk. |
| `BACKEND_RECONSTRUCTED` | Native (after inflating or reassembling) or external backends | The bytes are a reconstruction: zTXt/iCCP inflated, Extended XMP or multi-segment ICC reassembled, HEVC NAL with emulation bytes removed, ExifTool `-b` output. They are probably original but not guaranteed identical, and there is no file offset. |
| `BACKEND_DECODED` | Any | A typed value only, with no original bytes (XMP properties, ExifTool and ffprobe values, derived counts). |
| `DESCRIPTION_ONLY` | Any | Only a human-readable value exists. |

Enforcement: `MetadataCollector` downgrades any `BYTE_EXACT` claim from a non-native backend to
`BACKEND_RECONSTRUCTED` and records a diagnostic. This is covered by a test.

## Size limits

Values up to `ExtractionOptions.rawValueByteLimit` (default 64 KiB) have their bytes captured inline. Larger values
(thumbnails, previews, large MakerNotes) keep `fileOffset` and `byteLength` with `omittedReason = SIZE_LIMIT` and stay
`BYTE_EXACT`, so you can read them yourself. `includeBinaryPayloads = true` raises the cap to `maxBinaryPayloadBytes`
(16 MiB). Numeric arrays with more than 4096 components are decoded as `Binary(length)`, and their bytes stay in
`original`.

## MakerNotes

1. The whole MakerNote (`EXIF:ExifIFD` tag `0x927C`) is always an observation with its byte-exact bytes (subject to the
   cap) and location.
2. When the vendor layout is recognised, its IFD entries are also parsed byte-exactly under `MakerNotes:<Vendor>`. This
   works for Nikon type 1/3, Canon, Olympus (old and new, incl. Equipment/CameraSettings sub-IFDs), OM System, Sony,
   Panasonic, Leica, Fujifilm, Pentax (AOC/PENTAX), Samsung, Apple, Casio, Sigma and Ricoh. Each candidate layout must
   pass a structural check (type codes, counts, offsets in bounds) before it is used. Otherwise a `MAKERNOTE_UNPARSED`
   diagnostic is recorded and only the blob is kept.
3. `DNGPrivateData` (Adobe `MakN`) MakerNotes are parsed using their recorded original offset.
4. Encrypted vendor structures (Nikon `LensData`/`ShotInfo` v02xx, Sony `0x94xx` ciphers) are kept as raw bytes and
   not decrypted. ExifTool deep mode decodes many of them, and those values are marked `BACKEND_DECODED`.

## Example

```kotlin
val r = extractor.extract(Path.of("IMG_0001.CR2"))
for (o in r.full.observations.filter { it.groupPath == listOf("MakerNotes", "Canon") }) {
    println("${o.tagIdHex} ${o.tagName} ${o.nativeType?.name}[${o.nativeType?.count}] = ${o.value}")
    o.original?.let { println("   ${it.fidelity} @${it.fileOffset}+${it.byteLength} ${it.byteOrder} ${it.bytes?.hex(16)}") }
}
```
