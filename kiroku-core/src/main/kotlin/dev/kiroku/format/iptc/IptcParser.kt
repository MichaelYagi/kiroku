package dev.kiroku.format.iptc

import dev.kiroku.format.NativeContext
import dev.kiroku.format.icc.IccParser
import dev.kiroku.format.tiff.TiffReader
import dev.kiroku.format.xmp.XmpParser
import dev.kiroku.io.ByteSource
import dev.kiroku.io.Bytes
import dev.kiroku.model.ByteOrderMark
import dev.kiroku.model.DiagnosticCode
import dev.kiroku.model.DirectoryKind
import dev.kiroku.model.EmbeddedImageInfo
import dev.kiroku.model.EmbeddedImageKind
import dev.kiroku.model.NativeType
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.Severity
import dev.kiroku.model.TypeSystem

/**
 * IPTC IIM (Information Interchange Model) datasets. Each dataset is recorded byte-exactly with
 * `tagId = record * 256 + dataset`. Text uses UTF-8 when record 1:90 declares it (`ESC % G`),
 * and is otherwise decoded as UTF-8 with an ISO-8859-1 fallback.
 */
internal object IptcParser {
    private val record1 =
        mapOf(
            0 to "EnvelopeRecordVersion", 5 to "Destination", 20 to "FileFormat", 22 to "FileVersion",
            30 to "ServiceIdentifier", 40 to "EnvelopeNumber", 50 to "ProductID", 60 to "EnvelopePriority",
            70 to "DateSent", 80 to "TimeSent", 90 to "CodedCharacterSet", 100 to "UniqueObjectName",
            120 to "ARMIdentifier", 122 to "ARMVersion",
        )
    private val record2 =
        mapOf(
            0 to "ApplicationRecordVersion", 3 to "ObjectTypeReference", 4 to "ObjectAttributeReference",
            5 to "ObjectName", 7 to "EditStatus", 8 to "EditorialUpdate", 10 to "Urgency", 12 to "SubjectReference",
            15 to "Category", 20 to "SupplementalCategories", 22 to "FixtureIdentifier", 25 to "Keywords",
            26 to "ContentLocationCode", 27 to "ContentLocationName", 30 to "ReleaseDate", 35 to "ReleaseTime",
            37 to "ExpirationDate", 38 to "ExpirationTime", 40 to "SpecialInstructions", 42 to "ActionAdvised",
            45 to "ReferenceService", 47 to "ReferenceDate", 50 to "ReferenceNumber", 55 to "DateCreated",
            60 to "TimeCreated", 62 to "DigitalCreationDate", 63 to "DigitalCreationTime", 65 to "OriginatingProgram",
            70 to "ProgramVersion", 75 to "ObjectCycle", 80 to "By-line", 85 to "By-lineTitle", 90 to "City",
            92 to "Sub-location", 95 to "Province-State", 100 to "Country-PrimaryLocationCode",
            101 to "Country-PrimaryLocationName", 103 to "OriginalTransmissionReference", 105 to "Headline",
            110 to "Credit", 115 to "Source", 116 to "CopyrightNotice", 118 to "Contact", 120 to "Caption-Abstract",
            121 to "LocalCaption", 122 to "Writer-Editor", 125 to "RasterizedCaption", 130 to "ImageType",
            131 to "ImageOrientation", 135 to "LanguageIdentifier", 150 to "AudioType", 151 to "AudioSamplingRate",
            152 to "AudioSamplingResolution", 153 to "AudioDuration", 154 to "AudioOutcue", 184 to "JobID",
            185 to "MasterDocumentID", 186 to "ShortDocumentID", 187 to "UniqueDocumentID", 188 to "OwnerID",
            200 to "ObjectPreviewFileFormat", 201 to "ObjectPreviewFileVersion", 202 to "ObjectPreviewData",
            221 to "Prefs", 225 to "ClassifyState", 228 to "SimilarityIndex", 230 to "DocumentNotes",
            231 to "DocumentHistory", 232 to "ExifCameraInfo", 255 to "CatalogSets",
        )

