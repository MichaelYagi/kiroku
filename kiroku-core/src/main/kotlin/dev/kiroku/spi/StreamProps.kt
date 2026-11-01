package dev.kiroku.spi

/**
 * Well-known keys for [dev.kiroku.model.StreamInfo.properties], shared by all backends so that the
 * gallery projection reads streams uniformly.
 */
public object StreamProps {
    public const val WIDTH: String = "width"
    public const val HEIGHT: String = "height"
    public const val DISPLAY_WIDTH: String = "displayWidth"
    public const val DISPLAY_HEIGHT: String = "displayHeight"
    public const val PIXEL_ASPECT_RATIO: String = "pixelAspectRatio"
    public const val FRAME_RATE: String = "frameRate"
    public const val AVERAGE_FRAME_RATE: String = "averageFrameRate"
    public const val VARIABLE_FRAME_RATE: String = "variableFrameRate"
    public const val BIT_RATE: String = "bitRate"
    public const val DURATION_SECONDS: String = "durationSeconds"
    public const val PROFILE: String = "profile"
    public const val LEVEL: String = "level"
    public const val LANGUAGE: String = "language"
    public const val DEFAULT: String = "default"
    public const val ENABLED: String = "enabled"
    public const val MATRIX: String = "matrix"
    public const val ROTATION: String = "rotation"
    public const val SAMPLE_RATE: String = "sampleRate"
    public const val CHANNELS: String = "channels"
    public const val CHANNEL_LAYOUT: String = "channelLayout"
    public const val BITS_PER_SAMPLE: String = "bitsPerSample"
    public const val BIT_DEPTH_LUMA: String = "bitDepthLuma"
    public const val CHROMA_FORMAT: String = "chromaFormat"
    public const val PIXEL_FORMAT: String = "pixelFormat"
    public const val COLOR_PRIMARIES: String = "colorPrimaries"
    public const val TRANSFER: String = "transferCharacteristics"
    public const val MATRIX_COEFFICIENTS: String = "matrixCoefficients"
    public const val DOLBY_VISION_PROFILE: String = "dolbyVisionProfile"
    public const val TIME_BASE: String = "timeBase"
    public const val ENCODER: String = "encoder"
}
