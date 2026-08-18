package com.example.data

/**
 * User-configurable settings for capture delays and image processing.
 *
 * CRITICAL: The delay is enforced AFTER the AI finishes processing the previous frame,
 * NOT measured from previous capture.
 */
data class CaptureSettings(
    val delaySeconds: Int = 5,
    val maxResolutionDimension: Int = 1080,
    val compressionQuality: Int = 80,
    val autoScrollToBottom: Boolean = true
) {
    companion object {
        val DELAY_PRESETS = listOf(1, 2, 5, 10, 30, 60)
        val DEFAULT = CaptureSettings()
    }
}
