package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.data.diagnostics.retryCount
import com.poyka.ripdpi.diagnostics.ConnectivityServiceRuntimeAssessment
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.ObservationFact
import com.poyka.ripdpi.diagnostics.ResolverRecommendation
import com.poyka.ripdpi.diagnostics.StrategyEmitterTier
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditAssessment
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateSummary
import com.poyka.ripdpi.diagnostics.StrategyProbeReport
import com.poyka.ripdpi.diagnostics.TransportFailureKind

private const val TlsErrorSampleLimit = 5
private const val AcceptanceMatrixCoverageMin = 75
private const val AcceptanceWinnerCoverageMin = 60
private const val RecommendedLatencyBudgetMs = 250L
private const val InstabilityRetryBudget = 2L
private val KnownRuntimeCapabilityIds =
    setOf(
        "ttl_write",
        "raw_tcp_fake_send",
        "raw_udp_fragmentation",
        "replacement_socket",
        "root_helper_available",
        "vpn_protect_callback",
        "network_binding",
    )

internal fun buildAnalysis(
    selection: DiagnosticsArchiveSelection,
    redactor: DiagnosticsArchiveRedactor,
): DiagnosticsArchiveAnalysisPayload {
    val telemetry =
        selection.payload.telemetry
            .map { it.redactForArchive() }
            .sortedBy { it.createdAt }
    val failureSamples =
        telemetry.filter {
            !it.failureClass.isNullOrBlank() ||
                !it.lastFailureClass.isNullOrBlank() ||
                !it.lastFallbackAction.isNullOrBlank()
        }
    val latestTelemetry =
        selection.payload.telemetry
            .firstOrNull()
            ?.redactForArchive()
    val projectedReport = redactor.redact(selection.primaryReport)
    val strategyProbe = projectedReport?.strategyProbeReport
    val observations = projectedReport?.observations.orEmpty()
    val measurementSnapshot = buildMeasurementSnapshot(selection, strategyProbe, latestTelemetry)
    return DiagnosticsArchiveAnalysisPayload(
        failureEnvelope = buildFailureEnvelope(failureSamples, latestTelemetry),
        strategyExecutionDetail = buildStrategyExecutionDetail(strategyProbe, observations),
        recommendationTrace = buildRecommendationTrace(selection, projectedReport),
        measurementSnapshot = measurementSnapshot,
        connectivityAssessment = redactor.redact(selection.homeCompositeOutcome?.connectivityAssessment),
        runtimeSnapshotTimeline = buildRuntimeSnapshotTimeline(selection, redactor),
    )
}

private fun buildRuntimeSnapshotTimeline(
    selection: DiagnosticsArchiveSelection,
    redactor: DiagnosticsArchiveRedactor,
): List<DiagnosticsArchiveRuntimeSnapshotTimelineEntry> {
    val assessment =
        redactor
            .redact(selection.homeCompositeOutcome?.connectivityAssessment)
            ?.serviceRuntimeAssessment
    return buildList {
        selection.primaryContexts.mapNotNullTo(this) { entity ->
            redactor.decodeDiagnosticContext(entity)?.let(redactor::redact)?.toTimelineEntry(
                source = "scan_session",
                capturedAt = entity.capturedAt,
            )
        }
        assessment?.let { add(it.toTimelineEntry()) }
        selection.latestPassiveContext?.let { entity ->
            redactor.decodeDiagnosticContext(entity)?.let(redactor::redact)?.let { context ->
                add(
                    context.toTimelineEntry(
                        source = "passive_runtime",
                        capturedAt = entity.capturedAt,
                    ),
                )
            }
        }
    }.sortedWith(compareBy(nullsLast()) { it.capturedAt })
}

private fun DiagnosticContextModel.toTimelineEntry(
    source: String,
    capturedAt: Long,
) = DiagnosticsArchiveRuntimeSnapshotTimelineEntry(
    source = source,
    capturedAt = capturedAt,
    serviceStatus = service.serviceStatus,
    proxyHealth = service.proxy?.health,
    tunnelHealth = service.tunnel?.health,
    relayHealth = service.relay?.health,
    warpHealth = service.warp?.health,
)

private fun ConnectivityServiceRuntimeAssessment.toTimelineEntry() =
    DiagnosticsArchiveRuntimeSnapshotTimelineEntry(
        source = "assessment",
        capturedAt = capturedAt,
        serviceStatus = serviceStatus,
        proxyHealth = proxy?.health,
        tunnelHealth = tunnel?.health,
        relayHealth = relay?.health,
        warpHealth = warp?.health,
    )

