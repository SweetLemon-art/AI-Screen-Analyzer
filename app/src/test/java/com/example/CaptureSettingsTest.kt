package com.example

import com.example.data.CaptureSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSettingsBoundsTest {

    @Test
    fun `safe settings clamp delay to one second minimum`() {
        val settings = CaptureSettings.createSafe(delay = 0)
        assertEquals(1, settings.delaySeconds)
        assertEquals(1, settings.delaySecondsClamped)
    }

    @Test
    fun `safe settings clamp delay to ten minutes maximum`() {
        val settings = CaptureSettings.createSafe(delay = 601)
        assertEquals(600, settings.delaySeconds)
        assertEquals(600, settings.delaySecondsClamped)
    }

    @Test
    fun `ten minute delay is an available preset`() {
        assertTrue(600 in CaptureSettings.DELAY_PRESETS)
    }

    @Test
    fun `safe settings clamp image resolution and quality`() {
        val settings = CaptureSettings.createSafe(
            resolution = 100,
            quality = 5
        )
        assertEquals(480, settings.maxResolutionDimension)
        assertEquals(40, settings.compressionQuality)
    }

    @Test
    fun `safe settings clamp upper image limits`() {
        val settings = CaptureSettings.createSafe(
            resolution = 5000,
            quality = 200
        )
        assertEquals(2160, settings.maxResolutionDimension)
        assertEquals(100, settings.compressionQuality)
    }
}
