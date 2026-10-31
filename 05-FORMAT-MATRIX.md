# Format Coverage and Capability Matrix

Recognize every extension below, but report metadata capability based on actual parsers and file signatures. A recognized extension does not guarantee metadata extraction.

| Extensions | Actual media kind | Primary path | Rich fallback / caveats |
|---|---|---|---|
| JPG, JPEG | image | metadata-extractor | ExifTool for MakerNotes, XMP, ICC |
| PNG | image | metadata-extractor | ExifTool for eXIf/iTXt/XMP/ICC |
| BMP | image | header/JVM parser | Usually sparse camera metadata |
| GIF | image/animation | image metadata parser | Animation frames/loop if available; not assumed |
| WEBP | image/animation | RIFF/parser | ExifTool for EXIF/XMP/ICC; animated WebP |
| HEIF, HEIC | image/sequence | HEIF-capable backend | ExifTool, validate actual container and metadata |
| TIF, TIFF | image or RAW image | TIFF IFD parser | ExifTool; multi-page, RAW-in-TIFF ambiguity |
| NEF, CR2, ORF, ARW, RW2, RWL, SRW, DNG | RAW image | metadata-extractor where supported | ExifTool for broad proprietary tags |
| MP4, MOV | video/audio container | ffprobe | ExifTool for device, GPS, camera, maker tags |
| AVI | video/audio container | ffprobe | Limited embedded camera metadata common |
| WEBM | video/audio container | ffprobe | Usually sparse camera tags |
| HEVC | video elementary stream | ffprobe | May lack timestamps, duration, device/GPS |
| WAV | audio | ffprobe | RIFF INFO/BWF/iXML if available; no assumed video |

## Requirements
- Include MIME detection for common signatures, with explicit unknown and mislabeled-file behavior.
- HEVC codec inside MP4/MOV is different from `.hevc` elementary stream; expose `container` and `codec` separately.
- HEIF is a family; HEIC is a HEVC-coded HEIF variant; recognize AVIF if convenient but it is not mandatory.
- TIFF appears in both image and RAW lists; classify from actual TIFF subtype/metadata when possible, else IMAGE with warning/uncertainty.
- Some camera/phone videos contain device tags; many exported/edited videos do not. Null is correct when missing.
- Support all listed extensions in detection and routing; tests must specify baseline metadata and deep-mode metadata per fixture, not promise universal proprietary tag coverage.
- Add `Capabilities` API: backend availability, supported signatures/extensions, and known metadata families by backend.
