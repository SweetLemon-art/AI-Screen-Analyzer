package com.example

import android.graphics.Bitmap
import com.example.image.ImageProcessor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageProcessorMemoryTest {

    @Test
    fun createPreviewBitmap_downscales_large_source_without_recycling_source() {
        val source = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)

        val preview = ImageProcessor.createPreviewBitmap(source, maxDimension = 720)

        assertNotNull(preview)
        assertNotSame(source, preview)
        assertTrue(preview.width <= 720)
        assertTrue(preview.height <= 720)
        assertFalse(source.isRecycled)
        assertFalse(preview.isRecycled)

        preview.recycle()
        source.recycle()
    }

    @Test
    fun createPreviewBitmap_does_not_allocate_duplicate_when_already_small() {
        val source = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)

        val preview = ImageProcessor.createPreviewBitmap(source, maxDimension = 720)

        assertTrue(source === preview)
        assertFalse(source.isRecycled)

        source.recycle()
    }

    @Test
    fun processForGeminiBase64_does_not_recycle_caller_bitmap() {
        val source = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)

        val encoded = ImageProcessor.processForGeminiBase64(
            rawBitmap = source,
            maxDimension = 1080,
            quality = 80
        )

        assertTrue(encoded.isNotEmpty())
        assertFalse(source.isRecycled)

        source.recycle()
    }

    @Test
    fun processForGeminiBase64_releases_scaled_intermediate_without_recycling_source() {
        val source = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)

        val encoded = ImageProcessor.processForGeminiBase64(
            rawBitmap = source,
            maxDimension = 720,
            quality = 80
        )

        assertTrue(encoded.isNotEmpty())
        assertFalse(source.isRecycled)

        source.recycle()
    }
}
