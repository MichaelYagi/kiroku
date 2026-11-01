package dev.kiroku.format.tiff

import dev.kiroku.format.NativeContext
import dev.kiroku.io.ByteSource
import dev.kiroku.model.NativeType
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.TypeSystem

/**
 * Vendor MakerNote tags that pack many settings into one array of 16-bit values (Canon CameraSettings, ShotInfo, ...).
 * Each listed element becomes its own observation in group `MakerNotes:<array name>`, with the element index as its tag
 * ID, its native type and its original bytes. The stored value is kept; unit conversions belong to the gallery layer.
 * Names follow ExifTool; unlisted elements are not reported (the whole array stays as its own observation).
 */
internal object MakerNoteArrays {
    enum class Format(val size: Int) { INT16S(2), INT16U(2), INT32U(4) }

    class Field(val index: Int, val name: String, val format: Format)

    /** [unit] is the byte size of one index step; element `i` starts at `i * unit`. */
    class Table(val fields: List<Field>, val unit: Int = 2)

    private fun table(default: Format, vararg entries: Pair<Int, String>, overrides: Map<Int, Format> = emptyMap()) =
        Table(entries.map { (i, n) -> Field(i, n, overrides[i] ?: default) })

    private val canonCameraSettings =
        table(
            Format.INT16S,
            1 to "MacroMode", 2 to "SelfTimer", 3 to "Quality", 4 to "CanonFlashMode", 5 to "ContinuousDrive", 7 to "FocusMode",
            9 to "RecordMode", 10 to "CanonImageSize", 11 to "EasyMode", 12 to "DigitalZoom", 13 to "Contrast", 14 to "Saturation",
            15 to "Sharpness", 16 to "CameraISO", 17 to "MeteringMode", 18 to "FocusRange", 19 to "AFPoint", 20 to "CanonExposureMode",
            22 to "LensType", 23 to "MaxFocalLength", 24 to "MinFocalLength", 25 to "FocalUnits", 26 to "MaxAperture", 27 to "MinAperture",
            28 to "FlashModel", 29 to "FlashBits", 32 to "FocusContinuous", 33 to "AESetting", 34 to "ImageStabilization",
            35 to "DisplayAperture", 36 to "ZoomSourceWidth", 37 to "ZoomTargetWidth", 39 to "SpotMeteringMode", 40 to "PhotoEffect",
            41 to "ManualFlashOutput", 42 to "ColorTone", 46 to "SRAWQuality", 50 to "FocusBracketing", 51 to "Clarity", 52 to "HDR-PQ",
            overrides = mapOf(22 to Format.INT16U, 23 to Format.INT16U, 24 to Format.INT16U, 29 to Format.INT16U),
        )

    private val canonFocalLength =
        // Elements 2 and 3 are the sensor size only on some models (ExifTool decodes them per model), so they are not named.
        table(Format.INT16U, 0 to "FocalType", 1 to "FocalLength")

    private val canonShotInfo =
        table(
            Format.INT16S,
            1 to "AutoISO", 2 to "BaseISO", 3 to "MeasuredEV", 4 to "TargetAperture", 5 to "TargetExposureTime",
            6 to "ExposureCompensation", 7 to "WhiteBalance", 8 to "SlowShutter", 9 to "SequenceNumber", 10 to "OpticalZoomCode",
            12 to "CameraTemperature", 13 to "FlashGuideNumber", 14 to "AFPointsInFocus", 15 to "FlashExposureComp",
            16 to "AutoExposureBracketing", 17 to "AEBBracketValue", 18 to "ControlMode", 19 to "FocusDistanceUpper",
            20 to "FocusDistanceLower", 21 to "FNumber", 22 to "ExposureTime", 23 to "MeasuredEV2", 24 to "BulbDuration",
            26 to "CameraType", 27 to "AutoRotate", 28 to "NDFilter", 29 to "SelfTimer2", 33 to "FlashOutput",
            overrides = mapOf(19 to Format.INT16U, 20 to Format.INT16U),
        )

    private val canonPanorama = table(Format.INT16S, 2 to "PanoramaFrameNumber", 5 to "PanoramaDirection")

