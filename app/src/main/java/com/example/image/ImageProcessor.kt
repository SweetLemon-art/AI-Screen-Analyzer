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
     * Closes the provided [image] inside a use/finally block to prevent memory leaks.
     */
    fun convertImageToBitmap(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            if (planes.isEmpty()) return null

            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmapWidth = image.width + rowPadding / pixelStride
            val bitmapHeight = image.height

            val tempBitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            tempBitmap.copyPixelsFromBuffer(buffer)

            if (bitmapWidth != image.width) {
                val cleanBitmap = Bitmap.createBitmap(tempBitmap, 0, 0, image.width, image.height)
                tempBitmap.recycle()
                cleanBitmap
            } else {
                tempBitmap
            }
        } catch (e: Exception) {
            null
        } finally {
            try {
                image.close()
            } catch (ignored: Exception) {
            }
        }
    }

    /**
     * Scales down the bitmap if width or height exceeds [maxDimension], keeping exact aspect ratio.
     * Compresses the bitmap with [quality] and encodes to Base64 JPEG string for Gemini REST API.
     */
    fun processForGemini(
        rawBitmap: Bitmap,
        maxDimension: Int = 1080,
        quality: Int = 80
    ): Pair<Bitmap, String> {
        val width = rawBitmap.width
        val height = rawBitmap.height

        val scaledBitmap: Bitmap = if (width > maxDimension || height > maxDimension) {
            val scale = min(maxDimension.toFloat() / width, maxDimension.toFloat() / height)
            val targetWidth = (width * scale).toInt().coerceAtLeast(1)
            val targetHeight = (height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(rawBitmap, targetWidth, targetHeight, true)
        } else {
            rawBitmap
        }

        val outputStream = ByteArrayOutputStream()
        val clampedQuality = quality.coerceIn(10, 100)
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, clampedQuality, outputStream)
        val byteArray = outputStream.toByteArray()
        val base64String = Base64.encodeToString(byteArray, Base64.NO_WRAP)

        return Pair(scaledBitmap, base64String)
    }
}
