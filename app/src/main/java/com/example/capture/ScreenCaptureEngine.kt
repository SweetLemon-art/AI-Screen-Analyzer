package com.example.capture

import android.graphics.Bitmap
import android.media.ImageReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

            closeRetiredReadersIfIdle()
            _isReady.value = generation == sessionGeneration.get()
        } catch (e: Exception) {
            closeReaderQuietly(newReader)
            handleSessionStop(generation)
        }
    }

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
            val reader: ImageReader? = synchronized(this@ScreenCaptureEngine) {
                val currentReader = imageReader
                val projection = mediaProjection
                val vDisplay = virtualDisplay

                if (currentReader == null || projection == null || vDisplay == null || !_isReady.value) {
                    null
                } else {
                    activeCaptureCount.incrementAndGet()
                    currentReader
                }
            }

            if (reader == null) {
                return@withLock CaptureResult.Error("Screen capture session is not active or permission was revoked.")
            }

            try {
                val immediateImage = try {
                    reader.acquireLatestImage()
                } catch (e: Exception) {
                    null
                }

                if (immediateImage != null) {
                    val bitmap = ImageProcessor.convertImageToBitmap(immediateImage)
                    return@withLock if (bitmap != null) {
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
                            } catch (ignored: Exception) {
                            }
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
            } finally {
                synchronized(this@ScreenCaptureEngine) {
                    activeCaptureCount.decrementAndGet()
                    closeRetiredReadersIfIdle()
                }
            }
        }
    }
