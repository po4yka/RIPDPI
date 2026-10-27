package com.poyka.ripdpi.diagnostics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
    fun `homeNetworkIdentitySignal drops wifi identity rather than scraping SSID`() {
        assertNull(homeNetworkIdentitySignal(transport = "wifi"))
    }
}