    private val canonFileInfo =
        table(
            Format.INT16S,
            // Elements 1 (file number or shutter count) and 16 (macro magnification or exit pupil) depend on the model.
            3 to "BracketMode", 4 to "BracketValue", 5 to "BracketShotNumber", 6 to "RawJpgQuality",
            7 to "RawJpgSize", 8 to "LongExposureNoiseReduction2", 9 to "WBBracketMode", 12 to "WBBracketValueAB",
            13 to "WBBracketValueGM", 14 to "FilterEffect", 15 to "ToningEffect", 19 to "LiveViewShooting",
            20 to "FocusDistanceUpper", 21 to "FocusDistanceLower", 23 to "ShutterMode", 25 to "FlashExposureLock", 32 to "AntiFlicker",
            61 to "RFLensType",
            overrides = mapOf(20 to Format.INT16U, 21 to Format.INT16U, 61 to Format.INT16U),
        )

    // The fixed-size head of the AF info records; the per-point arrays that follow depend on NumAFPoints.
    private val canonAFInfo =
        table(
            Format.INT16U,
            0 to "NumAFPoints",
            1 to "ValidAFPoints",
            2 to "CanonImageWidth",
            3 to "CanonImageHeight",
            4 to "AFImageWidth",
            5 to "AFImageHeight",
        )

    private val canonAFInfo2 =
        table(
            Format.INT16U,
            0 to "AFInfoSize", 1 to "AFAreaMode", 2 to "NumAFPoints", 3 to "ValidAFPoints", 4 to "CanonImageWidth",
            5 to "CanonImageHeight", 6 to "AFImageWidth", 7 to "AFImageHeight",
        )

    private val tables: Map<Pair<String, Int>, Pair<String, Table>> =
        mapOf(
            ("Canon" to 0x0001) to ("CanonCameraSettings" to canonCameraSettings),
            ("Canon" to 0x0002) to ("CanonFocalLength" to canonFocalLength),
            ("Canon" to 0x0004) to ("CanonShotInfo" to canonShotInfo),
            ("Canon" to 0x0005) to ("CanonPanorama" to canonPanorama),
            ("Canon" to 0x0012) to ("CanonAFInfo" to canonAFInfo),
            ("Canon" to 0x0026) to ("CanonAFInfo2" to canonAFInfo2),
            ("Canon" to 0x0093) to ("CanonFileInfo" to canonFileInfo),
        )

    // ---- Fixed binary records (Kodak) ---------------------------------------------------------------------------

    enum class Kind { U8, S8, U16, S16, U32, TEXT }

    class Rec(val offset: Int, val name: String, val kind: Kind, val count: Int = 1)

    // Kodak "1a" MakerNote (DC/DX/LS series): "KDK INFO", then this record. Offsets are relative to the end of the header.
    private val kodak1a =
        listOf(
            Rec(0x00, "KodakModel", Kind.TEXT, 8), Rec(0x09, "Quality", Kind.U8), Rec(0x0A, "BurstMode", Kind.U8),
            Rec(0x0C, "KodakImageWidth", Kind.U16), Rec(0x0E, "KodakImageHeight", Kind.U16), Rec(0x10, "YearCreated", Kind.U16),
            Rec(0x12, "MonthDayCreated", Kind.U8, 2), Rec(0x14, "TimeCreated", Kind.U8, 4), Rec(0x18, "BurstMode2", Kind.U16),
            Rec(0x1B, "ShutterMode", Kind.U8), Rec(0x1C, "MeteringMode", Kind.U8), Rec(0x1D, "SequenceNumber", Kind.U8),
            Rec(0x1E, "FNumber", Kind.U16), Rec(0x20, "ExposureTime", Kind.U32), Rec(0x24, "ExposureCompensation", Kind.S16),
            Rec(0x26, "VariousModes", Kind.U16), Rec(0x28, "Distance1", Kind.U32), Rec(0x2C, "Distance2", Kind.U32),
            Rec(0x30, "Distance3", Kind.U32), Rec(0x34, "Distance4", Kind.U32), Rec(0x38, "FocusMode", Kind.U8),
            Rec(0x3A, "VariousModes2", Kind.U16), Rec(0x3C, "PanoramaMode", Kind.U16), Rec(0x3E, "SubjectDistance", Kind.U16),
            Rec(0x40, "WhiteBalance", Kind.U8), Rec(0x5C, "FlashMode", Kind.U8), Rec(0x5D, "FlashFired", Kind.U8),
            Rec(0x5E, "ISOSetting", Kind.U16), Rec(0x60, "ISO", Kind.U16), Rec(0x62, "TotalZoom", Kind.U16),
            Rec(0x64, "DateTimeStamp", Kind.U16), Rec(0x66, "ColorMode", Kind.U16), Rec(0x68, "DigitalZoom", Kind.U16),
            Rec(0x6B, "Sharpness", Kind.S8),
        )

