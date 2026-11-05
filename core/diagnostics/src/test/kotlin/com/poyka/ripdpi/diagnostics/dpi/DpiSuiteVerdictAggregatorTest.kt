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
    fun dpiDetectedWhenQuicFingerprintIsBlocked() {
        val aggregate =
            DpiSuiteVerdictAggregator.aggregate(
                results = listOf(quicH3(verdict = QuicProbeVerdict.QUIC_DPI_FINGERPRINT_BLOCK)),
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

    @Test
    fun uncertainDnsEvidenceDoesNotBecomeInterference() {
        listOf(
            DnsIntegrityVerdict.DNS_DISAGREEMENT,
            DnsIntegrityVerdict.UDP_UNAVAILABLE,
            DnsIntegrityVerdict.ENCRYPTED_UNAVAILABLE,
            DnsIntegrityVerdict.DNS_NEGATIVE,
            DnsIntegrityVerdict.DOH_BLOCKED,
        ).forEach {
            assertEquals(
                SuiteVerdict.INCONCLUSIVE,
                DpiSuiteVerdictAggregator.aggregate(listOf(dnsIntegrity(it))).verdict,
            )
        }
    }

    @Test
    fun unavailableOrUnmeasuredProbesAreInconclusive() {
        val incomplete =
            listOf(
                quicH3(QuicProbeVerdict.QUIC_TIMEOUT),
                telegram(TelegramTestVerdict.BLOCKED),
                telegram(TelegramTestVerdict.ERROR),
                telegram(TelegramTestVerdict.PARTIAL),
                tcp16(Tcp16Verdict.DEAD),
                tcp16(Tcp16Verdict.ERROR),
                tcp16(Tcp16Verdict.INVALID_RECONNECTED),
                DpiSuiteProbeResult.Skipped(DpiProbeKind.QUIC_H3, "unavailable"),
                DpiSuiteProbeResult.DomainReachability(emptyList()),
                DpiSuiteProbeResult.DnsAvailability(emptyList()),
                DpiSuiteProbeResult.DnsAvailability(listOf(DnsServerResult("resolver", DnsServerType.UDP, 0, 2, null))),
                DpiSuiteProbeResult.DnsIntegrity(DnsIntegrityResult(emptyList(), emptySet(), 0)),
                DpiSuiteProbeResult.Tcp16(emptyList()),
                DpiSuiteProbeResult.QuicH3(emptyList()),
                DpiSuiteProbeResult.EchReadiness(emptyList()),
                DpiSuiteProbeResult.AllowlistSni(emptyMap()),
            )
        assertEquals(SuiteVerdict.INCONCLUSIVE, DpiSuiteVerdictAggregator.aggregate(emptyList()).verdict)
        incomplete.forEach { result ->
            assertEquals(
                result.toString(),
                SuiteVerdict.INCONCLUSIVE,
                DpiSuiteVerdictAggregator.aggregate(listOf(result)).verdict,
            )
            assertEquals(
                result.toString(),
                SuiteVerdict.INCONCLUSIVE,
                DpiSuiteVerdictAggregator.aggregate(listOf(domainReachability(DomainVerdict.OK), result)).verdict,
            )
        }
    }

    @Test
    fun genericTransportAndProtocolFailuresDoNotEstablishDpi() {
        val uncertain =
            listOf(
                domainReachability(DomainVerdict.UNREACHABLE),
                domainReachability(DomainVerdict.BLOCKED),
                domainReachability(DomainVerdict.TLS_VERSION_BLOCK),
                domainReachability(DomainVerdict.DNS_FAIL),
                quicH3(QuicProbeVerdict.QUIC_DROPPED),
                quicH3(QuicProbeVerdict.QUIC_DEGRADED),
                quicH3(QuicProbeVerdict.QUIC_VN_REJECTED),
            ) +
                EchProbeVerdict.entries.filter { it != EchProbeVerdict.ECH_OK }.map { verdict ->
                    DpiSuiteProbeResult.EchReadiness(
                        listOf(EchProbeResult("example.com", verdict, true, null, null, false, "probe failed")),
                    )
                }
        uncertain.forEach { result ->
            assertEquals(
                result.toString(),
                SuiteVerdict.INCONCLUSIVE,
                DpiSuiteVerdictAggregator.aggregate(listOf(result)).verdict,
            )
        }
    }

    @Test
    fun independentInterferenceEvidenceSurvivesOtherProbeFailures() {
        val failures =
            listOf(
                DpiSuiteProbeResult.Failed(DpiProbeKind.QUIC_H3, "unavailable"),
                DpiSuiteProbeResult.Failed(DpiProbeKind.ECH_READINESS, "unavailable"),
            )
        assertEquals(
            SuiteVerdict.DPI_DETECTED,
            DpiSuiteVerdictAggregator.aggregate(failures + tcp16(Tcp16Verdict.DETECTED_AT_KB)).verdict,
        )
        assertEquals(
            SuiteVerdict.DNS_INTERFERENCE,
            DpiSuiteVerdictAggregator.aggregate(failures + dnsIntegrity(DnsIntegrityVerdict.DNS_SUBSTITUTION)).verdict,
        )
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

    private fun quicH3(verdict: QuicProbeVerdict): DpiSuiteProbeResult.QuicH3 =
        DpiSuiteProbeResult.QuicH3(
            listOf(
                QuicProbeResult(
                    target = "example.com",
                    verdict = verdict,
                    chromeOk = verdict == QuicProbeVerdict.QUIC_OK,
                    firefoxOk = true,
                    genericOk = true,
                    vnOk = true,
                    udpReachable = true,
                    serverInitialLatencyMs = 12,
                ),
            ),
        )
}
