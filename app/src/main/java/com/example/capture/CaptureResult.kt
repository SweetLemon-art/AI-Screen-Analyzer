package com.example.capture

import android.graphics.Bitmap

sealed interface CaptureResult {
    data class Success(val bitmap: Bitmap) : CaptureResult
    data class Error(val message: String, val throwable: Throwable? = null) : CaptureResult
}
