package com.poyka.ripdpi.core.detection.checker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DetectionHostResolverTest {
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    @Test(timeout = TestTimeoutMillis)
    fun `literal resolves without DNS`() =
        runBlocking {
            assertEquals(listOf(loopback), resolveDetectionAddresses("127.0.0.1"))
        }

    @Test(timeout = TestTimeoutMillis)
    fun `lookup timeout returns unknown before blocked resolver ends`() =
        runBlocking {
            val release = CountDownLatch(1)
            try {
                val result =
                    withTimeout(2_000L) {
                        resolveDetectionHostname(100L) {
                            release.await()
                            listOf(loopback)
                        }
                    }

                assertNull(result)
            } finally {
                release.countDown()
            }
        }

    @Test(timeout = TestTimeoutMillis)
    fun `cancelled active lookups keep their bounded slots until they return`() =
        runBlocking {
            val started = CountDownLatch(2)
            val release = CountDownLatch(1)
            val active =
                List(2) {
                    async(Dispatchers.Default) {
                        resolveDetectionHostname(30_000L) {
                            started.countDown()
                            release.await()
                            listOf(loopback)
                        }
                    }
                }
            try {
                assertTrue(started.await(2L, TimeUnit.SECONDS))
                assertNull(resolveDetectionHostname(100L) { listOf(loopback) })

                active.forEach { it.cancelAndJoin() }

                assertNull(resolveDetectionHostname(100L) { listOf(loopback) })
                release.countDown()
                assertEquals(listOf(loopback), resolveDetectionHostname(2_000L) { listOf(loopback) })
            } finally {
                release.countDown()
                active.forEach { it.cancelAndJoin() }
            }
        }

    @Test(timeout = TestTimeoutMillis)
    fun `cancel does not wait for the blocking resolver`() =
        runBlocking {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                val pending =
                    async(Dispatchers.Default) {
                        resolveDetectionHostname(30_000L) {
                            started.countDown()
                            release.await()
                            listOf(loopback)
                        }
                    }
                assertTrue(started.await(2L, TimeUnit.SECONDS))

                withTimeout(1_000L) { pending.cancelAndJoin() }
            } finally {
                release.countDown()
            }
        }
}

private const val TestTimeoutMillis = 10_000L
