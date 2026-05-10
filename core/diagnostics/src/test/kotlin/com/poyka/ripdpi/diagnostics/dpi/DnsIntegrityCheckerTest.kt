package com.poyka.ripdpi.diagnostics.dpi

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

    private fun checker(
        udp: suspend (String) -> List<String>,
        dohJson: suspend (String) -> Set<String>,
        dohWire: suspend (String) -> Set<String>,
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
        )
}
