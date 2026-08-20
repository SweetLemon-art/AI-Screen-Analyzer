package com.example.image

import android.graphics.Bitmap
import android.media.Image
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.min

object ImageProcessor {

    /**
     * Converts an ImageReader RGBA_8888 frame to a Bitmap.
     *
     * Ownership:
     * - The caller remains responsible for the returned Bitmap.
     * - The supplied Image is always closed before this method returns.
     * - Intermediate padding bitmap memory is recycled immediately after cropping.
     */
    fun convertImageToBitmap(image: Image): Bitmap? {
        var tempBitmap: Bitmap? = null
        return try {
            val planes = image.planes
            if (planes.isEmpty() || image.width <= 0 || image.height <= 0) return null

            val plane = planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride

            // ImageReader should provide valid RGBA plane metadata. Reject malformed
            // metadata instead of risking division-by-zero or invalid allocations.
            if (pixelStride <= 0 || rowStride <= 0 || rowStride < pixelStride * image.width) {
                return null
            }

            val rowPadding = rowStride - pixelStride * image.width
            val bitmapWidth = image.width + rowPadding / pixelStride
            if (bitmapWidth < image.width || bitmapWidth <= 0) return null

            val requiredBytes = bitmapWidth.toLong() * image.height.toLong() * 4L
            if (requiredBytes > Int.MAX_VALUE || buffer.remaining().toLong() < requiredBytes) {
                return null
            }

            tempBitmap = Bitmap.createBitmap(bitmapWidth, image.height, Bitmap.Config.ARGB_8888)
            tempBitmap.copyPixelsFromBuffer(buffer)

            if (bitmapWidth != image.width) {
                val cleanBitmap = Bitmap.createBitmap(
                    tempBitmap,
                    0,
                    0,
                    image.width,
                    image.height
                )
                tempBitmap.recycle()
                tempBitmap = null
                cleanBitmap
            } else {
                val result = tempBitmap
                tempBitmap = null
                result
            }
        } catch (e: Exception) {
            tempBitmap?.let { recycleIfSafe(it) }
            null
        } finally {
            try {
                image.close()
            } catch (ignored: Exception) {
            }
        }
    }

    /**
     * Creates a UI preview that is independent from the analysis bitmap when downscaling
     * is required. The returned bitmap is owned by the caller.
     *
     * If no downscale is required, the source is returned unchanged so no unnecessary
     * duplicate allocation is made. In that case the caller must retain ownership of source.
     */
    fun createPreviewBitmap(
        source: Bitmap,
        maxDimension: Int = DEFAULT_PREVIEW_MAX_DIMENSION
    ): Bitmap {
        require(maxDimension > 0) { "maxDimension must be positive" }
        require(!source.isRecycled) { "source bitmap is already recycled" }

        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) {
            throw IllegalArgumentException("source bitmap has invalid dimensions")
        }

        if (width <= maxDimension && height <= maxDimension) {
            return source
        }

        val scale = min(
            maxDimension.toFloat() / width.toFloat(),
            maxDimension.toFloat() / height.toFloat()
        )
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)

        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }

    /**
     * Compresses the supplied bitmap to a Base64-encoded JPEG for Gemini Vision.
     *
     * Ownership:
     * - The caller owns [rawBitmap] and this method NEVER recycles it.
     * - Any temporary scaled bitmap is recycled before returning or throwing.
     * - The byte-array/JPEG buffers are released when this method returns.
     */
    fun processForGeminiBase64(
        rawBitmap: Bitmap,
        maxDimension: Int = 1080,
        quality: Int = 80
    ): String {
        require(!rawBitmap.isRecycled) { "rawBitmap is already recycled" }
        require(rawBitmap.width > 0 && rawBitmap.height > 0) {
            "rawBitmap has invalid dimensions"
        }
        require(maxDimension > 0) { "maxDimension must be positive" }

        val width = rawBitmap.width
        val height = rawBitmap.height
        val needsScale = width > maxDimension || height > maxDimension

        val bitmapToCompress = if (needsScale) {
            val scale = min(
                maxDimension.toFloat() / width.toFloat(),
                maxDimension.toFloat() / height.toFloat()
            )
            val targetWidth = (width * scale).toInt().coerceAtLeast(1)
            val targetHeight = (height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(rawBitmap, targetWidth, targetHeight, true)
        } else {
            rawBitmap
        }

        val shouldRecycleScaled = bitmapToCompress !== rawBitmap

        return try {
            ByteArrayOutputStream().use { outputStream ->
                val clampedQuality = quality.coerceIn(40, 100)
                check(
                    bitmapToCompress.compress(
                        Bitmap.CompressFormat.JPEG,
                        clampedQuality,
                        outputStream
                    )
                ) { "Bitmap JPEG compression failed" }

                Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            }
        } finally {
            if (shouldRecycleScaled) {
                recycleIfSafe(bitmapToCompress)
            }
        }
    }

    private fun recycleIfSafe(bitmap: Bitmap) {
        if (!bitmap.isRecycled) {
            bitmap.recycle()
        }
    }

    private const val DEFAULT_PREVIEW_MAX_DIMENSION = 720
}
