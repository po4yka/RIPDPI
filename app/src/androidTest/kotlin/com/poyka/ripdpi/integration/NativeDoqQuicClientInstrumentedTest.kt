package com.poyka.ripdpi.integration

import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.diagnostics.dpi.DoqWireCodec
import com.poyka.ripdpi.diagnostics.dpi.NativeDoqQuicClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class NativeDoqQuicClientInstrumentedTest {
    @Test
    fun cloudflareDoqResolvesWhenNetworkTestsEnabled() =
        runBlocking {
            assumeTrue(
                "Set RIPDPI_RUN_NETWORK_TESTS=1 or -Pandroid.testInstrumentationRunnerArguments.ripdpi.runNetworkTests=1",
                networkTestsEnabled(),
            )
            val (transactionId, query) = DoqWireCodec.buildQuery(TestDomain)

            val response =
                NativeDoqQuicClient().exchange(
                    endpoint = "1.1.1.1",
                    port = 853,
                    tlsServerName = "cloudflare-dns.com",
                    query = query,
                    timeoutMs = 6_000,
                )

            assertTrue(DoqWireCodec.parseResponse(response, transactionId).any(::isIpv4Literal))
        }

    private fun networkTestsEnabled(): Boolean =
        System.getenv("RIPDPI_RUN_NETWORK_TESTS") == "1" ||
            InstrumentationRegistry
                .getArguments()
                .getString("ripdpi.runNetworkTests") == "1"

    private companion object {
        private const val TestDomain = "example.com"
    }
}

private fun isIpv4Literal(value: String): Boolean {
    val octets = value.split('.')
    return octets.size == Ipv4Octets && octets.all { octet -> octet.toIntOrNull() in Ipv4OctetRange }
}

private const val Ipv4Octets = 4
private val Ipv4OctetRange = 0..255
