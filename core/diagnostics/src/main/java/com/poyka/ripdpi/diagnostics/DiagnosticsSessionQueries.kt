package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import com.poyka.ripdpi.data.diagnostics.BypassUsageHistoryStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactReadStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.Locale

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
            val snapshots =
                artifactReadStore.observeSnapshots(limit = 200).first().filter { it.sessionId == sessionId }
            val latestContext =
                artifactReadStore
                    .observeContexts(limit = 200)
                    .first()
                    .filter { it.sessionId == sessionId }
                    .maxByOrNull { it.capturedAt }
            val events =
                artifactReadStore.observeNativeEvents(limit = 500).first().filter { it.sessionId == sessionId }
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
                            .filterNot { it.outcome.isSuccessfulOutcome() }
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
            runCatching { json.decodeFromString(ScanReport.serializer(), it) }.getOrNull()
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
                    report.results.all { it.outcome.isSuccessfulOutcome() }
            }
        val allResults = validatedReports.flatMap { it.second.results }
        val failureOutcomes =
            allResults
                .filterNot { it.outcome.isSuccessfulOutcome() }
                .groupingBy { it.outcome }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .map { "${it.key} (${it.value})" }
                .take(3)
        val outcomeBreakdown =
            allResults
                .groupBy { it.probeType }
                .map { (probeType, results) ->
                    val failures = results.filterNot { it.outcome.isSuccessfulOutcome() }
                    BypassOutcomeBreakdown(
                        probeType = probeType,
                        successCount = results.count { it.outcome.isSuccessfulOutcome() },
                        warningCount = results.count { it.outcome.isWarningOutcome() },
                        failureCount = failures.size,
                        dominantFailureOutcome =
                            failures
                                .groupingBy { it.outcome }
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

private fun String.isSuccessfulOutcome(): Boolean {
    val normalized = lowercase(Locale.US)
    return normalized.contains("ok") ||
        normalized.contains("success") ||
        normalized.contains("completed") ||
        normalized.contains("reachable") ||
        normalized.contains("allowed")
}

private fun String.isWarningOutcome(): Boolean {
    if (isSuccessfulOutcome()) {
        return false
    }
    val normalized = lowercase(Locale.US)
    return normalized.contains("timeout") ||
        normalized.contains("partial") ||
        normalized.contains("mixed") ||
        normalized.contains("warn")
}
