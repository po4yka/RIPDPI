package com.poyka.ripdpi.diagnostics.dpi

import org.junit.Assert.assertEquals
import org.junit.Test

class DpiSuiteVerdictAggregatorTest {
    @Test
    fun unsupportedTargetsAreExcludedButNeverCountAsMeasurements() {
        val unsupported = tcp16(Tcp16Verdict.UNSUPPORTED)
        assertEquals(SuiteVerdict.INCONCLUSIVE, DpiSuiteVerdictAggregator.aggregate(listOf(unsupported)).verdict)
        val completed = tcp16(Tcp16Verdict.OK)
        val mixed = completed.copy(results = completed.results + unsupported.results)
        assertEquals(SuiteVerdict.CLEAN, DpiSuiteVerdictAggregator.aggregate(listOf(mixed)).verdict)
    }

    @Test
    fun inapplicableSniDoesNotChangeSuccessfulMeasuredVerdict() {
        val skipped = DpiSuiteProbeResult.Skipped(DpiProbeKind.WHITELIST_SNI, DpiSuiteSkipReason.NOT_APPLICABLE)
        assertEquals(
            SuiteVerdict.CLEAN,
            DpiSuiteVerdictAggregator.aggregate(listOf(tcp16(Tcp16Verdict.OK), skipped)).verdict,
        )
        assertEquals(SuiteVerdict.INCONCLUSIVE, DpiSuiteVerdictAggregator.aggregate(listOf(skipped)).verdict)
    }

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
    fun doqDisagreementAndMissingChecksKeepDnsSummaryInconclusive() {
        val dns = dnsIntegrity(DnsIntegrityVerdict.DNS_OK).result
        val agreed = DoqProbeResult("Resolver", "127.0.0.1", "example.test", DoqVerdict.DOQ_OK, emptyList(), 1)
        val divergent = agreed.copy(verdict = DoqVerdict.DOQ_INTEGRITY_DIVERGENT)
        val timeout = agreed.copy(verdict = DoqVerdict.DOQ_TIMEOUT)
        val cases =
            listOf(
                listOf(divergent, agreed) to "2/2 observed, 1 flagged",
                listOf(timeout, agreed) to "2/2 observed, 1 flagged",
                listOf(agreed) to "1/2 observed, 0 flagged",
                emptyList<DoqProbeResult>() to "0/2 observed, 0 flagged",
            )
        cases.forEach { (observed, expectedLine) ->
            val result = DpiSuiteProbeResult.DnsIntegrity(dns.copy(doqResults = observed, doqExpectedChecks = 2))
            val aggregate = DpiSuiteVerdictAggregator.aggregate(listOf(result))
            assertEquals(SuiteVerdict.INCONCLUSIVE, aggregate.verdict)
            assertEquals("DNS comparison: 0/1 flagged · DoQ: $expectedLine", aggregate.summaryLines.single())
        }
        val complete = DpiSuiteProbeResult.DnsIntegrity(dns.copy(doqResults = listOf(agreed), doqExpectedChecks = 1))
        assertEquals(SuiteVerdict.CLEAN, DpiSuiteVerdictAggregator.aggregate(listOf(complete)).verdict)
        assertEquals(
            "DNS comparison: 0/1 flagged · DoQ: skipped",
            DpiSuiteVerdictAggregator.aggregate(listOf(DpiSuiteProbeResult.DnsIntegrity(dns))).summaryLines.single(),
        )
    }

    @Test
    fun interruptedTcp16TrainDoesNotEstablishDpi() {
        val aggregate =
            DpiSuiteVerdictAggregator.aggregate(
                results =
                    listOf(
                        dnsIntegrity(verdict = DnsIntegrityVerdict.DNS_OK),
                        tcp16(verdict = Tcp16Verdict.PADDED_TRAIN_INTERRUPTED),
                    ),
            )

        assertEquals(SuiteVerdict.INCONCLUSIVE, aggregate.verdict)
    }

    @Test
    fun inconclusiveWhenQuicTemplatesHaveDifferentResponses() {
        val aggregate =
            DpiSuiteVerdictAggregator.aggregate(
                results = listOf(quicH3(QuicProbeOutcome.RETRY)),
            )

        assertEquals(SuiteVerdict.INCONCLUSIVE, aggregate.verdict)
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
    fun incompleteTelegramTransferDoesNotEstablishThrottling() {
        val aggregate =
            DpiSuiteVerdictAggregator.aggregate(
                results = listOf(telegram(verdict = TelegramTestVerdict.PARTIAL)),
            )

        assertEquals(SuiteVerdict.INCONCLUSIVE, aggregate.verdict)
    }

    @Test
    fun quicTemplateLossDoesNotAddDpiToDnsInterference() {
        val aggregate =
            DpiSuiteVerdictAggregator.aggregate(
                results =
                    listOf(
                        dnsIntegrity(verdict = DnsIntegrityVerdict.DNS_INTERCEPTION),
                        quicH3(QuicProbeOutcome.NO_RESPONSE),
                    ),
            )

        assertEquals(SuiteVerdict.DNS_INTERFERENCE, aggregate.verdict)
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
                quicH3(QuicProbeOutcome.NO_RESPONSE),
                telegram(TelegramTestVerdict.ERROR),
                telegram(TelegramTestVerdict.PARTIAL),
                tcp16(Tcp16Verdict.DEAD),
                tcp16(Tcp16Verdict.ERROR),
                tcp16(Tcp16Verdict.INVALID_RECONNECTED),
                DpiSuiteProbeResult.Skipped(
                    DpiProbeKind.WHITELIST_SNI,
                    DpiSuiteSkipReason.PREREQUISITE_UNAVAILABLE,
                ),
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
                quicH3(QuicProbeOutcome.UNCORRELATED_RESPONSE),
                quicH3(QuicProbeOutcome.INITIAL_ACK),
                quicH3(QuicProbeOutcome.RECEIVE_LIMIT_REACHED),
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
            DpiSuiteVerdictAggregator.aggregate(failures + domainReachability(DomainVerdict.ISP_PAGE)).verdict,
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
                    completedPaddingBytes = if (verdict == Tcp16Verdict.PADDED_TRAIN_INTERRUPTED) 16_384 else 0,
                    meanResponseMs = 20,
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

    private fun quicH3(outcome: QuicProbeOutcome): DpiSuiteProbeResult.QuicH3 =
        DpiSuiteProbeResult.QuicH3(
            results =
                listOf(
                    QuicProbeResult(
                        target = "quic.example",
                        attempts = mapOf(QuicFingerprint.CHROME_120 to listOf(QuicProbeSample(outcome))),
                    ),
                ),
        )
}
