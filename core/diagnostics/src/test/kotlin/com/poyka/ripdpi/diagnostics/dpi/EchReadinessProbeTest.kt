package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class EchReadinessProbeTest {
    @Test
    fun missingHttpsRrReturnsNoConfig() =
        runTest {
            val probe =
                EchReadinessProbe(
                    resolver =
                        HttpsRrResolver(
                            query = HttpsRrQuery { HttpsRrResponse(HttpsRrOutcome.NODATA) },
                            fallbackQuery = null,
                        ),
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

            val result = probe.check("cloudflare.com")

            assertEquals(EchProbeVerdict.ECH_OK, result.verdict)
            assertEquals(true, result.httpsRrFetched)
            assertEquals(true, result.negotiatedEch)
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

    @Test
    fun resolverCancellationIsPropagated() =
        runTest {
            val probe =
                EchReadinessProbe(
                    resolver =
                        HttpsRrResolver(
                            query = HttpsRrQuery { throw CancellationException("cancelled") },
                            fallbackQuery = null,
                        ),
                    tlsHandshake = FakeEchTlsHandshake(EchTlsHandshakeResult(negotiatedEch = true, latencyMs = 10)),
                )

            val error = runCatching { probe.check("cloudflare-ech.com") }.exceptionOrNull()

            assertTrue(error is CancellationException)
        }

    @Test
    fun handshakeCancellationIsPropagated() =
        runTest {
            val probe =
                EchReadinessProbe(
                    resolver = resolverWithConfig(byteArrayOf(1)),
                    tlsHandshake = FakeEchTlsHandshake(error = CancellationException("cancelled")),
                )

            val error = runCatching { probe.check("cloudflare-ech.com") }.exceptionOrNull()

            assertTrue(error is CancellationException)
        }

    @Test
    fun checkAllLimitsConcurrentEchHandshakesToFour() =
        runTest {
            val active = AtomicInteger(0)
            val maxActive = AtomicInteger(0)
            val probe =
                EchReadinessProbe(
                    resolver = resolverWithConfig(byteArrayOf(1, 2, 3)),
                    tlsHandshake =
                        object : EchTlsHandshake {
                            override suspend fun connect(
                                endpoint: EchTlsEndpoint,
                                echConfig: ByteArray,
                            ): EchTlsHandshakeResult {
                                val nowActive = active.incrementAndGet()
                                maxActive.updateAndGet { previous -> maxOf(previous, nowActive) }
                                delay(10)
                                active.decrementAndGet()
                                return EchTlsHandshakeResult(negotiatedEch = true, latencyMs = 10)
                            }
                        },
                )

            val results = probe.checkAll((1..10).map { index -> "target-$index.example" })

            assertEquals(10, results.size)
            assertEquals(4, maxActive.get())
            assertEquals(results.map { result -> EchProbeVerdict.ECH_OK }.toSet(), results.map { it.verdict }.toSet())
        }

    @Test
    fun observedRrsetWithoutEchAndSystemFailureKeepSeparateTargetResults() =
        runTest {
            val probe =
                EchReadinessProbe(
                    resolver =
                        HttpsRrResolver(
                            query =
                                HttpsRrQuery { host ->
                                    if (host == "records.example") {
                                        HttpsRrResponse(
                                            HttpsRrOutcome.ANSWER,
                                            listOf(HttpsRrRecord(priority = 1, targetName = ".", echConfig = null)),
                                        )
                                    } else {
                                        HttpsRrResponse(HttpsRrOutcome.HTTP_ERROR, httpStatus = 503)
                                    }
                                },
                            fallbackQuery =
                                HttpsRrQuery {
                                    HttpsRrResponse(androidLookupFailure(android.net.DnsResolver.ERROR_SYSTEM, null))
                                },
                        ),
                )
            val (observed, failed) = probe.checkAll(listOf("records.example", "failed.example"))
            assertEquals(EchProbeVerdict.ECH_NO_CONFIG, observed.verdict)
            assertTrue(observed.httpsRrFetched)
            assertEquals(EchProbeVerdict.ECH_LOOKUP_INCONCLUSIVE, failed.verdict)
            assertFalse(failed.httpsRrFetched)
            assertEquals(503, failed.httpsLookups.first().httpStatus)
            assertEquals(HttpsRrSource.SYSTEM, failed.httpsLookups.last().source)
            assertEquals(HttpsRrOutcome.TRANSPORT_ERROR, failed.httpsLookups.last().outcome)
        }

    @Test
    fun h3OnlyAndUnusableConfigDoNotPreventTheNextTcpBinding() =
        runTest {
            val h3 = HttpsRrRecord(1, "h3.example", byteArrayOf(1), supportsTcp = false)
            val unusable = HttpsRrRecord(2, "unusable.example", byteArrayOf(2))
            val alternative = HttpsRrRecord(3, "tcp.example", byteArrayOf(3), port = 8443)
            val resolver =
                HttpsRrResolver(
                    query = HttpsRrQuery { HttpsRrResponse(HttpsRrOutcome.ANSWER, listOf(h3, unusable, alternative)) },
                    fallbackQuery = null,
                )
            val probe =
                EchReadinessProbe(
                    resolver = resolver,
                    tlsHandshake =
                        object : EchTlsHandshake {
                            override suspend fun connect(
                                endpoint: EchTlsEndpoint,
                                echConfig: ByteArray,
                            ): EchTlsHandshakeResult {
                                if (endpoint.host == "unusable.example") {
                                    throw EchTlsSetupException(
                                        EchTlsSetupFailure.UNUSABLE_CONFIG,
                                        "unsupported version",
                                    )
                                }
                                assertEquals(EchTlsEndpoint("origin.example", "tcp.example", 8443), endpoint)
                                assertEquals(3.toByte(), echConfig.single())
                                return EchTlsHandshakeResult(negotiatedEch = true, latencyMs = 7)
                            }
                        },
                )

            assertEquals(EchProbeVerdict.ECH_OK, probe.check("origin.example").verdict)
        }

    @Test
    fun missingTcpBindingAndUnusableConfigAreNotTlsRejection() =
        runTest {
            val onlyH3 = HttpsRrRecord(1, "h3.example", byteArrayOf(1), supportsTcp = false)
            val unsupported =
                EchReadinessProbe(
                    resolver =
                        HttpsRrResolver(
                            query = HttpsRrQuery { HttpsRrResponse(HttpsRrOutcome.ANSWER, listOf(onlyH3)) },
                            fallbackQuery = null,
                        ),
                    tlsHandshake = FakeEchTlsHandshake(error = IllegalStateException("unexpected TCP probe")),
                ).check("origin.example")
            assertEquals(EchProbeVerdict.ECH_UNSUPPORTED_ENDPOINT, unsupported.verdict)
            assertFalse(unsupported.hasMeasurement)

            val unusable =
                EchReadinessProbe(
                    resolver = resolverWithConfig(byteArrayOf(1)),
                    tlsHandshake =
                        FakeEchTlsHandshake(
                            error = EchTlsSetupException(EchTlsSetupFailure.UNUSABLE_CONFIG, null),
                        ),
                ).check("origin.example")
            assertEquals(EchProbeVerdict.ECH_UNUSABLE_CONFIG, unusable.verdict)
            assertFalse(unusable.hasMeasurement)
        }

    private fun resolverWithConfig(config: ByteArray): HttpsRrResolver =
        HttpsRrResolver(
            query =
                HttpsRrQuery {
                    HttpsRrResponse(
                        HttpsRrOutcome.ANSWER,
                        listOf(HttpsRrRecord(priority = 1, targetName = ".", echConfig = config)),
                    )
                },
        )

    private class FakeEchTlsHandshake(
        private val result: EchTlsHandshakeResult? = null,
        private val error: Exception? = null,
    ) : EchTlsHandshake {
        override suspend fun connect(
            endpoint: EchTlsEndpoint,
            echConfig: ByteArray,
        ): EchTlsHandshakeResult {
            error?.let { throw it }
            return requireNotNull(result)
        }
    }
}
