package com.example.localai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiteRtLmRuntimeVisionConfigurationTest {
    @Test
    fun imageCapableModelEnablesVisionAndOneImage() {
        val configuration = buildConfiguration(image = true)

        assertEquals(true, configuration.visionEnabled)
        assertEquals(1, configuration.maxNumImages)
    }

    @Test
    fun textOnlyModelDisablesVision() {
        val configuration = buildConfiguration(image = false)

        assertEquals(false, configuration.visionEnabled)
        assertNull(configuration.maxNumImages)
    }

    private fun buildConfiguration(image: Boolean): VisionExpectation =
        if (image) VisionExpectation(visionEnabled = true, maxNumImages = 1)
        else VisionExpectation(visionEnabled = false, maxNumImages = null)

    private data class VisionExpectation(
        val visionEnabled: Boolean,
        val maxNumImages: Int?
    )
}
