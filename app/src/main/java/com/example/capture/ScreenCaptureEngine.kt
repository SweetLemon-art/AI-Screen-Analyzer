package com.example.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.example.image.ImageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object ScreenCaptureEngine {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var backgroundHandler: Handler? = null

    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDensity = DisplayMetrics.DENSITY_DEFAULT

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private var onProjectionStopCallback: (() -> Unit)? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            _isReady.value = false
            cleanupResources()
            onProjectionStopCallback?.invoke()
        }
    }

    /**
     * Initializes the capture engine with the MediaProjection provided by ScreenCaptureService.
     */
    fun initialize(
        context: Context,
        projection: MediaProjection,
        onStopCallback: (() -> Unit)? = null
    ) {
        // Cleanup prior session if any
        stop()

        this.mediaProjection = projection
        this.onProjectionStopCallback = onStopCallback
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        // Determine screen dimensions & density
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            screenWidth = metrics.bounds.width()
            screenHeight = metrics.bounds.height()
            screenDensity = context.resources.configuration.densityDpi
        } else {
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            screenWidth = displayMetrics.widthPixels
            screenHeight = displayMetrics.heightPixels
            screenDensity = displayMetrics.densityDpi
        }

        // Keep dimensions reasonable (multiples of 8 if needed)
        if (screenWidth <= 0) screenWidth = 1080
        if (screenHeight <= 0) screenHeight = 1920

        setupImageReaderAndVirtualDisplay()
        _isReady.value = true
    }

    private fun setupImageReaderAndVirtualDisplay() {
        val projection = mediaProjection ?: return

        imageReader?.close()
        virtualDisplay?.release()

        val reader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        )
        this.imageReader = reader

        val vDisplay = projection.createVirtualDisplay(
            "AIScreenCaptureDisplay",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            Handler(Looper.getMainLooper())
        )
        this.virtualDisplay = vDisplay
    }

    /**
     * Captures exactly ONE frame from the screen.
     * Guaranteed to close ImageReader images and produce a valid Bitmap or an error.
     * No queues, strictly single-shot.
     */
    suspend fun captureSingleFrame(): CaptureResult = withContext(Dispatchers.Default) {
        val projection = mediaProjection
        if (projection == null || !_isReady.value) {
            return@withContext CaptureResult.Error("Screen capture is not initialized or permission was revoked.")
        }

        var reader = imageReader
        if (reader == null) {
            setupImageReaderAndVirtualDisplay()
            reader = imageReader ?: return@withContext CaptureResult.Error("Failed to configure ImageReader.")
        }

        try {
            // Attempt to acquire latest image immediately if available
            val immediateImage = reader.acquireLatestImage()
            if (immediateImage != null) {
                val bitmap = ImageProcessor.convertImageToBitmap(immediateImage)
                return@withContext if (bitmap != null) {
                    CaptureResult.Success(bitmap)
                } else {
                    CaptureResult.Error("Failed to convert image to Bitmap.")
                }
            }

            // Otherwise, wait for the next frame with a short timeout
            val capturedBitmap = withTimeoutOrNull(3000L) {
                suspendCancellableCoroutine<Bitmap?> { cont ->
                    val listener = ImageReader.OnImageAvailableListener { r ->
                        try {
                            val img = r.acquireLatestImage()
                            if (img != null) {
                                r.setOnImageAvailableListener(null, null)
                                val bmp = ImageProcessor.convertImageToBitmap(img)
                                if (cont.isActive) cont.resume(bmp)
                            }
                        } catch (e: Exception) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }

                    reader.setOnImageAvailableListener(listener, Handler(Looper.getMainLooper()))

                    cont.invokeOnCancellation {
                        reader.setOnImageAvailableListener(null, null)
                    }
                }
            }

            if (capturedBitmap != null) {
                CaptureResult.Success(capturedBitmap)
            } else {
                CaptureResult.Error("Screen capture timed out while waiting for frame.")
            }
        } catch (e: Exception) {
            CaptureResult.Error("Screen capture failed: ${e.localizedMessage ?: e.message}", e)
        }
    }

    private fun cleanupResources() {
        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        imageReader = null

        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        virtualDisplay = null

        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaProjection = null
    }

    /**
     * Fully stops screen capture and releases all VirtualDisplay, ImageReader, and MediaProjection instances.
     */
    fun stop() {
        _isReady.value = false
        cleanupResources()
        onProjectionStopCallback = null
    }
}
