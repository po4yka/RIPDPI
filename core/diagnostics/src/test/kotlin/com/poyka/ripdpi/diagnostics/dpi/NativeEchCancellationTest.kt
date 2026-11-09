package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class NativeEchCancellationTest {
    @Test
    fun cancellationStopsTheNativeWorkerAndReleasesItsHandle() =
        runTest(timeout = 10.seconds) {
            val bindings = BlockingBindings()
            val active = launch { NativeEchTlsHandshake(bindings).connect("cancel.example", byteArrayOf(1)) }
            assertTrue(withContext(Dispatchers.IO) { bindings.started.await(3, TimeUnit.SECONDS) })

            active.cancelAndJoin()

            assertTrue(active.isCancelled)
            assertFalse(bindings.running.get())
            assertFalse(bindings.reserved.get())
        }

    @Test
    fun nativeCancelledPayloadRetainsCoroutineCancellation() =
        runTest {
            val bindings = BlockingBindings()
            bindings.cancelRequest(1L)
            val result = runCatching { NativeEchTlsHandshake(bindings).connect("cancel.example", byteArrayOf(1)) }

            assertTrue(result.exceptionOrNull() is CancellationException)
            assertFalse(bindings.reserved.get())
        }

    @Test
    fun bridgeFailuresPropagateAndReleaseTheRequestOnce() =
        runTest {
            val failures =
                listOf(IOException("io failure"), IllegalStateException("bridge failure"), AssertionError("fatal"))
            for (failure in failures) {
                val bindings = FailingBindings(failure, waitForCancellation = false)
                val error = runCatching { NativeEchTlsHandshake(bindings).connect("test.example", byteArrayOf(1)) }
                assertEquals(failure.javaClass, error.exceptionOrNull()?.javaClass)
                assertEquals(failure.message, error.exceptionOrNull()?.message)
                assertEquals(1, bindings.releases.get())
            }
        }

    @Test
    fun cancellationWinsWhenBridgeFailsAndReleasesTheRequestOnce() =
        runTest(timeout = 10.seconds) {
            val bindings = FailingBindings(IOException("late failure"), waitForCancellation = true)
            val active = launch { NativeEchTlsHandshake(bindings).connect("test.example", byteArrayOf(1)) }
            assertTrue(withContext(Dispatchers.IO) { bindings.started.await(3, TimeUnit.SECONDS) })

            active.cancelAndJoin()

            assertTrue(active.isCancelled)
            assertEquals(1, bindings.releases.get())
        }

    private class FailingBindings(
        private val failure: Throwable,
        private val waitForCancellation: Boolean,
    ) : NativeEchTlsHandshakeBindings {
        val started = CountDownLatch(1)
        val releases = AtomicInteger()
        private val cancelled = CountDownLatch(1)

        override fun createRequest(): Long = 1L

        override fun connect(
            requestHandle: Long,
            requestJson: String,
        ): String {
            started.countDown()
            if (waitForCancellation) check(cancelled.await(5, TimeUnit.SECONDS))
            throw failure
        }

        override fun cancelRequest(requestHandle: Long) = cancelled.countDown()

        override fun releaseRequest(requestHandle: Long) {
            releases.incrementAndGet()
        }
    }

    private class BlockingBindings : NativeEchTlsHandshakeBindings {
        val started = CountDownLatch(1)
        val running = AtomicBoolean(false)
        val reserved = AtomicBoolean(false)
        private val cancelled = CountDownLatch(1)

        override fun createRequest(): Long {
            check(reserved.compareAndSet(false, true))
            return 1L
        }

        override fun connect(
            requestHandle: Long,
            requestJson: String,
        ): String {
            running.set(true)
            started.countDown()
            try {
                check(cancelled.await(5, TimeUnit.SECONDS)) { "native worker was not cancelled" }
                return """{"cancelled":true,"error":"ECH handshake cancelled"}"""
            } finally {
                running.set(false)
            }
        }

        override fun cancelRequest(requestHandle: Long) {
            cancelled.countDown()
        }

        override fun releaseRequest(requestHandle: Long) {
            reserved.set(false)
        }
    }
}
