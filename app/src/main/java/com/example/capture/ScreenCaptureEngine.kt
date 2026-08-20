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
import androidx.annotation.RequiresApi
import com.example.image.ImageProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * Engine managing MediaProjection, VirtualDisplay, and ImageReader lifecycle.
 *
 * INVARIANTS:
 * - Exactly ONE VirtualDisplay per MediaProjection session.
 * - [captureSingleFrame] NEVER creates or recreates VirtualDisplay.
 * - MediaProjection content resize is handled by recreating only the display/reader pair.
 * - Callbacks are explicitly bound to session generations to prevent stale callback pollution.
 * - Callbacks are unregistered before MediaProjection is stopped.
 * - [stop] is strictly idempotent and safe when called repeatedly.
 * - Synchronized with a Mutex so only one frame capture occurs at any instant.
 */
object ScreenCaptureEngine : ScreenCaptureProvider {

    private val sessionGeneration = AtomicLong(0)

    @Volatile
    private var mediaProjection: MediaProjection? = null

    @Volatile
    private var registeredProjCallback: MediaProjection.Callback? = null

    @Volatile
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile
    private var imageReader: ImageReader? = null

    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDensity = DisplayMetrics.DENSITY_DEFAULT

    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    @Volatile
    private var onProjectionStopCallback: (() -> Unit)? = null

    @Volatile
    private var sessionContext: Context? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureMutex = Mutex()

    @Synchronized
    fun initialize(
        context: Context,
        projection: MediaProjection,
        onStopCallback: (() -> Unit)? = null
    ) {
        val currentGen = sessionGeneration.incrementAndGet()
        cleanupResourcesInternal()

        sessionContext = context.applicationContext
        mediaProjection = projection
        onProjectionStopCallback = onStopCallback

        val projCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                handleSessionStop(currentGen)
            }

            @RequiresApi(34)
            override fun onCapturedContentResize(width: Int, height: Int) {
                super.onCapturedContentResize(width, height)
                handleCapturedContentResize(currentGen, width, height)
            }
        }
        registeredProjCallback = projCallback

        try {
            projection.registerCallback(projCallback, mainHandler)
        } catch (e: Exception) {
            stop()
            return
        }

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

        val success = setupSessionResources(projection, currentGen)
        _isReady.value = success
        if (!success) stop()
    }

    @Synchronized
    private fun setupSessionResources(
        projection: MediaProjection,
        generation: Long
    ): Boolean {
        return try {
            val reader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                2
            )
            imageReader = reader

            val vDisplay = projection.createVirtualDisplay(
                "AIScreenCaptureDisplay",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                object : VirtualDisplay.Callback() {
                    override fun onPaused() {}
                    override fun onResumed() {}
                    override fun onStopped() {
                        super.onStopped()
                        // MediaProjection.Callback.onStop() is the authoritative session-stop signal.
                        // Intentional display replacement during resize must not stop the session.
                    }
                },
                mainHandler
            )
            virtualDisplay = vDisplay
            generation == sessionGeneration.get()
        } catch (e: Exception) {
            imageReader?.close()
            imageReader = null
            false
        }
    }

    @Synchronized
    private fun handleCapturedContentResize(generation: Long, width: Int, height: Int) {
        if (generation != sessionGeneration.get() || mediaProjection == null) return
        if (width <= 0 || height <= 0) return
        if (width == screenWidth && height == screenHeight) return

        screenWidth = width
        screenHeight = height
        _isReady.value = false

        releaseDisplayResourcesOnly()

        val projection = mediaProjection ?: return
        val success = setupSessionResources(projection, generation)
        _isReady.value = success && generation == sessionGeneration.get()
        if (!success) {
            handleSessionStop(generation)
        }
    }

    private fun handleSessionStop(callbackGen: Long) {
        synchronized(this) {
            if (callbackGen != sessionGeneration.get()) return
            _isReady.value = false
            cleanupResourcesInternal()
            val callback = onProjectionStopCallback
            onProjectionStopCallback = null
            callback?.invoke()
        }
    }

    override suspend fun captureSingleFrame(): CaptureResult = withContext(Dispatchers.Default) {
        captureMutex.withLock {
            val reader = imageReader
            val projection = mediaProjection
            val vDisplay = virtualDisplay

            if (reader == null || projection == null || vDisplay == null || !_isReady.value) {
                return@withContext CaptureResult.Error("Screen capture session is not active or permission was revoked.")
            }

            try {
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
                                    } else {
                                        bmp?.let { if (!it.isRecycled) it.recycle() }
                                    }
                                }
                            } catch (e: Exception) {
                                if (cont.isActive) cont.resume(null)
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CaptureResult.Error("Screen capture error: ${e.localizedMessage ?: e.message}")
            }
        }
    }

    @Synchronized
    private fun releaseDisplayResourcesOnly() {
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
    }

    @Synchronized
    private fun cleanupResourcesInternal() {
        releaseDisplayResourcesOnly()

        val callback = registeredProjCallback
        registeredProjCallback = null
        val projection = mediaProjection
        mediaProjection = null
        sessionContext = null
        try {
            if (callback != null) projection?.unregisterCallback(callback)
            projection?.stop()
        } catch (ignored: Exception) {}
    }

    @Synchronized
    fun stop() {
        sessionGeneration.incrementAndGet()
        _isReady.value = false
        cleanupResourcesInternal()
        onProjectionStopCallback = null
    }
}
