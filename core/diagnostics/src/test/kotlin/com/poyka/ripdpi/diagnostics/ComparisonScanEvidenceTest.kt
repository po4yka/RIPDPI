package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComparisonScanEvidenceTest {
    private val coordinator = ComparisonScanCoordinator()

    @Test
    fun `one direct failure and one control without comparison provide low confidence`() {
        val raw =
            minimalReport(
                observations =
                    listOf(
                        domainObservation("control.example", isControl = true, httpStatus = HttpProbeStatus.OK),
                        domainObservation("affected.example", httpStatus = HttpProbeStatus.UNREACHABLE),
                    ),
            )

        val assessment = assessmentFor(raw)

        assertEquals(ConnectivityAssessmentCode.RAW_NETWORK_SELECTIVE_BLOCKING, assessment.assessmentCode)
        assertEquals("low", assessment.confidence)
    }

    @Test
    fun `a completed matching HTTP exchange reports recovery separately`() {
        val raw = comparisonHttpReport("raw", HttpProbeStatus.UNREACHABLE)
        val active = comparisonHttpReport("active", HttpProbeStatus.OK).copy(pathMode = ScanPathMode.IN_PATH)

        val assessment = assessmentFor(raw, active)
        val comparison = requireNotNull(assessment.pathComparison)
        val row = comparison.stages.single { it.target == "affected.example" && it.stage == "http" }

        assertEquals(ConnectivityAssessmentCode.RAW_NETWORK_SELECTIVE_BLOCKING, assessment.assessmentCode)
        assertEquals("medium", assessment.confidence)
        assertEquals(listOf("affected.example"), comparison.httpRecoveredTargets)
        assertEquals(ConnectivityStageMeasurement(ConnectivityStageOutcome.FAILED, 1), row.raw)
        assertEquals(ConnectivityStageMeasurement(ConnectivityStageOutcome.SUCCEEDED, 1), row.inPath)
    }

    @Test
    fun `partial and conflicting measurements cannot confirm recovery`() {
        val raw = comparisonHttpReport("raw", HttpProbeStatus.UNREACHABLE)
        val active = comparisonHttpReport("active", HttpProbeStatus.OK).copy(pathMode = ScanPathMode.IN_PATH)
        val partial = assessmentFor(raw, active.copy(completionKind = ScanCompletionKind.PARTIAL_RESULTS))
        val contradictory = comparisonHttpReport("raw-second", HttpProbeStatus.OK)
        val conflicting =
            coordinator.assessConnectivity(listOf(raw, contradictory), active, listOf("raw", "raw-second"), "active")

        val partialComparison = requireNotNull(partial.pathComparison)
        assertEquals(ConnectivityActivePathCoverage.INCOMPLETE, partialComparison.activePathCoverage)
        assertTrue(partialComparison.httpRecoveredTargets.isEmpty())
        assertEquals("low", partial.confidence)

        val conflictingComparison = requireNotNull(conflicting.pathComparison)
        val row = conflictingComparison.stages.single { it.target == "affected.example" && it.stage == "http" }
        assertEquals(ConnectivityStageMeasurement(ConnectivityStageOutcome.CONFLICTING, 2), row.raw)
        assertTrue(conflictingComparison.httpRecoveredTargets.isEmpty())
        assertEquals("low", conflicting.confidence)
    }

    @Test
    fun `a different configured RAW probe cannot confirm recovery on the same host`() {
        val raw = comparisonHttpReport("raw", HttpProbeStatus.UNREACHABLE)
        val active = comparisonHttpReport("active", HttpProbeStatus.OK).copy(pathMode = ScanPathMode.IN_PATH)

        val assessment =
            coordinator.assessConnectivity(
                rawReports = listOf(raw),
                inPathReport = active,
                rawPathSessionIds = listOf("raw"),
                inPathSessionId = "active",
                rawProfilesByTarget =
                    mapOf(ConnectivityTargetKey(ObservationKind.DOMAIN, "affected.example") to setOf("other")),
            )

        val comparison = requireNotNull(assessment.pathComparison)
        assertEquals(
            ConnectivityStageOutcome.UNMEASURED,
            comparison.stages
                .single()
                .raw.outcome,
        )
        assertTrue(comparison.httpRecoveredTargets.isEmpty())
    }

    @Test
    fun `high confidence requires distinct RAW observations and multiple paired targets`() {
        val raw = comparisonHttpReport("raw", HttpProbeStatus.UNREACHABLE, secondTarget = true)
        val active =
            comparisonHttpReport(
                "active",
                HttpProbeStatus.OK,
                secondTarget = true,
            ).copy(pathMode = ScanPathMode.IN_PATH)
        val duplicate = coordinator.assessConnectivity(listOf(raw, raw), active, listOf("raw"), "active")
        val repeated =
            coordinator.assessConnectivity(
                listOf(raw, raw.copy(sessionId = "raw-second")),
                active,
                listOf("raw", "raw-second"),
                "active",
            )

        assertEquals("medium", duplicate.confidence)
        assertEquals("high", repeated.confidence)
        assertEquals(2, repeated.pathComparison?.completeRawReports)
    }

    private fun comparisonHttpReport(
        sessionId: String,
        status: HttpProbeStatus,
        secondTarget: Boolean = false,
    ) = minimalReport(
        observations =
            buildList {
                add(domainObservation("control.example", isControl = true, httpStatus = HttpProbeStatus.OK))
                add(domainObservation("affected.example", httpStatus = status))
                if (secondTarget) add(domainObservation("other-affected.example", httpStatus = status))
            },
    ).copy(sessionId = sessionId)

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
    fun `HTTP refusal and a generic QUIC reply do not prove that the direct path works`() {
        val raw =
            minimalReport(
                results =
                    listOf(
                        ProbeResult(
                            "service_reachability",
                            "messaging",
                            "service_partial",
                            listOf(ProbeDetail("bootstrapStatus", "http_status_503")),
                        ),
                        ProbeResult(
                            "circumvention_reachability",
                            "tunnel",
                            "circumvention_degraded",
                            listOf(ProbeDetail("bootstrapStatus", "http_status_503")),
                        ),
                    ),
                observations =
                    listOf(
                        ObservationFact(
                            kind = ObservationKind.SERVICE,
                            target = "messaging",
                            service =
                                ServiceObservationFact(
                                    "messaging",
                                    HttpProbeStatus.OK,
                                    quicStatus = QuicProbeStatus.RESPONSE,
                                ),
                            evidence = listOf("service_partial"),
                        ),
                        ObservationFact(
                            kind = ObservationKind.CIRCUMVENTION,
                            target = "tunnel",
                            circumvention = CircumventionObservationFact("tunnel", HttpProbeStatus.OK),
                            evidence = listOf("circumvention_degraded"),
                        ),
                    ),
            )
        val failedPair =
            minimalReport(
                observations =
                    listOf(
                        ObservationFact(
                            kind = ObservationKind.SERVICE,
                            target = "messaging",
                            service =
                                ServiceObservationFact(
                                    "messaging",
                                    HttpProbeStatus.UNREACHABLE,
                                    quicStatus = QuicProbeStatus.ERROR,
                                ),
                            evidence = listOf("service_blocked"),
                        ),
                        ObservationFact(
                            kind = ObservationKind.CIRCUMVENTION,
                            target = "tunnel",
                            circumvention = CircumventionObservationFact("tunnel", HttpProbeStatus.UNREACHABLE),
                            evidence = listOf("circumvention_blocked"),
                        ),
                    ),
            )
        val assessment = assessmentFor(raw, failedPair)
        assertEquals(ConnectivityAssessmentCode.MIXED_OR_INCONCLUSIVE, assessment.assessmentCode)
        assertEquals(0, assessment.rawPathEvidence.affectedTargetSuccessCount)
        assertEquals(2, assessment.inPathEvidence.affectedTargetFailureCount)
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
    fun `HTTP body timeout is comparable when only one TLS version works`() {
        val host = "example.com"
        val raw = minimalReport(observations = listOf(domainObservation(host, httpStatus = HttpProbeStatus.OK)))
        val observation =
            domainObservation(host, httpStatus = HttpProbeStatus.UNREACHABLE, tls13Status = TlsProbeStatus.OK)
        val timedOut =
            ProbeResult(
                "domain_reachability",
                host,
                "tls_version_split",
                listOf(
                    ProbeDetail("httpStatus", "http_unreachable"),
                    ProbeDetail("httpResponse", "operation timed out"),
                ),
            )
        val inPath =
            minimalReport(
                results = listOf(timedOut),
                observations = listOf(observation),
            ).copy(pathMode = ScanPathMode.IN_PATH)

        assertEquals(ConnectivityAssessmentCode.VPN_PATH_REGRESSION, assessmentFor(raw, inPath).assessmentCode)
    }

    @Test
    fun `plain HTTP success does not hide an invalid control certificate`() {
        val report =
            minimalReport(
                observations =
                    listOf(
                        domainObservation(
                            "control.example",
                            isControl = true,
                            httpStatus = HttpProbeStatus.OK,
                            tls13Status = TlsProbeStatus.CERT_INVALID,
                        ),
                    ),
            )
        val evidence = assessmentFor(report).rawPathEvidence
        assertEquals(0, evidence.controlSuccessCount)
        assertEquals(1, evidence.controlFailureCount)
    }

    @Test
    fun `a local HTTP response cap or a server error does not imply a VPN regression`() {
        val host = "example.com"
        val raw = minimalReport(observations = listOf(domainObservation(host, httpStatus = HttpProbeStatus.OK)))
        val observation =
            domainObservation(host, httpStatus = HttpProbeStatus.UNREACHABLE, tls13Status = TlsProbeStatus.OK)
        val outcomes =
            listOf(
                listOf(
                    ProbeDetail("httpStatus", "http_unreachable"),
                    ProbeDetail("httpResponse", "response_too_large"),
                ),
                listOf(ProbeDetail("httpStatus", "http_status_503"), ProbeDetail("httpResponse", "503 Unavailable")),
            )
        outcomes.forEach { details ->
            val result = ProbeResult("domain_reachability", host, "tls_ok", details)
            val inPath =
                minimalReport(
                    results = listOf(result),
                    observations = listOf(observation),
                ).copy(pathMode = ScanPathMode.IN_PATH)

            assertEquals(ConnectivityAssessmentCode.MIXED_OR_INCONCLUSIVE, assessmentFor(raw, inPath).assessmentCode)
        }
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
