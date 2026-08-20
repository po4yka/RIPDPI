package com.poyka.ripdpi.diagnostics

internal object CurrentStrategyVerdictEvaluator {
    fun evaluate(
        report: ScanReport,
        sessionStrategyId: String?,
        expectedStrategyId: String,
        expectedStrategySignature: BypassStrategySignature?,
    ): CurrentStrategyAssessment =
        validateScanAndSnapshot(
            report = report,
            sessionStrategyId = sessionStrategyId,
            expectedStrategyId = expectedStrategyId,
            expectedStrategySignature = expectedStrategySignature,
        ) ?: assessStrategyProbe(report, requireNotNull(expectedStrategySignature))

    private fun validateScanAndSnapshot(
        report: ScanReport,
        sessionStrategyId: String?,
        expectedStrategyId: String,
        expectedStrategySignature: BypassStrategySignature?,
    ): CurrentStrategyAssessment? =
        when {
            report.completionKind != ScanCompletionKind.NORMAL || report.terminationReason != null -> {
                incomplete(CurrentStrategyEvidenceReason.OUTER_SCAN_INCOMPLETE)
            }

            !strategySnapshotMatches(sessionStrategyId, expectedStrategyId, expectedStrategySignature) -> {
                unverified(CurrentStrategyEvidenceReason.STRATEGY_SNAPSHOT_MISMATCH)
            }

            else -> {
                null
            }
        }

    private fun strategySnapshotMatches(
        sessionStrategyId: String?,
        expectedStrategyId: String,
        expectedStrategySignature: BypassStrategySignature?,
    ): Boolean =
        !sessionStrategyId.isNullOrBlank() &&
            sessionStrategyId == expectedStrategyId &&
            expectedStrategySignature?.stableId() == sessionStrategyId

    private fun assessStrategyProbe(
        report: ScanReport,
        expectedStrategySignature: BypassStrategySignature,
    ): CurrentStrategyAssessment {
        val strategyProbe = report.strategyProbeReport
        val baselines = strategyProbe?.tcpCandidates?.filter { it.id == BaselineCurrentCandidateId }.orEmpty()
        return when {
            strategyProbe?.completionKind != StrategyProbeCompletionKind.NORMAL -> {
                incomplete(CurrentStrategyEvidenceReason.INNER_STRATEGY_SCAN_INCOMPLETE)
            }

            baselines.size != 1 -> {
                incomplete(CurrentStrategyEvidenceReason.BASELINE_MISSING_OR_AMBIGUOUS)
            }

            else -> {
                assessBaseline(
                    pathMode = report.pathMode,
                    baseline = baselines.single(),
                    expectedStrategySignature = expectedStrategySignature,
                    activePathObservation = strategyProbe.activePathObservation,
                )
            }
        }
    }

