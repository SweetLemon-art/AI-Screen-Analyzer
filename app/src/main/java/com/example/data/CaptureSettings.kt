package com.example.data

/**
 * User-configurable settings for capture delays and image processing.
 *
 * CRITICAL: The delay is enforced AFTER the AI finishes processing the previous frame,
 * NOT measured from previous capture.
 * Delay is clamped between 1 second and 600 seconds (10 minutes).
 */
data class CaptureSettings(
    val delaySeconds: Int = 5,
    val maxResolutionDimension: Int = 1080,
    val compressionQuality: Int = 80
) {
    init {
        require(delaySeconds in 1..600) { "delaySeconds must be between 1 and 600" }
        require(compressionQuality in 40..100) { "compressionQuality must be between 40 and 100" }
        require(maxResolutionDimension in 480..2160) { "maxResolutionDimension must be between 480 and 2160" }
    }

    companion object {
        val DELAY_PRESETS = listOf(1, 2, 5, 10, 30, 60, 120, 300, 600)
        val DEFAULT = CaptureSettings()
    }
}
