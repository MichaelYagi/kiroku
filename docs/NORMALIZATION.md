# Gallery normalization

`gallery` is a projection of `full.observations`. It is never a second parse. Each field is resolved by the
`Resolver`, which works like this:

1. It collects candidate observations from ranked sources.
2. It converts each one to the gallery unit, or rejects it with a reason.
3. It picks the lowest-rank valid candidate. Ties go to the native byte-exact observation, then to the earlier observation.
4. It records `full.fieldProvenance[field]`: the chosen tag, every candidate with its normalized value, any rejection
   reasons, and `conflict = true` when valid candidates disagree. For key fields, a conflict also emits a
   `FIELD_CONFLICT` diagnostic.

Display strings are never parsed for numbers. A missing value is `null`, never `0` or `"unknown"`.

## Capture time (`capturedAt`)

| Rank | Source |
|---|---|
| 5 | EXIF `DateTimeOriginal` + `SubSecTimeOriginal` + `OffsetTimeOriginal` |
| 6 | QuickTime `mdta` `com.apple.quicktime.creationdate` (local time with offset, set by the device) |
| 10–12 | XMP `exif:DateTimeOriginal`, `photoshop:DateCreated` |
| 15–18 | EXIF `CreateDate` (DateTimeDigitized) + offset, XMP `xmp:CreateDate` |
| 19–28 | QuickTime `©day`, AVI `IDIT`, BWF `bext` origination date+time, IPTC `DateCreated`+`TimeCreated`, iTunes `©day`, RIFF `ICRD` |
| 30–35 | QuickTime `mvhd` creation time (UTC), Matroska `DateUTC`, track/media creation times |
| 40–45 | PNG `Creation Time`, ffprobe `creation_time` |
| 1000 | The filesystem modification time, **only** with `fileSystemTimeAsCaptureFallback = true` (source `FileSystem:ModifyDate`, plus a diagnostic) |

Time zones:
- When the source states an offset, `instant`, `localDateTime` and `offset` are all set and `timezoneKnown = true`.
- When there is no offset (most EXIF), only `localDateTime` is set. `instant` is `null`, `timezoneKnown = false`,
  and a `TIMEZONE_UNKNOWN` info diagnostic is recorded. Kiroku never assumes UTC, and never borrows the offset from
  GPS time or the system.
- Sources that are UTC by definition (QuickTime header times, Matroska `DateUTC`, GPS) get `instant` and offset `Z`,
  and `localDateTime = null`, because the wall-clock time at the scene is unknown.
- `precision` keeps the source precision (YEAR, MONTH, DAY, MINUTE, SECOND, MILLISECOND, MICROSECOND, NANOSECOND).
- QuickTime header times of 0 (1904) or exactly the Unix epoch (1970-01-01T00:00:00Z) mean "unset" and are rejected.
- A YEAR- or MONTH-only value is ranked after every valid value that has at least a day, whatever its rank. For
  example, an iTunes `©day` of `2010` does not beat the exact `mvhd` creation time. It stays a candidate in provenance.
- Container creation times on exported or transcoded files often differ from capture. They stay as alternatives in
  provenance, and `video.creationTime` reports the container time separately.
- A UTC instant cannot be compared with a local time that has no offset, so that pair is not reported as a conflict.

`modifiedAt` is the *embedded* modification time (EXIF `ModifyDate` with `OffsetTime`, XMP `ModifyDate`, QuickTime
`mvhd`). It is `null` when no offset is known.

## Camera (photos, RAW and video use the same fields)

`make` and `model` come from EXIF IFD0, then QuickTime `mdta` keys (`com.apple.quicktime.make/model`), then QuickTime
`©mak`/`©mod`, Android keys, XMP `tiff:`, DNG `UniqueCameraModel`, RIFF/Matroska tags and ffprobe tags. The `lens*`,
`cameraSerialNumber`, `firmware` and `owner` fields come from EXIF, then XMP `aux:`/`exifEX:`, then vendor MakerNote
**text** tags. Vendor numeric codes are never shown as names. `software` comes from EXIF `Software`, Apple/Android
keys, `©swr`, XMP `CreatorTool`, RIFF `ISFT` or PNG `Software`. `cameraType` comes from
`com.apple.quicktime.camera.identifier`. A camera is never inferred from encoder software. Strings are trimmed, and
whitespace runs collapse. Punctuation-only placeholders such as `----` are rejected.

## Exposure

