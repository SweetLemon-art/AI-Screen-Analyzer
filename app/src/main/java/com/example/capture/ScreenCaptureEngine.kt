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
 * Engine managing MediaProjection, VirtualDisplay, and ImageReader lifecycle.
 *
 * INVARIANTS:
 * - Exactly ONE VirtualDisplay per MediaProjection session.
 * - [captureSingleFrame] NEVER recreates VirtualDisplay/ImageReader.
 * - [stop] is strictly idempotent and safe when called repeatedly.
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
     * Sets up exactly ONE VirtualDisplay and ONE ImageReader for this session.
     */
    @Synchronized
    fun initialize(
        context: Context,
        projection: MediaProjection,
        onStopCallback: (() -> Unit)? = null
    ) {
        // Clean up prior session if any
        stop()

        this.mediaProjection = projection
        this.onProjectionStopCallback = onStopCallback

        try {
            projection.registerCallback(projectionCallback, mainHandler)
        } catch (e: Exception) {
            stop()
            return
        }

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

        val success = setupSessionResources(projection)
        _isReady.value = success
        if (!success) {
            stop()
        }
    }

    @Synchronized
    private fun setupSessionResources(projection: MediaProjection): Boolean {
        return try {
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
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Captures exactly ONE frame from the screen using the existing VirtualDisplay/ImageReader.
     * NEVER recreates VirtualDisplay or ImageReader.
     */
    suspend fun captureSingleFrame(): CaptureResult = withContext(Dispatchers.Default) {
        val reader = imageReader
        val projection = mediaProjection
        val vDisplay = virtualDisplay

        if (reader == null || projection == null || vDisplay == null || !_isReady.value) {
            return@withContext CaptureResult.Error("Screen capture session is not active or permission was revoked.")
        }

        try {
            // Check if a frame is immediately available in the buffer
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
                    CaptureResult.Error("Failed to convert captured frame buffer to Bitmap.")
                }
            }

            // Await next frame with a 3-second timeout
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
        val reader = imageReader
        imageReader = null
        try {
            reader?.setOnImageAvailableListener(null, null)
            reader?.close()
        } catch (ignored: Exception) {}

        val vDisplay = virtualDisplay
        virtualDisplay = null
        try {
            vDisplay?.release()
        } catch (ignored: Exception) {}

        val projection = mediaProjection
        mediaProjection = null
        try {
            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
        } catch (ignored: Exception) {}
    }

    /**
     * Fully stops screen capture and releases all VirtualDisplay, ImageReader, and MediaProjection instances.
     * Strictly idempotent: safe to call multiple times in succession.
     */
    @Synchronized
    fun stop() {
        _isReady.value = false
        cleanupResources()
        onProjectionStopCallback = null
    }
}
