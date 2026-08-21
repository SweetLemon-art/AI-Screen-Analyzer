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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * Engine managing MediaProjection, VirtualDisplay, and ImageReader lifecycle.
 *
 * INVARIANTS:
 * - Exactly ONE VirtualDisplay is created for each MediaProjection session.
 * - [captureSingleFrame] never creates or recreates a VirtualDisplay.
 * - Android 14+ captured-content resize is handled with VirtualDisplay.resize()/setSurface().
 * - ImageReader replacement and session cleanup are deferred until active captures finish,
 *   preventing close/use races.
 * - Retired readers keep their listener until the owning capture finishes, preventing a resize
 *   or stop callback from racing with listener use on the old reader.
 * - Callbacks are explicitly bound to session generations to prevent stale callback pollution.
 * - Callbacks are unregistered before MediaProjection is stopped.
 * - [stop] is strictly idempotent and safe when called repeatedly.
 * - [captureSingleFrame] serializes frame capture with a Mutex.
 */
object ScreenCaptureEngine : ScreenCaptureProvider, ScreenCaptureLifecycleProvider {

    private val sessionGeneration = AtomicLong(0)

    @Volatile
    private var mediaProjection: MediaProjection? = null

    @Volatile
    private var registeredProjCallback: MediaProjection.Callback? = null

    @Volatile
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile
    private var imageReader: ImageReader? = null

    private val retiredReaders = mutableListOf<ImageReader>()
    private val activeCaptureCount = AtomicInteger(0)

    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDensity = DisplayMetrics.DENSITY_DEFAULT

    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    @Volatile
    private var onProjectionStopCallback: (() -> Unit)? = null

    @Volatile
    private var onSessionStoppedListener: (() -> Unit)? = null

    @Volatile
    private var sessionContext: Context? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureMutex = Mutex()

    override fun setOnSessionStoppedListener(listener: (() -> Unit)?) {
        onSessionStoppedListener = listener
    }

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
            val failureCallback = onProjectionStopCallback
            stop()
            failureCallback?.invoke()
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
        if (screenDensity <= 0) screenDensity = DisplayMetrics.DENSITY_DEFAULT

        val success = setupSessionResources(projection, currentGen)
        _isReady.value = success
        if (!success) {
            val failureCallback = onProjectionStopCallback
            stop()
            failureCallback?.invoke()
        }
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
                    }
                },
                mainHandler
            )
            virtualDisplay = vDisplay
            generation == sessionGeneration.get()
        } catch (e: Exception) {
            imageReader?.let(::closeReaderQuietly)
            imageReader = null
            false
        }
    }

    @Synchronized
    private fun handleCapturedContentResize(generation: Long, width: Int, height: Int) {
        if (generation != sessionGeneration.get() || mediaProjection == null) return
        if (width <= 0 || height <= 0) return
        if (width == screenWidth && height == screenHeight) return

        val display = virtualDisplay ?: return
        val oldReader = imageReader ?: return

        _isReady.value = false

        val newReader = try {
            ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
            )
        } catch (e: Exception) {
            handleSessionStop(generation)
            return
        }

        try {
            display.resize(width, height, screenDensity)
            display.setSurface(newReader.surface)

            screenWidth = width
            screenHeight = height
            imageReader = newReader

            if (activeCaptureCount.get() == 0) {
                closeReaderQuietly(oldReader)
            } else {
                retiredReaders.add(oldReader)
            }

            closeRetiredReadersIfIdle()
            _isReady.value = generation == sessionGeneration.get()
        } catch (e: Exception) {
            closeReaderQuietly(newReader)
            handleSessionStop(generation)
        }
    }

    @Synchronized
    private fun closeRetiredReadersIfIdle() {
        if (activeCaptureCount.get() != 0) return
        if (retiredReaders.isEmpty()) return

        val readersToClose = retiredReaders.toList()
        retiredReaders.clear()
        readersToClose.forEach(::closeReaderQuietly)
    }

    private fun closeReaderQuietly(reader: ImageReader?) {
        try {
            reader?.setOnImageAvailableListener(null, null)
        } catch (ignored: Exception) {
        }
        try {
            reader?.close()
        } catch (ignored: Exception) {
        }
    }

    @Synchronized
    private fun handleSessionStop(callbackGen: Long) {
        if (callbackGen != sessionGeneration.get()) return
        _isReady.value = false
        cleanupResourcesInternal()
        val callback = onProjectionStopCallback
        onProjectionStopCallback = null
        callback?.invoke()
        onSessionStoppedListener?.invoke()
    }

    @Synchronized
    private fun cleanupResourcesInternal() {
        val currentReader = imageReader
        imageReader = null

        if (currentReader != null) {
            if (activeCaptureCount.get() == 0) {
                closeReaderQuietly(currentReader)
            } else {
                retiredReaders.add(currentReader)
            }
        }
        closeRetiredReadersIfIdle()

        val vDisplay = virtualDisplay
        virtualDisplay = null
        try {
            vDisplay?.release()
        } catch (ignored: Exception) {
        }

        val callback = registeredProjCallback
        registeredProjCallback = null
        val projection = mediaProjection
        mediaProjection = null
        sessionContext = null
        try {
            if (callback != null) projection?.unregisterCallback(callback)
            projection?.stop()
        } catch (ignored: Exception) {
        }
    }

    @Synchronized
    fun stop() {
        val hadActiveSession = mediaProjection != null || virtualDisplay != null || imageReader != null
        sessionGeneration.incrementAndGet()
        _isReady.value = false
        cleanupResourcesInternal()
        onProjectionStopCallback = null
        if (hadActiveSession) {
            onSessionStoppedListener?.invoke()
        }
    }
}
