# Normalization, Provenance, and Rich Metadata

## Principle
Gallery output is a projection of full observations, never a separate extraction. All mapped values should carry an optional provenance pointer in a separate `fieldSources: Map<String,List<TagReference>>` in `FullMetadata`, including rejected alternatives and conflict warnings.

## Sources to collect
- EXIF IFD0/ExifIFD/GPS/Interop, MakerNotes, thumbnails and embedded previews metadata (without extracting binaries by default).
- IPTC IIM, XMP RDF namespaces (Dublin Core, Photoshop, Lightroom, EXIF, IPTC Core/Extension, vendor-specific), ICC profiles, PNG text chunks, GIF application/comment extensions, WebP RIFF chunks, TIFF IFDs, HEIF item properties and metadata boxes.
- QuickTime/ISO BMFF user data, `meta/keys/ilst`, manufacturer keys (including Apple `com.apple.quicktime.*`), timed metadata where accessible, video stream side data, display matrices, audio/video codec properties.
- RAW camera settings, white balance, exposure, lens identifiers, proprietary tags when backend supports them.
- File and container: creation/modification timestamps, encoder, software, MIME, bitrate, stream languages, subtitles, chapter markers, timecodes, frame rate, pixel aspect, audio channels, HDR mastering metadata, content-light metadata.

## Cross-media mapping
- `camera.make/model` from EXIF Make/Model, XMP camera tags, QuickTime make/model/device metadata, ExifTool composite/vendor fields, and supported device metadata atoms. Same public fields for photos and videos.
- `camera.lensModel`, `exposure.iso`, `exposure.focalLengthMm`, `exposure.apertureFNumber`, `exposure.exposureTimeSeconds` map for both still and video when source tags exist; do not infer video shutter from FPS.
- `location` from EXIF GPS, XMP GPS, QuickTime ISO6709, or other valid geo metadata; parse signed coordinates and altitude robustly; preserve original string and coordinate reference. Do not confuse compass direction with GPS coordinates.
- `capturedAt` candidate ordering: DateTimeOriginal + offset/subseconds; XMP CreateDate; device original capture time; QuickTime creation date; container creation time. Container timestamps can reflect export/transcode: retain alternatives and provenance. Filesystem timestamps never silently become capture times.
- Normalize aperture to f-number, focal length to millimeters, shutter to seconds, speed to meters/second, GPS to decimal degrees, duration to `Duration`, bitrate to bits/second, size to bytes, frame rate to rational numerator/denominator.
- Normalize display orientation from EXIF 1–8 and/or container display matrix, including mirror transforms; stored and display dimensions both exposed. Do not rotate twice.

## Collision and missing-data rules
- Each normalized field has candidate list, validity checks, source ranking, chosen candidate and reason. Surface conflicts if non-equivalent candidates remain.
- Distinguish `absent`, `invalid`, `redacted`, `unsupported`, and `backendUnavailable` in diagnostics, without inflating gallery JSON with per-field status.
- No guessed GPS, camera model, lens, or timestamp. Do not infer focal length from 35mm equivalent unless explicitly marked derived and reliable sensor data exists.
- Preserve original precision and time-zone uncertainty. GPS and QuickTime timestamps require special handling of UTC conventions vs real-world camera bugs; don't blindly convert all values to UTC.
- Manufacturer camera model strings may have extra whitespace/aliases; trim safely but retain raw originals.

## Example gallery JSON (illustrative, not a claim about any fixture)
```json
{
  "mediaKind": "VIDEO",
  "format": "MOV",
  "mimeType": "video/quicktime",
  "file": {"name": "clip.mov", "extension": "mov", "sizeBytes": 42000000},
  "capturedAt": {"instant": "2025-04-05T10:30:00Z", "localDateTime": null, "offset": "Z", "timezoneKnown": true, "precision": "SECOND", "source": "QuickTime:CreationDate"},
  "dimensions": {"storedWidth": 1920, "storedHeight": 1080, "displayWidth": 1080, "displayHeight": 1920},
  "orientation": {"rotationDegrees": 90, "mirrored": false},
  "camera": {"make": "Example Manufacturer", "model": "Example Phone", "lensModel": null},
  "exposure": {"iso": null, "apertureFNumber": null, "focalLengthMm": null},
  "location": null,
  "video": {"duration": "PT12.5S", "codec": "hevc", "frameRate": {"numerator": 30, "denominator": 1}},
  "audio": {"codec": "aac", "channels": 2}
}
```
Fields omitted in this compact example remain part of the full contract; serializers may omit nulls consistently. Provide a comparable JPEG and RAW example, and JSON schema snapshots in repository.
