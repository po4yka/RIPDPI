package com.poyka.ripdpi.diagnostics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class DefaultHomeAnalysisAugmentationSourceTest {
    @Test
    fun `homeNetworkIdentitySignal uses coarse cellular operator code`() {
        assertEquals(
            "Carrier 25099",
            homeNetworkIdentitySignal(
                transport = "cellular",
                cellularOperatorCode = "25099",
            ),
        )
    }

    @Test
    fun `a blocking probe that outlives its budget returns at the deadline`() =
        runBlocking {
            val startedNs = System.nanoTime()

            val result =
                awaitBlockingProbeWithin(timeoutMs = 100L) {
                    Thread.sleep(2_000L)
                    "late"
                }

            assertNull(result)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs) < 1_000L)
        }

    @Test
    fun `cancelling the caller cancels a blocking probe that is still running`() =
        runBlocking {
            val probeStarted = CompletableDeferred<Unit>()
            val probeStopped = CompletableDeferred<Unit>()
            val caller =
                launch(Dispatchers.Default) {
                    awaitBlockingProbeWithin(timeoutMs = 60_000L) {
                        probeStarted.complete(Unit)
                        try {
                            // Like the load download: blocking chunks with a cancellation check between them.
                            while (currentCoroutineContext().isActive) Thread.sleep(10L)
                        } finally {
                            probeStopped.complete(Unit)
                        }
                    }
                }
            probeStarted.await()

            caller.cancelAndJoin()

            assertNotNull(withTimeoutOrNull(5_000L) { probeStopped.await() })
        }

    @Test
    fun `homeNetworkIdentitySignal drops wifi identity rather than scraping SSID`() {
        assertNull(homeNetworkIdentitySignal(transport = "wifi"))
    }
}
