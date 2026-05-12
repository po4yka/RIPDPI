package com.poyka.ripdpi.integration

import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.diagnostics.dpi.EchProbeVerdict
import com.poyka.ripdpi.diagnostics.dpi.EchReadinessProbe
import com.poyka.ripdpi.diagnostics.dpi.NativeEchTlsHandshake
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class EchReadinessProbeInstrumentedTest {
    @Test
    fun cloudflareEchNegotiatesWhenNetworkTestsEnabled() =
        runBlocking {
            assumeTrue(
                "Set RIPDPI_RUN_NETWORK_TESTS=1 or -Pandroid.testInstrumentationRunnerArguments.ripdpi.runNetworkTests=1",
                networkTestsEnabled(),
            )

            val result =
                EchReadinessProbe(tlsHandshake = NativeEchTlsHandshake())
                    .check("cloudflare-ech.com", vanillaTlsOk = true)

            assertEquals(
                "Expected Cloudflare ECH test domain to publish HTTPS RR: $result",
                true,
                result.httpsRrFetched,
            )
            assertEquals(
                "Expected native rustls ECH handshake to negotiate ECH: $result",
                EchProbeVerdict.ECH_OK,
                result.verdict,
            )
            assertTrue(
                "Expected negotiatedEch=true from native handshake: $result",
                result.negotiatedEch,
            )
        }

    private fun networkTestsEnabled(): Boolean =
        System.getenv("RIPDPI_RUN_NETWORK_TESTS") == "1" ||
            InstrumentationRegistry
                .getArguments()
                .getString("ripdpi.runNetworkTests") == "1"
}