private fun buildFailureEnvelope(
    failureSamples: List<TelemetrySampleEntity>,
    latestTelemetry: TelemetrySampleEntity?,
): DiagnosticsArchiveFailureEnvelope {
    val latestFailureSample = failureSamples.lastOrNull()
    return DiagnosticsArchiveFailureEnvelope(
        firstFailureTimestamp = failureSamples.firstOrNull()?.createdAt,
        lastFailureTimestamp = latestFailureSample?.createdAt,
        latestFailureClass = latestFailureSample?.failureClass ?: latestFailureSample?.lastFailureClass,
        lastFallbackAction = latestFailureSample?.lastFallbackAction,
        retryCounters =
            DiagnosticsArchiveRetryCounters(
                proxyRouteRetryCount = latestTelemetry?.proxyRouteRetryCount ?: 0,
                tunnelRecoveryRetryCount = latestTelemetry?.tunnelRecoveryRetryCount ?: 0,
                totalRetryCount = latestTelemetry?.retryCount() ?: 0,
            ),
        failureClassTransitions =
            failureSamples
                .flatMap { sample -> listOfNotNull(sample.failureClass, sample.lastFailureClass) }
                .distinctConsecutive(),
    )
}

private fun buildStrategyExecutionDetail(
    strategyProbe: StrategyProbeReport?,
    observations: List<ObservationFact>,
): DiagnosticsArchiveStrategyExecutionDetail =
    DiagnosticsArchiveStrategyExecutionDetail(
        suiteId = strategyProbe?.suiteId,
        completionKind = strategyProbe?.completionKind?.name,
        tcpCandidates =
            strategyProbe
                ?.tcpCandidates
                ?.map { candidate -> candidate.toExecutionDetail(lane = "tcp", observations = observations) }
                .orEmpty(),
        quicCandidates =
            strategyProbe
                ?.quicCandidates
                ?.map { candidate -> candidate.toExecutionDetail(lane = "quic", observations = observations) }
                .orEmpty(),
    )

internal fun buildMeasurementSnapshot(
    selection: DiagnosticsArchiveSelection,
    strategyProbe: StrategyProbeReport?,
    latestTelemetry: TelemetrySampleEntity?,
): DiagnosticsArchiveMeasurementSnapshot {
    val allCandidates = strategyProbe.allCandidates()
    val recommendedTcp =
        strategyProbe
            ?.tcpCandidates
            ?.firstOrNull { it.id == strategyProbe.recommendation.tcpCandidateId }
    val recommendedQuic =
        strategyProbe
            ?.quicCandidates
            ?.firstOrNull { it.id == strategyProbe.recommendation.quicCandidateId }
    val acceptanceMetrics = buildAcceptanceMetrics(strategyProbe)
    val detectabilityMetrics =
        buildDetectabilityMetrics(
            strategyProbe = strategyProbe,
            candidates = allCandidates,
            recommendedTcp = recommendedTcp,
            recommendedQuic = recommendedQuic,
        )
    val capabilitySnapshot = buildCapabilitySnapshot(allCandidates)
    val capabilityEvidence = buildDiagnosticsArchiveCapabilityEvidence(selection.primaryReport, referencePrefix = "")
    val hasCapabilityAssessment = selection.primaryReport?.executionPlan?.strategy != null
    val instabilityRetryCount = selection.payload.telemetry.maxOfOrNull(TelemetrySampleEntity::retryCount)
    return DiagnosticsArchiveMeasurementSnapshot(
        networkIdentityBucket = resolveNetworkIdentityBucket(selection, latestTelemetry),
        targetBucket = resolveTargetBucket(strategyProbe),
        recommendedTcpEmitterTier = recommendedTcp?.emitterTier?.name,
        recommendedQuicEmitterTier = recommendedQuic?.emitterTier?.name,
        capabilitySnapshot = capabilitySnapshot,
        acceptanceMetrics = acceptanceMetrics,
        detectabilityMetrics = detectabilityMetrics,
        rolloutGateAssessment =
            buildRolloutGateAssessment(
                acceptanceMetrics = acceptanceMetrics,
                detectabilityMetrics = detectabilityMetrics,
                capabilitySnapshot = capabilitySnapshot,
                capabilityEvidence = capabilityEvidence,
                hasCapabilityAssessment = hasCapabilityAssessment,
                instabilityRetryCount = instabilityRetryCount,
                recommendedLatencyMs =
                    listOfNotNull(recommendedTcp?.averageLatencyMs, recommendedQuic?.averageLatencyMs)
                        .maxOrNull(),
            ),
    )
}

