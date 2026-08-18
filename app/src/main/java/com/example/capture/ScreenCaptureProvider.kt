package com.example.capture

import kotlinx.coroutines.flow.StateFlow

interface ScreenCaptureProvider {
    val isReady: StateFlow<Boolean>
    suspend fun captureSingleFrame(): CaptureResult
}
