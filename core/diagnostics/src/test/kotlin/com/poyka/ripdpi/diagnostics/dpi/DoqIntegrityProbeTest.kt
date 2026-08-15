package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
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
    fun wireFormatResponseParsedIntoIpList() {
        val transactionId = 1234
        val response = dnsResponse(transactionId, "1.2.3.4")

        assertEquals(listOf("1.2.3.4"), DoqWireCodec.parseResponse(response, transactionId))
    }

    private fun probeWithResponse(ip: String): DoqIntegrityProbe =
        probe(
            client =
                object : DoqQuicClient {
                    override suspend fun exchange(
                        endpoint: String,
                        port: Int,
                        tlsServerName: String,
                        query: ByteArray,
                        timeoutMs: Long,
                    ): ByteArray = dnsResponse(query.transactionId(), ip)
                },
        )

    private fun probe(
        client: DoqQuicClient,
        providers: List<DoqProvider> = listOf(DoqProvider("test", "doq.test")),
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
    ): ByteArray {
        val question =
            DnsWireBuilder
                .buildQuery(TestDomain, transactionId)
                .copyOfRange(12, 12 + TestDomain.length + 6)
        val body =
            ByteArrayOutputStream()
                .apply {
                    write((transactionId shr Byte.SIZE_BITS) and 0xFF)
                    write(transactionId and 0xFF)
                    write(0x81)
                    write(0x80)
                    write(0x00)
                    write(0x01)
                    write(0x00)
                    write(0x01)
                    write(0x00)
                    write(0x00)
                    write(0x00)
                    write(0x00)
                    write(question)
                    write(0xC0)
                    write(0x0C)
                    write(0x00)
                    write(0x01)
                    write(0x00)
                    write(0x01)
                    write(0x00)
                    write(0x00)
                    write(0x00)
                    write(0x3C)
                    write(0x00)
                    write(0x04)
                    ip.split('.').forEach { octet -> write(octet.toInt()) }
                }.toByteArray()
        return ByteArrayOutputStream(body.size + 2)
            .apply {
                write((body.size shr Byte.SIZE_BITS) and 0xFF)
                write(body.size and 0xFF)
                write(body)
            }.toByteArray()
    }

    private companion object {
        private const val TestDomain = "example.com"
    }
}
