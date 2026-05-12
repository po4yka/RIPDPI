package com.poyka.ripdpi.diagnostics.dpich

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.net.ServerSocket
import java.security.Principal
import java.security.PublicKey
import java.security.cert.CertificateEncodingException
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import javax.security.auth.x500.X500Principal as JavaX500Principal

class TlsCertSniDiscovererTest {
    @Test
    fun extractsMultipleSanDnsNames() =
        runTest {
            val discoverer =
                TlsCertSniDiscoverer(
                    fetcher =
                        TlsPeerCertificateFetcher { _, _, _ ->
                            FakeCertificate(
                                subjectAltNames =
                                    listOf(
                                        listOf(2, "a.example.com"),
                                        listOf(2, "b.example.com"),
                                    ),
                            )
                        },
                )

            val result = discoverer.discover("192.0.2.10")

            assertEquals(listOf("a.example.com", "b.example.com"), result.subjectAltNames)
            assertEquals(null, result.error)
        }

    @Test
    fun extractsWildcardSanUnchanged() =
        runTest {
            val discoverer =
                TlsCertSniDiscoverer(
                    fetcher =
                        TlsPeerCertificateFetcher { _, _, _ ->
                            FakeCertificate(subjectAltNames = listOf(listOf(2, "*.cloudflare.com")))
                        },
                )

            val result = discoverer.discover("192.0.2.11")

            assertEquals(listOf("*.cloudflare.com"), result.subjectAltNames)
        }

    @Test
    fun fallsBackToCommonNameWhenNoSans() =
        runTest {
            val discoverer =
                TlsCertSniDiscoverer(
                    fetcher =
                        TlsPeerCertificateFetcher { _, _, _ ->
                            FakeCertificate(subject = "CN=example.com,O=Example")
                        },
                )

            val result = discoverer.discover("192.0.2.12")

            assertEquals("example.com", result.commonName)
            assertEquals(emptyList<String>(), result.subjectAltNames)
            assertEquals(listOf("example.com"), result.hostnames)
        }

    @Test
    fun tlsFailureReturnsErrorNoException() =
        runTest {
            val discoverer =
                TlsCertSniDiscoverer(
                    fetcher =
                        TlsPeerCertificateFetcher { _, _, _ ->
                            error("handshake failed")
                        },
                )

            val result = discoverer.discover("192.0.2.13")

            assertEquals("192.0.2.13", result.ip)
            assertEquals(null, result.commonName)
            assertEquals(emptyList<String>(), result.subjectAltNames)
            assertEquals("handshake failed", result.error)
        }

    @Test
    fun nonDnsSanTypesFilteredOut() =
        runTest {
            val discoverer =
                TlsCertSniDiscoverer(
                    fetcher =
                        TlsPeerCertificateFetcher { _, _, _ ->
                            FakeCertificate(
                                subjectAltNames =
                                    listOf(
                                        listOf(2, "dns.example"),
                                        listOf(7, "192.0.2.44"),
                                        listOf(1, "admin@example.com"),
                                    ),
                            )
                        },
                )

            val result = discoverer.discover("192.0.2.14")

            assertEquals(listOf("dns.example"), result.subjectAltNames)
        }

    @Test
    fun concurrentDiscoverBatchRespectsWorkerCap() =
        runTest {
            val inFlight = AtomicInteger()
            val maxInFlight = AtomicInteger()
            val discoverer =
                TlsCertSniDiscoverer(
                    fetcher =
                        TlsPeerCertificateFetcher { ip, _, _ ->
                            val active = inFlight.incrementAndGet()
                            maxInFlight.updateAndGet { current -> maxOf(current, active) }
                            try {
                                Thread.sleep(10)
                                FakeCertificate(subject = "CN=$ip.example")
                            } finally {
                                inFlight.decrementAndGet()
                            }
                        },
                )

            val results = discoverer.discoverBatch((1..20).map { index -> "192.0.2.$index" }, workers = 4)

            assertEquals(20, results.size)
            assertTrue(maxInFlight.get() <= 4)
        }

    @Test
    fun discovererPassesOnlyIpPortAndTimeoutToFetcher() =
        runTest {
            var requested: Triple<String, Int, Long>? = null
            val discoverer =
                TlsCertSniDiscoverer(
                    fetcher =
                        TlsPeerCertificateFetcher { ip, port, timeout ->
                            requested = Triple(ip, port, timeout)
                            FakeCertificate(subject = "CN=default.example")
                        },
                )

            discoverer.discover(ip = "203.0.113.1", port = 8443, timeoutMs = 1_234)

            assertEquals(Triple("203.0.113.1", 8443, 1_234L), requested)
        }

