package dev.kiroku.format.tiff

import dev.kiroku.model.NormalizedValue
import dev.kiroku.model.asDouble
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Readable forms of vendor MakerNote values that are stored scaled or encoded (Pentax FNumber 130 means f/13, Canon
 * apertures are APEX-like units). The stored value is never changed: the result only becomes the observation's
 * `displayValue`. Each formula was checked against ExifTool's converted value on the fixtures.
 */
private typealias Conv = (NormalizedValue, String?) -> String?

internal object MakerNoteConv {
    fun display(group: String, tag: String, value: NormalizedValue, model: String?): String? = try {
        TABLE[group]?.get(tag)?.invoke(value, model)
    } catch (_: ArithmeticException) {
        null
    }

    private fun num(v: NormalizedValue): Double? = v.asDouble()

    private fun ints(v: NormalizedValue): List<Long>? = when (v) {
        is NormalizedValue.Integer -> listOf(v.value)
        is NormalizedValue.ListValue -> v.items.map { (it as? NormalizedValue.Integer)?.value ?: return null }
        else -> null
    }

    private fun fmt(x: Double, decimals: Int): String {
        val s = String.format(Locale.ROOT, "%.${decimals}f", x)
        return if (s.startsWith("-") && s.trimStart('-').all { it == '0' || it == '.' }) s.substring(1) else s
    }

    /** Trims trailing zeros: 2.19, 13, 0.7. */
    private fun plain(x: Double): String {
        val s = String.format(Locale.ROOT, "%.6f", x).trimEnd('0').trimEnd('.')
        return if (s == "-0" || s.isEmpty()) "0" else s
    }

    private fun signed(x: Double): String = if (x > 0) "+${plain(x)}" else plain(x)

    private fun exposure(seconds: Double): String = when {
        seconds <= 0 -> plain(seconds)
        seconds < 0.25001 -> "1/${(1 / seconds).roundToLong()}"
        else -> plain(seconds)
    }

    private fun scaled(divisor: Double, unit: String? = null, offset: Double = 0.0): Conv = { v, _ ->
        num(v)?.let { plain((it - offset) / divisor) + (unit?.let { u -> " $u" } ?: "") }
    }

    /** Canon's EV encoding: 1/32 EV steps, with 0x0C and 0x14 standing for 1/3 and 2/3 stops. */
    private fun canonEv(raw: Long): Double {
        val sign = if (raw < 0) -1 else 1
        var v = abs(raw)
        val frac = v and 0x1F
        v -= frac
        val f =
            when (frac) {
                0x0CL -> 32.0 / 3
                0x14L -> 64.0 / 3
                else -> frac.toDouble()
            }
        return sign * (v + f) / 32
    }

    /** Two significant digits, as f-numbers are usually written (2.8, 5.7, 22.6 -> 23). */
    private fun sig2(x: Double): String = java.math.BigDecimal(x).round(java.math.MathContext(2)).stripTrailingZeros().toPlainString()

    private val canonAperture: Conv = { v, _ -> ints(v)?.singleOrNull()?.let { sig2(exp(canonEv(it) * ln(2.0) / 2)) } }
    private val canonExposure: Conv = { v, model ->
        ints(v)?.singleOrNull()?.let {
            // ExifTool: these models store ExposureTime with another scale.
            val k = if (model != null && Regex("\\b(20D|350D|REBEL XT|Kiss Digital N)\\b").containsMatchIn(model)) 1000.0 / 32 else 1.0
            exposure(exp(-canonEv(it) * ln(2.0)) * k)
        }
    }

    private val olympusAperture: Conv = { v, _ -> num(v)?.let { fmt(2.0.pow(it / 512), 1) } }
    private val olympusBalance: Conv = { v, _ -> ints(v)?.firstOrNull()?.let { plain(it / 256.0) } }

    /** Nikon PictureControl adjustments: 128 is the neutral value, 255 means not applicable. */
    private fun pictureControl(neutral: String?): Conv = { v, _ ->
        ints(v)?.singleOrNull()?.let {
            when {
                it == 255L -> "n/a"
                neutral == null -> plain((it - 128).toDouble())
                it == 128L -> neutral
                else -> signed((it - 128).toDouble())
            }
        }
    }

