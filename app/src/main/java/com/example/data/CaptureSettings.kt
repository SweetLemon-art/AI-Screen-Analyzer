package com.example.data

/**
 * User-configurable settings for capture delays and image processing.
 *
 * CRITICAL: The delay is enforced AFTER the AI finishes processing the previous frame.
 * Delay is clamped between 1 second and 600 seconds (10 minutes).
 * Quality is clamped between 40 and 100.
 * Resolution dimension is clamped between 480 and 2160.
 */
data class CaptureSettings(
    val delaySeconds: Int = 5,
    val maxResolutionDimension: Int = 1080,
    val compressionQuality: Int = 80
) {
    val delaySecondsClamped: Int = delaySeconds.coerceIn(1, 600)
    val maxResolutionDimensionClamped: Int = maxResolutionDimension.coerceIn(480, 2160)
    val compressionQualityClamped: Int = compressionQuality.coerceIn(40, 100)

    companion object {
        val DELAY_PRESETS = listOf(1, 2, 5, 10, 30, 60, 120, 300, 600)
        val DEFAULT = CaptureSettings()

        fun createSafe(
            delay: Int = 5,
            resolution: Int = 1080,
            quality: Int = 80
        ): CaptureSettings {
            return CaptureSettings(
                delaySeconds = delay.coerceIn(1, 600),
                maxResolutionDimension = resolution.coerceIn(480, 2160),
                compressionQuality = quality.coerceIn(40, 100)
            )
        }
    }
}
