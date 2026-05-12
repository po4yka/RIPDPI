package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class EchReadinessProbeTest {
    @Test
    fun missingHttpsRrReturnsNoConfig() =
        runTest {
            val probe =
                EchReadinessProbe(
                    resolver = HttpsRrResolver(query = HttpsRrQuery { emptyList() }),
                    tlsHandshake = FakeEchTlsHandshake(EchTlsHandshakeResult(negotiatedEch = true, latencyMs = 10)),
                )

            val result = probe.check("cloudflare.com")

            assertEquals(EchProbeVerdict.ECH_NO_CONFIG, result.verdict)
            assertEquals(false, result.httpsRrFetched)
        }

    @Test
    fun acceptedHandshakeReturnsOk() =
        runTest {
            val probe =
                EchReadinessProbe(
                    resolver = resolverWithConfig(byteArrayOf(1, 2, 3)),
                    tlsHandshake = FakeEchTlsHandshake(EchTlsHandshakeResult(negotiatedEch = true, latencyMs = 42)),
                )

            val result = probe.check("cloudflare.com", vanillaTlsOk = false)

            assertEquals(EchProbeVerdict.ECH_OK, result.verdict)
            assertEquals(true, result.httpsRrFetched)
            assertEquals(true, result.negotiatedEch)
            assertEquals(true, result.bypassedDpi)
            assertEquals(42L, result.tlsLatencyMs)
        }

    @Test
    fun encryptedClientHelloAlertReturnsRejected() =
        runTest {
            val probe =
                EchReadinessProbe(
                    resolver = resolverWithConfig(byteArrayOf(1)),
                    tlsHandshake = FakeEchTlsHandshake(error = IllegalStateException("alert encrypted_client_hello")),
                )

            val result = probe.check("mozilla.org")

            assertEquals(EchProbeVerdict.ECH_REJECTED, result.verdict)
        }

    @Test
    fun echRequiredAlertReturnsUnknownKey() =
        runTest {
            val probe =
                EchReadinessProbe(
                    resolver = resolverWithConfig(byteArrayOf(1)),
                    tlsHandshake = FakeEchTlsHandshake(error = IllegalStateException("TLS alert ech_required")),
                )

            val result = probe.check("fastly.com")

            assertEquals(EchProbeVerdict.ECH_UNKNOWN_KEY, result.verdict)
        }

    @Test
    fun tcpLayerFailureReturnsNetworkBlock() =
        runTest {
            val probe =
                EchReadinessProbe(
                    resolver = resolverWithConfig(byteArrayOf(1)),
                    tlsHandshake = FakeEchTlsHandshake(error = java.net.SocketTimeoutException("connect timed out")),
                )

            val result = probe.check("cloudflare-ech.com")

            assertEquals(EchProbeVerdict.ECH_NETWORK_BLOCK, result.verdict)
        }

    private fun resolverWithConfig(config: ByteArray): HttpsRrResolver =
        HttpsRrResolver(
            query =
                HttpsRrQuery {
                    listOf(HttpsRrRecord(priority = 1, targetName = ".", echConfig = config))
                },
        )

    private class FakeEchTlsHandshake(
        private val result: EchTlsHandshakeResult? = null,
        private val error: Exception? = null,
    ) : EchTlsHandshake {
        override suspend fun connect(
            target: String,
            echConfig: ByteArray,
        ): EchTlsHandshakeResult {
            error?.let { throw it }
            return requireNotNull(result)
        }
    }
}
