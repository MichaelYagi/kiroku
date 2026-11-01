package dev.kiroku.format.tiff

import dev.kiroku.format.NativeContext
import dev.kiroku.io.ByteSource
import dev.kiroku.model.DiagnosticCode
import dev.kiroku.model.DirectoryKind
import dev.kiroku.model.NativeType
import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.Severity
import dev.kiroku.model.TypeSystem

/**
 * GeoTIFF keys (GeoTIFF 1.0/1.1). `GeoTiffDirectory` (34735) is a SHORT array: a header (version, revision, minor
 * revision, key count) and 4-SHORT keys (ID, location, count, value). Location 0 means the value is the SHORT itself;
 * 34736 points into `GeoTiffDoubleParams`; 34737 into `GeoTiffAsciiParams` (`|`-terminated strings).
 * Keys are reported in group `GeoTiff:GeoTiff` with the names ExifTool uses; unknown keys keep numeric names.
 */
internal object GeoTiff {
    private val GROUP = listOf("GeoTiff", "GeoTiff")
    private const val DIRECTORY = 0x87AF
    private const val DOUBLES = 0x87B0
    private const val ASCII = 0x87B1
    private const val MAX_KEYS = 512

    fun decode(ctx: NativeContext, s: ByteSource, entries: List<IfdEntry>, bigEndian: Boolean, path: String) {
        val dir = entries.firstOrNull { it.tag == DIRECTORY && it.inBounds && it.type == TiffType.SHORT } ?: return
        val doubles = entries.firstOrNull { it.tag == DOUBLES && it.inBounds && it.type == TiffType.DOUBLE }
        val ascii = entries.firstOrNull { it.tag == ASCII && it.inBounds && it.type == TiffType.ASCII }
        if (dir.count < 4) return
        fun short(i: Long) = s.u16(dir.dataOffset + i * 2, bigEndian)
        val keyCount = short(3).toLong()
        if (keyCount > MAX_KEYS || 4 + keyCount * 4 > dir.count) {
            ctx.warn(DiagnosticCode.MALFORMED, "GeoTIFF key directory declares $keyCount keys for ${dir.count} values", severity = Severity.INFO)
            return
        }
        ctx.collector.directory(
            path.split('/') + "GeoTiff",
            DirectoryKind.TIFF_IFD,
            s.absolute(dir.dataOffset),
            dir.dataSize,
            TiffReader.orderOf(bigEndian),
            keyCount.toInt(),
        )
        ctx.observe(
            GROUP,
            "GeoTiffVersion",
            NormalizedValue.Text("${short(0)}.${short(1)}.${short(2)}"),
            original = ctx.original(s, dir.dataOffset, 6, TiffReader.orderOf(bigEndian)),
            nativeType = NativeType(TypeSystem.TIFF, "SHORT", 3, 3),
            sourcePath = "$path/GeoTiff",
        )
        for (k in 0 until keyCount) {
            val base = 4 + k * 4
            val id = short(base)
            val location = short(base + 1)
            val count = short(base + 2).toLong()
            val valueOrIndex = short(base + 3).toLong()
            val name = NAMES[id] ?: "GeoTiff_0x%04X".format(id)
            fun o(v: NormalizedValue, off: Long, len: Long, type: String, display: String? = null) = ctx.observe(
                GROUP, name, v, tagId = id.toLong(), original = ctx.original(s, off, len, TiffReader.orderOf(bigEndian)),
                nativeType = NativeType(TypeSystem.TIFF, type, null, count), displayValue = display, known = id in NAMES,
                sourcePath = "$path/GeoTiff",
            )
            when (location) {
                0 -> o(NormalizedValue.Integer(valueOrIndex), dir.dataOffset + (base + 3) * 2, 2, "SHORT", displayFor(id, valueOrIndex))

                DIRECTORY ->
                    if (valueOrIndex + count <= dir.count) {
                        val v = (0 until count).map { NormalizedValue.Integer(short(valueOrIndex + it).toLong()) }
                        o(NormalizedValue.list(v), dir.dataOffset + valueOrIndex * 2, count * 2, "SHORT")
                    }

                DOUBLES ->
                    if (doubles != null && valueOrIndex + count <= doubles.count) {
                        val off = doubles.dataOffset + valueOrIndex * 8
                        val v = (0 until count).map { NormalizedValue.Decimal(java.lang.Double.longBitsToDouble(s.u64(off + it * 8, bigEndian))) }
                        o(NormalizedValue.list(v), off, count * 8, "DOUBLE")
                    }

                ASCII ->
                    if (ascii != null && valueOrIndex + count <= ascii.count) {
                        val off = ascii.dataOffset + valueOrIndex
                        // Strings in GeoTiffAsciiParams end with '|'.
                        o(NormalizedValue.Text(s.ascii(off, count.toInt()).trimEnd('|', '\u0000')), off, count, "ASCII")
                    }

                else -> ctx.warnOnce(
                    "geotiff-location",
                    DiagnosticCode.MALFORMED,
                    "GeoTIFF key $id refers to unsupported tag $location",
                    severity = Severity.INFO,
                )
            }
        }
    }

    private fun displayFor(id: Int, v: Long): String? = when {
        v == 32767L -> "User Defined"

        id == 1024 -> when (v) {
            1L -> "Projected"
            2L -> "Geographic"
            3L -> "Geocentric"
            else -> null
        }

        id == 1025 -> when (v) {
            1L -> "Pixel Is Area"
            2L -> "Pixel Is Point"
            else -> null
        }

        else -> null
    }

    // GeoTIFF key IDs and the names ExifTool gives them.
    private val NAMES =
        mapOf(
            1024 to "GTModelType", 1025 to "GTRasterType", 1026 to "GTCitation",
            2048 to "GeographicType", 2049 to "GeogCitation", 2050 to "GeogGeodeticDatum", 2051 to "GeogPrimeMeridian",
            2052 to "GeogLinearUnits", 2053 to "GeogLinearUnitSize", 2054 to "GeogAngularUnits", 2055 to "GeogAngularUnitSize",
            2056 to "GeogEllipsoid", 2057 to "GeogSemiMajorAxis", 2058 to "GeogSemiMinorAxis", 2059 to "GeogInvFlattening",
            2060 to "GeogAzimuthUnits", 2061 to "GeogPrimeMeridianLong", 2062 to "GeogToWGS84",
            3072 to "ProjectedCSType", 3073 to "PCSCitation", 3074 to "Projection", 3075 to "ProjCoordTrans",
            3076 to "ProjLinearUnits", 3077 to "ProjLinearUnitSize", 3078 to "ProjStdParallel1", 3079 to "ProjStdParallel2",
            3080 to "ProjNatOriginLong", 3081 to "ProjNatOriginLat", 3082 to "ProjFalseEasting", 3083 to "ProjFalseNorthing",
            3084 to "ProjFalseOriginLong", 3085 to "ProjFalseOriginLat", 3086 to "ProjFalseOriginEasting",
            3087 to "ProjFalseOriginNorthing", 3088 to "ProjCenterLong", 3089 to "ProjCenterLat", 3090 to "ProjCenterEasting",
            3091 to "ProjCenterNorthing", 3092 to "ProjScaleAtNatOrigin", 3093 to "ProjScaleAtCenter", 3094 to "ProjAzimuthAngle",
            3095 to "ProjStraightVertPoleLong", 4096 to "VerticalCSType", 4097 to "VerticalCitation", 4098 to "VerticalDatum",
            4099 to "VerticalUnits",
        )
}
