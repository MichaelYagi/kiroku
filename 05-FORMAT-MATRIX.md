# Format Coverage and Capability Matrix

Recognize every extension below, but report metadata capability based on actual parsers and file signatures. A recognized extension does not guarantee metadata extraction.

All primary paths are Kiroku's native parsers (`09-DECISIONS.md` D1). Deep-mode backends are optional.

| Extensions | Actual media kind | Primary path (native) | Deep-mode extras / caveats |
|---|---|---|---|
| JPG, JPEG | image | JPEG markers → EXIF (byte-exact), XMP (+Extended), IPTC/APP13, ICC, JFIF, MPF | ExifTool for proprietary MakerNote names |
| PNG | image | PNG chunks: IHDR, eXIf, tEXt/zTXt/iTXt, iCCP, pHYs, tIME, acTL | ExifTool |
| BMP | image | BITMAPINFOHEADER variants | Usually sparse camera metadata |
| GIF | image/animation | GIF blocks: frames, NETSCAPE loop, comments, XMP app ext | — |
| WEBP | image/animation | RIFF: VP8/VP8L/VP8X, ANIM/ANMF, EXIF, XMP, ICCP | ExifTool |
| HEIF, HEIC | image/sequence | ISO-BMFF meta: ispe, irot, imir, colr, pixi, Exif/XMP items | ExifTool |
| TIF, TIFF | image or RAW image | TIFF IFD walker, multi-page, SubIFDs | ExifTool; RAW-in-TIFF classification |
| NEF, CR2, ORF, ARW, RW2, RWL, SRW, DNG | RAW image | TIFF-variant IFD walker + MakerNote IFDs (byte-exact) | ExifTool for extra vendor tag names |
| MP4, MOV | video/audio container | ISO-BMFF/QuickTime: mvhd, tkhd matrix, mdhd, stsd, udta, meta/keys/ilst, ©xyz | ffprobe, ExifTool |
| AVI | video/audio container | RIFF: avih, strh/strf, INFO, IDIT | ffprobe |
| WEBM | video/audio container | EBML/Matroska: Info, Tracks, Tags | ffprobe |
| HEVC | video elementary stream | Annex-B VPS/SPS parsing (profile, level, size, chroma, bit depth, VUI) | ffprobe for duration/frame count |
| WAV | audio | RIFF: fmt, LIST/INFO, bext (BWF), iXML, data size | ffprobe |

## Requirements
- Include MIME detection for common signatures, with explicit unknown and mislabeled-file behavior.
- HEVC codec inside MP4/MOV is different from `.hevc` elementary stream; expose `container` and `codec` separately.
- HEIF is a family; HEIC is a HEVC-coded HEIF variant; recognize AVIF if convenient but it is not mandatory.
- TIFF appears in both image and RAW lists; classify from actual TIFF subtype/metadata when possible, else IMAGE with warning/uncertainty.
- Some camera/phone videos contain device tags; many exported/edited videos do not. Null is correct when missing.
- Support all listed extensions in detection and routing; tests must specify baseline metadata and deep-mode metadata per fixture, not promise universal proprietary tag coverage.
- Add `Capabilities` API: backend availability, supported signatures/extensions, and known metadata families by backend.