    /** Decodes a Kodak "KDK INFO" MakerNote at [start] (the header) of [length] bytes. */
    fun kodak1a(ctx: NativeContext, s: ByteSource, start: Long, length: Long, bigEndian: Boolean, path: String) =
        records(ctx, s, start + 8, length - 8, bigEndian, listOf("MakerNotes", "Kodak"), kodak1a, "$path/Kodak")

    private fun records(ctx: NativeContext, s: ByteSource, base: Long, length: Long, be: Boolean, group: List<String>, recs: List<Rec>, path: String) {
        for (r in recs) {
            val unit = when (r.kind) {
                Kind.U8, Kind.S8, Kind.TEXT -> 1
                Kind.U16, Kind.S16 -> 2
                Kind.U32 -> 4
            }
            val len = unit * r.count
            if (r.offset + len > length) continue
            val at = base + r.offset
            val value =
                if (r.kind == Kind.TEXT) {
                    NormalizedValue.Text(String(s.read(at, len), Charsets.ISO_8859_1).substringBefore('\u0000').trimEnd())
                } else {
                    NormalizedValue.list(
                        (0 until r.count).map { i ->
                            val o = at + i * unit
                            NormalizedValue.Integer(
                                when (r.kind) {
                                    Kind.U8 -> s.u8(o).toLong()
                                    Kind.S8 -> s.u8(o).toByte().toLong()
                                    Kind.U16 -> s.u16(o, be).toLong()
                                    Kind.S16 -> s.u16(o, be).toShort().toLong()
                                    else -> s.u32(o, be)
                                },
                            )
                        },
                    )
                }
            ctx.observe(
                group,
                r.name,
                value,
                tagId = r.offset.toLong(),
                original = ctx.original(s, at, len.toLong(), TiffReader.orderOf(be)),
                nativeType = NativeType(TypeSystem.TIFF, r.kind.name.lowercase(), null, r.count.toLong()),
                sourcePath = path,
            )
        }
    }

    // ---- Apple RunTime --------------------------------------------------------------------------------------------

    private val RUN_TIME_NAMES = mapOf("flags" to "RunTimeFlags", "value" to "RunTimeValue", "epoch" to "RunTimeEpoch", "timescale" to "RunTimeScale")

    /**
     * Apple 0x0003 is a binary property list (`bplist00`) holding a CMTime: flags, value, timescale and epoch (time
     * since the device powered up is value / timescale). Only a flat dictionary of ASCII keys and integers is read.
     */
    private fun appleRunTime(ctx: NativeContext, s: ByteSource, entry: IfdEntry, path: String) {
        val base = entry.dataOffset
        val n = entry.dataSize
        if (n < 40 || n > 4096 || s.ascii(base, 8) != "bplist00") return
        val t = base + n - 32
        val offsetSize = s.u8(t + 6)
        val refSize = s.u8(t + 7)
        val objects = s.u64(t + 8, true)
        val top = s.u64(t + 16, true)
        val table = s.u64(t + 24, true)
        if (offsetSize !in 1..8 || refSize !in 1..8 || objects !in 1..256 || top >= objects || table + objects * offsetSize > n - 32) return
        fun uint(at: Long, size: Int): Long = (0 until size).fold(0L) { acc, i -> (acc shl 8) or s.u8(at + i).toLong() }
        fun objectAt(ref: Long): Long = base + uint(base + table + ref * offsetSize, offsetSize)
        val dict = objectAt(top)
        val marker = s.u8(dict)
        if (marker shr 4 != 0xD || marker and 0x0F == 0x0F) return
        val count = marker and 0x0F
        for (i in 0 until count) {
            val keyAt = objectAt(uint(dict + 1 + i.toLong() * refSize, refSize))
            val valueAt = objectAt(uint(dict + 1 + (count + i).toLong() * refSize, refSize))
            val km = s.u8(keyAt)
            val vm = s.u8(valueAt)
            if (km shr 4 != 0x5 || km and 0x0F == 0x0F || vm shr 4 != 0x1) continue
            val key = s.ascii(keyAt + 1, km and 0x0F)
            val size = 1 shl (vm and 0x0F)
            if (size > 8 || valueAt + 1 + size > base + n) continue
            ctx.observe(
                listOf("MakerNotes", "AppleRunTime"),
                RUN_TIME_NAMES[key] ?: "RunTime_$key",
                NormalizedValue.Integer(uint(valueAt + 1, size)),
                original = ctx.original(s, valueAt + 1, size.toLong(), dev.kiroku.model.ByteOrderMark.BIG_ENDIAN),
                nativeType = NativeType(TypeSystem.TIFF, "bplist int${size * 8}"),
                known = key in RUN_TIME_NAMES,
                sourcePath = "$path/RunTime",
            )
        }
    }

