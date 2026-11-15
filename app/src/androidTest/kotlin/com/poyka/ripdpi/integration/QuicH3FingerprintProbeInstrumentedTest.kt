package com.poyka.ripdpi.integration

import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.diagnostics.dpi.QuicFingerprint
import com.poyka.ripdpi.diagnostics.dpi.QuicH3FingerprintProbe
import com.poyka.ripdpi.diagnostics.dpi.QuicProbeOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class QuicH3FingerprintProbeInstrumentedTest {
    @Test
    fun cloudflareReturnsInitialCryptoForTemplateAWhenNetworkTestsEnabled() =
        runBlocking {
            assumeTrue(
                "Set RIPDPI_RUN_NETWORK_TESTS=1 or pass the ripdpi.runNetworkTests=1 instrumentation argument.",
                networkTestsEnabled(),
            )

            val result = QuicH3FingerprintProbe().check("cloudflare.com")

            val samples = result.attempts[QuicFingerprint.CHROME_120].orEmpty()
            assertTrue(
                "Expected the template to receive a Server Initial with CRYPTO: $result",
                samples.any { it.outcome == QuicProbeOutcome.INITIAL_CRYPTO },
            )
        }

    private fun networkTestsEnabled(): Boolean =
        System.getenv("RIPDPI_RUN_NETWORK_TESTS") == "1" ||
            InstrumentationRegistry
                .getArguments()
                .getString("ripdpi.runNetworkTests") == "1"
}
