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

/**
 * Singleton engine managing MediaProjection, VirtualDisplay, and ImageReader lifecycle.
 * Guarantees zero-queue, strictly single-shot frame capture.
 */
object ScreenCaptureEngine {

    @Volatile
    private var mediaProjection: MediaProjection? = null
    @Volatile
    private var virtualDisplay: VirtualDisplay? = null
    @Volatile
    private var imageReader: ImageReader? = null

    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDensity = DisplayMetrics.DENSITY_DEFAULT

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    @Volatile
    private var onProjectionStopCallback: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            _isReady.value = false
            cleanupResources()
            val callback = onProjectionStopCallback
            onProjectionStopCallback = null
            callback?.invoke()
        }
    }

    /**
     * Initializes the capture engine with the MediaProjection provided by ScreenCaptureService.
     */
    @Synchronized
    fun initialize(
        context: Context,
        projection: MediaProjection,
        onStopCallback: (() -> Unit)? = null
    ) {
        // Cleanup prior session if any
        stop()

        this.mediaProjection = projection
        this.onProjectionStopCallback = onStopCallback
        projection.registerCallback(projectionCallback, mainHandler)

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

        if (screenWidth <= 0) screenWidth = 1080
        if (screenHeight <= 0) screenHeight = 1920

        setupImageReaderAndVirtualDisplay()
        _isReady.value = true
    }

    @Synchronized
    private fun setupImageReaderAndVirtualDisplay() {
        val projection = mediaProjection ?: return

        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (ignored: Exception) {}
        imageReader = null

        try {
            virtualDisplay?.release()
        } catch (ignored: Exception) {}
        virtualDisplay = null

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
            mainHandler
        )
        this.virtualDisplay = vDisplay
    }

    /**
     * Captures exactly ONE frame from the screen.
     * Guaranteed to close ImageReader images and produce a valid Bitmap or an error.
     */
    suspend fun captureSingleFrame(): CaptureResult = withContext(Dispatchers.Default) {
        val projection = mediaProjection
        if (projection == null || !_isReady.value) {
            return@withContext CaptureResult.Error("Screen capture session is not active or permission was revoked.")
        }

        var reader = imageReader
        if (reader == null) {
            setupImageReaderAndVirtualDisplay()
            reader = imageReader ?: return@withContext CaptureResult.Error("Failed to initialize screen capture buffers.")
        }

        try {
            // Check if frame is immediately available
            val immediateImage = try {
                reader.acquireLatestImage()
            } catch (e: Exception) {
                null
            }

            if (immediateImage != null) {
                val bitmap = ImageProcessor.convertImageToBitmap(immediateImage)
                return@withContext if (bitmap != null) {
                    CaptureResult.Success(bitmap)
                } else {
                    CaptureResult.Error("Failed to convert captured frame to Bitmap.")
                }
            }

            // Otherwise, await the next frame with a 3-second timeout
            val capturedBitmap = withTimeoutOrNull(3000L) {
                suspendCancellableCoroutine<Bitmap?> { cont ->
                    val listener = ImageReader.OnImageAvailableListener { r ->
                        try {
                            val img = r.acquireLatestImage()
                            if (img != null) {
                                r.setOnImageAvailableListener(null, null)
                                val bmp = ImageProcessor.convertImageToBitmap(img)
                                if (cont.isActive) {
                                    cont.resume(bmp)
                                }
                            }
                        } catch (e: Exception) {
                            if (cont.isActive) {
                                cont.resume(null)
                            }
                        }
                    }

                    reader.setOnImageAvailableListener(listener, mainHandler)

                    cont.invokeOnCancellation {
                        try {
                            reader.setOnImageAvailableListener(null, null)
                        } catch (ignored: Exception) {}
                    }
                }
            }

            if (capturedBitmap != null) {
                CaptureResult.Success(capturedBitmap)
            } else {
                CaptureResult.Error("Timeout waiting for display frame.")
            }
        } catch (e: Exception) {
            CaptureResult.Error("Screen capture error: ${e.localizedMessage ?: e.message}")
        }
    }

    @Synchronized
    private fun cleanupResources() {
        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (ignored: Exception) {}
        imageReader = null

        try {
            virtualDisplay?.release()
        } catch (ignored: Exception) {}
        virtualDisplay = null

        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (ignored: Exception) {}
        mediaProjection = null
    }

    /**
     * Fully stops screen capture and releases all VirtualDisplay, ImageReader, and MediaProjection instances.
     * Safe to call multiple times (idempotent).
     */
    @Synchronized
    fun stop() {
        _isReady.value = false
        cleanupResources()
        onProjectionStopCallback = null
    }
}
