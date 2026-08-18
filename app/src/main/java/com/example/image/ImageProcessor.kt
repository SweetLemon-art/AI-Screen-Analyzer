package com.example.image

import android.graphics.Bitmap
import android.media.Image
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.min

object ImageProcessor {

    /**
     * Converts an android.media.Image from ImageReader (PixelFormat.RGBA_8888) to an Android Bitmap.
     * Accurately handles rowStride padding where rowStride != width * pixelStride.
     * Guaranteed to close the provided [image] inside a try/finally block to prevent memory leaks.
     * If tempBitmap is allocated and subsequent operations throw, tempBitmap is recycled before returning null.
     */
    fun convertImageToBitmap(image: Image): Bitmap? {
        var tempBitmap: Bitmap? = null
        return try {
            val planes = image.planes
            if (planes.isEmpty()) return null

            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmapWidth = image.width + rowPadding / pixelStride
            val bitmapHeight = image.height

            tempBitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            tempBitmap.copyPixelsFromBuffer(buffer)

            if (bitmapWidth != image.width) {
                val cleanBitmap = Bitmap.createBitmap(tempBitmap, 0, 0, image.width, image.height)
                tempBitmap.recycle()
                tempBitmap = null
                cleanBitmap
            } else {
                val result = tempBitmap
                tempBitmap = null
                result
            }
        } catch (e: Exception) {
            tempBitmap?.let {
                if (!it.isRecycled) {
                    it.recycle()
                }
            }
            null
        } finally {
            try {
                image.close()
            } catch (ignored: Exception) {
            }
        }
    }

    /**
     * Compresses and converts the given [rawBitmap] to a Base64-encoded JPEG string for Gemini Vision API.
     * If scaling is necessary, a temporary scaled bitmap is created, encoded, and immediately recycled.
     * Does NOT recycle the caller's [rawBitmap].
     *
     * @return Base64-encoded JPEG string
     */
    fun processForGeminiBase64(
        rawBitmap: Bitmap,
        maxDimension: Int = 1080,
        quality: Int = 80
    ): String {
        val width = rawBitmap.width
        val height = rawBitmap.height

        val needsScale = width > maxDimension || height > maxDimension
        val bitmapToCompress: Bitmap
        val shouldRecycleScaled: Boolean

        if (needsScale) {
            val scale = min(maxDimension.toFloat() / width, maxDimension.toFloat() / height)
            val targetWidth = (width * scale).toInt().coerceAtLeast(1)
            val targetHeight = (height * scale).toInt().coerceAtLeast(1)
            bitmapToCompress = Bitmap.createScaledBitmap(rawBitmap, targetWidth, targetHeight, true)
            shouldRecycleScaled = (bitmapToCompress !== rawBitmap)
        } else {
            bitmapToCompress = rawBitmap
            shouldRecycleScaled = false
        }

        return try {
            val outputStream = ByteArrayOutputStream()
            val clampedQuality = quality.coerceIn(40, 100)
            bitmapToCompress.compress(Bitmap.CompressFormat.JPEG, clampedQuality, outputStream)
            val byteArray = outputStream.toByteArray()
            Base64.encodeToString(byteArray, Base64.NO_WRAP)
        } finally {
            if (shouldRecycleScaled && !bitmapToCompress.isRecycled) {
                bitmapToCompress.recycle()
            }
        }
    }
}
