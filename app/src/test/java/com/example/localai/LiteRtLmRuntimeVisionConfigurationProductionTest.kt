package com.example.localai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiteRtLmRuntimeVisionConfigurationProductionTest {
    @Test
    fun imageCapableModelUsesVisionAndOneImage() {
        val configuration = LiteRtLmRuntime.buildVisionConfiguration(true)
        assertEquals(true, configuration.enabled)
        assertEquals(1, configuration.maxNumImages)
    }

    @Test
    fun textOnlyModelDoesNotUseVision() {
        val configuration = LiteRtLmRuntime.buildVisionConfiguration(false)
        assertEquals(false, configuration.enabled)
        assertNull(configuration.maxNumImages)
    }
}
