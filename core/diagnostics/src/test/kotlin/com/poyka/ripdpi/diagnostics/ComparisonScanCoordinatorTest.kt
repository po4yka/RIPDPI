package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComparisonScanCoordinatorTest {
    private val coordinator =
        ComparisonScanCoordinator(
            scanRecordStore = FakeDiagnosticsHistoryStores(),
            json = diagnosticsTestJson(),
        )

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
    fun `assessConnectivity qualifies joint raw failure without claiming a broad network cause`() {
        val report =
            minimalReport(
                observations =
                    listOf(
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
        val summary = assessment.assessmentSummary.lowercase()
        val nextAction = assessment.recommendedNextAction.lowercase()

        assertEquals(ConnectivityAssessmentCode.RAW_NETWORK_GENERAL_FAILURE, assessment.assessmentCode)
        assertEquals("raw_controls_failed", assessment.controlOutcome)
        assertEquals(1, assessment.rawPathEvidence.controlFailureCount)
        assertEquals(1, assessment.rawPathEvidence.affectedTargetFailureCount)
        assertTrue(assessment.resolverAssessment.mismatchTargets.isEmpty())
        assertTrue(assessment.inPathEvidence.sessionIds.isEmpty())
        assertTrue(!assessment.serviceRuntimeAssessment.actionable)
        assertTrue(
            listOf("observed", "together", "consistent", "reachability", "insufficient", "not established")
                .all(summary::contains),
        )
        assertTrue(!summary.contains("network looks broadly broken"))
        assertTrue(nextAction.contains("verify"))
        assertTrue(nextAction.contains("diagnostics"))
        assertTrue(listOf("censorship", "blocking", "treat this as").none(nextAction::contains))
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
    fun `assessConnectivity qualifies resolver divergence as a candidate signal with mixed controls`() {
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
        val summary = assessment.assessmentSummary.lowercase()

        assertTrue(
            "Resolver divergence must remain a qualified candidate signal when the raw controls are mixed " +
                "and no in-path evidence exists",
            assessment.assessmentCode == ConnectivityAssessmentCode.RESOLVER_INTERFERENCE &&
                assessment.controlOutcome == "raw_controls_mixed" &&
                assessment.rawPathEvidence.affectedTargetFailureCount == 1 &&
                assessment.inPathEvidence.sessionIds.isEmpty() &&
                summary.contains("dns") &&
                summary.contains("candidate") &&
                summary.contains("signal") &&
                (summary.contains("not established") || summary.contains("not confirm")) &&
                !summary.contains("likely cause"),
        )
    }

    @Test
    fun `assessConnectivity qualifies selective blocking as an observed pattern without establishing cause`() {
        val report =
            minimalReport(
                observations =
                    listOf(
                        domainObservation(
                            host = "healthy-control.example",
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
        val summary = assessment.assessmentSummary.lowercase()
        val nextAction = assessment.recommendedNextAction.lowercase()

        assertTrue(
            "Selective blocking must remain an observed pattern requiring paired in-path corroboration",
            assessment.assessmentCode == ConnectivityAssessmentCode.RAW_NETWORK_SELECTIVE_BLOCKING &&
                assessment.controlOutcome == "raw_controls_passed" &&
                assessment.rawPathEvidence.controlSuccessCount == 1 &&
                assessment.rawPathEvidence.affectedTargetFailureCount == 1 &&
                assessment.resolverAssessment.mismatchTargets.isEmpty() &&
                assessment.inPathEvidence.sessionIds.isEmpty() &&
                (
                    summary.contains("observed") ||
                        summary.contains("pattern") ||
                        summary.contains("consistent")
                ) &&
                (
                    summary.contains("not established") ||
                        summary.contains("not confirmed") ||
                        summary.contains("cannot establish")
                ) &&
                (nextAction.contains("paired") || nextAction.contains("compare")) &&
                nextAction.contains("in-path") &&
                !nextAction.contains("treat this as") &&
                !nextAction.contains("censorship") &&
                !nextAction.contains("confirmed blocking"),
        )
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
    fun `assessConnectivity qualifies paired path regression without identifying a root cause`() {
        val rawReport =
            minimalReport(
                observations =
                    listOf(
                        domainObservation(
                            host = "target.example",
                            httpStatus = HttpProbeStatus.OK,
                        ),
                    ),
            )
        val inPathReport =
            minimalReport(
                observations =
                    listOf(
                        domainObservation(
                            host = "target.example",
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
        val summary = assessment.assessmentSummary.lowercase()
        val nextAction = assessment.recommendedNextAction.lowercase()

        assertTrue(
            "Paired path differences must remain an observed association without attributing a component or root cause",
            assessment.assessmentCode == ConnectivityAssessmentCode.VPN_PATH_REGRESSION &&
                assessment.rawPathEvidence.affectedTargetSuccessCount == 1 &&
                assessment.inPathEvidence.affectedTargetFailureCount == 1 &&
                assessment.resolverAssessment.mismatchTargets.isEmpty() &&
                summary.contains("paired") &&
                (
                    summary.contains("observed") ||
                        summary.contains("association") ||
                        summary.contains("path-dependent")
                ) &&
                (
                    summary.contains("not established") ||
                        summary.contains("does not identify") ||
                        summary.contains("cannot identify") ||
                        summary.contains("insufficient")
                ) &&
                (summary.contains("component") || summary.contains("root cause")) &&
                !summary.contains("points to a ripdpi") &&
                !summary.contains("caused by ripdpi") &&
                !summary.contains("root cause is") &&
                nextAction.contains("reproduce") &&
                nextAction.contains("inspect"),
        )
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
        observations: List<ObservationFact> = emptyList(),
        diagnoses: List<Diagnosis> = emptyList(),
    ) = ScanReport(
        sessionId = "test-session",
        profileId = "default",
        pathMode = ScanPathMode.RAW_PATH,
        startedAt = 0L,
        finishedAt = 1L,
        summary = "test",
        diagnoses = diagnoses,
        observations = observations,
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
