package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.BypassUsageHistoryStore
import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactReadStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

internal object DiagnosticsSessionQueries {
    suspend fun loadSessionDetail(
        sessionId: String,
        scanRecordStore: DiagnosticsScanRecordStore,
        artifactReadStore: DiagnosticsArtifactReadStore,
        mapper: DiagnosticsBoundaryMapper,
    ): DiagnosticSessionDetail =
        withContext(Dispatchers.IO) {
            val session =
                requireNotNull(
                    scanRecordStore.getScanSession(sessionId),
                ) { "Unknown diagnostics session: $sessionId" }
            val results = scanRecordStore.getProbeResults(sessionId)
            val snapshots = artifactReadStore.getSnapshotsForSession(sessionId)
            val latestContext =
                artifactReadStore
                    .getContextsForSession(sessionId)
                    .maxByOrNull { it.capturedAt }
            val events = artifactReadStore.getNativeEventsForSession(sessionId)
            DiagnosticSessionDetail(
                session = mapper.toDiagnosticScanSession(session),
                results = results.map(mapper::toProbeResult),
                snapshots = snapshots.map(mapper::toDiagnosticNetworkSnapshot),
                events = events.map(mapper::toDiagnosticEvent),
                context = latestContext?.let(mapper::toDiagnosticContextSnapshot),
            )
        }

    suspend fun loadApproachDetail(
        kind: BypassApproachKind,
        id: String,
        scanRecordStore: DiagnosticsScanRecordStore,
        bypassUsageHistoryStore: BypassUsageHistoryStore,
        mapper: DiagnosticsBoundaryMapper,
        json: Json,
    ): BypassApproachDetail =
        withContext(Dispatchers.IO) {
            val sessions = scanRecordStore.observeRecentScanSessions(limit = 200).first()
            val usageSessions = bypassUsageHistoryStore.observeBypassUsageSessions(limit = 200).first()
            val summary =
                buildApproachSummaries(scanSessions = sessions, usageSessions = usageSessions, json = json)
                    .firstOrNull { it.approachId.kind == kind && it.approachId.value == id }
                    ?: throw IllegalArgumentException("Unknown bypass approach: $kind/$id")

            val matchingSessions =
                sessions.filter { session ->
                    when (kind) {
                        BypassApproachKind.Profile -> session.approachProfileId == id || session.profileId == id
                        BypassApproachKind.Strategy -> session.strategyId == id
                    }
                }
            val matchingUsageSessions =
                usageSessions.filter { usage ->
                    when (kind) {
                        BypassApproachKind.Profile -> usage.approachProfileId == id
                        BypassApproachKind.Strategy -> usage.strategyId == id
                    }
                }
            val failureNotes =
                matchingSessions
                    .flatMap { session ->
                        decodeScanReport(json, session.reportJson)
                            ?.results
                            .orEmpty()
                            .filterNot { result ->
                                DiagnosticsOutcomeTaxonomy
                                    .classifyProbeOutcome(
                                        probeType = result.probeType,
                                        pathMode = parsePathModeOrDefault(session.pathMode),
                                        outcome = result.outcome,
                                    ).healthyEnoughForSummary
                            }
                            .map { result -> "${result.probeType}:${result.target}=${result.outcome}" }
                    }.take(8)
            val strategySignature =
                when (kind) {
                    BypassApproachKind.Profile -> {
                        matchingSessions
                            .firstNotNullOfOrNull { decodeStrategySignature(json, it.strategyJson) }
                            ?: matchingUsageSessions.firstNotNullOfOrNull {
                                decodeStrategySignature(
                                    json,
                                    it.strategyJson,
                                )
                            }
                    }

                    BypassApproachKind.Strategy -> {
                        matchingSessions
                            .firstNotNullOfOrNull { decodeStrategySignature(json, it.strategyJson) }
                            ?: matchingUsageSessions.firstNotNullOfOrNull {
                                decodeStrategySignature(
                                    json,
                                    it.strategyJson,
                                )
                            }
                    }
                }

            BypassApproachDetail(
                summary = summary,
                strategySignature = strategySignature,
                recentValidatedSessions = matchingSessions.take(6).map(mapper::toDiagnosticScanSession),
                recentUsageSessions = matchingUsageSessions.take(6).map(mapper::toDiagnosticConnectionSession),
                commonProbeFailures = summary.topFailureOutcomes,
                recentFailureNotes = failureNotes,
            )
        }

