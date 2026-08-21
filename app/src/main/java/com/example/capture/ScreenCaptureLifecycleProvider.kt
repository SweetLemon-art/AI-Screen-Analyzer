package com.example.capture

/**
 * Optional lifecycle signal emitted when the underlying screen-capture session is
 * terminated externally (for example, MediaProjection permission is revoked).
 *
 * This is deliberately separate from [ScreenCaptureProvider] so existing test fakes
 * and non-Android capture implementations do not need to implement Android lifecycle APIs.
 */
interface ScreenCaptureLifecycleProvider {
    fun setOnSessionStoppedListener(listener: (() -> Unit)?)
}
