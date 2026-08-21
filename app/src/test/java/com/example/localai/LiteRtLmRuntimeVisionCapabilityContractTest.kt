package com.example.localai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiteRtLmRuntimeVisionCapabilityContractTest {
    @Test
    fun imageCapableModelEnablesVisionAndOneImage() {
        val configuration = LiteRtLmRuntime.buildVisionConfiguration(true)
        assertEquals(true, configuration.enabled)
        assertEquals(1, configuration.maxNumImages)
    }

    @Test
    fun textOnlyModelDisablesVision() {
        val configuration = LiteRtLmRuntime.buildVisionConfiguration(false)
        assertEquals(false, configuration.enabled)
        assertNull(configuration.maxNumImages)
    }
}
