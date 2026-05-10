package com.poyka.ripdpi.diagnostics.rkn

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

class SystemDohDnsComparatorTest {
    @Test
    fun systemFailsDohSucceedsReturnsDnsBlockHigh() =
        runTest {
            val result =
                comparator(
                    system = { throw UnknownHostException("blocked") },
                    doh = { setOf("1.2.3.4") },
                ).compare("example.org")

            assertEquals(DnsComparisonVerdict.DNS_BLOCK, result.verdict)
            assertEquals(DnsComparisonConfidence.HIGH, result.confidence)
        }

    @Test
    fun bothFailReturnsDownLow() =
        runTest {
            val result =
                comparator(
                    system = { throw UnknownHostException("blocked") },
                    doh = { emptySet() },
                ).compare("example.org")

            assertEquals(DnsComparisonVerdict.DOWN, result.verdict)
            assertEquals(DnsComparisonConfidence.LOW, result.confidence)
        }

    @Test
    fun systemSucceedsDohFailsReturnsOkWithNote() =
        runTest {
            val result =
                comparator(
                    system = { setOf("1.2.3.4") },
                    doh = { emptySet() },
                ).compare("example.org")

            assertEquals(DnsComparisonVerdict.OK, result.verdict)
            assertTrue(result.notes.any { it.contains("DoH lookup failed") })
        }

    @Test
    fun disjointSetsReturnsDnsRewriteMedium() =
        runTest {
            val result =
                comparator(
                    system = { setOf("1.2.3.4") },
                    doh = { setOf("5.6.7.8") },
                ).compare("example.org")

            assertEquals(DnsComparisonVerdict.DNS_REWRITE, result.verdict)
            assertEquals(DnsComparisonConfidence.MEDIUM, result.confidence)
        }

    @Test
    fun sharedIpMeansNoFlagEvenIfOtherIpsDiffer() =
        runTest {
            val result =
                comparator(
                    system = { setOf("1.2.3.4", "9.9.9.9") },
                    doh = { setOf("1.2.3.4", "5.6.7.8") },
                ).compare("example.org")

            assertEquals(DnsComparisonVerdict.OK, result.verdict)
        }

    @Test
    fun multiARecordLoadBalancingNotFlagged() =
        runTest {
            val result =
                comparator(
                    system = { setOf("1.1.1.1", "2.2.2.2", "3.3.3.3") },
                    doh = { setOf("3.3.3.3", "1.1.1.1", "2.2.2.2") },
                ).compare("example.org")

            assertEquals(DnsComparisonVerdict.OK, result.verdict)
        }

    @Test
    fun firstIpFieldIsLowestSorted() =
        runTest {
            val result =
                comparator(
                    system = { setOf("9.9.9.9", "1.1.1.1") },
                    doh = { setOf("8.8.8.8", "4.4.4.4") },
                ).compare("example.org")

            assertEquals("1.1.1.1", result.sysIp)
            assertEquals("4.4.4.4", result.dohIp)
        }

    @Test
    fun privateDnsMethodIsReported() =
        runTest {
            val result =
                comparator(
                    system = { setOf("1.2.3.4") },
                    doh = { setOf("1.2.3.4") },
                    method = DnsMethod.PRIVATE_DNS_DOT,
                ).compare("example.org")

            assertEquals(DnsMethod.PRIVATE_DNS_DOT, result.dnsMethod)
        }

    private fun comparator(
        system: suspend (String) -> Set<String>,
        doh: suspend (String) -> Set<String>,
        method: DnsMethod = DnsMethod.SYSTEM,
    ): SystemDohDnsComparator =
        SystemDohDnsComparator(
            systemResolver =
                object : Ipv4Resolver {
                    override suspend fun resolve(host: String): Set<String> = system(host)
                },
            dohResolver =
                object : Ipv4Resolver {
                    override suspend fun resolve(host: String): Set<String> = doh(host)
                },
            dnsMethodProvider = { method },
        )
}