private fun StrategyProbeReport?.allCandidates(): List<StrategyProbeCandidateSummary> =
    if (this == null) {
        emptyList()
    } else {
        tcpCandidates + quicCandidates
    }

private fun resolveNetworkIdentityBucket(
    selection: DiagnosticsArchiveSelection,
    latestTelemetry: TelemetrySampleEntity?,
): String {
    val transport =
        selection.latestSnapshotModel?.transport
            ?: latestTelemetry?.networkType
            ?: "unknown"
    val handoverClass =
        latestTelemetry
            ?.networkHandoverClass
            ?.takeIf { it.isNotBlank() }
            ?: "steady"
    val fingerprint =
        archiveFingerprintProjection(
            latestTelemetry?.telemetryNetworkFingerprintHash
                ?: (selection.primaryEvents + selection.globalEvents).latestCorrelation { it.fingerprintHash },
        ) ?: "unavailable"
    return "$transport:$handoverClass:$fingerprint"
}

private fun resolveTargetBucket(strategyProbe: StrategyProbeReport?): String =
    when {
        strategyProbe == null -> {
            "unavailable"
        }

        strategyProbe.pilotBucketLabels.isNotEmpty() -> {
            strategyProbe.pilotBucketLabels.distinct().joinToString("|")
        }

        strategyProbe.targetSelection != null -> {
            "${strategyProbe.targetSelection.cohortId}:${strategyProbe.targetSelection.cohortLabel}"
        }

        else -> {
            "unavailable"
        }
    }

private fun buildAcceptanceMetrics(strategyProbe: StrategyProbeReport?): DiagnosticsArchiveAcceptanceMetrics {
    val assessment = strategyProbe?.auditAssessment
    val coverage = assessment?.coverage
    return DiagnosticsArchiveAcceptanceMetrics(
        matrixCoveragePercent = coverage?.matrixCoveragePercent,
        winnerCoveragePercent = coverage?.winnerCoveragePercent,
        tcpCandidatesPlanned = coverage?.tcpCandidatesPlanned ?: 0,
        tcpCandidatesExecuted = coverage?.tcpCandidatesExecuted ?: 0,
        quicCandidatesPlanned = coverage?.quicCandidatesPlanned ?: 0,
        quicCandidatesExecuted = coverage?.quicCandidatesExecuted ?: 0,
        tcpWinnerSucceededTargets = coverage?.tcpWinnerSucceededTargets ?: 0,
        tcpWinnerTotalTargets = coverage?.tcpWinnerTotalTargets ?: 0,
        quicWinnerSucceededTargets = coverage?.quicWinnerSucceededTargets ?: 0,
        quicWinnerTotalTargets = coverage?.quicWinnerTotalTargets ?: 0,
        confidenceLevel = assessment?.confidence?.level?.name,
        confidenceScore = assessment?.confidence?.score,
    )
}

