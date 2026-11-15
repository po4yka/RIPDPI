package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
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
    fun authenticatedRejectionIsMeasured() =
        runTest {
            val probe =
                EchReadinessProbe(
                    resolver = resolverWithConfig(byteArrayOf(1)),
                    tlsHandshake = FakeEchTlsHandshake(EchTlsHandshakeResult(negotiatedEch = false, latencyMs = 7)),
                )

            val result = probe.check("mozilla.org")

            assertEquals(EchProbeVerdict.ECH_REJECTED, result.verdict)
            assertTrue(result.hasMeasurement)
        }

    @Test
    fun bridgeErrorTextDoesNotProveRejection() =
        runTest {
            val probe =
                EchReadinessProbe(
                    resolver = resolverWithConfig(byteArrayOf(1)),
                    tlsHandshake = FakeEchTlsHandshake(error = IllegalStateException("TLS alert ech_required")),
                )

            val result = probe.check("fastly.com")

            assertEquals(EchProbeVerdict.ECH_BRIDGE_UNAVAILABLE, result.verdict)
            assertFalse(result.hasMeasurement)
        }

    @Test
    fun socketTimeoutDoesNotProveAPathBlock() =
        runTest {
            val probe =
                EchReadinessProbe(
                    resolver = resolverWithConfig(byteArrayOf(1)),
                    tlsHandshake = FakeEchTlsHandshake(error = java.net.SocketTimeoutException("connect timed out")),
                )

            val result = probe.check("cloudflare-ech.com")

            assertEquals(EchProbeVerdict.ECH_TIMEOUT, result.verdict)
            assertFalse(result.hasMeasurement)
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
    fun oneNegativeResolverAndOneFailedResolverAreInconclusive() =
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
            assertEquals(EchProbeVerdict.ECH_LOOKUP_INCONCLUSIVE, observed.verdict)
            assertTrue(observed.httpsRrFetched)
            assertEquals(
                listOf(
                    HttpsRrSource.DOH to HttpsRrOutcome.ANSWER,
                    HttpsRrSource.SYSTEM to HttpsRrOutcome.TRANSPORT_ERROR,
                ),
                observed.httpsLookups.map { it.source to it.outcome },
            )
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
                                    throw EchTlsException(EchTlsOutcome.UNUSABLE_CONFIG)
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
                            error = EchTlsException(EchTlsOutcome.UNUSABLE_CONFIG),
                        ),
                ).check("origin.example")
            assertEquals(EchProbeVerdict.ECH_UNUSABLE_CONFIG, unusable.verdict)
            assertFalse(unusable.hasMeasurement)
        }

    @Test
    fun transportFailureRetainsEvidenceAndContinuesToTheNextBinding() =
        runTest {
            val first = HttpsRrRecord(1, "first.example", byteArrayOf(1))
            val second = HttpsRrRecord(2, "second.example", byteArrayOf(2), port = 8443)
            val resolver =
                HttpsRrResolver(
                    query = HttpsRrQuery { HttpsRrResponse(HttpsRrOutcome.TIMEOUT) },
                    fallbackQuery = HttpsRrQuery { HttpsRrResponse(HttpsRrOutcome.ANSWER, listOf(second, first)) },
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
                                if (endpoint.host == first.targetName) {
                                    throw EchTlsException(EchTlsOutcome.TRANSPORT_ERROR, EchTlsStage.CONNECT)
                                }
                                return EchTlsHandshakeResult(true, 12L, "203.0.113.9:8443", retryAttempted = true)
                            }
                        },
                )

            val result = probe.check("origin.example")

            assertEquals(EchProbeVerdict.ECH_OK, result.verdict)
            assertEquals(
                listOf(
                    EchBindingAttempt(
                        HttpsRrSource.SYSTEM,
                        EchTlsEndpoint("origin.example", "first.example", 443),
                        EchProbeVerdict.ECH_TRANSPORT_ERROR,
                        EchTlsStage.CONNECT,
                        errorDetail = "TRANSPORT_ERROR",
                    ),
                    EchBindingAttempt(
                        HttpsRrSource.SYSTEM,
                        EchTlsEndpoint("origin.example", "second.example", 8443),
                        EchProbeVerdict.ECH_OK,
                        EchTlsStage.TLS,
                        "203.0.113.9:8443",
                        12L,
                        retryAttempted = true,
                    ),
                ),
                result.bindingAttempts,
            )
        }

    @Test
    fun stalledBindingsUseBoundedAttemptsAndDoNotDescribeUntestedBindings() =
        runTest {
            val records = (1..5).map { HttpsRrRecord(it, "edge-$it.example", byteArrayOf(1)) }
            val probe =
                EchReadinessProbe(
                    resolver =
                        HttpsRrResolver(
                            query = HttpsRrQuery { HttpsRrResponse(HttpsRrOutcome.ANSWER, records) },
                            fallbackQuery = null,
                        ),
                    tlsHandshake =
                        object : EchTlsHandshake {
                            override suspend fun connect(
                                endpoint: EchTlsEndpoint,
                                echConfig: ByteArray,
                            ): EchTlsHandshakeResult = awaitCancellation()
                        },
                )

            val result = probe.check("origin.example")

            assertEquals(EchProbeVerdict.ECH_HANDSHAKE_INCONCLUSIVE, result.verdict)
            assertEquals(records.take(4).map { it.targetName }, result.bindingAttempts.map { it.endpoint.host })
            assertTrue(result.bindingAttempts.all { it.verdict == EchProbeVerdict.ECH_TIMEOUT })
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