    /** Datasets stored as binary integers, not text. */
    private val binaryInts = setOf(0x0100, 0x0114, 0x0116, 0x0178, 0x017A, 0x0200, 0x02C8, 0x02C9)

    fun parse(ctx: NativeContext, source: ByteSource, offset: Long, length: Long, path: String) {
        if (length <= 0 || !source.contains(offset, length)) return
        ctx.collector.directory(path.split('/'), DirectoryKind.IPTC_RECORD, source.absolute(offset), length)
        var pos = offset
        val end = offset + length
        var utf8 = false
        var count = 0
        while (pos + 5 <= end) {
            ctx.countBox()
            if (source.u8(pos) != 0x1C) {
                // Trailing padding (NULs) is common. Anything else is malformed.
                if (source.u8(pos) !=
                    0
                ) {
                    ctx.warnOnce("iptc:$path", DiagnosticCode.MALFORMED, "Unexpected byte in IPTC data; stopped parsing")
                }
                break
            }
            val record = source.u8(pos + 1)
            val dataset = source.u8(pos + 2)
            var len = source.u16(pos + 3, true).toLong()
            var dataStart = pos + 5
            if (len and 0x8000L != 0L) {
                val n = (len and 0x7FFF).toInt()
                if (n !in 1..4 || dataStart + n > end) break
                len = 0
                for (i in 0 until n) len = (len shl 8) or source.u8(dataStart + i).toLong()
                dataStart += n
            }
            if (dataStart + len > end) {
                ctx.warn(DiagnosticCode.TRUNCATED, "IPTC dataset $record:$dataset extends beyond its block")
                break
            }
            val id = record * 256 + dataset
            val name = (
                if (record == 1) {
                    record1[dataset]
                } else if (record == 2) {
                    record2[dataset]
                } else {
                    null
                }
                )
            val value: NormalizedValue =
                when {
                    id in binaryInts && len in 1..4 -> {
                        val b = source.read(dataStart, len.toInt())
                        var v = 0L
                        for (x in b) v = (v shl 8) or (x.toLong() and 0xFF)
                        NormalizedValue.Integer(v)
                    }

                    len > ctx.limits.maxStringLength || id == 0x02CA -> NormalizedValue.Binary(len)

                    else -> {
                        val b = source.read(dataStart, len.toInt())
                        if (id == 0x015A) utf8 = Bytes.startsWith(b, byteArrayOf(0x1B, 0x25, 0x47))
                        NormalizedValue.Text(ctx.clip(if (utf8) String(b, Charsets.UTF_8) else Bytes.decodeText(b)))
                    }
                }
            ctx.observe(
                groupPath = listOf("IPTC", "IPTC"),
                tagName = name ?: "IPTC_$record:$dataset",
                value = value,
                tagId = id.toLong(),
                original = ctx.original(source, dataStart, len),
                nativeType = NativeType(TypeSystem.IPTC, if (value is NormalizedValue.Integer) "int" else "string", null, len),
                known = name != null,
                sourcePath = "$path/$record:$dataset",
            )
            count++
            pos = dataStart + len
        }
    }
}

/**
 * Photoshop Image Resource Blocks (`8BIM`), as found in JPEG APP13, TIFF tag 0x8649 and PSD.
 * IPTC (0x0404), EXIF (0x0422/0x0423), XMP (0x0424) and ICC (0x040F) resources are parsed further.
 */
