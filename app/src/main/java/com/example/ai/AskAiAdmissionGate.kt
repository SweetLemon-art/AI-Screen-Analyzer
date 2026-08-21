package com.example.ai

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Atomically admits at most one Ask AI request at a time.
 * The gate is released only after the active request has completed cleanup.
 */
class AskAiAdmissionGate {
    private val active = AtomicBoolean(false)

    fun tryAcquire(): Boolean = active.compareAndSet(false, true)

    fun release() {
        active.set(false)
    }

    fun isActive(): Boolean = active.get()
}
