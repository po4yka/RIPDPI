package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComparisonScanEvidenceTest {
    private val coordinator = ComparisonScanCoordinator()

    @Test
    fun `normalized domain success preserves supported TLS version`() {
        val observation = domainObservation("modern.example", tls13Status = TlsProbeStatus.OK)
        val report =
            minimalReport(
                results = listOf(ProbeResult("domain_reachability", "modern.example", "tls_ok")),
                observations =
                    listOf(
                        domainObservation("control.example", isControl = true, httpStatus = HttpProbeStatus.OK),
                        observation.copy(
                            domain = observation.domain!!.copy(tls12Status = TlsProbeStatus.HANDSHAKE_FAILED),
                        ),
                    ),
            )
        val assessment = assessmentFor(report)
        assertEquals(ConnectivityAssessmentCode.MIXED_OR_INCONCLUSIVE, assessment.assessmentCode)
        assertEquals(1, assessment.rawPathEvidence.affectedTargetSuccessCount)
        assertEquals(0, assessment.rawPathEvidence.affectedTargetFailureCount)
    }

    @Test
    fun `successful bootstrap does not hide failed media or tunnel handshake`() {
        val report =
            minimalReport(
                observations =
                    listOf(
                        domainObservation("control.example", isControl = true, httpStatus = HttpProbeStatus.OK),
                        ObservationFact(
                            kind = ObservationKind.SERVICE,
                            target = "messaging",
                            service =
                                ServiceObservationFact(
                                    "messaging",
                                    HttpProbeStatus.OK,
                                    HttpProbeStatus.UNREACHABLE,
                                ),
                        ),
                        ObservationFact(
                            kind = ObservationKind.CIRCUMVENTION,
                            target = "tunnel",
                            circumvention =
                                CircumventionObservationFact(
                                    "tunnel",
                                    HttpProbeStatus.OK,
                                    EndpointProbeStatus.FAILED,
                                ),
                        ),
                    ),
            )
        val assessment = assessmentFor(report)
        assertEquals(listOf("messaging", "tunnel"), assessment.affectedTargets)
        assertEquals(2, assessment.rawPathEvidence.affectedTargetFailureCount)
        assertEquals(0, assessment.rawPathEvidence.affectedTargetSuccessCount)
    }

    @Test
    fun `path regression requires the same measured protocol`() {
        val assessment =
            assessmentFor(
                minimalReport(observations = listOf(domainObservation("example.com", httpStatus = HttpProbeStatus.OK))),
                minimalReport(
                    observations =
                        listOf(
                            domainObservation("example.com", tls13Status = TlsProbeStatus.HANDSHAKE_FAILED),
                        ),
                ),
            )
        assertEquals(ConnectivityAssessmentCode.MIXED_OR_INCONCLUSIVE, assessment.assessmentCode)
    }

    @Test
    fun `service media regression is preserved when bootstrap still works`() {
        fun report(media: HttpProbeStatus) =
            minimalReport(
                observations =
                    listOf(
                        ObservationFact(
                            kind = ObservationKind.SERVICE,
                            target = "messaging",
                            service = ServiceObservationFact("messaging", HttpProbeStatus.OK, media),
                        ),
                    ),
            )
        val assessment = assessmentFor(report(HttpProbeStatus.OK), report(HttpProbeStatus.UNREACHABLE))
        assertEquals(ConnectivityAssessmentCode.VPN_PATH_REGRESSION, assessment.assessmentCode)
        assertEquals(1, assessment.inPathEvidence.affectedTargetFailureCount)
    }

    @Test
    fun `unmeasured and partial evidence does not become a failed target or path regression`() {
        val raw =
            minimalReport(observations = listOf(domainObservation("example.com", httpStatus = HttpProbeStatus.OK)))
        val unmeasured = minimalReport(observations = listOf(domainObservation("example.com")))
        val incomplete =
            minimalReport(
                observations = listOf(domainObservation("example.com", httpStatus = HttpProbeStatus.UNREACHABLE)),
            ).copy(completionKind = ScanCompletionKind.PARTIAL_RESULTS)
        listOf(unmeasured, incomplete).forEach { report ->
            val assessment = assessmentFor(raw, report)
            assertEquals(ConnectivityAssessmentCode.MIXED_OR_INCONCLUSIVE, assessment.assessmentCode)
            assertEquals(0, assessment.inPathEvidence.affectedTargetFailureCount)
        }
    }

    @Test
    fun `partial DNS observations do not establish resolver interference`() {
        val complete =
            minimalReport(
                observations =
                    listOf(
                        domainObservation("control.example", isControl = true, httpStatus = HttpProbeStatus.OK),
                        domainObservation("affected.example", tls13Status = TlsProbeStatus.HANDSHAKE_FAILED),
                    ),
            )
        val partialDns =
            minimalReport(
                observations =
                    listOf(
                        ObservationFact(
                            kind = ObservationKind.DNS,
                            target = "affected.example",
                            dns = DnsObservationFact("affected.example", DnsObservationStatus.SUSPICIOUS_DIVERGENCE),
                        ),
                    ),
            ).copy(completionKind = ScanCompletionKind.PARTIAL_RESULTS)
        val assessment =
            coordinator.assessConnectivity(
                listOf(complete, partialDns),
                null,
                listOf("complete", "partial"),
                null,
            )
        assertEquals(ConnectivityAssessmentCode.RAW_NETWORK_SELECTIVE_BLOCKING, assessment.assessmentCode)
        assertTrue(assessment.resolverAssessment.mismatchTargets.isEmpty())
    }

    @Test
    fun `unmeasured controls are inconclusive rather than failed`() {
        val assessment =
            assessmentFor(
                minimalReport(observations = listOf(domainObservation("control.example", isControl = true))),
            )
        assertEquals("raw_controls_inconclusive", assessment.controlOutcome)
        assertEquals(0, assessment.rawPathEvidence.controlFailureCount)
    }

    @Test
    fun `a later success does not erase mixed repeated measurements`() {
        val assessment =
            coordinator.assessConnectivity(
                rawReports =
                    listOf(
                        minimalReport(
                            observations =
                                listOf(
                                    domainObservation("example.com", httpStatus = HttpProbeStatus.UNREACHABLE),
                                ),
                        ),
                        minimalReport(
                            observations = listOf(domainObservation("example.com", httpStatus = HttpProbeStatus.OK)),
                        ),
                    ),
                inPathReport =
                    minimalReport(
                        observations =
                            listOf(
                                domainObservation("example.com", httpStatus = HttpProbeStatus.UNREACHABLE),
                            ),
                    ),
                rawPathSessionIds = listOf("raw-1", "raw-2"),
                inPathSessionId = "in-path",
            )
        assertEquals(ConnectivityAssessmentCode.MIXED_OR_INCONCLUSIVE, assessment.assessmentCode)
        assertEquals(0, assessment.rawPathEvidence.affectedTargetSuccessCount)
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
