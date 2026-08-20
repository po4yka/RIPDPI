package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CurrentStrategyVerdictEvaluatorTest {
    @Test
    fun `unrelated failed candidate cannot invalidate applied successful current baseline`() {
        val report =
            report(
                baseline = baseline(attempts = listOf(attempt(success = true))),
                otherCandidates = listOf(baseline(id = "split_host", outcome = "failed", attempts = emptyList())),
            )

        val assessment = CurrentStrategyVerdictEvaluator.evaluate(report, StrategyId, StrategyId, ExpectedSignature)

        assertEquals(CurrentStrategyCandidateVerdict.WORKING_ON_TESTED_CANDIDATE_PATH, assessment.candidateVerdict)
        assertEquals(ActiveStrategyPathOutcome.UNVERIFIED, assessment.activePathOutcome)
        assertEquals(StrategyProbeObservationRole.EPHEMERAL_CANDIDATE_RAW_PATH, assessment.observationRole)
        assertEquals(CurrentStrategyLastProvenStage.ENDPOINT_RESPONSE, assessment.lastProvenStage)
    }

    @Test
    fun `all failed endpoints after applied writes prove only candidate path ineffective`() {
        val assessment =
            CurrentStrategyVerdictEvaluator.evaluate(
                report(baseline(attempts = listOf(attempt(success = false), attempt(success = false)))),
                StrategyId,
                StrategyId,
                ExpectedSignature,
            )

        assertEquals(CurrentStrategyCandidateVerdict.INEFFECTIVE_ON_TESTED_CANDIDATE_PATH, assessment.candidateVerdict)
        assertEquals(ActiveStrategyPathOutcome.UNVERIFIED, assessment.activePathOutcome)
        assertEquals(CurrentStrategyLastProvenStage.DESYNC_WRITES_COMMITTED, assessment.lastProvenStage)
    }

    @Test
    fun `missing or non-applied receipt remains unverified execution`() {
        val missing =
            CurrentStrategyVerdictEvaluator.evaluate(
                report(baseline(attempts = listOf(attempt(success = true, receipts = emptyList())))),
                StrategyId,
                StrategyId,
                ExpectedSignature,
            )
        val plainFallback =
            CurrentStrategyVerdictEvaluator.evaluate(
                report(
                    baseline(
                        attempts =
                            listOf(
                                attempt(
                                    success = false,
                                    receipts = listOf(receipt(StrategyExecutionDisposition.PLAN_FAILED_PLAIN_FALLBACK)),
                                ),
                            ),
                    ),
                ),
                StrategyId,
                StrategyId,
                ExpectedSignature,
            )

        assertEquals(CurrentStrategyCandidateVerdict.UNVERIFIED_EXECUTION, missing.candidateVerdict)
        assertEquals(CurrentStrategyCandidateVerdict.UNVERIFIED_EXECUTION, plainFallback.candidateVerdict)
    }

    @Test
    fun `outer or inner partial evidence is incomplete before endpoint verdicts`() {
        val outer =
            CurrentStrategyVerdictEvaluator.evaluate(
                report(baseline(attempts = listOf(attempt(true))))
                    .copy(completionKind = ScanCompletionKind.PARTIAL_RESULTS),
                StrategyId,
                StrategyId,
                ExpectedSignature,
            )
        val inner =
            CurrentStrategyVerdictEvaluator.evaluate(
                report(baseline(attempts = listOf(attempt(true))), StrategyProbeCompletionKind.PARTIAL_RESULTS),
                StrategyId,
                StrategyId,
                ExpectedSignature,
            )

        assertEquals(CurrentStrategyCandidateVerdict.INCOMPLETE, outer.candidateVerdict)
        assertEquals(CurrentStrategyCandidateVerdict.INCOMPLETE, inner.candidateVerdict)
    }

    @Test
    fun `strategy snapshot mismatch cannot be credited`() {
        val assessment =
            CurrentStrategyVerdictEvaluator.evaluate(
                report(baseline(attempts = listOf(attempt(true)))),
                sessionStrategyId = "different",
                expectedStrategyId = StrategyId,
                expectedStrategySignature = ExpectedSignature,
            )

        assertEquals(CurrentStrategyCandidateVerdict.UNVERIFIED_EXECUTION, assessment.candidateVerdict)
        assertEquals(CurrentStrategyEvidenceReason.STRATEGY_SNAPSHOT_MISMATCH, assessment.reason)
    }

    @Test
    fun `receipt for another split marker cannot be credited`() {
        val assessment =
            CurrentStrategyVerdictEvaluator.evaluate(
                report(
                    baseline(
                        attempts =
                            listOf(
                                attempt(
                                    success = true,
                                    receipts =
                                        listOf(
                                            receipt(StrategyExecutionDisposition.APPLIED).copy(markerDelta = 2),
                                        ),
                                ),
                            ),
                    ),
                ),
                StrategyId,
                StrategyId,
                ExpectedSignature,
            )

        assertEquals(CurrentStrategyCandidateVerdict.UNVERIFIED_EXECUTION, assessment.candidateVerdict)
        assertEquals(CurrentStrategyEvidenceReason.STRATEGY_PLAN_MISMATCH, assessment.reason)
    }

    @Test
    fun `partially completed split actions cannot be credited`() {
        val partialReceipt =
            receipt(StrategyExecutionDisposition.APPLIED).copy(
                attemptedActions = 4,
                completedActions = 3,
            )

        val assessment =
            CurrentStrategyVerdictEvaluator.evaluate(
                report(baseline(attempts = listOf(attempt(success = true, receipts = listOf(partialReceipt))))),
                StrategyId,
                StrategyId,
                ExpectedSignature,
            )

        assertEquals(CurrentStrategyCandidateVerdict.UNVERIFIED_EXECUTION, assessment.candidateVerdict)
        assertEquals(CurrentStrategyEvidenceReason.STRATEGY_EXECUTION_NOT_APPLIED, assessment.reason)
    }

    @Test
    fun `udp receipt cannot prove current tcp split host plus one strategy`() {
        val assessment =
            CurrentStrategyVerdictEvaluator.evaluate(
                report(
                    baseline(
                        attempts =
                            listOf(
                                attempt(
                                    success = true,
                                    receipts =
                                        listOf(
                                            receipt(StrategyExecutionDisposition.APPLIED).copy(
                                                transport = StrategyExecutionTransport.UDP,
                                            ),
                                        ),
                                ),
                            ),
                    ),
                ),
                StrategyId,
                StrategyId,
                ExpectedSignature,
            )

        assertEquals(CurrentStrategyCandidateVerdict.UNVERIFIED_EXECUTION, assessment.candidateVerdict)
    }

    @Test
    fun `plain split receipt cannot prove tls record plus split snapshot`() {
        val signature =
            ExpectedSignature.copy(
                desyncMethod = "tlsrec_split",
                chainSummary = "tcp: tlsrec(sniext), split(host+1)",
                tcpStrategyFamily = "tlsrec_split",
                tlsRecordSplitEnabled = true,
                tlsPreludeKind = StrategyTlsPreludeKind.TLS_REC,
                tlsRecordMarker = "sniext",
            )
        val strategyId = signature.stableId()

        val assessment =
            CurrentStrategyVerdictEvaluator.evaluate(
                report(baseline(attempts = listOf(attempt(true)))),
                strategyId,
                strategyId,
                signature,
            )

        assertEquals(CurrentStrategyCandidateVerdict.UNVERIFIED_EXECUTION, assessment.candidateVerdict)
        assertEquals(CurrentStrategyEvidenceReason.STRATEGY_PLAN_MISMATCH, assessment.reason)
    }

    @Test
    fun `configured tls record family remains unverified without applied prelude receipt`() {
        val signature =
            ExpectedSignature.copy(
                desyncMethod = "tlsrec_split",
                chainSummary = "tcp: tlsrec(extlen), split(host+1)",
                tcpStrategyFamily = "tlsrec_split",
                tlsRecordSplitEnabled = true,
                tlsPreludeKind = StrategyTlsPreludeKind.TLS_REC,
                tlsRecordMarker = "extlen",
            )
        val strategyId = signature.stableId()
        val forged =
            receipt(StrategyExecutionDisposition.APPLIED).copy(
                configuredFamily = StrategyExecutionFamily.TLS_RECORD_SPLIT,
                effectiveFamily = StrategyExecutionFamily.TLS_RECORD_SPLIT,
            )

        val assessment =
            CurrentStrategyVerdictEvaluator.evaluate(
                report(baseline(attempts = listOf(attempt(true, listOf(forged))))),
                strategyId,
                strategyId,
                signature,
            )

        assertEquals(CurrentStrategyCandidateVerdict.UNVERIFIED_EXECUTION, assessment.candidateVerdict)
        assertEquals(CurrentStrategyEvidenceReason.STRATEGY_PLAN_MISMATCH, assessment.reason)
    }

    @Test
    fun `exact tls record prelude plus host split receipt proves tested candidate path`() {
        val signature =
            ExpectedSignature.copy(
                desyncMethod = "tlsrec_split",
                chainSummary = "tcp: tlsrec(extlen), split(host+1)",
                tcpStrategyFamily = "tlsrec_split",
                tlsRecordSplitEnabled = true,
                tlsPreludeKind = StrategyTlsPreludeKind.TLS_REC,
                tlsRecordMarker = "extlen",
            )
        val strategyId = signature.stableId()
        val exactReceipt =
            receipt(StrategyExecutionDisposition.APPLIED).copy(
                configuredFamily = StrategyExecutionFamily.TLS_RECORD_SPLIT,
                effectiveFamily = StrategyExecutionFamily.TLS_RECORD_SPLIT,
                tlsRecordPreludeApplied = true,
                tlsPreludeConfiguredCount = 1,
                tlsPreludeAppliedCount = 1,
                tlsPreludeKind = StrategyTlsPreludeKind.TLS_REC,
                tlsPreludeMarkerBase = StrategyOffsetMarkerBase.EXT_LEN,
                tlsPreludeMarkerDelta = 0,
                tlsPreludeResolvedOffset = 42,
            )

        val assessment =
            CurrentStrategyVerdictEvaluator.evaluate(
                report(baseline(attempts = listOf(attempt(true, listOf(exactReceipt))))),
                strategyId,
                strategyId,
                signature,
            )

        assertEquals(CurrentStrategyCandidateVerdict.WORKING_ON_TESTED_CANDIDATE_PATH, assessment.candidateVerdict)
        assertEquals(CurrentStrategyEvidenceReason.APPLIED_ATTEMPT_SUCCEEDED, assessment.reason)
    }

    @Test
    fun `exact tls rand record prelude receipt proves tested candidate path`() {
        val signature =
            ExpectedSignature.copy(
                desyncMethod = "tlsrec_split",
                chainSummary = "tcp: tlsrandrec(sniext+4), split(host+1)",
                tcpStrategyFamily = "tlsrec_split",
                tlsRecordSplitEnabled = true,
                tlsPreludeKind = StrategyTlsPreludeKind.TLS_RAND_REC,
                tlsRecordMarker = "sniext+4",
            )
        val strategyId = signature.stableId()
        val exactReceipt =
            receipt(StrategyExecutionDisposition.APPLIED).copy(
                configuredFamily = StrategyExecutionFamily.TLS_RECORD_SPLIT,
                effectiveFamily = StrategyExecutionFamily.TLS_RECORD_SPLIT,
                tlsRecordPreludeApplied = true,
                tlsPreludeConfiguredCount = 1,
                tlsPreludeAppliedCount = 1,
                tlsPreludeKind = StrategyTlsPreludeKind.TLS_RAND_REC,
                tlsPreludeMarkerBase = StrategyOffsetMarkerBase.SNI_EXT,
                tlsPreludeMarkerDelta = 4,
                tlsPreludeResolvedOffset = 42,
            )

        val assessment =
            CurrentStrategyVerdictEvaluator.evaluate(
                report(baseline(attempts = listOf(attempt(true, listOf(exactReceipt))))),
                strategyId,
                strategyId,
                signature,
            )

        assertEquals(CurrentStrategyCandidateVerdict.WORKING_ON_TESTED_CANDIDATE_PATH, assessment.candidateVerdict)
        assertEquals(CurrentStrategyEvidenceReason.APPLIED_ATTEMPT_SUCCEEDED, assessment.reason)
    }

    @Test
    fun `exact tls record prelude plus disorder receipt proves tested candidate path`() {
        val signature =
            ExpectedSignature.copy(
                desyncMethod = "disorder",
                chainSummary = "tcp: tlsrec(extlen), disorder(host+1)",
                tcpStrategyFamily = "tlsrec_disorder",
                tlsRecordSplitEnabled = true,
                tlsPreludeKind = StrategyTlsPreludeKind.TLS_REC,
                tlsRecordMarker = "extlen",
                splitMarker = null,
            )
        val strategyId = signature.stableId()
        val exactReceipt =
            receipt(StrategyExecutionDisposition.APPLIED).copy(
                configuredFamily = StrategyExecutionFamily.DISORDER,
                effectiveFamily = StrategyExecutionFamily.DISORDER,
                tlsRecordPreludeApplied = true,
                tlsPreludeConfiguredCount = 1,
                tlsPreludeAppliedCount = 1,
                tlsPreludeKind = StrategyTlsPreludeKind.TLS_REC,
                tlsPreludeMarkerBase = StrategyOffsetMarkerBase.EXT_LEN,
                tlsPreludeMarkerDelta = 0,
                tlsPreludeResolvedOffset = 42,
            )

        val assessment =
            CurrentStrategyVerdictEvaluator.evaluate(
                report(baseline(attempts = listOf(attempt(true, listOf(exactReceipt))))),
                strategyId,
                strategyId,
                signature,
            )

        assertEquals(CurrentStrategyCandidateVerdict.WORKING_ON_TESTED_CANDIDATE_PATH, assessment.candidateVerdict)
    }

    @Test
    fun `numeric tls record marker matches absolute execution offset`() {
        val signature =
            ExpectedSignature.copy(
                desyncMethod = "tlsrec_split",
                chainSummary = "tcp: tlsrec(0), split(host+1)",
                tcpStrategyFamily = "tlsrec_split",
                tlsRecordSplitEnabled = true,
                tlsPreludeKind = StrategyTlsPreludeKind.TLS_REC,
                tlsRecordMarker = "0",
            )
        val strategyId = signature.stableId()
        val exactReceipt =
            receipt(StrategyExecutionDisposition.APPLIED).copy(
                configuredFamily = StrategyExecutionFamily.TLS_RECORD_SPLIT,
                effectiveFamily = StrategyExecutionFamily.TLS_RECORD_SPLIT,
                tlsRecordPreludeApplied = true,
                tlsPreludeConfiguredCount = 1,
                tlsPreludeAppliedCount = 1,
                tlsPreludeKind = StrategyTlsPreludeKind.TLS_REC,
                tlsPreludeMarkerBase = StrategyOffsetMarkerBase.ABSOLUTE,
                tlsPreludeMarkerDelta = 0,
                tlsPreludeResolvedOffset = 0,
            )

        val assessment =
            CurrentStrategyVerdictEvaluator.evaluate(
                report(baseline(attempts = listOf(attempt(true, listOf(exactReceipt))))),
                strategyId,
                strategyId,
                signature,
            )

        assertEquals(CurrentStrategyCandidateVerdict.WORKING_ON_TESTED_CANDIDATE_PATH, assessment.candidateVerdict)
    }

    @Test
    fun `unavailable observation role cannot prove candidate or active path`() {
        val assessment =
            CurrentStrategyVerdictEvaluator.evaluate(
                report(
                    baseline(attempts = listOf(attempt(true))).copy(
                        observationRole = StrategyProbeObservationRole.UNAVAILABLE,
                    ),
                ),
                StrategyId,
                StrategyId,
                ExpectedSignature,
            )

        assertEquals(CurrentStrategyCandidateVerdict.UNVERIFIED_EXECUTION, assessment.candidateVerdict)
        assertEquals(ActiveStrategyPathOutcome.UNVERIFIED, assessment.activePathOutcome)
        assertEquals(CurrentStrategyEvidenceReason.OBSERVATION_PATH_MISMATCH, assessment.reason)
    }

    @Test
    fun `authoritative in-path evidence verifies active service path`() {
        val assessment =
            CurrentStrategyVerdictEvaluator.evaluate(
                report(
                    baseline(attempts = listOf(attempt(true))),
                    pathMode = ScanPathMode.IN_PATH,
                    activePathObservation = activePathObservation(StrategyProbeResponseStage.RESPONSE_OBSERVED, true),
                ),
                StrategyId,
                StrategyId,
                ExpectedSignature,
            )

        assertEquals(CurrentStrategyCandidateVerdict.WORKING_ON_TESTED_CANDIDATE_PATH, assessment.candidateVerdict)
        assertEquals(ActiveStrategyPathOutcome.WORKING, assessment.activePathOutcome)
        assertEquals(null, assessment.activePathUnavailableReason)
    }

    @Test
    fun `working active service does not confirm a strategy with unverified execution`() {
        val assessment =
            CurrentStrategyVerdictEvaluator.evaluate(
                report(
                    baseline(attempts = emptyList()).copy(executionEvidenceComplete = false),
                    pathMode = ScanPathMode.IN_PATH,
                    activePathObservation = activePathObservation(StrategyProbeResponseStage.RESPONSE_OBSERVED, true),
                ),
                StrategyId,
                StrategyId,
                ExpectedSignature,
            )

        assertEquals(CurrentStrategyCandidateVerdict.UNVERIFIED_EXECUTION, assessment.candidateVerdict)
        assertEquals(ActiveStrategyPathOutcome.WORKING, assessment.activePathOutcome)
        assertFalse(assessment.isEvaluatedStrategyApproachEvidence())
        assertFalse(assessment.isConfirmedWorkingStrategyApproach())
    }

    @Test
    fun `observed block or interception response does not make active path working`() {
        val assessment =
            CurrentStrategyVerdictEvaluator.evaluate(
                report(
                    baseline(attempts = listOf(attempt(true))),
                    pathMode = ScanPathMode.IN_PATH,
                    activePathObservation =
                        activePathObservation(StrategyProbeResponseStage.RESPONSE_OBSERVED, true).copy(
                            successfulTargets = 0,
                        ),
                ),
                StrategyId,
                StrategyId,
                ExpectedSignature,
            )

        assertEquals(ActiveStrategyPathOutcome.INEFFECTIVE, assessment.activePathOutcome)
    }

    @Test
    fun `incoherent active path response counters remain unverified`() {
        val observations =
            listOf(
                activePathObservation(StrategyProbeResponseStage.RESPONSE_OBSERVED, true).copy(
                    attemptedTargets = 0,
                    responseObservedTargets = 0,
                ),
                activePathObservation(StrategyProbeResponseStage.RESPONSE_OBSERVED, true).copy(
                    attemptedTargets = 1,
                    responseObservedTargets = 0,
                ),
                activePathObservation(StrategyProbeResponseStage.RESPONSE_NOT_OBSERVED, true).copy(
                    attemptedTargets = 1,
                    responseObservedTargets = 1,
                ),
                activePathObservation(StrategyProbeResponseStage.RESPONSE_OBSERVED, true).copy(
                    attemptedTargets = 1,
                    responseObservedTargets = 2,
                ),
            )

        observations.forEach { observation ->
            val assessment =
                CurrentStrategyVerdictEvaluator.evaluate(
                    report(
                        baseline(attempts = listOf(attempt(true))),
                        pathMode = ScanPathMode.IN_PATH,
                        activePathObservation = observation,
                    ),
                    StrategyId,
                    StrategyId,
                    ExpectedSignature,
                )

            assertEquals(ActiveStrategyPathOutcome.UNVERIFIED, assessment.activePathOutcome)
        }
    }

    @Test
    fun `in-path role without owned route authority stays unverified`() {
        val assessment =
            CurrentStrategyVerdictEvaluator.evaluate(
                report(
                    baseline(attempts = listOf(attempt(true))),
                    pathMode = ScanPathMode.IN_PATH,
                    activePathObservation = activePathObservation(StrategyProbeResponseStage.RESPONSE_OBSERVED, false),
                ),
                StrategyId,
                StrategyId,
                ExpectedSignature,
            )

        assertEquals(ActiveStrategyPathOutcome.UNVERIFIED, assessment.activePathOutcome)
        assertEquals(
            ActiveStrategyPathUnavailableReason.ACTIVE_SERVICE_PATH_NOT_OBSERVED,
            assessment.activePathUnavailableReason,
        )
    }

    @Test
    fun `candidate success and active route failure remain separate verdict axes`() {
        val assessment =
            CurrentStrategyVerdictEvaluator.evaluate(
                report(
                    baseline(attempts = listOf(attempt(true))),
                    pathMode = ScanPathMode.IN_PATH,
                    activePathObservation =
                        activePathObservation(StrategyProbeResponseStage.RESPONSE_NOT_OBSERVED, true),
                ),
                StrategyId,
                StrategyId,
                ExpectedSignature,
            )

        assertEquals(CurrentStrategyCandidateVerdict.WORKING_ON_TESTED_CANDIDATE_PATH, assessment.candidateVerdict)
        assertEquals(ActiveStrategyPathOutcome.INEFFECTIVE, assessment.activePathOutcome)
    }

    @Test
    fun `negative active observation without a reached owned route remains unverified`() {
        val assessment =
            CurrentStrategyVerdictEvaluator.evaluate(
                report(
                    baseline(attempts = listOf(attempt(true))),
                    pathMode = ScanPathMode.IN_PATH,
                    activePathObservation =
                        activePathObservation(StrategyProbeResponseStage.RESPONSE_NOT_OBSERVED, true).copy(
                            routeReachedTargets = 0,
                        ),
                ),
                StrategyId,
                StrategyId,
                ExpectedSignature,
            )

        assertEquals(ActiveStrategyPathOutcome.UNVERIFIED, assessment.activePathOutcome)
    }

    @Test
    fun `candidate failure and active route success remain separate verdict axes`() {
        val assessment =
            CurrentStrategyVerdictEvaluator.evaluate(
                report(
                    baseline(attempts = listOf(attempt(false))),
                    pathMode = ScanPathMode.IN_PATH,
                    activePathObservation = activePathObservation(StrategyProbeResponseStage.RESPONSE_OBSERVED, true),
                ),
                StrategyId,
                StrategyId,
                ExpectedSignature,
            )

        assertEquals(CurrentStrategyCandidateVerdict.INEFFECTIVE_ON_TESTED_CANDIDATE_PATH, assessment.candidateVerdict)
        assertEquals(ActiveStrategyPathOutcome.WORKING, assessment.activePathOutcome)
    }

    @Test
    fun `partial split action receipt cannot prove current strategy`() {
        val partial =
            receipt(StrategyExecutionDisposition.APPLIED).copy(
                resolvedOffset = null,
                realWritesCommitted = 1,
                completedAwaits = 0,
                completedActions = 1,
            )
        val assessment =
            CurrentStrategyVerdictEvaluator.evaluate(
                report(baseline(attempts = listOf(attempt(true, listOf(partial))))),
                StrategyId,
                StrategyId,
                ExpectedSignature,
            )

        assertEquals(CurrentStrategyCandidateVerdict.UNVERIFIED_EXECUTION, assessment.candidateVerdict)
        assertEquals(CurrentStrategyEvidenceReason.STRATEGY_EXECUTION_NOT_APPLIED, assessment.reason)
    }

    @Test
    fun `sanitized autolearn baseline cannot be attributed to active snapshot`() {
        val assessment =
            CurrentStrategyVerdictEvaluator.evaluate(
                report(baseline(attempts = listOf(attempt(true))).copy(activeSnapshotFaithful = false)),
                StrategyId,
                StrategyId,
                ExpectedSignature,
            )

        assertEquals(CurrentStrategyCandidateVerdict.UNVERIFIED_EXECUTION, assessment.candidateVerdict)
        assertEquals(CurrentStrategyEvidenceReason.STRATEGY_SNAPSHOT_MISMATCH, assessment.reason)
    }

    @Test
    fun `runtime failure cannot be credited despite applied writes and response`() {
        val assessment =
            CurrentStrategyVerdictEvaluator.evaluate(
                report(
                    baseline(attempts = listOf(attempt(true))).copy(
                        runtimeTerminalStatus = StrategyProbeRuntimeTerminalStatus.RUNTIME_FAILED,
                    ),
                ),
                StrategyId,
                StrategyId,
                ExpectedSignature,
            )

        assertEquals(CurrentStrategyCandidateVerdict.UNVERIFIED_EXECUTION, assessment.candidateVerdict)
        assertEquals(CurrentStrategyEvidenceReason.RUNTIME_TERMINAL_FAILURE, assessment.reason)
    }

    @Test
    fun `contradictory response stage cannot be credited`() {
        val succeededWithoutResponse =
            attempt(true).copy(responseStage = StrategyProbeResponseStage.RESPONSE_NOT_OBSERVED)
        val failedWithResponse = attempt(false).copy(responseStage = StrategyProbeResponseStage.RESPONSE_OBSERVED)

        listOf(succeededWithoutResponse, failedWithResponse).forEach { contradictoryAttempt ->
            val assessment =
                CurrentStrategyVerdictEvaluator.evaluate(
                    report(baseline(attempts = listOf(contradictoryAttempt))),
                    StrategyId,
                    StrategyId,
                    ExpectedSignature,
                )

            assertEquals(CurrentStrategyCandidateVerdict.UNVERIFIED_EXECUTION, assessment.candidateVerdict)
            assertEquals(CurrentStrategyEvidenceReason.RESPONSE_STAGE_MISMATCH, assessment.reason)
        }
    }

    @Test
    fun `unavailable terminal and response evidence stays unverified`() {
        val unavailableTerminal =
            CurrentStrategyVerdictEvaluator.evaluate(
                report(
                    baseline(attempts = listOf(attempt(true))).copy(
                        runtimeTerminalStatus = StrategyProbeRuntimeTerminalStatus.UNAVAILABLE,
                    ),
                ),
                StrategyId,
                StrategyId,
                ExpectedSignature,
            )
        val unavailableResponse =
            CurrentStrategyVerdictEvaluator.evaluate(
                report(
                    baseline(
                        attempts = listOf(attempt(true).copy(responseStage = StrategyProbeResponseStage.UNAVAILABLE)),
                    ),
                ),
                StrategyId,
                StrategyId,
                ExpectedSignature,
            )

        assertEquals(CurrentStrategyEvidenceReason.RUNTIME_TERMINAL_FAILURE, unavailableTerminal.reason)
        assertEquals(CurrentStrategyEvidenceReason.RESPONSE_STAGE_MISMATCH, unavailableResponse.reason)
    }

    @Test
    fun `duplicate or gapped connection ordinals stay unverified`() {
        listOf(listOf(1, 1), listOf(1, 3)).forEach { ordinals ->
            val receipts =
                ordinals.map { ordinal ->
                    receipt(StrategyExecutionDisposition.APPLIED).copy(connectionOrdinal = ordinal)
                }
            val assessment =
                CurrentStrategyVerdictEvaluator.evaluate(
                    report(baseline(attempts = listOf(attempt(true, receipts)))),
                    StrategyId,
                    StrategyId,
                    ExpectedSignature,
                )

            assertEquals(CurrentStrategyCandidateVerdict.UNVERIFIED_EXECUTION, assessment.candidateVerdict)
            assertEquals(CurrentStrategyEvidenceReason.ATTEMPT_EVIDENCE_MISSING, assessment.reason)
        }
    }

    private fun report(
        baseline: StrategyProbeCandidateSummary,
        completionKind: StrategyProbeCompletionKind = StrategyProbeCompletionKind.NORMAL,
        otherCandidates: List<StrategyProbeCandidateSummary> = emptyList(),
        pathMode: ScanPathMode = ScanPathMode.RAW_PATH,
        activePathObservation: StrategyActivePathObservation? = null,
    ) = ScanReport(
        sessionId = "session",
        profileId = "automatic-audit",
        pathMode = pathMode,
        startedAt = 1,
        finishedAt = 2,
        summary = "complete",
        strategyProbeReport =
            StrategyProbeReport(
                suiteId = "quick_v1",
                tcpCandidates = listOf(baseline) + otherCandidates,
                completionKind = completionKind,
                activePathObservation = activePathObservation,
            ),
    )

    private fun activePathObservation(
        responseStage: StrategyProbeResponseStage,
        owned: Boolean,
    ) = StrategyActivePathObservation(
        role = StrategyProbeObservationRole.ACTIVE_SERVICE_IN_PATH,
        responseStage = responseStage,
        activePathAuthority =
            if (owned) {
                StrategyActivePathAuthority.OWNED_ROUTE_LEASE_AT_SCAN
            } else {
                StrategyActivePathAuthority.UNVERIFIED
            },
        attemptedTargets = 1,
        routeReachedTargets = 1,
        responseObservedTargets = if (responseStage == StrategyProbeResponseStage.RESPONSE_OBSERVED) 1 else 0,
        successfulTargets = if (responseStage == StrategyProbeResponseStage.RESPONSE_OBSERVED) 1 else 0,
    )

    private fun baseline(
        id: String = "baseline_current",
        outcome: String = "success",
        attempts: List<StrategyProbeAttemptExecutionEvidence>,
    ) = StrategyProbeCandidateSummary(
        id = id,
        label = id,
        family = "baseline",
        outcome = outcome,
        rationale = "typed evidence",
        succeededTargets = attempts.count(StrategyProbeAttemptExecutionEvidence::probeSucceeded),
        totalTargets = attempts.size,
        weightedSuccessScore = 0,
        totalWeight = attempts.size,
        qualityScore = 0,
        observationRole = StrategyProbeObservationRole.EPHEMERAL_CANDIDATE_RAW_PATH,
        activeSnapshotFaithful = true,
        runtimeTerminalStatus = StrategyProbeRuntimeTerminalStatus.CLEAN_SHUTDOWN,
        executionEvidenceComplete =
            attempts.isNotEmpty() && attempts.all { it.complete && it.receipts.isNotEmpty() },
        executionAttempts = attempts,
    )

    private fun attempt(
        success: Boolean,
        receipts: List<StrategyDesyncExecutionReceipt> = listOf(receipt(StrategyExecutionDisposition.APPLIED)),
    ) = StrategyProbeAttemptExecutionEvidence(
        probeSucceeded = success,
        complete = receipts.isNotEmpty(),
        responseStage =
            if (success) {
                StrategyProbeResponseStage.RESPONSE_OBSERVED
            } else {
                StrategyProbeResponseStage.RESPONSE_NOT_OBSERVED
            },
        receipts = receipts,
    )

    private fun receipt(disposition: StrategyExecutionDisposition) =
        StrategyDesyncExecutionReceipt(
            connectionOrdinal = 1,
            disposition = disposition,
            configuredFamily = StrategyExecutionFamily.SPLIT,
            effectiveFamily =
                StrategyExecutionFamily.SPLIT.takeIf {
                    disposition == StrategyExecutionDisposition.APPLIED
                },
            markerBase = StrategyOffsetMarkerBase.HOST,
            markerDelta = 1,
            resolvedOffset = 16,
            plannedSteps = 1,
            attemptedActions = 3,
            completedActions = 3,
            realWritesCommitted = if (disposition == StrategyExecutionDisposition.APPLIED) 2 else 1,
            completedAwaits = if (disposition == StrategyExecutionDisposition.APPLIED) 1 else 0,
            payloadBytesCommitted = 32,
        )

    private companion object {
        val ExpectedSignature =
            BypassStrategySignature(
                mode = "VPN",
                configSource = "ui",
                hostAutolearn = "disabled",
                desyncMethod = "split",
                chainSummary = "tcp: split(host+1)",
                tcpStrategyFamily = "split",
                protocolToggles = listOf("HTTPS"),
                tlsRecordSplitEnabled = false,
                splitMarker = "host+1",
            )
        val StrategyId = ExpectedSignature.stableId()
    }
}
