package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComparisonScanCoordinatorTest {
    private val coordinator =
        ComparisonScanCoordinator(
            scanRecordStore = FakeDiagnosticsHistoryStores(),
            json = diagnosticsTestJson(),
        )

    @Test
    @Suppress("detekt.LongMethod")
    fun `assessment text qualifies observed patterns and recommends paired inspection`() {
        val jointRawFailure =
            assessmentFor(
                rawReport =
                    minimalReport(
                        observations =
                            listOf(
                                domainObservation(
                                    host = "control.example",
                                    isControl = true,
                                    httpStatus = HttpProbeStatus.UNREACHABLE,
                                ),
                                domainObservation(
                                    host = "affected.example",
                                    tls13Status = TlsProbeStatus.HANDSHAKE_FAILED,
                                ),
                            ),
                    ),
            )
        val selectiveAffectedFailure =
            assessmentFor(
                rawReport =
                    minimalReport(
                        observations =
                            listOf(
                                domainObservation(
                                    host = "control.example",
                                    isControl = true,
                                    httpStatus = HttpProbeStatus.OK,
                                ),
                                domainObservation(
                                    host = "affected.example",
                                    tls13Status = TlsProbeStatus.HANDSHAKE_FAILED,
                                ),
                            ),
                    ),
            )
        val resolverDivergenceWithMixedControls =
            assessmentFor(
                rawReport =
                    minimalReport(
                        observations =
                            listOf(
                                ObservationFact(
                                    kind = ObservationKind.DNS,
                                    target = "affected.example",
                                    dns =
                                        DnsObservationFact(
                                            domain = "affected.example",
                                            status = DnsObservationStatus.SUSPICIOUS_DIVERGENCE,
                                        ),
                                ),
                                domainObservation(
                                    host = "passing-control.example",
                                    isControl = true,
                                    httpStatus = HttpProbeStatus.OK,
                                ),
                                domainObservation(
                                    host = "failing-control.example",
                                    isControl = true,
                                    httpStatus = HttpProbeStatus.UNREACHABLE,
                                ),
                                domainObservation(
                                    host = "affected.example",
                                    tls13Status = TlsProbeStatus.HANDSHAKE_FAILED,
                                ),
                            ),
                    ),
            )
        val pairedInPathRegression =
            assessmentFor(
                rawReport =
                    minimalReport(
                        observations =
                            listOf(
                                domainObservation(
                                    host = "affected.example",
                                    httpStatus = HttpProbeStatus.OK,
                                ),
                            ),
                    ),
                inPathReport =
                    minimalReport(
                        observations =
                            listOf(
                                domainObservation(
                                    host = "affected.example",
                                    tls13Status = TlsProbeStatus.HANDSHAKE_FAILED,
                                ),
                            ),
                    ),
            )
        val noInPathEvidence = assessmentFor(rawReport = minimalReport())

        assertEquals(ConnectivityAssessmentCode.RAW_NETWORK_GENERAL_FAILURE, jointRawFailure.assessmentCode)
        assertEquals(ConnectivityAssessmentCode.RAW_NETWORK_SELECTIVE_BLOCKING, selectiveAffectedFailure.assessmentCode)
        assertEquals(
            ConnectivityAssessmentCode.RESOLVER_INTERFERENCE,
            resolverDivergenceWithMixedControls.assessmentCode,
        )
        assertEquals(ConnectivityAssessmentCode.VPN_PATH_REGRESSION, pairedInPathRegression.assessmentCode)
        assertEquals(ConnectivityAssessmentCode.MIXED_OR_INCONCLUSIVE, noInPathEvidence.assessmentCode)

        listOf(
            jointRawFailure,
            selectiveAffectedFailure,
            resolverDivergenceWithMixedControls,
            pairedInPathRegression,
            noInPathEvidence,
        ).forEach { assessment ->
            assertTrue(assessment.assessmentSummary.contains("Observed pattern"))
            assertTrue(assessment.assessmentSummary.contains("cause is not established"))
            assertTrue(assessment.recommendedNextAction.contains("paired raw-path and in-path"))
            assertTrue(assessment.recommendedNextAction.contains("inspect"))
            listOf(
                "network looks broadly broken",
                "points to selective blocking",
                "points to a RIPDPI in-path regression",
                "DNS interference is the likely cause",
                "Treat this as a censorship/blocking issue",
            ).forEach { forbiddenPhrase ->
                assertFalse(assessment.assessmentSummary.contains(forbiddenPhrase))
                assertFalse(assessment.recommendedNextAction.contains(forbiddenPhrase))
            }
        }
    }

    @Test
    fun `assessConnectivity reports raw network general failure when controls and affected targets fail`() {
        val report =
            minimalReport(
                observations =
                    listOf(
                        domainObservation(
                            host = "control.example",
                            isControl = true,
                            httpStatus = HttpProbeStatus.UNREACHABLE,
                        ),
                        domainObservation(
                            host = "blocked.example",
                            tls13Status = TlsProbeStatus.HANDSHAKE_FAILED,
                        ),
                    ),
            )

        val assessment =
            coordinator.assessConnectivity(
                rawReports = listOf(report),
                inPathReport = null,
                rawPathSessionIds = listOf("raw-1"),
                inPathSessionId = null,
            )

        assertEquals(ConnectivityAssessmentCode.RAW_NETWORK_GENERAL_FAILURE, assessment.assessmentCode)
        assertEquals("raw_controls_failed", assessment.controlOutcome)
        assertEquals(listOf("blocked.example"), assessment.affectedTargets)
    }

    @Test
    fun `assessConnectivity reports resolver interference when resolver diagnosis accompanies affected failures`() {
        val report =
            minimalReport(
                observations =
                    listOf(
                        ObservationFact(
                            kind = ObservationKind.DNS,
                            target = "discord.com",
                            dns =
                                DnsObservationFact(
                                    domain = "discord.com",
                                    status = DnsObservationStatus.SUSPICIOUS_DIVERGENCE,
                                ),
                        ),
                        domainObservation(
                            host = "control.example",
                            isControl = true,
                            httpStatus = HttpProbeStatus.OK,
                        ),
                        domainObservation(
                            host = "discord.com",
                            tls13Status = TlsProbeStatus.HANDSHAKE_FAILED,
                        ),
                    ),
                diagnoses =
                    listOf(
                        Diagnosis(
                            code = "dns_record_divergence",
                            summary = "Encrypted and raw resolver answers diverged.",
                            target = "discord.com",
                        ),
                    ),
            )

        val assessment =
            coordinator.assessConnectivity(
                rawReports = listOf(report),
                inPathReport = null,
                rawPathSessionIds = listOf("raw-1"),
                inPathSessionId = null,
            )

        assertEquals(ConnectivityAssessmentCode.RESOLVER_INTERFERENCE, assessment.assessmentCode)
        assertEquals("medium", assessment.confidence)
        assertEquals("dns_record_divergence", assessment.resolverAssessment.strongestSignal)
        assertEquals(listOf("dns_record_divergence"), assessment.resolverAssessment.diagnosisCodes)
    }

    @Test
    fun `assessConnectivity does not attribute unrelated DNS divergence to affected TLS failure`() {
        val report =
            minimalReport(
                observations =
                    listOf(
                        ObservationFact(
                            kind = ObservationKind.DNS,
                            target = "healthy.example",
                            dns =
                                DnsObservationFact(
                                    domain = "healthy.example",
                                    status = DnsObservationStatus.SUSPICIOUS_DIVERGENCE,
                                ),
                        ),
                        domainObservation(
                            host = "control.example",
                            isControl = true,
                            httpStatus = HttpProbeStatus.OK,
                        ),
                        domainObservation(
                            host = "healthy.example",
                            httpStatus = HttpProbeStatus.OK,
                        ),
                        domainObservation(
                            host = "blocked.example",
                            tls13Status = TlsProbeStatus.HANDSHAKE_FAILED,
                        ),
                    ),
            )

        val assessment =
            coordinator.assessConnectivity(
                rawReports = listOf(report),
                inPathReport = null,
                rawPathSessionIds = listOf("raw-1"),
                inPathSessionId = null,
            )

        assertEquals(ConnectivityAssessmentCode.RAW_NETWORK_SELECTIVE_BLOCKING, assessment.assessmentCode)
        assertEquals(listOf("blocked.example"), assessment.affectedTargets)
        assertEquals(listOf("healthy.example"), assessment.resolverAssessment.mismatchTargets)
    }

    @Test
    fun `assessConnectivity does not treat compatible DNS divergence as resolver interference`() {
        val report =
            minimalReport(
                observations =
                    listOf(
                        ObservationFact(
                            kind = ObservationKind.DNS,
                            target = "blocked.example",
                            dns =
                                DnsObservationFact(
                                    domain = "blocked.example",
                                    status = DnsObservationStatus.COMPATIBLE_DIVERGENCE,
                                ),
                        ),
                        domainObservation(
                            host = "control.example",
                            isControl = true,
                            httpStatus = HttpProbeStatus.OK,
                        ),
                        domainObservation(
                            host = "blocked.example",
                            tls13Status = TlsProbeStatus.HANDSHAKE_FAILED,
                        ),
                    ),
            )

        val assessment =
            coordinator.assessConnectivity(
                rawReports = listOf(report),
                inPathReport = null,
                rawPathSessionIds = listOf("raw-1"),
                inPathSessionId = null,
            )

        assertEquals(ConnectivityAssessmentCode.RAW_NETWORK_SELECTIVE_BLOCKING, assessment.assessmentCode)
        assertTrue(assessment.resolverAssessment.mismatchTargets.isEmpty())
    }

    @Test
    fun `resolver divergence without diagnosis and with mixed controls does not claim high confidence`() {
        val report =
            minimalReport(
                observations =
                    listOf(
                        ObservationFact(
                            kind = ObservationKind.DNS,
                            target = "affected.example",
                            dns =
                                DnsObservationFact(
                                    domain = "affected.example",
                                    status = DnsObservationStatus.SUSPICIOUS_DIVERGENCE,
                                ),
                        ),
                        domainObservation(
                            host = "healthy-control.example",
                            isControl = true,
                            httpStatus = HttpProbeStatus.OK,
                        ),
                        domainObservation(
                            host = "failed-control.example",
                            isControl = true,
                            httpStatus = HttpProbeStatus.UNREACHABLE,
                        ),
                        domainObservation(
                            host = "affected.example",
                            tls13Status = TlsProbeStatus.HANDSHAKE_FAILED,
                        ),
                    ),
            )

        val assessment =
            coordinator.assessConnectivity(
                rawReports = listOf(report),
                inPathReport = null,
                rawPathSessionIds = listOf("raw-1"),
                inPathSessionId = null,
            )

        assertEquals(ConnectivityAssessmentCode.RESOLVER_INTERFERENCE, assessment.assessmentCode)
        assertEquals("medium", assessment.confidence)
        assertEquals("raw_controls_mixed", assessment.controlOutcome)
        assertTrue(assessment.resolverAssessment.diagnosisCodes.isEmpty())
    }

    @Test
    fun `resolver interference summary keeps unrelated failures explicit`() {
        val report =
            minimalReport(
                observations =
                    listOf(
                        ObservationFact(
                            kind = ObservationKind.DNS,
                            target = "dns-failed.example",
                            dns =
                                DnsObservationFact(
                                    domain = "dns-failed.example",
                                    status = DnsObservationStatus.SUSPICIOUS_DIVERGENCE,
                                ),
                        ),
                        domainObservation(
                            host = "control.example",
                            isControl = true,
                            httpStatus = HttpProbeStatus.OK,
                        ),
                        domainObservation(
                            host = "dns-failed.example",
                            tls13Status = TlsProbeStatus.HANDSHAKE_FAILED,
                        ),
                        domainObservation(
                            host = "route-failed.example",
                            tls13Status = TlsProbeStatus.HANDSHAKE_FAILED,
                        ),
                    ),
            )

        val assessment =
            coordinator.assessConnectivity(
                rawReports = listOf(report),
                inPathReport = null,
                rawPathSessionIds = listOf("raw-1"),
                inPathSessionId = null,
            )

        assertEquals(ConnectivityAssessmentCode.RESOLVER_INTERFERENCE, assessment.assessmentCode)
        assertTrue(assessment.assessmentSummary.contains("1 of 2 affected-target failures"))
        assertTrue(assessment.assessmentSummary.contains("1 failure remains unexplained by DNS evidence"))
    }

    @Test
    fun `trusted system DNS failure outcome is correlated with its affected target`() {
        val report =
            minimalReport(
                results =
                    listOf(
                        ProbeResult(
                            probeType = "dns_integrity",
                            target = "blocked.example",
                            outcome = "dns_system_resolution_failed",
                            details = listOf(ProbeDetail("oracleTrust", "trusted_agreement")),
                        ),
                        ProbeResult(
                            probeType = "dns_integrity",
                            target = "unstable-oracle.example",
                            outcome = "dns_oracle_unavailable",
                            details = listOf(ProbeDetail("oracleTrust", "disagreement")),
                        ),
                        ProbeResult(
                            probeType = "dns_integrity",
                            target = "single-fallback.example",
                            outcome = "dns_oracle_unavailable",
                            details = listOf(ProbeDetail("oracleTrust", "single_fallback")),
                        ),
                    ),
                observations =
                    listOf(
                        domainObservation(
                            host = "control.example",
                            isControl = true,
                            httpStatus = HttpProbeStatus.OK,
                        ),
                        domainObservation(
                            host = "blocked.example",
                            tls13Status = TlsProbeStatus.HANDSHAKE_FAILED,
                        ),
                    ),
            )

        val assessment = assessmentFor(report)

        assertEquals(ConnectivityAssessmentCode.RESOLVER_INTERFERENCE, assessment.assessmentCode)
        assertEquals(listOf("blocked.example"), assessment.resolverAssessment.mismatchTargets)
        assertTrue(assessment.resolverAssessment.summary.contains("Across 3 DNS checks"))
        assertTrue(assessment.resolverAssessment.summary.contains("1 trusted agreement"))
        assertTrue(assessment.resolverAssessment.summary.contains("2 limited, disagreement, or unavailable"))
    }

    @Test
    fun `assessConnectivity reports vpn path regression when raw success becomes in-path failure`() {
        val rawReport =
            minimalReport(
                observations =
                    listOf(
                        domainObservation(
                            host = "blocked.example",
                            httpStatus = HttpProbeStatus.OK,
                        ),
                    ),
            )
        val inPathReport =
            minimalReport(
                observations =
                    listOf(
                        domainObservation(
                            host = "blocked.example",
                            tls13Status = TlsProbeStatus.HANDSHAKE_FAILED,
                        ),
                    ),
            )

        val assessment =
            coordinator.assessConnectivity(
                rawReports = listOf(rawReport),
                inPathReport = inPathReport,
                rawPathSessionIds = listOf("raw-1"),
                inPathSessionId = "vpn-1",
            )

        assertEquals(ConnectivityAssessmentCode.VPN_PATH_REGRESSION, assessment.assessmentCode)
        assertEquals(1, assessment.rawPathEvidence.affectedTargetSuccessCount)
        assertEquals(1, assessment.inPathEvidence.affectedTargetFailureCount)
    }

    @Test
    fun `assessConnectivity reports actionable service runtime failure when in-path confirmation is absent`() {
        val assessment =
            coordinator.assessConnectivity(
                rawReports = listOf(minimalReport()),
                inPathReport = null,
                rawPathSessionIds = listOf("raw-1"),
                inPathSessionId = null,
                serviceRuntimeAssessment =
                    ConnectivityServiceRuntimeAssessment(
                        serviceStatus = "halted",
                        nativeFailureClass = "proxy_start_failed",
                        lastNativeErrorHeadline = "SOCKS listener failed",
                        actionable = true,
                        summary = "Proxy runtime failed before validation finished.",
                    ),
            )

        assertEquals(ConnectivityAssessmentCode.SERVICE_RUNTIME_FAILURE, assessment.assessmentCode)
        assertEquals("medium", assessment.confidence)
        assertTrue(assessment.recommendedNextAction.contains("Inspect proxy/tunnel runtime errors"))
    }

    private fun minimalReport(
        results: List<ProbeResult> = emptyList(),
        observations: List<ObservationFact> = emptyList(),
        diagnoses: List<Diagnosis> = emptyList(),
    ) = ScanReport(
        sessionId = "test-session",
        profileId = "default",
        pathMode = ScanPathMode.RAW_PATH,
        startedAt = 0L,
        finishedAt = 1L,
        summary = "test",
        results = results,
        diagnoses = diagnoses,
        observations = observations,
    )

    private fun assessmentFor(
        rawReport: ScanReport,
        inPathReport: ScanReport? = null,
    ): ConnectivityAssessment =
        coordinator.assessConnectivity(
            rawReports = listOf(rawReport),
            inPathReport = inPathReport,
            rawPathSessionIds = listOf("raw-1"),
            inPathSessionId = inPathReport?.let { "in-path-1" },
        )

    private fun domainObservation(
        host: String,
        isControl: Boolean = false,
        httpStatus: HttpProbeStatus = HttpProbeStatus.NOT_RUN,
        tls13Status: TlsProbeStatus = TlsProbeStatus.NOT_RUN,
    ) = ObservationFact(
        kind = ObservationKind.DOMAIN,
        target = host,
        domain =
            DomainObservationFact(
                host = host,
                httpStatus = httpStatus,
                tls13Status = tls13Status,
                isControl = isControl,
            ),
    )
}
