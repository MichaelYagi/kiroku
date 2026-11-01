# Capability matrix

All extensions in `01-PRODUCT-REQUIREMENTS.md` are recognised. The format is decided by signature. Coverage is what
the native parser decodes. The "Tested" column says how each row is verified. At runtime,
`extractor.capabilities()` reports the same information, plus which backends are available.

| Extension(s) | Detected format → kind | Native coverage | Tested | Deep mode adds | Known limitations |
|---|---|---|---|---|---|
| .jpg .jpeg | JPEG → IMAGE | SOF frame info, JFIF/JFXX, EXIF (multiple APP1), MakerNotes (12 vendor layouts), XMP + Extended XMP, IPTC via Photoshop IRB, ICC (single or multi-segment), MPF index, Adobe APP14, COM, unknown APPn kept byte-exact | 20 ExifTool camera samples and generated fixtures. Oracles: ExifTool, metadata-extractor | ExifTool names for proprietary MakerNote fields, Composite tags | Parsing stops at SOS. Trailers after EOI (Samsung, Google motion photos) are not read. |
| .png | PNG / APNG → IMAGE | IHDR, eXIf, tEXt/zTXt/iTXt (incl. XMP), iCCP (inflated), pHYs, tIME, gAMA, cHRM, sRGB, cICP, sBIT, acTL/fcTL, ImageMagick raw profiles, unknown chunks byte-exact | generated + ExifTool sample | — | Compressed chunks are reconstructed, so they are not byte-exact. |
| .bmp | BMP → IMAGE | OS/2 and Windows V3/V4/V5 headers, compression, resolution, colour space, embedded ICC, linked profile name | generated + ExifTool sample | — | BMP has no camera, time or GPS fields. |
| .gif | GIF → IMAGE | header, frame count, total delay, NETSCAPE/ANIMEXTS loop, comments, XMP app extension | generated (static, animated) + ExifTool sample | ffprobe frames | The frame count requires walking all blocks (sub-blocks are skipped, never decoded). |
| .webp | WEBP → IMAGE | VP8 / VP8L / VP8X size and flags, ANIM loop, ANMF frames and duration, EXIF, XMP, ICCP | generated (still with EXIF/XMP, animated) + ExifTool sample | — | — |
| .heic .heif | HEIC / HEIF (AVIF recognised) → IMAGE | ftyp brands, primary item, ispe, irot, imir, pixi, colr (nclx/ICC), hvcC, clap, pasp, clli; Exif and XMP items via iloc; thumbnail/aux/grid item listing | ExifTool sample | ExifTool | iloc construction method 2 is not read. Grid tiles are counted, not listed. |
| .tif .tiff | TIFF / BigTIFF → IMAGE, or TIFF_RAW / DNG / vendor RAW → RAW_IMAGE | all IFDs (multi-page), SubIFDs, EXIF/GPS/Interop, XMP/IPTC/Photoshop/ICC tags | generated + ExifTool sample | ExifTool | A TIFF with no RAW evidence and a RAW extension is classified IMAGE, with `TIFF_CLASSIFICATION_UNCERTAIN`. |
| .nef | NEF → RAW | Nikon type 1/3 MakerNote (incl. PreviewIFD), SubIFD raw/preview | ExifTool sample + Nikon D2H (CC0) | ExifTool decrypted LensData/ShotInfo | Encrypted Nikon blocks are kept as raw bytes. |
| .cr2 | CR2 → RAW | Canon MakerNote IFD, preview IFDs, raw size from EXIF | ExifTool sample + EOS 20D (CC0) | ExifTool CameraSettings arrays decoded | The CR2 raw IFD has no size tags, so EXIF size is used. |
| .orf | ORF → RAW | IIRO/MMOR header, Olympus MakerNote incl. Equipment/CameraSettings | Olympus E-3 (CC0) | ExifTool | — |
| .arw | ARW → RAW | Sony MakerNote IFD, raw SubIFD | Sony ILCE-7S (CC0) | ExifTool (0x94xx ciphers) | Sony enciphered tags are kept as raw bytes. |
| .rw2 .rwl | RW2 / RWL → RAW | PanasonicRaw IFD0, sensor borders, JpgFromRaw EXIF + Panasonic/Leica MakerNotes | ExifTool sample + LX7, D-LUX 5 (CC0) | ExifTool | — |
| .srw | SRW → RAW | Samsung MakerNote, CFA SubIFD | Samsung NX500 (CC0) | ExifTool | — |
| .dng | DNG → RAW | DNG tags (incl. 1.4–1.7 additions), DefaultCropSize, DNGPrivateData MakerNote | ExifTool sample + Blackmagic, MLV App (CC0) | ExifTool | — |
| .mp4 .mov | MP4 / MOV (M4A, 3GP) → VIDEO, or AUDIO when there is no video track | ftyp, mvhd, tkhd matrix, mdhd language, hdlr, stsd video (avcC/hvcC+SPS VUI/av1C/vpcC/colr/pasp/btrt/clli/mdcv/dvcC) and audio (esds, wave, chan, dOps), stts (frame rate, VFR), stsz (bit rate), Apple `mdta` keys, iTunes `ilst`, udta ©xxx/3GPP/loci/vendor atoms (embedded TIFF parsed), XMP uuid/XMP_ | generated (iPhone-style, Android-style, stripped, no-timestamp, HDR10, VFR, mixed streams) + ExifTool sample. Native/ffprobe stream agreement test | ffprobe side data, frame side data. ExifTool `-ee` timed metadata | Timed metadata samples (per-frame GPS, gyro) are not read natively. |
| .avi | AVI → VIDEO | avih, strh/strf (BITMAPINFO/WAVEFORMATEX), strn, strd EXIF, IDIT, ISMP, LIST INFO | generated + 2 ExifTool samples | ffprobe | Camera metadata is rare. |
| .webm | WEBM / MATROSKA → VIDEO | EBML header, Info (duration, DateUTC, apps), Tracks (video incl. Colour, audio), Tags, Chapters, Attachments | generated + ExifTool sample (.mkv) | ffprobe | Unknown-size clusters end the scan. Camera tags are rare. |
| .hevc | HEVC (Annex-B) → VIDEO | VPS/SPS: profile, tier, level, coded and cropped size, chroma, bit depth, VUI SAR/colour/timing, SEI user-data encoder string | generated | ffprobe | No container, so no duration, time, device or GPS. `video.container` is null. |
| .wav | WAV (RF64/BW64) → AUDIO | fmt (incl. extensible), fact, data size and duration, LIST INFO, BWF bext, iXML, _PMX XMP, ds64 | generated (BWF) + ExifTool sample | ffprobe | No video or camera fields. |

## Status per backend

| Backend | Formats | Depth | Fidelity |
|---|---|---|---|
| native | all of the above | all | `BYTE_EXACT` where values sit at file offsets |
| exiftool | all known formats | `DEEP` (default) | `BACKEND_DECODED` (`-b` binaries `BACKEND_RECONSTRUCTED`) |
| ffprobe | video, audio, GIF, WebP, APNG | `DEEP` (default) | `BACKEND_DECODED` |
