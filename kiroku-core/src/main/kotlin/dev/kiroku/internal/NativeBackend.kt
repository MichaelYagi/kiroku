package dev.kiroku.internal

import dev.kiroku.BackendMode
import dev.kiroku.KirokuInfo
import dev.kiroku.MetadataConfig
import dev.kiroku.format.LimitExceededException
import dev.kiroku.format.NativeContext
import dev.kiroku.format.bmp.BmpParser
import dev.kiroku.format.ebml.MatroskaParser
import dev.kiroku.format.gif.GifParser
import dev.kiroku.format.hevc.HevcParser
import dev.kiroku.format.isobmff.IsoBmffParser
import dev.kiroku.format.jpeg.JpegParser
import dev.kiroku.format.png.PngParser
import dev.kiroku.format.riff.RiffParser
import dev.kiroku.format.tiff.TiffReader
import dev.kiroku.io.FileSource
import dev.kiroku.model.BackendStatus
import dev.kiroku.model.DiagnosticCode
import dev.kiroku.model.MediaFormat
import dev.kiroku.model.Severity
import dev.kiroku.spi.BackendAvailability
import dev.kiroku.spi.BackendContext
import dev.kiroku.spi.BackendKind
import dev.kiroku.spi.BackendOutcome
import dev.kiroku.spi.MetadataBackend
import dev.kiroku.spi.MetadataCollector
import java.io.EOFException

/** Kiroku's built-in native parsers. They are always available and run at every depth. */
internal class NativeBackend : MetadataBackend {
    override val id: String = MetadataCollector.NATIVE_BACKEND_ID
    override val kind: BackendKind = BackendKind.NATIVE
    override val metadataFamilies: Set<String> =
        setOf(
            "EXIF", "TIFF", "GPS", "Interop", "MakerNotes", "XMP", "IPTC", "Photoshop", "ICC_Profile", "JFIF", "MPF",
            "PNG", "GIF", "BMP", "RIFF", "WebP", "AVI", "WAV", "BWF", "iXML", "QuickTime", "ISOBMFF", "HEIF", "Matroska", "H265",
        )

    override fun mode(config: MetadataConfig): BackendMode = BackendMode.ALWAYS

    override fun probeAvailability(): BackendAvailability = BackendAvailability(true, KirokuInfo.VERSION)

    override fun supports(format: MediaFormat): Boolean = format != MediaFormat.UNKNOWN

    override fun extract(context: BackendContext): BackendOutcome {
        val ctx = NativeContext(context.collector, context.options, context)
        FileSource.open(context.path).use { src ->
            try {
                when (context.probe.detectedFormat) {
                    MediaFormat.JPEG -> JpegParser.parse(ctx, src)

                    MediaFormat.PNG, MediaFormat.APNG -> PngParser.parse(ctx, src)

                    MediaFormat.GIF -> GifParser.parse(ctx, src)

                    MediaFormat.BMP -> BmpParser.parse(ctx, src)

                    MediaFormat.WEBP, MediaFormat.AVI, MediaFormat.WAV -> RiffParser(ctx, src).parse()

                    MediaFormat.TIFF, MediaFormat.BIGTIFF, MediaFormat.DNG, MediaFormat.NEF, MediaFormat.CR2, MediaFormat.ORF,
                    MediaFormat.ARW, MediaFormat.RW2, MediaFormat.RWL, MediaFormat.SRW, MediaFormat.PEF, MediaFormat.TIFF_RAW,
                    -> TiffReader(ctx, src, "TIFF").parse()

                    MediaFormat.HEIF, MediaFormat.HEIC, MediaFormat.AVIF, MediaFormat.MP4, MediaFormat.MOV, MediaFormat.M4A,
                    MediaFormat.THREE_GP,
                    -> IsoBmffParser(ctx, src, context.probe.detectedFormat).parse()

                    MediaFormat.WEBM, MediaFormat.MATROSKA -> MatroskaParser(ctx, src, context.probe.detectedFormat.id).parse()

                    MediaFormat.HEVC -> HevcParser.parse(ctx, src)

                    MediaFormat.UNKNOWN -> return BackendOutcome(BackendStatus.SKIPPED, KirokuInfo.VERSION, "unknown format")
                }
            } catch (e: EOFException) {
                ctx.warn(
                    DiagnosticCode.TRUNCATED,
                    "Unexpected end of data while parsing ${context.probe.detectedFormat.id}; partial results kept",
                    e,
                )
            } catch (e: LimitExceededException) {
                ctx.warn(DiagnosticCode.LIMIT_EXCEEDED, e.message ?: "limit exceeded", e)
            } catch (e: IndexOutOfBoundsException) {
                ctx.warn(DiagnosticCode.MALFORMED, "Malformed structure in ${context.probe.detectedFormat.id}; partial results kept", e)
            } catch (e: IllegalArgumentException) {
                ctx.warn(DiagnosticCode.MALFORMED, "Malformed structure in ${context.probe.detectedFormat.id}: ${e.message}", e)
            } catch (e: ArithmeticException) {
                ctx.warn(DiagnosticCode.MALFORMED, "Malformed numeric value in ${context.probe.detectedFormat.id}", e)
            }
        }
        if (context.collector.observationCount == 0) {
            context.collector.diagnostic(DiagnosticCode.MALFORMED, Severity.WARNING, "No metadata could be read")
        }
        return BackendOutcome.used(KirokuInfo.VERSION)
    }
}
