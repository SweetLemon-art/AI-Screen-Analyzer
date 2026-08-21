package com.example.ui

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes model-discovery operations so UI entry points cannot issue
 * overlapping discovery requests against the same provider.
 */
internal class ModelDiscoveryCoordinator {
    private val mutex = Mutex()

    suspend fun <T> discover(block: suspend () -> Result<T>): Result<T> =
        mutex.withLock { block() }
}
