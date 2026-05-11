package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.diagnostics.dpich.BootstrapVerdict
import com.poyka.ripdpi.diagnostics.dpich.DohBootstrapSpoofingDetector
import com.poyka.ripdpi.diagnostics.dpich.DohProviderFilter
import com.poyka.ripdpi.diagnostics.dpich.FakeSubnetMetadataLookup
import com.poyka.ripdpi.diagnostics.dpich.IpRange
import com.poyka.ripdpi.diagnostics.dpich.SubnetMetadata
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class DnsIntegrityCheckerTest {
    @Test
    fun substitutionDetectedWhenUdpAndDohIpsDiffer() =
        runTest {
            val result =
                checker(
                    udp = { listOf("1.2.3.4") },
                    dohJson = { setOf("5.6.7.8") },
                    dohWire = { setOf("5.6.7.8") },
                ).check(listOf("blocked.example"))

            assertEquals(DnsIntegrityVerdict.DNS_SUBSTITUTION, result.domains.single().verdict)
        }

    @Test
    fun interceptionDetectedWhenUdpTimesOutAndDohSucceeds() =
        runTest {
            val result =
                checker(
                    udp = { throw SocketTimeoutException("timed out") },
                    dohJson = { setOf("5.6.7.8") },
                    dohWire = { setOf("5.6.7.8") },
                ).check(listOf("blocked.example"))

            assertEquals(DnsIntegrityVerdict.DNS_INTERCEPTION, result.domains.single().verdict)
        }

    @Test
    fun fakeIpDetectedWhenUdpReturns19818Range() =
        runTest {
            val result =
                checker(
                    udp = { listOf("198.18.0.1") },
                    dohJson = { setOf("5.6.7.8") },
                    dohWire = { setOf("5.6.7.8") },
                ).check(listOf("blocked.example"))

            assertEquals(DnsIntegrityVerdict.FAKE_IP, result.domains.single().verdict)
        }

    @Test
    fun fakeNxdomainDetectedWhenUdpReturnsNxdomainAndDohSucceeds() =
        runTest {
            val result =
                checker(
                    udp = { listOf(DnsWireBuilder.NXDOMAIN) },
                    dohJson = { setOf("5.6.7.8") },
                    dohWire = { setOf("5.6.7.8") },
                ).check(listOf("blocked.example"))

            assertEquals(DnsIntegrityVerdict.FAKE_NXDOMAIN, result.domains.single().verdict)
        }

    @Test
    fun stubIpsCollectedFromRepeatedUdpResponses() =
        runTest {
            val result =
                checker(
                    udp = { listOf("203.0.113.10") },
                    dohJson = { setOf("203.0.113.20") },
                    dohWire = { setOf("203.0.113.20") },
                ).check(listOf("one.example", "two.example", "three.example"))

            assertTrue("203.0.113.10" in result.stubIps)
        }

    @Test
    fun dohBlockedCounterIncrementsWhenBothDohMethodsFail() =
        runTest {
            val result =
                checker(
                    udp = { listOf("1.2.3.4") },
                    dohJson = { emptySet() },
                    dohWire = { emptySet() },
                ).check(listOf("blocked.example"))

            assertEquals(1, result.dohBlocked)
            assertEquals(DnsIntegrityVerdict.DOH_BLOCKED, result.domains.single().verdict)
        }

    @Test
    fun doqProbeReceivesDohCrossCheckResults() =
        runTest {
            val result =
                checker(
                    udp = { listOf("1.2.3.4") },
                    dohJson = { setOf("5.6.7.8") },
                    dohWire = { setOf("5.6.7.8") },
                    doqProbe = probeWithResponse("9.9.9.9"),
                ).check(listOf("blocked.example"))

            assertEquals(DoqVerdict.DOQ_INTEGRITY_DIVERGENT, result.doqResults.single().verdict)
        }

    @Test
    fun spoofedBootstrapProvidersAreExcludedFromBootstrapAwareDohProbes() =
        runTest {
            val dohJson = RecordingBootstrapAwareProbe(setOf("5.6.7.8"))
            val dohWire = RecordingBootstrapAwareProbe(setOf("5.6.7.8"))

            val result =
                DnsIntegrityChecker(
                    udpProbe = DnsUdpProbe { listOf("5.6.7.8") },
                    dohJsonProbe = dohJson,
                    dohWireProbe = dohWire,
                    dohBootstrapDetector = spoofedGoogleBootstrapDetector(),
                ).check(listOf("blocked.example"))

            assertEquals(BootstrapVerdict.SPOOFED, result.dohBootstrapResults.single().verdict)
            assertEquals(setOf("dns.google"), dohJson.excludedHostnames)
            assertEquals(setOf("dns.google"), dohWire.excludedHostnames)
        }

    private fun checker(
        udp: suspend (String) -> List<String>,
        dohJson: suspend (String) -> Set<String>,
        dohWire: suspend (String) -> Set<String>,
        doqProbe: DoqIntegrityProbe? = null,
        dohBootstrapDetector: DohBootstrapSpoofingDetector? = null,
    ): DnsIntegrityChecker =
        DnsIntegrityChecker(
            udpProbe =
                object : DnsUdpProbe {
                    override suspend fun resolveA(domain: String): List<String> = udp(domain)
                },
            dohJsonProbe =
                object : DnsAddressProbe {
                    override suspend fun resolveA(domain: String): Set<String> = dohJson(domain)
                },
            dohWireProbe =
                object : DnsAddressProbe {
                    override suspend fun resolveA(domain: String): Set<String> = dohWire(domain)
                },
            doqProbe = doqProbe,
            dohBootstrapDetector = dohBootstrapDetector,
        )

    private fun probeWithResponse(ip: String): DoqIntegrityProbe =
        DoqIntegrityProbe(
            client =
                object : DoqQuicClient {
                    override suspend fun exchange(
                        endpoint: String,
                        port: Int,
                        query: ByteArray,
                        timeoutMs: Long,
                    ): ByteArray = dnsResponse(query.transactionId(), ip)
                },
            providers = listOf(DoqProvider("test", "doq.test")),
            timeoutMs = 1_000,
        )

    private fun ByteArray.transactionId(): Int =
        ((this[2].toInt() and 0xFF) shl Byte.SIZE_BITS) or (this[3].toInt() and 0xFF)

    private fun dnsResponse(
        transactionId: Int,
        ip: String,
    ): ByteArray = DoqIntegrityProbeTestFixtures.dnsResponse("blocked.example", transactionId, ip)

    private fun spoofedGoogleBootstrapDetector(): DohBootstrapSpoofingDetector =
        DohBootstrapSpoofingDetector(
            resolver = { listOf("1.2.3.4") },
            metadata =
                FakeSubnetMetadataLookup(
                    records =
                        listOf(
                            SubnetMetadata(IpRange("8.8.8.0/24"), asn = 15169, org = "Google LLC", country = "US"),
                            SubnetMetadata(IpRange("1.2.3.0/24"), asn = 12389, org = "Rostelecom", country = "RU"),
                        ),
                    ipToAsn = mapOf("1.2.3.4" to 12389),
                    ipToSubnet = mapOf("1.2.3.4" to IpRange("1.2.3.0/24")),
                ),
            providers = listOf(DohProviderFilter("Google", "dns.google", "as(15169)")),
        )

    private class RecordingBootstrapAwareProbe(
        private val result: Set<String>,
    ) : BootstrapFilterableDnsAddressProbe {
        var excludedHostnames: Set<String> = emptySet()
            private set

        override suspend fun resolveA(
            domain: String,
            excludedDohHostnames: Set<String>,
        ): Set<String> {
            excludedHostnames = excludedDohHostnames
            return result
        }

        override suspend fun resolveA(domain: String): Set<String> = result
    }
}