    private fun assessBaseline(
        pathMode: ScanPathMode,
        baseline: StrategyProbeCandidateSummary,
        expectedStrategySignature: BypassStrategySignature,
        activePathObservation: StrategyActivePathObservation?,
    ): CurrentStrategyAssessment {
        val pathAssessment =
            assessObservationPath(
                pathMode = pathMode,
                candidateRole = baseline.observationRole,
                activePathObservation = activePathObservation,
                signature = expectedStrategySignature,
            )
        val attempts = baseline.executionAttempts
        return when {
            !baseline.activeSnapshotFaithful -> {
                unverified(
                    reason = CurrentStrategyEvidenceReason.STRATEGY_SNAPSHOT_MISMATCH,
                    observationRole = baseline.observationRole,
                    activePathUnavailableReason = pathAssessment?.unavailableReason,
                )
            }

            pathAssessment == null -> {
                unverified(
                    reason = CurrentStrategyEvidenceReason.OBSERVATION_PATH_MISMATCH,
                    observationRole = baseline.observationRole,
                    activePathUnavailableReason = ActiveStrategyPathUnavailableReason.OBSERVATION_ROLE_UNAVAILABLE,
                )
            }

            !baseline.runtimeTerminalStatus.isEvaluable() -> {
                executionUnverified(
                    baseline,
                    pathAssessment,
                    CurrentStrategyEvidenceReason.RUNTIME_TERMINAL_FAILURE,
                )
            }

            !baseline.hasCompleteAttemptEvidence() -> {
                executionUnverified(
                    baseline,
                    pathAssessment,
                    CurrentStrategyEvidenceReason.ATTEMPT_EVIDENCE_MISSING,
                )
            }

            !attempts.haveCoherentResponseStages() -> {
                executionUnverified(
                    baseline,
                    pathAssessment,
                    CurrentStrategyEvidenceReason.RESPONSE_STAGE_MISMATCH,
                )
            }

            !attempts.allApplied() -> {
                executionUnverified(
                    baseline,
                    pathAssessment,
                    CurrentStrategyEvidenceReason.STRATEGY_EXECUTION_NOT_APPLIED,
                )
            }

            !attempts.allMatch(expectedStrategySignature) -> {
                executionUnverified(
                    baseline,
                    pathAssessment,
                    CurrentStrategyEvidenceReason.STRATEGY_PLAN_MISMATCH,
                )
            }

            else -> {
                evaluatedAssessment(baseline, pathAssessment)
            }
        }
    }

    private fun executionUnverified(
        baseline: StrategyProbeCandidateSummary,
        pathAssessment: ObservationPathAssessment,
        reason: CurrentStrategyEvidenceReason,
    ): CurrentStrategyAssessment =
        CurrentStrategyAssessment(
            candidateVerdict = CurrentStrategyCandidateVerdict.UNVERIFIED_EXECUTION,
            activePathOutcome = pathAssessment.outcome,
            observationRole = baseline.observationRole,
            reason = reason,
            lastProvenStage = CurrentStrategyLastProvenStage.CANDIDATE_RUNTIME,
            activePathUnavailableReason = pathAssessment.unavailableReason,
        )

    private fun evaluatedAssessment(
        baseline: StrategyProbeCandidateSummary,
        pathAssessment: ObservationPathAssessment,
    ): CurrentStrategyAssessment {
        val succeeded =
            baseline.executionAttempts.any {
                it.responseStage == StrategyProbeResponseStage.RESPONSE_OBSERVED
            }
        return CurrentStrategyAssessment(
            candidateVerdict =
                if (succeeded) {
                    CurrentStrategyCandidateVerdict.WORKING_ON_TESTED_CANDIDATE_PATH
                } else {
                    CurrentStrategyCandidateVerdict.INEFFECTIVE_ON_TESTED_CANDIDATE_PATH
                },
            activePathOutcome = pathAssessment.outcome,
            observationRole = baseline.observationRole,
            reason =
                if (succeeded) {
                    CurrentStrategyEvidenceReason.APPLIED_ATTEMPT_SUCCEEDED
                } else {
                    CurrentStrategyEvidenceReason.APPLIED_ATTEMPTS_FAILED
                },
            lastProvenStage =
                if (succeeded) {
                    CurrentStrategyLastProvenStage.ENDPOINT_RESPONSE
                } else {
                    CurrentStrategyLastProvenStage.DESYNC_WRITES_COMMITTED
                },
            activePathUnavailableReason = pathAssessment.unavailableReason,
        )
    }