internal object PhotoshopParser {
    private val names =
        mapOf(
            0x03E8 to "Photoshop2Info", 0x03E9 to "MacintoshPrintInfo", 0x03ED to "ResolutionInfo",
            0x03EE to "AlphaChannelsNames", 0x03F3 to "PrintFlags", 0x03F5 to "ColorHalftoneInfo",
            0x03F8 to "ColorTransferFuncs", 0x0400 to "LayerStateInfo", 0x0402 to "LayersGroupInfo",
            0x0404 to "IPTCData", 0x0405 to "RawImageMode", 0x0406 to "JPEG_Quality", 0x0408 to "GridGuidesInfo",
            0x0409 to "PhotoshopBGRThumbnail", 0x040A to "CopyrightFlag", 0x040B to "URL", 0x040C to "PhotoshopThumbnail",
            0x040D to "GlobalAngle", 0x040F to "ICC_Profile", 0x0410 to "Watermark", 0x0411 to "ICC_Untagged",
            0x0412 to "EffectsVisible", 0x0414 to "IDsBaseValue", 0x0415 to "UnicodeAlphaNames",
            0x0416 to "IndexedColorTableCount", 0x0417 to "TransparentIndex", 0x0419 to "GlobalAltitude",
            0x041A to "SliceInfo", 0x041B to "WorkflowURL", 0x041C to "JumpToXPEP", 0x041D to "AlphaIdentifiers",
            0x041E to "URL_List", 0x0421 to "VersionInfo", 0x0422 to "EXIFInfo", 0x0423 to "ExifInfo2",
            0x0424 to "XMP", 0x0425 to "IPTCDigest", 0x0426 to "PrintScaleInfo", 0x0428 to "PixelInfo",
            0x0429 to "LayerComps", 0x042A to "AlternateDuotoneColors", 0x042B to "AlternateSpotColors",
            0x042D to "LayerSelectionIDs", 0x042E to "HDRToningInfo", 0x042F to "PrintInfo",
            0x0430 to "LayerGroupsEnabledID", 0x0431 to "ColorSamplersResource", 0x0432 to "MeasurementScale",
            0x0433 to "TimelineInfo", 0x0434 to "SheetDisclosure", 0x0435 to "DisplayInfo", 0x0436 to "OnionSkins",
            0x0438 to "CountInfo", 0x043A to "PrintInfo2", 0x043B to "PrintStyle", 0x043C to "MacintoshNSPrintInfo",
            0x043D to "WindowsDEVMODE", 0x043E to "AutoSaveFilePath", 0x043F to "AutoSaveFormat",
            0x0440 to "PathSelectionState", 0x0BB7 to "ClippingPathName", 0x0BB8 to "OriginPathInfo",
            0x1B58 to "ImageReadyVariables", 0x1B59 to "ImageReadyDataSets", 0x1F40 to "LightroomWorkflow",
            0x2710 to "PrintFlagsInfo",
        )

    fun parse(ctx: NativeContext, source: ByteSource, offset: Long, length: Long, path: String) {
        if (length <= 0 || !source.contains(offset, length)) return
        var pos = offset
        val end = offset + length
        var index = 0
        while (pos + 12 <= end) {
            ctx.countBox()
            val sig = source.ascii(pos, 4)
            if (sig != "8BIM" && sig != "PHUT" && sig != "AgHg" && sig != "DCSR" && sig != "MeSa") break
            val id = source.u16(pos + 4, true)
            val nameLen = source.u8(pos + 6)
            var p = pos + 7 + nameLen
            if ((1 + nameLen) % 2 == 1) p++
            if (p + 4 > end) break
            val size = source.u32(p, true)
            val dataStart = p + 4
            if (dataStart + size > end) {
                ctx.warn(DiagnosticCode.TRUNCATED, "Photoshop resource 0x%04X extends beyond its block".format(id))
                break
            }
            val subPath = "$path/8BIM[$index]"
            val name = names[id] ?: if (id in 0x07D0..0x0BB6) "PathInfo" else null
            // A resource that holds one value is reported once, decoded, with the whole resource as its original bytes.
            val singleValue = id in SINGLE_VALUE && size >= (if (id == 0x040A || id == 0x040B) 1 else 4)
            if (!singleValue) {
                ctx.observe(
                    groupPath = listOf("Photoshop", "Photoshop"),
                    tagName = name ?: "0x%04X".format(id),
                    value = if (id == 0x0425 &&
                        size == 16L
                    ) {
                        NormalizedValue.Text(source.read(dataStart, 16).joinToString("") { "%02x".format(it) })
                    } else {
                        NormalizedValue.Binary(size)
                    },
                    tagId = id.toLong(),
                    original = ctx.original(source, dataStart, size),
                    nativeType = NativeType(TypeSystem.JPEG, "8BIM", null, size),
                    known = name != null,
                    sourcePath = subPath,
                )
            }
            when (id) {
                0x0404 -> IptcParser.parse(ctx, source, dataStart, size, "$subPath/IPTC")

                0x0424 -> XmpParser.parse(ctx, source, dataStart, size, "$subPath/XMP")

                0x040F -> IccParser.parse(ctx, source, dataStart, size, "$subPath/ICC_Profile")

                0x0422, 0x0423 ->
                    if (size > 8) {
                        val slice = source.slice(dataStart, size)
                        val skip = if (Bytes.startsWith(slice.readAtMost(0, 6), Bytes.ascii("Exif\u0000\u0000"))) 6L else 0L
                        TiffReader(ctx, slice.slice(skip, size - skip), "$subPath/TIFF").parse()
                    }

                else -> try {
                    decodeResource(ctx, source, id, dataStart, size, subPath)
                } catch (e: IndexOutOfBoundsException) {
                    ctx.warn(DiagnosticCode.MALFORMED, "Photoshop resource 0x%04X is shorter than its layout".format(id), e, Severity.INFO)
                }
            }
            pos = dataStart + size + (size and 1)
            index++
        }
    }