private fun buildDetectabilityMetrics(
    strategyProbe: StrategyProbeReport?,
    candidates: List<StrategyProbeCandidateSummary>,
    recommendedTcp: StrategyProbeCandidateSummary?,
    recommendedQuic: StrategyProbeCandidateSummary?,
): DiagnosticsArchiveDetectabilityMetrics {
    val rootedProductionCandidates =
        candidates.count { it.emitterTier == StrategyEmitterTier.ROOTED_PRODUCTION }
    val labOnlyCandidates =
        candidates.count { it.emitterTier == StrategyEmitterTier.LAB_DIAGNOSTICS_ONLY }
    val downgradedCandidates = candidates.count(StrategyProbeCandidateSummary::emitterDowngraded)
    val exactRootRequiredCandidates = candidates.count(StrategyProbeCandidateSummary::exactEmitterRequiresRoot)
    val capabilitySkippedCandidates =
        candidates.count { candidate ->
            candidate.notes.any(::containsUnavailableCapabilityId) ||
                (candidate.skipped && candidate.rationale.contains("capability", ignoreCase = true)) ||
                candidate.rationale.contains("unavailable", ignoreCase = true)
        }
    val notes =
        buildList {
            if (recommendedTcp?.emitterTier ==
                StrategyEmitterTier.ROOTED_PRODUCTION
            ) {
                add("recommended_tcp_rooted_emitter")
            }
            if (recommendedQuic?.emitterTier ==
                StrategyEmitterTier.ROOTED_PRODUCTION
            ) {
                add("recommended_quic_rooted_emitter")
            }
            if (recommendedTcp?.emitterDowngraded == true) add("recommended_tcp_downgraded")
            if (recommendedQuic?.emitterDowngraded == true) add("recommended_quic_downgraded")
            if (labOnlyCandidates > 0) add("lab_only_candidates_present")
            if (capabilitySkippedCandidates > 0) add("capability_skips_present")
            if (strategyProbe?.recommendation?.tlsPathSuppressed == true) {
                add(strategyProbe.recommendation.tlsPathSuppressionReason ?: "tls_path_suppressed")
            }
        }
    return DiagnosticsArchiveDetectabilityMetrics(
        rootedProductionCandidates = rootedProductionCandidates,
        labOnlyCandidates = labOnlyCandidates,
        downgradedCandidates = downgradedCandidates,
        exactRootRequiredCandidates = exactRootRequiredCandidates,
        capabilitySkippedCandidates = capabilitySkippedCandidates,
        skippedCandidates = candidates.count(StrategyProbeCandidateSummary::skipped),
        recommendedUsesRootedEmitter =
            recommendedTcp?.emitterTier == StrategyEmitterTier.ROOTED_PRODUCTION ||
                recommendedQuic?.emitterTier == StrategyEmitterTier.ROOTED_PRODUCTION,
        recommendedWasDowngraded =
            recommendedTcp?.emitterDowngraded == true || recommendedQuic?.emitterDowngraded == true,
        evidence =
            listOfNotNull(
                recommendedTcp?.toDetectabilityEvidence(lane = "tcp"),
                recommendedQuic?.toDetectabilityEvidence(lane = "quic"),
            ),
        notes = notes,
    )
}

private fun StrategyProbeCandidateSummary.toDetectabilityEvidence(lane: String): String =
    "lane=$lane;candidateId=$id;emitterTier=${emitterTier.name};" +
        "emitterDowngraded=$emitterDowngraded;exactEmitterRequiresRoot=$exactEmitterRequiresRoot"

private fun buildCapabilitySnapshot(
    candidates: List<StrategyProbeCandidateSummary>,
): DiagnosticsArchiveCapabilitySnapshot {
    val evidence =
        candidates
            .flatMap { candidate ->
                candidate.notes.filter(::containsUnavailableCapabilityId)
            }.distinct()
            .sorted()
    val inferredUnavailableCapabilities =
        evidence
            .flatMap(::extractCapabilityIds)
            .distinct()
            .sorted()
    return DiagnosticsArchiveCapabilitySnapshot(
        inferredUnavailableCapabilities = inferredUnavailableCapabilities,
        evidence = evidence,
    )
}

private fun buildRolloutGateAssessment(
    acceptanceMetrics: DiagnosticsArchiveAcceptanceMetrics,
    detectabilityMetrics: DiagnosticsArchiveDetectabilityMetrics,
    capabilitySnapshot: DiagnosticsArchiveCapabilitySnapshot,
    capabilityEvidence: List<DiagnosticsArchiveCapabilityEvidence>,
    hasCapabilityAssessment: Boolean,
    instabilityRetryCount: Long?,
    recommendedLatencyMs: Long?,
): DiagnosticsArchiveRolloutGateAssessment {
    val hasDetectabilityEvidence = detectabilityMetrics.evidence.isNotEmpty()
    val results =
        listOf(
            DiagnosticsArchiveRolloutGateResult(
                id = "acceptance",
                passed =
                    (acceptanceMetrics.matrixCoveragePercent ?: 0) >= AcceptanceMatrixCoverageMin &&
                        (acceptanceMetrics.winnerCoveragePercent ?: 0) >= AcceptanceWinnerCoverageMin,
                threshold =
                    "matrixCoveragePercent >= $AcceptanceMatrixCoverageMin and " +
                        "winnerCoveragePercent >= $AcceptanceWinnerCoverageMin",
                actual =
                    "matrix=${acceptanceMetrics.matrixCoveragePercent ?: "unknown"};" +
                        "winner=${acceptanceMetrics.winnerCoveragePercent ?: "unknown"}",
            ),
            DiagnosticsArchiveRolloutGateResult(
                id = "latency_budget",
                passed = recommendedLatencyMs != null && recommendedLatencyMs <= RecommendedLatencyBudgetMs,
                threshold = "recommendedLatencyMs <= $RecommendedLatencyBudgetMs",
                actual = recommendedLatencyMs?.toString() ?: "unknown",
                rationale =
                    if (recommendedLatencyMs ==
                        null
                    ) {
                        "Recommended candidate latency was unavailable."
                    } else {
                        null
                    },
            ),
            DiagnosticsArchiveRolloutGateResult(
                id = "instability_budget",
                passed = instabilityRetryCount != null && instabilityRetryCount <= InstabilityRetryBudget,
                threshold = "retryCount <= $InstabilityRetryBudget",
                actual = instabilityRetryCount?.toString() ?: "unknown",
            ),
            DiagnosticsArchiveRolloutGateResult(
                id = "detectability_budget",
                passed =
                    hasDetectabilityEvidence &&
                        !detectabilityMetrics.recommendedUsesRootedEmitter &&
                        !detectabilityMetrics.recommendedWasDowngraded,
                threshold = "recommended winner stays non-root and non-downgraded",
                actual =
                    if (hasDetectabilityEvidence) {
                        "rooted=${detectabilityMetrics.recommendedUsesRootedEmitter};" +
                            "downgraded=${detectabilityMetrics.recommendedWasDowngraded}"
                    } else {
                        "unknown"
                    },
                rationale =
                    detectabilityMetrics.evidence
                        .takeIf(List<String>::isNotEmpty)
                        ?.joinToString(" | ")
                        ?: "Recommended candidate emitter evidence was unavailable.",
            ),
            buildAndroidCompatibilityGate(capabilitySnapshot, capabilityEvidence, hasCapabilityAssessment),
        )
    return DiagnosticsArchiveRolloutGateAssessment(
        overallPassed = results.all(DiagnosticsArchiveRolloutGateResult::passed),
        results = results,
    )
}