    fun buildApproachSummaries(
        scanSessions: List<ScanSessionEntity>,
        usageSessions: List<BypassUsageSessionEntity>,
        json: Json,
    ): List<BypassApproachSummary> {
        val profileIds =
            (
                scanSessions.mapNotNull {
                    it.approachProfileId ?: it.profileId.takeIf { value ->
                        value.isNotBlank()
                    }
                } +
                    usageSessions.mapNotNull { it.approachProfileId }
            ).distinct()
        val strategyIds =
            (
                scanSessions.mapNotNull { it.strategyId } +
                    usageSessions.map { it.strategyId }
            ).distinct()

        val profileSummaries =
            profileIds.map { profileId ->
                val matchingSessions =
                    scanSessions.filter { session ->
                        session.approachProfileId == profileId || session.profileId == profileId
                    }
                val matchingUsage = usageSessions.filter { it.approachProfileId == profileId }
                aggregateApproachSummary(
                    kind = BypassApproachKind.Profile,
                    id = profileId,
                    displayName =
                        matchingSessions.firstNotNullOfOrNull { it.approachProfileName }
                            ?: matchingUsage.firstNotNullOfOrNull { it.approachProfileName }
                            ?: profileId,
                    secondaryLabel = "Profile",
                    matchingSessions = matchingSessions,
                    matchingUsage = matchingUsage,
                    json = json,
                )
            }

        val strategySummaries =
            strategyIds.map { strategyId ->
                val matchingSessions = scanSessions.filter { it.strategyId == strategyId }
                val matchingUsage = usageSessions.filter { it.strategyId == strategyId }
                aggregateApproachSummary(
                    kind = BypassApproachKind.Strategy,
                    id = strategyId,
                    displayName =
                        matchingSessions.firstNotNullOfOrNull { it.strategyLabel }
                            ?: matchingUsage.firstOrNull()?.strategyLabel
                            ?: strategyId,
                    secondaryLabel = "Strategy",
                    matchingSessions = matchingSessions,
                    matchingUsage = matchingUsage,
                    json = json,
                )
            }

        return (profileSummaries + strategySummaries)
            .sortedWith(
                compareByDescending<BypassApproachSummary> { it.validatedSuccessRate ?: -1f }
                    .thenByDescending { it.validatedScanCount }
                    .thenByDescending { it.usageCount }
                    .thenByDescending { it.lastUsedAt ?: 0L },
            )
    }