    /**
     * Decodes the common fixed-layout resources into named values (names as ExifTool uses them). The resource itself
     * stays in the output as a byte-exact blob; each decoded value points at its own bytes.
     */
    private fun decodeResource(ctx: NativeContext, s: ByteSource, id: Int, d: Long, size: Long, path: String) {
        val g = listOf("Photoshop", "Photoshop")
        fun o(name: String, v: NormalizedValue, off: Long, n: Long, type: String, display: String? = null) = ctx.observe(
            g,
            name,
            v,
            tagId = id.toLong(),
            original = ctx.original(s, d + off, n, ByteOrderMark.BIG_ENDIAN),
            nativeType = NativeType(TypeSystem.PSD, type, null, 1),
            displayValue = display,
            sourcePath = path,
        )
        fun int(name: String, off: Long, n: Int, display: (Long) -> String? = { null }) {
            if (off + n > size) return
            val v = when (n) {
                1 -> s.u8(d + off).toLong()
                2 -> s.u16(d + off, true).toLong()
                else -> s.u32(d + off, true)
            }
            o(name, NormalizedValue.Integer(v), off, n.toLong(), "int${n * 8}u", display(v))
        }
        fun unicode(off: Long): Pair<String, Long>? {
            if (off + 4 > size) return null
            val chars = s.u32(d + off, true)
            if (chars > 4096 || off + 4 + chars * 2 > size) return null
            return String(s.read(d + off + 4, (chars * 2).toInt()), Charsets.UTF_16BE).trimEnd('\u0000') to off + 4 + chars * 2
        }
        when (id) {
            0x03ED -> if (size >= 16) {
                o("XResolution", NormalizedValue.Decimal(s.u32(d, true) / 65536.0), 0, 4, "fixed32u")
                int("DisplayedUnitsX", 4, 2) { unitName(it) }
                o("YResolution", NormalizedValue.Decimal(s.u32(d + 8, true) / 65536.0), 8, 4, "fixed32u")
                int("DisplayedUnitsY", 12, 2) { unitName(it) }
            }

            0x0406 -> if (size >= 6) {
                // The stored value runs from -4; Photoshop's 0-12 quality scale is the stored value plus 4.
                val q = s.u16(d, true).toShort().toLong()
                o("PhotoshopQuality", NormalizedValue.Integer(q), 0, 2, "int16s", (q + 4).toString())
                int("PhotoshopFormat", 2, 2) {
                    if (it == 0L) {
                        "Standard"
                    } else if (it == 1L) {
                        "Optimized"
                    } else if (it == 0x101L) {
                        "Progressive"
                    } else {
                        null
                    }
                }
                int("ProgressiveScans", 4, 2)
            }

            0x040A -> int("CopyrightFlag", 0, 1) { if (it == 0L) "False" else "True" }

            0x040B -> o("URL", NormalizedValue.Text(String(s.read(d, minOf(size, 65_536).toInt()), Charsets.UTF_8).trimEnd('\u0000')), 0, size, "string")

            0x040C -> thumbnail(ctx, s, d, size, g, path)

            0x040D -> int("GlobalAngle", 0, 4)

            0x0414 -> int("IDsBaseValue", 0, 4)

            0x0419 -> int("GlobalAltitude", 0, 4)

            0x041A -> if (size >= 28 && s.u32(d, true) == 6L) {
                // Version 6: bounding rectangle (4 x int32), group name, slice count.
                unicode(20)?.let { (name, after) ->
                    o("SlicesGroupName", NormalizedValue.Text(name), 24, after - 24, "unicode")
                    int("NumSlices", after, 4)
                }
            }

            0x041E -> int("URL_ListCount", 0, 4)

            0x0421 -> if (size >= 13) {
                int("HasRealMergedData", 4, 1) { if (it == 0L) "No" else "Yes" }
                unicode(5)?.let { (writer, after) ->
                    o("WriterName", NormalizedValue.Text(writer), 9, after - 9, "unicode")
                    unicode(after)?.let { (reader, _) -> o("ReaderName", NormalizedValue.Text(reader), after + 4, reader.length * 2L, "unicode") }
                }
            }

            0x0426 -> if (size >= 14) {
                int("PrintStyle", 0, 2) { arrayOf("Centered", "Size to Fit", "User Defined").getOrNull(it.toInt()) }
                val x = java.lang.Float.intBitsToFloat(s.u32(d + 2, true).toInt())
                val y = java.lang.Float.intBitsToFloat(s.u32(d + 6, true).toInt())
                o("PrintPosition", NormalizedValue.list(listOf(NormalizedValue.Decimal(x.toDouble()), NormalizedValue.Decimal(y.toDouble()))), 2, 8, "float[2]")
                o("PrintScale", NormalizedValue.Decimal(java.lang.Float.intBitsToFloat(s.u32(d + 10, true).toInt()).toDouble()), 10, 4, "float")
            }

            0x0428 -> if (size >= 12) {
                o("PixelAspectRatio", NormalizedValue.Decimal(java.lang.Double.longBitsToDouble(s.u64(d + 4, true))), 4, 8, "double")
            }

            0x0BB7 -> if (size >= 1 && 1 + s.u8(d) <= size) {
                val n = s.u8(d)
                o("ClippingPathName", NormalizedValue.Text(String(s.read(d + 1, n), Charsets.ISO_8859_1)), 1, n.toLong(), "pstring")
            }
        }
    }

    private val SINGLE_VALUE = setOf(0x040A, 0x040B, 0x040D, 0x0414, 0x0419)

    private fun unitName(v: Long): String? = when (v) {
        1L -> "inches"
        2L -> "cm"
        else -> null
    }

    /** `PhotoshopThumbnail`: a 28-byte header (format 1 = JPEG, width, height ...), then the JPEG data. */
    private fun thumbnail(ctx: NativeContext, s: ByteSource, d: Long, size: Long, g: List<String>, path: String) {
        if (size < 32 || s.u32(d, true) != 1L || s.u16(d + 28, true) != 0xFFD8) return
        ctx.collector.embeddedImage(
            EmbeddedImageInfo(
                0, "", EmbeddedImageKind.THUMBNAIL, g, width = s.u32(d + 4, true).toInt(), height = s.u32(d + 8, true).toInt(),
                compression = "JPEG", mimeType = "image/jpeg", fileOffset = s.absolute(d + 28), length = size - 28,
            ),
        )
    }
}