    private fun assessObservationPath(
        pathMode: ScanPathMode,
        candidateRole: StrategyProbeObservationRole,
        activePathObservation: StrategyActivePathObservation?,
        signature: BypassStrategySignature,
    ): ObservationPathAssessment? {
        if (candidateRole != StrategyProbeObservationRole.EPHEMERAL_CANDIDATE_RAW_PATH) {
            return null
        }
        return when (pathMode) {
            ScanPathMode.RAW_PATH -> {
                ObservationPathAssessment(
                    outcome = ActiveStrategyPathOutcome.UNVERIFIED,
                    unavailableReason = rawPathUnavailableReason(signature),
                )
            }

            ScanPathMode.IN_PATH -> {
                if (
                    activePathObservation?.role == StrategyProbeObservationRole.ACTIVE_SERVICE_IN_PATH &&
                    activePathObservation.activePathAuthority ==
                    StrategyActivePathAuthority.OWNED_ROUTE_LEASE_AT_SCAN &&
                    activePathObservation.hasCoherentResponseCounts()
                ) {
                    when (activePathObservation.responseStage) {
                        StrategyProbeResponseStage.RESPONSE_OBSERVED -> {
                            ObservationPathAssessment(
                                outcome =
                                    if (activePathObservation.successfulTargets > 0) {
                                        ActiveStrategyPathOutcome.WORKING
                                    } else {
                                        ActiveStrategyPathOutcome.INEFFECTIVE
                                    },
                                unavailableReason = null,
                            )
                        }

                        StrategyProbeResponseStage.RESPONSE_NOT_OBSERVED -> {
                            ObservationPathAssessment(
                                outcome = ActiveStrategyPathOutcome.INEFFECTIVE,
                                unavailableReason = null,
                            )
                        }

                        StrategyProbeResponseStage.UNAVAILABLE -> {
                            ObservationPathAssessment(
                                outcome = ActiveStrategyPathOutcome.UNVERIFIED,
                                unavailableReason =
                                    ActiveStrategyPathUnavailableReason.ACTIVE_SERVICE_PATH_NOT_OBSERVED,
                            )
                        }
                    }
                } else {
                    ObservationPathAssessment(
                        outcome = ActiveStrategyPathOutcome.UNVERIFIED,
                        unavailableReason = ActiveStrategyPathUnavailableReason.ACTIVE_SERVICE_PATH_NOT_OBSERVED,
                    )
                }
            }
        }
    }

    private data class ObservationPathAssessment(
        val outcome: ActiveStrategyPathOutcome,
        val unavailableReason: ActiveStrategyPathUnavailableReason?,
    )

    private const val BaselineCurrentCandidateId = "baseline_current"
}

private const val HostSplitActionCount = 3

private fun StrategyProbeRuntimeTerminalStatus.isEvaluable(): Boolean =
    this == StrategyProbeRuntimeTerminalStatus.CLEAN_SHUTDOWN ||
        this == StrategyProbeRuntimeTerminalStatus.FORCED_ABORT

private fun StrategyProbeCandidateSummary.hasCompleteAttemptEvidence(): Boolean =
    executionEvidenceComplete &&
        executionAttempts.isNotEmpty() &&
        executionAttempts.all { attempt ->
            attempt.complete &&
                attempt.receipts.isNotEmpty() &&
                attempt.receipts
                    .sortedBy(StrategyDesyncExecutionReceipt::connectionOrdinal)
                    .map(StrategyDesyncExecutionReceipt::connectionOrdinal) ==
                (1..attempt.receipts.size).toList()
        }

private fun List<StrategyProbeAttemptExecutionEvidence>.haveCoherentResponseStages(): Boolean =
    all { attempt ->
        when (attempt.responseStage) {
            StrategyProbeResponseStage.RESPONSE_OBSERVED -> attempt.probeSucceeded
            StrategyProbeResponseStage.RESPONSE_NOT_OBSERVED -> !attempt.probeSucceeded
            StrategyProbeResponseStage.UNAVAILABLE -> false
        }
    }

private fun List<StrategyProbeAttemptExecutionEvidence>.allApplied(): Boolean =
    all { attempt ->
        attempt.receipts.all(StrategyDesyncExecutionReceipt::isCoherentAppliedReceipt)
    }