    // ---- Sony 0x9050 -------------------------------------------------------------------------------------------

    /** Sony's substitution cipher for 0x9050/0x94xx blocks: a byte b < 249 deciphers to b^55 mod 249. */
    internal fun sonyDecipher(b: Int): Int {
        if (b >= 249) return b
        var r = 1L
        repeat(55) { r = r * b % 249 }
        return r.toInt()
    }

    // The "9050a" layout (ExifTool's name), used by Sony cameras up to about 2015. Later models (9050b/c) store the same
    // fields at other offsets, so the block is decoded only for models known to use this layout.
    private val SONY_9050A_MODELS = Regex("^(NEX-|SLT-|ILCA-(68|77M2)$|ILCE-(7|7R|7S|7M2|5000|5100|6000|QX1)$|ILCE-QX)")

    private class ByteField(val offset: Int, val name: String, val size: Int, val count: Int = 1)

    private val sony9050a =
        listOf(
            ByteField(0x20, "Shutter", 2, 3), ByteField(0x31, "FlashStatus", 1), ByteField(0x32, "ShutterCount", 4),
            ByteField(0x3A, "SonyExposureTime", 2), ByteField(0x3C, "SonyFNumber", 2), ByteField(0x3F, "ReleaseMode2", 1),
            ByteField(0x4C, "ShutterCount2", 4), ByteField(0x7C, "InternalSerialNumber", 1, 4), ByteField(0x105, "LensMount", 1),
            ByteField(0x106, "LensFormat", 1), ByteField(0x10B, "DistortionCorrParamsPresent", 1), ByteField(0x114, "APS-CSizeCapture", 1),
        )

    private fun sony9050(ctx: NativeContext, s: ByteSource, entry: IfdEntry, path: String) {
        val model = ctx.facts["model"] ?: return
        if (!SONY_9050A_MODELS.containsMatchIn(model)) return
        val group = listOf("MakerNotes", "SonyTag9050")
        for (f in sony9050a) {
            val len = f.size * f.count
            if (f.offset + len > entry.dataSize) continue
            val at = entry.dataOffset + f.offset
            val b = s.read(at, len).map { sonyDecipher(it.toInt() and 0xFF) }
            // Little-endian values, like the rest of Sony's MakerNote data.
            val values = (0 until f.count).map { i -> (0 until f.size).fold(0L) { acc, k -> acc or (b[i * f.size + k].toLong() shl (8 * k)) } }
            ctx.observe(
                group,
                f.name,
                NormalizedValue.list(values.map { NormalizedValue.Integer(it) }),
                tagId = f.offset.toLong(),
                // The original bytes are the enciphered bytes on disk; the value is their deciphered form.
                original = ctx.original(s, at, len.toLong()),
                nativeType = NativeType(TypeSystem.TIFF, "enciphered int${f.size * 8}u", null, f.count.toLong()),
                sourcePath = "$path/SonyTag9050",
            )
        }
    }

    fun decode(ctx: NativeContext, s: ByteSource, vendor: String, entry: IfdEntry, bigEndian: Boolean, path: String) {
        if (!entry.inBounds || entry.dataSize < 2) return
        if (vendor == "Sony" && entry.tag == 0x9050) return sony9050(ctx, s, entry, path)
        if (vendor == "Apple" && entry.tag == 0x0003) return appleRunTime(ctx, s, entry, path)
        val (name, t) = tables[vendor to entry.tag] ?: return
        val group = listOf("MakerNotes", name)
        val order = TiffReader.orderOf(bigEndian)
        for (f in t.fields) {
            val off = f.index.toLong() * t.unit
            if (off + f.format.size > entry.dataSize) continue
            val at = entry.dataOffset + off
            val v =
                when (f.format) {
                    Format.INT16S -> s.u16(at, bigEndian).toShort().toLong()
                    Format.INT16U -> s.u16(at, bigEndian).toLong()
                    Format.INT32U -> s.u32(at, bigEndian)
                }
            ctx.observe(
                group,
                f.name,
                NormalizedValue.Integer(v),
                tagId = f.index.toLong(),
                original = ctx.original(s, at, f.format.size.toLong(), order),
                nativeType = NativeType(TypeSystem.TIFF, f.format.name.lowercase(), null, 1),
                sourcePath = "$path/$name",
            )
        }
    }
}
