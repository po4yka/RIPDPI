package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.diagnostics.DnsResponseOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class DoqIntegrityProbeTest {
    @Test
    fun successfulDoqWithMatchingDohReturnsOk() =
        runTest {
            val probe = probeWithResponse("1.2.3.4")

            val result = probe.run(listOf(TestDomain), mapOf(TestDomain to listOf("1.2.3.4"))).single()

            assertEquals(DoqVerdict.DOQ_OK, result.verdict)
            assertEquals(listOf("1.2.3.4"), result.resolvedIps)
        }

    @Test
    fun positiveReplyWithNoDohBaselineIsInconclusive() =
        runTest {
            val result = probeWithResponse("1.2.3.4").run(listOf(TestDomain), emptyMap()).single()

            assertEquals(DoqVerdict.DOQ_INTEGRITY_INCONCLUSIVE, result.verdict)
            assertEquals(DnsResponseOutcome.ANSWER, result.response?.outcome)
            assertEquals(listOf("1.2.3.4"), result.resolvedIps)
        }

    @Test
    fun negativeAndErrorResponsesPreserveTheirDnsOutcome() =
        runTest {
            val outcomes =
                mapOf(
                    0 to DnsResponseOutcome.NODATA,
                    1 to DnsResponseOutcome.OTHER_RCODE,
                    2 to DnsResponseOutcome.SERVFAIL,
                    3 to DnsResponseOutcome.NXDOMAIN,
                    4 to DnsResponseOutcome.OTHER_RCODE,
                    5 to DnsResponseOutcome.REFUSED,
                )
            outcomes.forEach { (rcode, outcome) ->
                val result =
                    probeWithResponse(ip = null, rcode = rcode)
                        .run(listOf(TestDomain), mapOf(TestDomain to listOf("1.2.3.4")))
                        .single()

                assertEquals(DoqVerdict.DOQ_INTEGRITY_INCONCLUSIVE, result.verdict)
                assertEquals(outcome, result.response?.outcome)
                assertEquals(rcode, result.response?.rcode)
                assertEquals(emptyList<String>(), result.resolvedIps)
            }
        }

    @Test
    fun invalidDomainIsSkippedWithoutAbortingTheOtherDomains() =
        runTest {
            val probe = probeWithResponse("1.2.3.4")

            val results = probe.run(listOf("x.com/i/api/1.1", TestDomain), mapOf(TestDomain to listOf("1.2.3.4")))

            assertEquals(listOf(TestDomain), results.map { it.domain })
            assertEquals(DoqVerdict.DOQ_OK, results.single().verdict)
        }

    @Test
    fun quicHandshakeFailureReturnsBlockedQuic() =
        runTest {
            val probe =
                probe(
                    client = ThrowingDoqClient(IOException("no Initial ACK")),
                )

            val result = probe.run(listOf(TestDomain), emptyMap()).single()

            assertEquals(DoqVerdict.DOQ_BLOCKED_QUIC, result.verdict)
            assertEquals("no Initial ACK", result.errorDetail)
        }

    @Test
    fun tlsAlertReturnsDpiReject() =
        runTest {
            val probe =
                probe(
                    client = ThrowingDoqClient(DoqTlsRejectException("handshake_failure")),
                )

            val result = probe.run(listOf(TestDomain), emptyMap()).single()

            assertEquals(DoqVerdict.DOQ_DPI_REJECT, result.verdict)
            assertEquals("handshake_failure", result.errorDetail)
        }

    @Test
    fun divergentIpsReturnIntegrityDivergent() =
        runTest {
            val probe = probeWithResponse("1.2.3.4")

            val result = probe.run(listOf(TestDomain), mapOf(TestDomain to listOf("5.6.7.8"))).single()

            assertEquals(DoqVerdict.DOQ_INTEGRITY_DIVERGENT, result.verdict)
        }

    @Test
    fun timeoutReturnsDoqTimeout() =
        runTest {
            val probe =
                probe(
                    client =
                        object : DoqQuicClient {
                            override suspend fun exchange(
                                endpoint: String,
                                port: Int,
                                tlsServerName: String,
                                query: ByteArray,
                                timeoutMs: Long,
                            ): ByteArray {
                                delay(Long.MAX_VALUE)
                                return ByteArray(0)
                            }
                        },
                    timeoutMs = 10,
                )

            val result = probe.run(listOf(TestDomain), emptyMap()).single()

            assertEquals(DoqVerdict.DOQ_TIMEOUT, result.verdict)
        }

    @Test
    fun concurrencyCappedAtEight() =
        runTest {
            val mutex = Mutex()
            var inFlight = 0
            var maxInFlight = 0
            val probe =
                probe(
                    client =
                        object : DoqQuicClient {
                            override suspend fun exchange(
                                endpoint: String,
                                port: Int,
                                tlsServerName: String,
                                query: ByteArray,
                                timeoutMs: Long,
                            ): ByteArray {
                                val transactionId = query.transactionId()
                                mutex.withLock {
                                    inFlight += 1
                                    maxInFlight = maxOf(maxInFlight, inFlight)
                                }
                                delay(25)
                                mutex.withLock {
                                    inFlight -= 1
                                }
                                return dnsResponse(transactionId, "1.2.3.4")
                            }
                        },
                    providers = (1..12).map { index -> DoqProvider("p$index", "p$index.test") },
                )

            probe.run(listOf(TestDomain), mapOf(TestDomain to listOf("1.2.3.4")))

            assertEquals(8, maxInFlight)
        }

    @Test
    fun defaultProvidersPreserveTlsIdentityForIpEndpoints() {
        val providers = DoqIntegrityProbe.DefaultDoqProviders.associateBy { it.name }

        assertEquals("dns.adguard-dns.com", providers.getValue("AdGuard").tlsServerName)
        assertEquals("cloudflare-dns.com", providers.getValue("Cloudflare").tlsServerName)
        assertEquals("dns.google", providers.getValue("Google").tlsServerName)
        assertEquals("dns.nextdns.io", providers.getValue("NextDNS").tlsServerName)
    }

    @Test
    fun probePassesTlsServerNameToClient() =
        runTest {
            var observedTlsServerName: String? = null
            val provider = DoqProvider("cloudflare", "1.1.1.1", tlsServerName = "cloudflare-dns.com")
            val probe =
                probe(
                    providers = listOf(provider),
                    client =
                        object : DoqQuicClient {
                            override suspend fun exchange(
                                endpoint: String,
                                port: Int,
                                tlsServerName: String,
                                query: ByteArray,
                                timeoutMs: Long,
                            ): ByteArray {
                                observedTlsServerName = tlsServerName
                                return dnsResponse(query.transactionId(), "1.2.3.4")
                            }
                        },
                )

            probe.run(listOf(TestDomain), mapOf(TestDomain to listOf("1.2.3.4")))

            assertEquals("cloudflare-dns.com", observedTlsServerName)
        }

    @Test
    fun wireFormatQueryIsLengthPrefixed() {
        val (transactionId, query) = DoqWireCodec.buildQuery(TestDomain)
        val length = ((query[0].toInt() and 0xFF) shl Byte.SIZE_BITS) or (query[1].toInt() and 0xFF)

        assertEquals(query.size - 2, length)
        assertEquals(transactionId, query.transactionId())
    }

    @Test
    fun truncatedResponseCannotBecomeSuccessfulDoqEvidence() {
        val (transactionId, query) = DoqWireCodec.buildQuery(TestDomain)
        val response = dnsResponse(transactionId, "1.2.3.4").also { it[4] = 0x83.toByte() }
        assertEquals(DnsResponseOutcome.TRUNCATED, DoqWireCodec.parseResponse(response, query).response?.outcome)
    }

    @Test
    fun wireFormatResponseParsedIntoIpList() {
        val (transactionId, query) = DoqWireCodec.buildQuery(TestDomain)
        val response = dnsResponse(transactionId, "1.2.3.4")

        assertEquals(listOf("1.2.3.4"), DoqWireCodec.parseResponse(response, query).addresses)
    }

    @Test
    fun malformedResponseDoesNotReportDoqSuccess() =
        runTest {
            val client =
                object : DoqQuicClient {
                    override suspend fun exchange(
                        endpoint: String,
                        port: Int,
                        tlsServerName: String,
                        query: ByteArray,
                        timeoutMs: Long,
                    ): ByteArray = byteArrayOf(0, 1, 0)
                }
            val result = probe(client).run(listOf(TestDomain), emptyMap()).single()
            assertEquals(DoqVerdict.DOQ_BLOCKED_QUIC, result.verdict)
            assertEquals("Malformed DoQ response", result.errorDetail)
        }

    @Test
    fun wireResponseRejectsAnotherQuestionAndTrailingFrameBytes() {
        val (transactionId, query) = DoqWireCodec.buildQuery("other.example")
        val response = dnsResponse(transactionId, "1.2.3.4")
        assertEquals(DnsResponseOutcome.MALFORMED, DoqWireCodec.parseResponse(response, query).response?.outcome)

        val (matchingId, matchingQuery) = DoqWireCodec.buildQuery(TestDomain)
        val matchingResponse = dnsResponse(matchingId, "1.2.3.4")
        assertEquals(
            DnsResponseOutcome.MALFORMED,
            DoqWireCodec.parseResponse(matchingResponse + byteArrayOf(0), matchingQuery).response?.outcome,
        )
    }

    private fun probeWithResponse(
        ip: String?,
        rcode: Int = 0,
    ): DoqIntegrityProbe =
        probe(
            client =
                object : DoqQuicClient {
                    override suspend fun exchange(
                        endpoint: String,
                        port: Int,
                        tlsServerName: String,
                        query: ByteArray,
                        timeoutMs: Long,
                    ): ByteArray =
                        DoqIntegrityProbeTestFixtures.dnsResponse(TestDomain, query.transactionId(), ip, rcode)
                },
        )

    private fun probe(
        client: DoqQuicClient,
        providers: List<DoqProvider> =
            listOf(DoqProvider("test", TestResolver, dohProfile = DohResolverProfile.CLOUDFLARE_STANDARD)),
        timeoutMs: Long = 1_000,
    ): DoqIntegrityProbe =
        DoqIntegrityProbe(
            client = client,
            providers = providers,
            timeoutMs = timeoutMs,
        )

    private class ThrowingDoqClient(
        private val error: Exception,
    ) : DoqQuicClient {
        override suspend fun exchange(
            endpoint: String,
            port: Int,
            tlsServerName: String,
            query: ByteArray,
            timeoutMs: Long,
        ): ByteArray = throw error
    }

    private fun ByteArray.transactionId(): Int =
        ((this[2].toInt() and 0xFF) shl Byte.SIZE_BITS) or (this[3].toInt() and 0xFF)

    private fun dnsResponse(
        transactionId: Int,
        ip: String,
    ): ByteArray = DoqIntegrityProbeTestFixtures.dnsResponse(TestDomain, transactionId, ip)

    private companion object {
        private const val TestDomain = "example.com"
    }
}