    fun decodeScanReport(
        json: Json,
        payload: String?,
    ): ScanReport? =
        payload?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.decodeEngineScanReportWireCompat(it).toLegacyScanReportCompat() }.getOrNull()
        }

    private fun decodeStrategySignature(
        json: Json,
        payload: String?,
    ): BypassStrategySignature? =
        payload?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.decodeFromString(BypassStrategySignature.serializer(), it) }.getOrNull()
        }

    private fun aggregateApproachSummary(
        kind: BypassApproachKind,
        id: String,
        displayName: String,
        secondaryLabel: String,
        matchingSessions: List<ScanSessionEntity>,
        matchingUsage: List<BypassUsageSessionEntity>,
        json: Json,
    ): BypassApproachSummary {
        val validatedReports =
            matchingSessions
                .mapNotNull { session -> decodeScanReport(json, session.reportJson)?.let { session to it } }
        val successfulReports =
            validatedReports.count { (_, report) ->
                report.results.isNotEmpty() &&
                    report.results.all { result ->
                        DiagnosticsOutcomeTaxonomy
                            .classifyProbeOutcome(
                                probeType = result.probeType,
                                pathMode = report.pathMode,
                                outcome = result.outcome,
                            ).healthyEnoughForSummary
                    }
            }
        val allResults =
            validatedReports.flatMap { (_, report) ->
                report.results.map { result ->
                    ClassifiedReportResult(
                        result = result,
                        classification =
                            DiagnosticsOutcomeTaxonomy.classifyProbeOutcome(
                                probeType = result.probeType,
                                pathMode = report.pathMode,
                                outcome = result.outcome,
                            ),
                    )
                }
            }
        val failureOutcomes =
            allResults
                .filterNot { it.classification.healthyEnoughForSummary }
                .groupingBy { it.result.outcome }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .map { "${it.key} (${it.value})" }
                .take(3)
        val outcomeBreakdown =
            allResults
                .groupBy { it.result.probeType }
                .map { (probeType, results) ->
                    val unhealthy = results.filterNot { it.classification.healthyEnoughForSummary }
                    BypassOutcomeBreakdown(
                        probeType = probeType,
                        successCount =
                            results.count { it.classification.bucket == DiagnosticsOutcomeBucket.Healthy },
                        warningCount =
                            results.count { it.classification.bucket == DiagnosticsOutcomeBucket.Attention },
                        failureCount =
                            results.count {
                                it.classification.bucket == DiagnosticsOutcomeBucket.Failed ||
                                    it.classification.bucket == DiagnosticsOutcomeBucket.Inconclusive
                            },
                        dominantFailureOutcome =
                            unhealthy
                                .groupingBy { it.result.outcome }
                                .eachCount()
                                .maxByOrNull { it.value }
                                ?.key,
                    )
                }.sortedBy { it.probeType }
        val totalRuntimeDurationMs =
            matchingUsage.sumOf { usage ->
                (usage.finishedAt ?: System.currentTimeMillis()) - usage.startedAt
            }
        val recentUsage = matchingUsage.sortedByDescending { it.startedAt }.take(5)
        val latestValidated = validatedReports.maxByOrNull { it.first.startedAt }?.first
        val verificationState = if (validatedReports.isEmpty()) "unverified" else "validated"

        return BypassApproachSummary(
            approachId = BypassApproachId(kind = kind, value = id),
            displayName = displayName,
            secondaryLabel = secondaryLabel,
            verificationState = verificationState,
            validatedScanCount = validatedReports.size,
            validatedSuccessCount = successfulReports,
            validatedSuccessRate =
                validatedReports.size
                    .takeIf { it > 0 }
                    ?.let { successfulReports.toFloat() / it.toFloat() },
            lastValidatedResult = latestValidated?.summary,
            usageCount = matchingUsage.size,
            totalRuntimeDurationMs = totalRuntimeDurationMs,
            recentRuntimeHealth =
                BypassRuntimeHealthSummary(
                    totalErrors = recentUsage.sumOf { it.totalErrors },
                    routeChanges = recentUsage.sumOf { it.routeChanges },
                    restartCount = recentUsage.maxOfOrNull { it.restartCount } ?: 0,
                    lastEndedReason = recentUsage.firstOrNull { !it.endedReason.isNullOrBlank() }?.endedReason,
                ),
            lastUsedAt = matchingUsage.maxOfOrNull { it.finishedAt ?: it.startedAt },
            topFailureOutcomes = failureOutcomes,
            outcomeBreakdown = outcomeBreakdown,
        )
    }
}

private data class ClassifiedReportResult(
    val result: ProbeResult,
    val classification: DiagnosticsOutcomeClassification,
)

private fun parsePathModeOrDefault(value: String): ScanPathMode =
    runCatching { ScanPathMode.valueOf(value) }.getOrDefault(ScanPathMode.RAW_PATH)
