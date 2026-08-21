package com.example.ai

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AskAiAdmissionGateTest {
    @Test
    fun concurrentAcquireAllowsOnlyOneRequest() {
        val gate = AskAiAdmissionGate()
        val acquired = AtomicInteger(0)
        val start = CountDownLatch(1)
        val threads = List(8) {
            thread(start = true) {
                start.await()
                if (gate.tryAcquire()) acquired.incrementAndGet()
            }
        }

        start.countDown()
        threads.forEach(Thread::join)

        assertEquals(1, acquired.get())
        assertTrue(gate.isActive())

        gate.release()
        assertTrue(gate.tryAcquire())
    }
}
