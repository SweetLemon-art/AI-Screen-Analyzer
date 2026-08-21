package com.example.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ModelDiscoveryCoordinatorTest {
    @Test
    fun discoveryOperationsAreSerialized() = runBlocking {
        val coordinator = ModelDiscoveryCoordinator()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var active = 0
        var maxActive = 0

        fun enter() {
            active += 1
            maxActive = maxOf(maxActive, active)
        }

        fun exit() {
            active -= 1
        }

        val first = async {
            coordinator.discover {
                enter()
                firstStarted.complete(Unit)
                releaseFirst.await()
                exit()
                Result.success("first")
            }
        }

        firstStarted.await()

        val second = async {
            coordinator.discover {
                enter()
                exit()
                Result.success("second")
            }
        }

        assertFalse(second.isCompleted)
        releaseFirst.complete(Unit)

        assertEquals("first", first.await().getOrThrow())
        assertEquals("second", second.await().getOrThrow())
        assertEquals(1, maxActive)
    }
}