private fun StrategyDesyncExecutionReceipt.isCoherentAppliedReceipt(): Boolean =
    disposition == StrategyExecutionDisposition.APPLIED &&
        configuredFamily != null &&
        effectiveFamily != null &&
        plannedSteps > 0 &&
        attemptedActions > 0 &&
        completedActions == attemptedActions &&
        realWritesCommitted > 0 &&
        payloadBytesCommitted > 0 &&
        terminalReason == null

private fun List<StrategyProbeAttemptExecutionEvidence>.allMatch(signature: BypassStrategySignature): Boolean =
    all { attempt -> attempt.receipts.all { it.matches(signature) } }

private fun incomplete(reason: CurrentStrategyEvidenceReason) =
    CurrentStrategyAssessment(
        candidateVerdict = CurrentStrategyCandidateVerdict.INCOMPLETE,
        observationRole = StrategyProbeObservationRole.UNAVAILABLE,
        reason = reason,
    )

private fun unverified(
    reason: CurrentStrategyEvidenceReason,
    observationRole: StrategyProbeObservationRole = StrategyProbeObservationRole.UNAVAILABLE,
    lastProvenStage: CurrentStrategyLastProvenStage = CurrentStrategyLastProvenStage.NONE,
    activePathUnavailableReason: ActiveStrategyPathUnavailableReason? = null,
) = CurrentStrategyAssessment(
    candidateVerdict = CurrentStrategyCandidateVerdict.UNVERIFIED_EXECUTION,
    observationRole = observationRole,
    reason = reason,
    lastProvenStage = lastProvenStage,
    activePathUnavailableReason = activePathUnavailableReason,
)

private fun rawPathUnavailableReason(signature: BypassStrategySignature) =
    if (signature.mode.equals("VPN", ignoreCase = true)) {
        ActiveStrategyPathUnavailableReason.ACTIVE_VPN_PATH_NOT_OBSERVED
    } else {
        ActiveStrategyPathUnavailableReason.ACTIVE_SERVICE_PATH_NOT_OBSERVED
    }

private fun StrategyDesyncExecutionReceipt.matches(signature: BypassStrategySignature): Boolean {
    val expectedFamily = expectedFamily(signature)
    val supportedTcpFamily = transport == StrategyExecutionTransport.TCP
    val expectedMarker = signature.splitMarker?.let(::parseMarker)
    val familyMatches =
        expectedFamily != null && configuredFamily == expectedFamily && effectiveFamily == expectedFamily
    val markerMatches =
        if (signature.splitMarker == null) {
            true
        } else {
            expectedMarker != null && markerBase == expectedMarker.first && markerDelta == expectedMarker.second
        }
    val exactSplitShape =
        if (
            expectedFamily in setOf(StrategyExecutionFamily.SPLIT, StrategyExecutionFamily.TLS_RECORD_SPLIT) &&
            signature.splitMarker != null
        ) {
            resolvedOffset != null &&
                plannedSteps >= 1 &&
                attemptedActions >= HostSplitActionCount &&
                completedActions >= HostSplitActionCount &&
                realWritesCommitted >= 2 &&
                completedAwaits >= 1 &&
                fallbackReason == null
        } else {
            true
        }
    val tlsPreludeMatches = matchesTlsPrelude(signature)
    return supportedTcpFamily && familyMatches && markerMatches && exactSplitShape && tlsPreludeMatches
}

private fun StrategyDesyncExecutionReceipt.matchesTlsPrelude(signature: BypassStrategySignature): Boolean =
    if (signature.tlsPreludeKind == null && signature.tlsRecordMarker == null) {
        !tlsRecordPreludeApplied &&
            tlsPreludeConfiguredCount == 0 &&
            tlsPreludeAppliedCount == 0 &&
            tlsPreludeKind == null &&
            tlsPreludeMarkerBase == null &&
            tlsPreludeMarkerDelta == null &&
            tlsPreludeResolvedOffset == null
    } else {
        val expectedMarker = signature.tlsRecordMarker?.let(::parseMarker)
        expectedMarker != null &&
            signature.tlsPreludeKind != null &&
            tlsRecordPreludeApplied &&
            tlsPreludeConfiguredCount == 1 &&
            tlsPreludeAppliedCount == 1 &&
            tlsPreludeKind == signature.tlsPreludeKind &&
            tlsPreludeMarkerBase == expectedMarker.first &&
            tlsPreludeMarkerDelta == expectedMarker.second &&
            tlsPreludeResolvedOffset != null
    }