    @Test
    fun noSniFetcherDoesNotSendServerNameExtension() {
        ServerSocket(0).use { server ->
            val clientHello = ByteArray(4096)
            val bytesRead = AtomicInteger()
            val acceptThread =
                thread(start = true) {
                    server.accept().use { socket ->
                        socket.soTimeout = 1_000
                        bytesRead.set(socket.getInputStream().read(clientHello))
                    }
                }

            runCatching {
                NoSniTlsPeerCertificateFetcher().fetch(
                    ip = "127.0.0.1",
                    port = server.localPort,
                    timeoutMs = 1_000,
                )
            }
            acceptThread.join(2_000)

            val captured = clientHello.copyOf(bytesRead.get().coerceAtLeast(0))
            assertTrue(captured.isNotEmpty())
            assertEquals(false, clientHelloContainsServerNameExtension(captured))
        }
    }

    @Test
    fun webhostFarmIntegrationEnrichesResults() =
        runTest {
            val farm =
                WebhostFarm(
                    probe =
                        WebhostProbe { _, _, _, _, _ ->
                            WebhostProbeResult(tcpOk = true, tlsOk = true, tcpTimeMs = 1, tlsTimeMs = 2)
                        },
                    random = kotlin.random.Random(1),
                )
            val discoverer =
                TlsCertSniDiscoverer(
                    fetcher =
                        TlsPeerCertificateFetcher { ip, _, _ ->
                            FakeCertificate(subject = "CN=$ip.example")
                        },
                )

            val hosts =
                farm.discoverWithCertHostnames(
                    subnets = setOf(IpRange("192.0.2.0/30")),
                    count = 1,
                    workers = 1,
                    certDiscoverer = discoverer,
                )

            assertEquals(listOf("${hosts.single().ip}.example"), hosts.single().certHostnames)
        }

    private fun clientHelloContainsServerNameExtension(bytes: ByteArray): Boolean {
        if (bytes.size < 5 || bytes[0] != 0x16.toByte()) return false
        val recordLength = bytes.readU16(3)
        val recordEnd = (5 + recordLength).coerceAtMost(bytes.size)
        var index = 5
        if (index + 4 > recordEnd || bytes[index] != 0x01.toByte()) return false
        val handshakeLength = bytes.readU24(index + 1)
        index += 4
        val handshakeEnd = (index + handshakeLength).coerceAtMost(recordEnd)
        index += 2 // legacy_version
        index += 32 // random
        if (index + 1 > handshakeEnd) return false
        val sessionIdLength = bytes[index].toInt() and 0xff
        index += 1 + sessionIdLength
        if (index + 2 > handshakeEnd) return false
        val cipherSuitesLength = bytes.readU16(index)
        index += 2 + cipherSuitesLength
        if (index + 1 > handshakeEnd) return false
        val compressionMethodsLength = bytes[index].toInt() and 0xff
        index += 1 + compressionMethodsLength
        if (index + 2 > handshakeEnd) return false
        val extensionsEnd = (index + 2 + bytes.readU16(index)).coerceAtMost(handshakeEnd)
        index += 2
        while (index + 4 <= extensionsEnd) {
            val extensionType = bytes.readU16(index)
            val extensionLength = bytes.readU16(index + 2)
            if (extensionType == 0) return true
            index += 4 + extensionLength
        }
        return false
    }

    private fun ByteArray.readU16(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)

    private fun ByteArray.readU24(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 16) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            (this[offset + 2].toInt() and 0xff)

    private class FakeCertificate(
        private val subject: String = "CN=unused.example",
        private val subjectAltNames: Collection<List<Any>>? = null,
    ) : X509Certificate() {
        override fun getSubjectAlternativeNames(): Collection<List<Any>>? = subjectAltNames

        override fun getSubjectX500Principal(): JavaX500Principal = JavaX500Principal(subject)

        override fun checkValidity() = Unit

        override fun checkValidity(date: Date?) = Unit

        override fun getVersion(): Int = 3

        override fun getSerialNumber(): BigInteger = BigInteger.ONE

        override fun getIssuerDN(): Principal = Principal { "CN=issuer" }

        override fun getSubjectDN(): Principal = Principal { subject }

        override fun getNotBefore(): Date = Date(0)

        override fun getNotAfter(): Date = Date(Long.MAX_VALUE)

        override fun getTBSCertificate(): ByteArray = throw CertificateEncodingException()

        override fun getSignature(): ByteArray = ByteArray(0)

        override fun getSigAlgName(): String = "none"

        override fun getSigAlgOID(): String = "0.0"

        override fun getSigAlgParams(): ByteArray? = null

        override fun getIssuerUniqueID(): BooleanArray? = null

        override fun getSubjectUniqueID(): BooleanArray? = null

        override fun getKeyUsage(): BooleanArray? = null

        override fun getBasicConstraints(): Int = -1

        override fun getEncoded(): ByteArray = ByteArray(0)

        override fun verify(key: PublicKey?) = Unit

        override fun verify(
            key: PublicKey?,
            sigProvider: String?,
        ) = Unit

        override fun toString(): String = subject

        override fun getPublicKey(): PublicKey? = null

        override fun hasUnsupportedCriticalExtension(): Boolean = false

        override fun getCriticalExtensionOIDs(): Set<String>? = null

        override fun getNonCriticalExtensionOIDs(): Set<String>? = null

        override fun getExtensionValue(oid: String?): ByteArray? = null
    }
}
