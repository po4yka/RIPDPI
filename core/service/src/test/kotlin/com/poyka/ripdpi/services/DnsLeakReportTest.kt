package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD: [DnsLeakReport] compares expected vs observed resolver path and returns a [DnsLeakVerdict].
 */
class DnsLeakReportTest {
    @Test
    fun equalExpectedAndObservedYieldsPass() {
        val report =
            DnsLeakReport(
                expected = ResolverSource.Proxy,
                observed = ResolverSource.Proxy,
            )
        assertEquals(DnsLeakVerdict.Pass, report.verdict())
    }

    @Test
    fun divergentObservedYieldsLeak() {
        val report =
            DnsLeakReport(
                expected = ResolverSource.Proxy,
                observed = ResolverSource.Direct,
            )
        val verdict = report.verdict()
        assertTrue(verdict is DnsLeakVerdict.Leak)
    }

    @Test
    fun leakVerdictCarriesDivergenceDetails() {
        val report =
            DnsLeakReport(
                expected = ResolverSource.Proxy,
                observed = ResolverSource.Direct,
            )
        val leak = report.verdict() as DnsLeakVerdict.Leak
        assertEquals(ResolverSource.Proxy, leak.expected)
        assertEquals(ResolverSource.Direct, leak.observed)
    }

    @Test
    fun directExpectedDirectObservedIsPass() {
        val report =
            DnsLeakReport(
                expected = ResolverSource.Direct,
                observed = ResolverSource.Direct,
            )
        assertEquals(DnsLeakVerdict.Pass, report.verdict())
    }

    @Test
    fun ipv6ExpectedProxyObservedIsLeak() {
        val report =
            DnsLeakReport(
                expected = ResolverSource.Ipv6,
                observed = ResolverSource.Proxy,
            )
        assertTrue(report.verdict() is DnsLeakVerdict.Leak)
    }

    @Test
    fun captiveExpectedCaptiveObservedIsPass() {
        val report =
            DnsLeakReport(
                expected = ResolverSource.Captive,
                observed = ResolverSource.Captive,
            )
        assertEquals(DnsLeakVerdict.Pass, report.verdict())
    }
}
