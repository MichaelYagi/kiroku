package dev.kiroku.model

public enum class MediaKind { IMAGE, VIDEO, AUDIO, RAW_IMAGE, UNKNOWN }

/**
 * A format identified from file signatures and structure. The extension is only a hint.
 *
 * [id] is the stable identifier used in `gallery.format`.
 */
public enum class MediaFormat(public val id: String, public val mimeType: String?, public val defaultKind: MediaKind, public val extensions: Set<String>) {
    JPEG("JPEG", "image/jpeg", MediaKind.IMAGE, setOf("jpg", "jpeg", "jpe", "jfif")),
    PNG("PNG", "image/png", MediaKind.IMAGE, setOf("png")),
    APNG("APNG", "image/apng", MediaKind.IMAGE, setOf("apng", "png")),
    GIF("GIF", "image/gif", MediaKind.IMAGE, setOf("gif")),
    BMP("BMP", "image/bmp", MediaKind.IMAGE, setOf("bmp", "dib")),
    WEBP("WEBP", "image/webp", MediaKind.IMAGE, setOf("webp")),
    HEIF("HEIF", "image/heif", MediaKind.IMAGE, setOf("heif", "heic", "hif")),
    HEIC("HEIC", "image/heic", MediaKind.IMAGE, setOf("heic", "heif", "hif")),
    AVIF("AVIF", "image/avif", MediaKind.IMAGE, setOf("avif")),
    TIFF("TIFF", "image/tiff", MediaKind.IMAGE, setOf("tif", "tiff")),
    BIGTIFF("BIGTIFF", "image/tiff", MediaKind.IMAGE, setOf("tif", "tiff", "btf")),
    DNG("DNG", "image/x-adobe-dng", MediaKind.RAW_IMAGE, setOf("dng", "tif", "tiff")),
    NEF("NEF", "image/x-nikon-nef", MediaKind.RAW_IMAGE, setOf("nef", "nrw")),
    CR2("CR2", "image/x-canon-cr2", MediaKind.RAW_IMAGE, setOf("cr2")),
    ORF("ORF", "image/x-olympus-orf", MediaKind.RAW_IMAGE, setOf("orf", "ori")),
    ARW("ARW", "image/x-sony-arw", MediaKind.RAW_IMAGE, setOf("arw", "sr2", "srf")),
    RW2("RW2", "image/x-panasonic-rw2", MediaKind.RAW_IMAGE, setOf("rw2", "raw")),
    RWL("RWL", "image/x-leica-rwl", MediaKind.RAW_IMAGE, setOf("rwl")),
    SRW("SRW", "image/x-samsung-srw", MediaKind.RAW_IMAGE, setOf("srw")),
    PEF("PEF", "image/x-pentax-pef", MediaKind.RAW_IMAGE, setOf("pef")),
    CR3("CR3", "image/x-canon-cr3", MediaKind.RAW_IMAGE, setOf("cr3")),
    RAF("RAF", "image/x-fujifilm-raf", MediaKind.RAW_IMAGE, setOf("raf")),

    /** A TIFF-structured RAW file whose vendor subtype could not be identified. */
    TIFF_RAW("TIFF_RAW", "image/tiff", MediaKind.RAW_IMAGE, setOf("tif", "tiff")),
    MP4("MP4", "video/mp4", MediaKind.VIDEO, setOf("mp4", "m4v")),
    MOV("MOV", "video/quicktime", MediaKind.VIDEO, setOf("mov", "qt")),
    M4A("M4A", "audio/mp4", MediaKind.AUDIO, setOf("m4a")),
    THREE_GP("3GP", "video/3gpp", MediaKind.VIDEO, setOf("3gp", "3g2")),
    AVI("AVI", "video/x-msvideo", MediaKind.VIDEO, setOf("avi")),
    WEBM("WEBM", "video/webm", MediaKind.VIDEO, setOf("webm")),
    MATROSKA("MATROSKA", "video/x-matroska", MediaKind.VIDEO, setOf("mkv", "mka")),

    /** A raw HEVC (H.265) Annex-B elementary stream with no container. */
    HEVC("HEVC", "video/H265", MediaKind.VIDEO, setOf("hevc", "h265", "265")),
    WAV("WAV", "audio/wav", MediaKind.AUDIO, setOf("wav", "wave")),
    UNKNOWN("UNKNOWN", null, MediaKind.UNKNOWN, emptySet()),
    ;

    public companion object {
        /** The extensions from the product requirements that must be recognised and routed. */
        @JvmField
        public val REQUIRED_EXTENSIONS: Set<String> =
            setOf(
                "jpg", "jpeg", "png", "bmp", "gif", "webm", "webp", "heif", "heic", "tif", "tiff",
                "mp4", "wav", "avi", "mov", "hevc", "nef", "cr2", "orf", "arw", "rw2", "rwl", "srw", "dng",
            )

        /** The format an extension usually suggests. This is a hint only, never ground truth. */
        @JvmStatic
        public fun fromExtension(extension: String?): MediaFormat? {
            val ext = extension?.lowercase() ?: return null
            return when (ext) {
                "jpg", "jpeg", "jpe", "jfif" -> JPEG
                "png" -> PNG
                "gif" -> GIF
                "bmp", "dib" -> BMP
                "webp" -> WEBP
                "heic" -> HEIC
                "heif", "hif" -> HEIF
                "avif" -> AVIF
                "tif", "tiff" -> TIFF
                "dng" -> DNG
                "nef", "nrw" -> NEF
                "cr2" -> CR2
                "orf", "ori" -> ORF
                "arw", "sr2", "srf" -> ARW
                "rw2" -> RW2
                "rwl" -> RWL
                "srw" -> SRW
                "pef" -> PEF
                "mp4", "m4v" -> MP4
                "mov", "qt" -> MOV
                "m4a" -> M4A
                "3gp", "3g2" -> THREE_GP
                "avi" -> AVI
                "webm" -> WEBM
                "mkv", "mka" -> MATROSKA
                "hevc", "h265", "265" -> HEVC
                "wav", "wave" -> WAV
                else -> null
            }
        }
    }
}