ISO comes from EXIF `ISO` (the value 65535 means "≥65535" and is rejected), then SOS/REI/ISOSpeed, then XMP, then the
Nikon MakerNote. Exposure time and f-number come from EXIF rationals (`exposureTime` keeps the exact stored rational),
then XMP. The f-number may also be derived from APEX `ApertureValue`, which is ranked low and marked "derived" in
provenance. `focalLength35mmEquivalentMm` comes from EXIF or the Apple video key. It is never computed from sensor
size. Enumerations are normalized to stable upper-case names (`PATTERN`, `APERTURE_PRIORITY`, …). `flash` decodes
the EXIF bit field. For video, the exposure fields stay null unless the file has explicit tags, and shutter speed is
never inferred from FPS.

## Orientation and dimensions

- **Images (EXIF):** codes 1–8 map to (clockwise rotation, horizontal mirror applied first). For example, 6 → 90°,
  5 → mirror + 270°.
- **HEIF:** `irot` (counter-clockwise quarter turns) and `imir` define the display orientation. EXIF Orientation in
  HEIF is informative only and is **not applied a second time**. Provenance records this.
- **Video:** the primary track's `tkhd` display matrix is decoded to rotation and mirror (non-right-angle matrices give
  `null`). ffprobe `rotation`, which is counter-clockwise, is used only when no native matrix exists.
- `dimensions.stored*` is the stored pixel size. `display*` applies the pixel aspect ratio (`pasp`, Matroska display
  size, HEVC SAR) and the rotation **once**. `aspectRatio` is the reduced display ratio.
- **RAW images:**
  1. DNG `DefaultCropSize` (the final rendered size)
  2. else the largest full-resolution raw IFD (CFA/LinearRaw photometric, CFA pattern tags, or SubIFD subfile type 0)
  3. else Panasonic sensor borders (RW2/RWL)
  4. else EXIF `ExifImageWidth/Height` (CR2)

  This matches ExifTool's `ImageSize` for every RAW fixture except the DNGs, where ExifTool reports the raw IFD size
  including margins (e.g. 1952×1104 vs Kiroku's 1920×1080 crop).
- **Video:** the primary video stream is the first enabled stream, or the default stream when one is marked. When
  there are several, a `PRIMARY_STREAM_SELECTED` diagnostic is recorded.

## Location

Candidate fixes, each kept internally consistent (latitude and longitude always come from the same source):
1. EXIF GPS
2. `com.apple.quicktime.location.ISO6709`
3. QuickTime/iTunes `©xyz`
4. XMP `exif:GPS*`
5. 3GPP `loci`
6. Matroska and ffprobe tags
7. ExifTool Composite

Handling rules:
- DMS rationals become signed decimal degrees, and an already-negative value is never negated twice.
- ISO 6709 decimal, DDMM and DDMMSS forms are all accepted.
- Altitude references are applied. Speed is converted to m/s from K/M/N.
- `headingDegrees` = GPSTrack (direction of movement). `directionDegrees` = GPSImgDirection (camera direction).
- `locationTimestamp` combines GPSDateStamp and GPSTimeStamp, in UTC by definition.
- `GPSStatus = V` (void) fixes are rejected.

`GpsPolicy` controls location output:
- `INCLUDE` reports location as stored.
- `COARSE_GALLERY` rounds to 2 decimals (~1.1 km) and drops the timestamp.
- `EXCLUDE_GALLERY` removes location from the gallery only.
- `EXCLUDE_ALL` also nulls every location observation in `full` and removes its bytes (`OmissionReason.REDACTED`).

Coordinates never appear in diagnostics or logs.

## Colour

- `colorSpace` comes from EXIF ColorSpace/InteropIndex, PNG sRGB, BMP colour space, or the video's colour primaries.
- `iccProfileDescription` comes from the ICC `desc`/`mluc` tag.
- Video and HEIC CICP values come from `colr nclx`, else from the HEVC SPS VUI inside `hvcC`. H.273 code 2
  ("unspecified") counts as absent.
- `hdr` is `true` for PQ/HLG transfer, Dolby Vision, or an HDR gain map. It is `false` for an explicit SDR transfer,
  and `null` when the transfer is unknown.

## Keywords, description, animation, extra

- `keywords` is the union of XMP `dc:subject`, IPTC Keywords, XPKeywords, QuickTime, RIFF and Matroska keywords, in
  source order and de-duplicated case-insensitively.
- `description` fields have explicit ranked sources. Camera placeholder captions such as "OLYMPUS DIGITAL CAMERA" are
  rejected.
- `animation` is set only for GIF, WebP and APNG files with more than one frame or a loop extension. A loop count of
  `0` means forever.
- `extra` holds `livePhotoContentIdentifier` (Apple MakerNote or QuickTime key, for pairing Live Photos),
  `burstId`, `imageUniqueId` and `documentId`.