    /** Sigma stores "Expo:+0.8"-style text; the readable form drops the label. */
    private val sigmaLabel: Conv = { v, _ -> (v as? NormalizedValue.Text)?.value?.substringAfter(':', "")?.trim()?.ifEmpty { null } }

    private val swappedSize: Conv = { v, _ -> ints(v)?.takeIf { it.size == 2 }?.let { "${it[1]}x${it[0]}" } }

    private val TABLE: Map<String, Map<String, Conv>> =
        mapOf(
            "CanonCameraSettings" to mapOf("MaxAperture" to canonAperture, "MinAperture" to canonAperture),
            "CanonShotInfo" to
                mapOf(
                    "TargetAperture" to canonAperture,
                    "FNumber" to canonAperture,
                    "ExposureTime" to canonExposure,
                    "TargetExposureTime" to canonExposure,
                    "BaseISO" to { v, _ -> ints(v)?.singleOrNull()?.let { plain(exp(canonEv(it) * ln(2.0)) * 100 / 32) } },
                    "AutoISO" to { v, _ -> ints(v)?.singleOrNull()?.let { plain(exp(it / 32.0 * ln(2.0)) * 100) } },
                    "MeasuredEV" to { v, _ -> num(v)?.let { plain(it / 32 + 5) } },
                    "MeasuredEV2" to { v, _ -> num(v)?.let { plain(it / 8 - 6) } },
                    "CameraTemperature" to { v, _ -> ints(v)?.singleOrNull()?.takeIf { it != 0L }?.let { "${it - 128} C" } },
                    "BulbDuration" to scaled(10.0),
                ),
            "CanonFileInfo" to mapOf("FocusDistanceUpper" to scaled(100.0, "m"), "FocusDistanceLower" to scaled(100.0, "m")),
            "Pentax" to
                mapOf(
                    "ExposureTime" to { v, _ -> num(v)?.let { exposure(it * 1e-5) } },
                    "FNumber" to { v, _ -> num(v)?.let { fmt(it / 10, 1) } },
                    "FocalLength" to { v, _ -> num(v)?.let { "${fmt(it / 100, 1)} mm" } },
                    "ExposureCompensation" to { v, _ -> num(v)?.let { signed((it - 50) / 10) } },
                    "SensitivityAdjust" to { v, _ -> num(v)?.let { signed((it - 50) / 10) } },
                    "EffectiveLV" to { v, _ -> num(v)?.let { fmt(it / 1024, 1) } },
                ),
            "Panasonic" to mapOf("TimeSincePowerOn" to scaled(100.0, "s")),
            "Kodak" to
                mapOf(
                    "FNumber" to scaled(100.0),
                    "ExposureTime" to { v, _ -> num(v)?.let { exposure(it / 1e5) } },
                    "TotalZoom" to scaled(100.0),
                    "DigitalZoom" to scaled(100.0),
                ),
            "Casio" to mapOf("ObjectDistance" to scaled(1000.0, "m")),
            "OlympusEquipment" to
                mapOf(
                    "MaxAperture" to olympusAperture,
                    "MaxApertureAtMinFocal" to olympusAperture,
                    "MaxApertureAtMaxFocal" to olympusAperture,
                ),
            "Olympus" to mapOf("RedBalance" to olympusBalance, "BlueBalance" to olympusBalance),
            "Sony" to mapOf("FullImageSize" to swappedSize, "PreviewImageSize" to swappedSize),
            "Sigma" to
                listOf(
                    "ExposureCompensation", "Contrast", "Shadow", "Highlight", "Saturation", "Sharpness", "X3FillLight",
                    "ColorAdjustment", "Quality",
                ).associateWith { sigmaLabel },
            "NikonPictureControl" to
                mapOf(
                    "PictureControlQuickAdjust" to pictureControl("Normal"),
                    "Sharpness" to pictureControl(null),
                    "MidRangeSharpness" to pictureControl(null),
                    "Clarity" to pictureControl(null),
                    "Contrast" to pictureControl("Normal"),
                    "Brightness" to pictureControl("Normal"),
                    "Saturation" to pictureControl("Normal"),
                    "Hue" to pictureControl("None"),
                    "HueAdjustment" to pictureControl("None"),
                    "ToningSaturation" to pictureControl(null),
                ),
        )
}