private fun expectedFamily(signature: BypassStrategySignature): StrategyExecutionFamily? {
    val normalized =
        signature.tcpStrategyFamily
            ?.removePrefix("circular_")
            ?.takeUnless { it == "fake_approx" }
            ?: signature.desyncMethod
    return when (normalized) {
        "split" -> StrategyExecutionFamily.SPLIT
        "tlsrec_split" -> StrategyExecutionFamily.TLS_RECORD_SPLIT
        "seqovl" -> StrategyExecutionFamily.SEQ_OVERLAP
        "tlsrec_seqovl" -> StrategyExecutionFamily.TLS_RECORD_SEQ_OVERLAP
        "multidisorder" -> StrategyExecutionFamily.MULTI_DISORDER
        "tlsrec_multidisorder" -> StrategyExecutionFamily.TLS_RECORD_MULTI_DISORDER
        "disorder", "tlsrec_disorder" -> StrategyExecutionFamily.DISORDER
        "oob" -> StrategyExecutionFamily.OUT_OF_BAND
        "disoob" -> StrategyExecutionFamily.DISORDERED_OUT_OF_BAND
        "fake", "tlsrec_fake" -> StrategyExecutionFamily.FAKE
        "fakedsplit" -> StrategyExecutionFamily.FAKE_SPLIT
        "fakeddisorder" -> StrategyExecutionFamily.FAKE_DISORDER
        "hostfake" -> StrategyExecutionFamily.HOST_FAKE
        "ipfrag2" -> StrategyExecutionFamily.IP_FRAGMENT2
        "fakerst" -> StrategyExecutionFamily.FAKE_RST
        "tlsrec", "tlsrandrec" -> StrategyExecutionFamily.TLS_RECORD
        else -> null
    }
}

private fun parseMarker(value: String): Pair<StrategyOffsetMarkerBase, Int>? =
    value.toIntOrNull()?.let { StrategyOffsetMarkerBase.ABSOLUTE to it }
        ?: MarkerPattern.matchEntire(value)?.let { match ->
            parseMarkerBase(match.groupValues[1])?.let { base ->
                base to (match.groupValues[2].toIntOrNull() ?: 0)
            }
        }

private fun parseMarkerBase(value: String): StrategyOffsetMarkerBase? =
    when (value) {
        "abs" -> StrategyOffsetMarkerBase.ABSOLUTE
        "payloadend" -> StrategyOffsetMarkerBase.PAYLOAD_END
        "payloadmid" -> StrategyOffsetMarkerBase.PAYLOAD_MID
        "payloadrand" -> StrategyOffsetMarkerBase.PAYLOAD_RAND
        "host" -> StrategyOffsetMarkerBase.HOST
        "endhost" -> StrategyOffsetMarkerBase.END_HOST
        "hostmid" -> StrategyOffsetMarkerBase.HOST_MID
        "hostrand" -> StrategyOffsetMarkerBase.HOST_RAND
        "sld" -> StrategyOffsetMarkerBase.SLD
        "midsld" -> StrategyOffsetMarkerBase.MID_SLD
        "endsld" -> StrategyOffsetMarkerBase.END_SLD
        "method" -> StrategyOffsetMarkerBase.METHOD
        "extlen" -> StrategyOffsetMarkerBase.EXT_LEN
        "echext" -> StrategyOffsetMarkerBase.ECH_EXT
        "sniext" -> StrategyOffsetMarkerBase.SNI_EXT
        else -> null
    }

private val MarkerPattern = Regex("([a-z]+)([+-]\\d+)?")