private fun buildAndroidCompatibilityGate(
    capabilitySnapshot: DiagnosticsArchiveCapabilitySnapshot,
    capabilityEvidence: List<DiagnosticsArchiveCapabilityEvidence>,
    hasCapabilityAssessment: Boolean,
): DiagnosticsArchiveRolloutGateResult =
    DiagnosticsArchiveRolloutGateResult(
        id = "android_compat_budget",
        passed =
            hasCapabilityAssessment &&
                capabilitySnapshot.inferredUnavailableCapabilities.isEmpty() &&
                capabilityEvidence.all { evidence ->
                    evidence.status == DiagnosticsArchiveCapabilityStatus.AVAILABLE
                },
        threshold = "no inferred missing runtime capabilities",
        actual =
            when {
                !hasCapabilityAssessment -> {
                    "unknown"
                }

                capabilitySnapshot.inferredUnavailableCapabilities.isNotEmpty() -> {
                    capabilitySnapshot.inferredUnavailableCapabilities.joinToString("|")
                }

                capabilityEvidence.isEmpty() -> {
                    "none"
                }

                else -> {
                    capabilityEvidence.joinToString("|") { "${it.id}:${it.status.name}" }
                }
            },
    )

private fun containsUnavailableCapabilityId(text: String): Boolean = extractCapabilityIds(text).isNotEmpty()

private fun extractCapabilityIds(text: String): List<String> =
    KnownRuntimeCapabilityIds.filter { capability ->
        text.contains("($capability)") || text.contains("$capability unavailable")
    }

private fun buildRecommendationEvidence(
    strategyProbe: StrategyProbeReport?,
    resolver: ResolverRecommendation?,
    assessment: StrategyProbeAuditAssessment?,
    selection: DiagnosticsArchiveSelection,
): List<String> {
    val recommendation = strategyProbe?.recommendation
    return buildList {
        recommendation?.rationale?.takeIf(String::isNotBlank)?.let {
            add(redactDiagnosticsArchiveText(it))
        }
        resolver?.rationale?.takeIf(String::isNotBlank)?.let {
            add("resolver:${redactDiagnosticsArchiveText(it)}")
        }
        assessment
            ?.confidence
            ?.rationale
            ?.takeIf(String::isNotBlank)
            ?.let { add(redactDiagnosticsArchiveText(it)) }
        addAll(
            assessment
                ?.confidence
                ?.warnings
                .orEmpty()
                .map(::redactDiagnosticsArchiveText),
        )
        strategyProbe?.targetSelection?.cohortLabel?.let {
            add("targetCohort=${redactDiagnosticsArchiveText(it)}")
        }
        recommendation?.tcpCandidateLabel?.let { add("tcpWinner=$it") }
        recommendation?.quicCandidateLabel?.let { add("quicWinner=$it") }
        val allTcpCandidatesFailed =
            strategyProbe != null &&
                strategyProbe.tcpCandidates.isNotEmpty() &&
                strategyProbe.tcpCandidates.none { it.succeededTargets > 0 && !it.skipped }
        if (allTcpCandidatesFailed) add("strategy_adequacy:all_tcp_candidates_failed")
        val blockedBootstraps =
            selection.primaryResults
                .filter {
                    it.probeType == "tcp_fat_header" &&
                        it.outcome in setOf("tcp_reset", "tcp_16kb_blocked")
                }.map { it.target }
        if (blockedBootstraps.isNotEmpty()) add("blocked_bootstrap_count:${blockedBootstraps.size}")
    }
}

