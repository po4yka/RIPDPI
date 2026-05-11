package com.poyka.ripdpi.integration

import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.diagnostics.dpi.QuicH3FingerprintProbe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class QuicH3FingerprintProbeInstrumentedTest {
    @Test
    fun cloudflareH3RespondsToChromeInitialWhenNetworkTestsEnabled() =
        runBlocking {
            assumeTrue(
                "Set RIPDPI_RUN_NETWORK_TESTS=1 or -Pandroid.testInstrumentationRunnerArguments.ripdpi.runNetworkTests=1",
                networkTestsEnabled(),
            )

            val result = QuicH3FingerprintProbe().check("cloudflare.com")

            assertTrue("Expected UDP/443 to be reachable: $result", result.udpReachable)
            assertTrue("Expected Chrome-shaped QUIC Initial to receive a Server Initial: $result", result.chromeOk)
        }

    private fun networkTestsEnabled(): Boolean =
        System.getenv("RIPDPI_RUN_NETWORK_TESTS") == "1" ||
            InstrumentationRegistry
                .getArguments()
                .getString("ripdpi.runNetworkTests") == "1"
}
