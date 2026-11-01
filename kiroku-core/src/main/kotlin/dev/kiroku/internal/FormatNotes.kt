package dev.kiroku.internal

import dev.kiroku.model.MediaFormat

/** What the native parser decodes per format, and known limitations, for the capabilities report. */
internal object FormatNotes {
    private val tiffFamilies = setOf("TIFF", "EXIF", "GPS", "Interop", "MakerNotes", "XMP", "IPTC", "ICC_Profile", "Photoshop")

    fun nativeFamilies(f: MediaFormat): Set<String> = when (f) {
        MediaFormat.JPEG -> setOf("JPEG", "JFIF", "EXIF", "GPS", "MakerNotes", "XMP", "ExtendedXMP", "IPTC", "Photoshop", "ICC_Profile", "MPF", "Comment")

        MediaFormat.PNG, MediaFormat.APNG -> setOf("PNG", "EXIF", "XMP", "ICC_Profile", "TextChunks", "Animation")

        MediaFormat.GIF -> setOf("GIF", "Animation", "Comment", "XMP")

        MediaFormat.BMP -> setOf("BMP", "ICC_Profile")

        MediaFormat.WEBP -> setOf("RIFF", "WebP", "EXIF", "XMP", "ICC_Profile", "Animation")

        MediaFormat.HEIF, MediaFormat.HEIC, MediaFormat.AVIF -> setOf(
            "ISOBMFF",
            "HEIF",
            "ItemProperties",
            "EXIF",
            "XMP",
            "ICC_Profile",
            "MakerNotes",
        )

        MediaFormat.TIFF, MediaFormat.BIGTIFF -> tiffFamilies

        MediaFormat.DNG, MediaFormat.NEF, MediaFormat.CR2, MediaFormat.ORF, MediaFormat.ARW, MediaFormat.RW2, MediaFormat.RWL,
        MediaFormat.SRW, MediaFormat.PEF, MediaFormat.TIFF_RAW,
        -> tiffFamilies + "RAW"

        MediaFormat.MP4, MediaFormat.MOV, MediaFormat.M4A, MediaFormat.THREE_GP -> setOf(
            "QuickTime",
            "ISOBMFF",
            "Keys",
            "ItemList",
            "UserData",
            "Streams",
            "XMP",
        )

        MediaFormat.AVI -> setOf("RIFF", "AVI", "INFO", "Streams", "EXIF")

        MediaFormat.WAV -> setOf("RIFF", "WAV", "INFO", "BWF", "iXML", "XMP")

        MediaFormat.WEBM, MediaFormat.MATROSKA -> setOf("Matroska", "Tracks", "Tags", "Chapters")

        MediaFormat.HEVC -> setOf("H265", "SPS", "VUI", "SEI")

        MediaFormat.UNKNOWN -> emptySet()
    }

    fun limitations(f: MediaFormat): List<String> = when (f) {
        MediaFormat.BMP -> listOf("BMP has no camera, capture-time or GPS metadata fields.")

        MediaFormat.GIF -> listOf("GIF has no camera, capture-time or GPS metadata; frame count needs a scan of all blocks.")

        MediaFormat.HEIF, MediaFormat.HEIC, MediaFormat.AVIF ->
            listOf("Items using construction method 2 (item offset) are not read.", "Grid tiles are counted, not listed individually.")

        MediaFormat.NEF, MediaFormat.ARW, MediaFormat.SRW, MediaFormat.PEF, MediaFormat.CR2,
        MediaFormat.ORF, MediaFormat.RW2, MediaFormat.RWL, MediaFormat.DNG, MediaFormat.TIFF_RAW,
        ->
            listOf(
                "MakerNote IFDs are decoded byte-exactly; tag names cover common tags only, unknown vendor tags keep numeric IDs.",
                "Encrypted MakerNote structures (e.g. Nikon LensData/ShotInfo, Sony 0x94xx) are preserved as raw bytes, not decrypted. Use ExifTool deep mode for decoded values.",
            )

        MediaFormat.MP4, MediaFormat.MOV, MediaFormat.THREE_GP ->
            listOf(
                "Timed metadata samples (per-frame GPS, gyro) are not read natively; ExifTool deep mode (-ee) extracts them.",
                "Edited/exported videos often lack device tags; camera is then null.",
            )

        MediaFormat.AVI -> listOf("AVI rarely carries camera metadata; vendor 'strd' EXIF blocks are parsed when present.")

        MediaFormat.WEBM, MediaFormat.MATROSKA -> listOf(
            "Clusters of unknown size end the scan; Tags after them are not read.",
            "WebM rarely carries camera metadata.",
        )

        MediaFormat.HEVC -> listOf(
            "Elementary stream: no container, capture time, device or GPS metadata. Duration is not computed natively.",
        )

        MediaFormat.WAV -> listOf("Audio only; no video or camera fields.")

        MediaFormat.UNKNOWN -> listOf("Unrecognised signature.")

        else -> emptyList()
    }
}