private fun buildRecommendationTrace(
    selection: DiagnosticsArchiveSelection,
    projectedReport: com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire?,
): DiagnosticsArchiveRecommendationTrace? {
    val strategyProbe = projectedReport?.strategyProbeReport
    val resolver = projectedReport?.resolverRecommendation
    if (strategyProbe == null && resolver == null) return null
    val assessment = strategyProbe?.auditAssessment
    val recommendation = strategyProbe?.recommendation
    val evidence = buildRecommendationEvidence(strategyProbe, resolver, assessment, selection)
    return DiagnosticsArchiveRecommendationTrace(
        selectedApproach =
            selection.selectedApproachProjection()?.displayName
                ?: recommendation?.tcpCandidateLabel
                ?: resolver?.selectedResolverId
                ?: "unavailable",
        selectedStrategy =
            recommendation?.let {
                "${it.tcpCandidateLabel} + ${it.quicCandidateLabel}"
            } ?: "unavailable",
        quicLayoutFamily = recommendation?.quicCandidateLayoutFamily,
        selectedResolver = recommendation?.dnsStrategyLabel ?: resolver?.selectedResolverId,
        confidenceLevel = assessment?.confidence?.level?.name,
        confidenceScore = assessment?.confidence?.score,
        coveragePercent = assessment?.coverage?.matrixCoveragePercent,
        winnerCoveragePercent = assessment?.coverage?.winnerCoveragePercent,
        targetCohort = strategyProbe?.targetSelection?.cohortLabel,
        evidence = evidence.distinct(),
    )
}

private fun StrategyProbeCandidateSummary.toExecutionDetail(
    lane: String,
    observations: List<ObservationFact>,
): DiagnosticsArchiveCandidateExecutionDetail {
    val strategyFacts = observations.mapNotNull(ObservationFact::strategy).filter { it.candidateId == id }
    val statusCounts =
        strategyFacts
            .groupingBy { it.status.name.lowercase() }
            .eachCount()
            .toSortedMap()
    val transportFailureCounts =
        strategyFacts
            .map { it.transportFailure.name.lowercase() }
            .filterNot { it == TransportFailureKind.NONE.name.lowercase() }
            .groupingBy { it }
            .eachCount()
            .toSortedMap()
    val tlsErrorSamples =
        strategyFacts
            .flatMap { fact -> listOfNotNull(fact.tlsError, fact.tlsEchError) }
            .distinct()
            .take(TlsErrorSampleLimit)
    return DiagnosticsArchiveCandidateExecutionDetail(
        lane = lane,
        id = id,
        label = label,
        family = family,
        quicLayoutFamily = quicLayoutFamily,
        outcome = outcome,
        rationale = rationale,
        succeededTargets = succeededTargets,
        totalTargets = totalTargets,
        weightedSuccessScore = weightedSuccessScore,
        totalWeight = totalWeight,
        qualityScore = qualityScore,
        averageLatencyMs = averageLatencyMs,
        skipped = skipped,
        skipReasons =
            buildList {
                if (skipped) add(rationale)
                addAll(
                    notes.filter {
                        it.contains("skip", ignoreCase = true) ||
                            it.contains("not applicable", ignoreCase = true)
                    },
                )
            }.distinct(),
        notes = notes,
        factBreakdown =
            DiagnosticsArchiveCandidateFactBreakdown(
                observationCount = strategyFacts.size,
                statusCounts = statusCounts,
                transportFailureCounts = transportFailureCounts,
                tlsErrorSamples = tlsErrorSamples,
            ),
    )
}

private fun List<String>.distinctConsecutive(): List<String> {
    val result = mutableListOf<String>()
    for (value in this) {
        if (value.isBlank()) continue
        if (result.lastOrNull() != value) result += value
    }
    return result
}
