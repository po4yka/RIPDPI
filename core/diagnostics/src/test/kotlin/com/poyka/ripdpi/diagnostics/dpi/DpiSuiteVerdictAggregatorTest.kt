package com.poyka.ripdpi.diagnostics.dpi

import org.junit.Assert.assertEquals
import org.junit.Test

class DpiSuiteVerdictAggregatorTest {
    @Test
    fun cleanWhenAllProbesOk() {
        val aggregate =
            DpiSuiteVerdictAggregator.aggregate(
                results =
                    listOf(
                        dnsIntegrity(verdict = DnsIntegrityVerdict.DNS_OK),
                        domainReachability(verdict = DomainVerdict.OK),
                        tcp16(verdict = Tcp16Verdict.OK),
                        telegram(verdict = TelegramTestVerdict.OK),
                    ),
            )

        assertEquals(SuiteVerdict.CLEAN, aggregate.verdict)
    }

    @Test
    fun dpiDetectedWhenTcp16HasBlockedAsns() {
        val aggregate =
            DpiSuiteVerdictAggregator.aggregate(
                results =
                    listOf(
                        dnsIntegrity(verdict = DnsIntegrityVerdict.DNS_OK),
                        tcp16(verdict = Tcp16Verdict.DETECTED_AT_KB),
                    ),
            )

        assertEquals(SuiteVerdict.DPI_DETECTED, aggregate.verdict)
    }

    @Test
    fun dnsInterferenceWhenSubstitutionDetected() {
        val aggregate =
            DpiSuiteVerdictAggregator.aggregate(
                results = listOf(dnsIntegrity(verdict = DnsIntegrityVerdict.DNS_SUBSTITUTION)),
            )

        assertEquals(SuiteVerdict.DNS_INTERFERENCE, aggregate.verdict)
    }

    @Test
    fun throttlingWhenTelegramStalled() {
        val aggregate =
            DpiSuiteVerdictAggregator.aggregate(
                results = listOf(telegram(verdict = TelegramTestVerdict.SLOW)),
            )

        assertEquals(SuiteVerdict.THROTTLING, aggregate.verdict)
    }

    @Test
    fun mixedWhenDnsAndTcp16BothBlocked() {
        val aggregate =
            DpiSuiteVerdictAggregator.aggregate(
                results =
                    listOf(
                        dnsIntegrity(verdict = DnsIntegrityVerdict.DNS_INTERCEPTION),
                        tcp16(verdict = Tcp16Verdict.DETECTED_AT_KB),
                    ),
            )

        assertEquals(SuiteVerdict.MIXED, aggregate.verdict)
    }

    @Test
    fun inconclusiveWhenThreeOfSixProbesErrored() {
        val aggregate =
            DpiSuiteVerdictAggregator.aggregate(
                results =
                    listOf(
                        DpiSuiteProbeResult.Failed(DpiProbeKind.DNS_INTEGRITY, "failed"),
                        DpiSuiteProbeResult.Failed(DpiProbeKind.DOMAIN_REACHABILITY, "failed"),
                        DpiSuiteProbeResult.Failed(DpiProbeKind.TCP16, "failed"),
                        telegram(verdict = TelegramTestVerdict.OK),
                        tcp16(verdict = Tcp16Verdict.OK),
                        domainReachability(verdict = DomainVerdict.OK),
                    ),
            )

        assertEquals(SuiteVerdict.INCONCLUSIVE, aggregate.verdict)
    }

    private fun dnsIntegrity(verdict: DnsIntegrityVerdict): DpiSuiteProbeResult.DnsIntegrity =
        DpiSuiteProbeResult.DnsIntegrity(
            DnsIntegrityResult(
                domains =
                    listOf(
                        DnsIntegrityDomainResult(
                            domain = "example.com",
                            verdict = verdict,
                            udpRecords = if (verdict == DnsIntegrityVerdict.DNS_OK) listOf("1.1.1.1") else emptyList(),
                            dohJsonIps = setOf("1.1.1.1"),
                            dohWireIps = setOf("1.1.1.1"),
                        ),
                    ),
                stubIps = emptySet(),
                dohBlocked = if (verdict == DnsIntegrityVerdict.DOH_BLOCKED) 1 else 0,
            ),
        )

    private fun domainReachability(verdict: DomainVerdict): DpiSuiteProbeResult.DomainReachability =
        DpiSuiteProbeResult.DomainReachability(
            listOf(
                DomainReachabilityResult(
                    domain = "example.com",
                    resolvedIps = listOf("1.1.1.1"),
                    tls13 = AttemptResult(AttemptStatus.OK),
                    tls12 = AttemptResult(AttemptStatus.OK),
                    http = AttemptResult(AttemptStatus.OK),
                    verdict = verdict,
                ),
            ),
        )

    private fun tcp16(verdict: Tcp16Verdict): DpiSuiteProbeResult.Tcp16 =
        DpiSuiteProbeResult.Tcp16(
            listOf(
                Tcp16ProbeResult(
                    targetId = "target",
                    asn = "AS1",
                    provider = "provider",
                    ip = "1.1.1.1",
                    port = 443,
                    alive = true,
                    verdict = verdict,
                    detectedAtKb = if (verdict == Tcp16Verdict.DETECTED_AT_KB) 16 else null,
                    measuredRttMs = 20,
                    errorDetail = null,
                    connectionCount = 1,
                ),
            ),
        )

    private fun telegram(verdict: TelegramTestVerdict): DpiSuiteProbeResult.Telegram =
        DpiSuiteProbeResult.Telegram(
            TelegramTestResult(
                verdict = verdict,
                download = ProbeStats(TelegramProbeStatus.OK, 100, 10, 10_000, 100),
                upload = ProbeStats(TelegramProbeStatus.OK, 100, 10, 10_000, 100),
                dcResults = listOf(DcReachability("DC1", "1.1.1.1", true, 10)),
            ),
        )
}
