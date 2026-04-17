package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSummaryDocument
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

internal class DiagnosticsArchiveJsonEntryBuilder(
    private val redactor: DiagnosticsArchiveRedactor,
    private val projector: DiagnosticsSummaryProjector,
    private val json: Json,
) {
    private val csvEntryBuilder = DiagnosticsArchiveCsvEntryBuilder(json)

    internal fun buildJsonEntries(
        target: DiagnosticsArchiveTarget,
        selection: DiagnosticsArchiveSelection,
        sectionStatuses: Map<String, DiagnosticsArchiveSectionStatus>,
        snapshotPayload: DiagnosticsArchiveSnapshotPayload,
        contextPayload: DiagnosticsArchiveContextPayload,
        completeness: DiagnosticsArchiveCompletenessPayload,
        compositeEntries: List<DiagnosticsArchiveEntry>,
        developerAnalytics: DeveloperAnalyticsPayload,
    ): List<DiagnosticsArchiveEntry> =
        buildList {
            add(
                textEntry(
                    name = "summary.txt",
                    content = buildSummary(createdAt = target.createdAt, selection = selection),
                ),
            )
            add(
                DiagnosticsArchiveEntry(
                    name = "manifest.json",
                    bytes = encodeManifest(target, selection, sectionStatuses).toByteArray(),
                ),
            )
            add(
                jsonEntry(
                    name = "report.json",
                    serializer = DiagnosticsArchivePayload.serializer(),
                    value = buildRedactedPayload(selection),
                ),
            )
            add(
                jsonEntry(
                    name = "strategy-matrix.json",
                    serializer = StrategyMatrixArchivePayload.serializer(),
                    value =
                        StrategyMatrixArchivePayload(
                            sessionId = selection.primarySession?.id,
                            profileId = selection.primarySession?.profileId,
                            strategyProbeReport = selection.primaryReport?.strategyProbeReport,
                        ),
                ),
            )
            addAll(compositeEntries)
            add(
                jsonEntry(
                    name = "archive-provenance.json",
                    serializer = DiagnosticsArchiveProvenancePayload.serializer(),
                    value = buildArchiveProvenance(target, selection),
                ),
            )
            add(
                jsonEntry(
                    name = "runtime-config.json",
                    serializer = DiagnosticsArchiveRuntimeConfigPayload.serializer(),
                    value = buildRuntimeConfig(selection),
                ),
            )
            add(
                jsonEntry(
                    name = "analysis.json",
                    serializer = DiagnosticsArchiveAnalysisPayload.serializer(),
                    value = buildAnalysis(selection),
                ),
            )
            add(
                jsonEntry(
                    name = "completeness.json",
                    serializer = DiagnosticsArchiveCompletenessPayload.serializer(),
                    value = completeness,
                ),
            )
            add(
                jsonEntry(
                    name = "network-snapshots.json",
                    serializer = DiagnosticsArchiveSnapshotPayload.serializer(),
                    value = snapshotPayload,
                ),
            )
            add(
                jsonEntry(
                    name = "diagnostic-context.json",
                    serializer = DiagnosticsArchiveContextPayload.serializer(),
                    value = contextPayload,
                ),
            )
            add(
                jsonEntry(
                    name = "developer-analytics.json",
                    serializer = DeveloperAnalyticsPayload.serializer(),
                    value = developerAnalytics,
                ),
            )
        }

    internal fun buildRedactedPayload(selection: DiagnosticsArchiveSelection): DiagnosticsArchivePayload =
        selection.payload.copy(
            primaryReport = selection.primaryReport,
            sessionSnapshots = selection.payload.sessionSnapshots.map(redactor::redact),
            sessionContexts = selection.payload.sessionContexts.map(redactor::redact),
            latestPassiveSnapshot = selection.payload.latestPassiveSnapshot?.let(redactor::redact),
            latestPassiveContext = selection.payload.latestPassiveContext?.let(redactor::redact),
            telemetry =
                selection.payload.telemetry.map { sample ->
                    sample.copy(publicIp = if (sample.publicIp != null) "redacted" else null)
                },
        )

    internal fun buildSnapshotPayload(selection: DiagnosticsArchiveSelection): DiagnosticsArchiveSnapshotPayload =
        DiagnosticsArchiveSnapshotPayload(
            sessionSnapshots =
                selection.primarySnapshots
                    .mapNotNull(redactor::decodeNetworkSnapshot)
                    .map(redactor::redact),
            latestPassiveSnapshot =
                redactor
                    .decodeNetworkSnapshot(selection.latestPassiveSnapshot)
                    ?.let(redactor::redact),
        )

    internal fun buildContextPayload(selection: DiagnosticsArchiveSelection): DiagnosticsArchiveContextPayload =
        DiagnosticsArchiveContextPayload(
            sessionContexts =
                selection.primaryContexts
                    .mapNotNull(redactor::decodeDiagnosticContext)
                    .map(redactor::redact),
            latestPassiveContext =
                redactor
                    .decodeDiagnosticContext(selection.latestPassiveContext)
                    ?.let(redactor::redact),
        )

    internal fun buildSummary(
        createdAt: Long,
        selection: DiagnosticsArchiveSelection,
    ): String {
        val summaryDocument = buildSummaryDocument(selection)
        val allEvents = selection.primaryEvents + selection.globalEvents
        val runtimeId = allEvents.latestCorrelation { it.runtimeId }
        val mode = selection.primarySession?.serviceMode ?: allEvents.latestCorrelation { it.mode }
        val policySignature = allEvents.latestCorrelation { it.policySignature }
        val fingerprintHash =
            selection.payload.telemetry
                .firstOrNull()
                ?.telemetryNetworkFingerprintHash
                ?: allEvents.latestCorrelation { it.fingerprintHash }
        val recentWarnings = allEvents.recentWarningPreview()
        return DiagnosticsSummaryTextRenderer.render(
            document = summaryDocument,
            preludeLines =
                buildList {
                    add("RIPDPI diagnostics archive")
                    add("generatedAt=$createdAt")
                    add("scope=${DiagnosticsArchiveFormat.scope}")
                    add("runType=${selection.runType.name.lowercase()}")
                    add("privacyMode=${DiagnosticsArchiveFormat.privacyMode}")
                    add("logcatIncluded=${selection.logcatSnapshot != null}")
                    val logcatScope =
                        selection.logcatSnapshot?.captureScope
                            ?: LogcatSnapshotCollector.AppVisibleSnapshotScope
                    add("logcatCaptureScope=$logcatScope")
                    add("logcatByteCount=${selection.logcatSnapshot?.byteCount ?: 0}")
                    add("selectedSession=${selection.primarySession?.id ?: "latest-live"}")
                    selection.homeRunId?.let { add("homeRunId=$it") }
                    selection.homeCompositeOutcome?.recommendedSessionId?.let { add("recommendedSession=$it") }
                    selection.homeCompositeOutcome?.let { outcome ->
                        add("stageCount=${outcome.stageSummaries.size}")
                        add("completedStageCount=${outcome.completedStageCount}")
                        add("failedStageCount=${outcome.failedStageCount}")
                    }
                    runtimeId?.let { add("runtimeId=$it") }
                    mode?.let { add("mode=$it") }
                    policySignature?.let { add("policySignature=$it") }
                    fingerprintHash?.let {
                        add("fingerprintHash=$it")
                        add("networkScope=$it")
                    }
                    recentWarnings.firstOrNull()?.let { add("lastFailure=$it") }
                    allEvents.lifecycleMilestones().forEach { add("lifecycle=$it") }
                    selection.selectedApproachSummary?.let {
                        add("approach=${it.displayName}")
                        add("approachVerification=${it.verificationState}")
                        add("approachSuccessRate=${it.successRateLabel()}")
                        add("approachUsageCount=${it.usageCount}")
                        add("approachRuntimeMs=${it.totalRuntimeDurationMs}")
                    }
                    selection.homeCompositeOutcome
                        ?.stageSummaries
                        ?.forEach { stage ->
                            add(
                                "stage=${stage.stageKey}:${stage.status.name.lowercase()}:" +
                                    (stage.sessionId ?: "no-session"),
                            )
                        }
                },
        )
    }

    internal fun buildCompositeEntries(selection: DiagnosticsArchiveSelection): List<DiagnosticsArchiveEntry> {
        val outcome = selection.homeCompositeOutcome ?: return emptyList()
        return buildList {
            add(
                jsonEntry(
                    name = "home-analysis.json",
                    serializer = DiagnosticsArchiveHomeAnalysisPayload.serializer(),
                    value =
                        DiagnosticsArchiveHomeAnalysisPayload(
                            runId = outcome.runId,
                            fingerprintHash = outcome.fingerprintHash,
                            actionable = outcome.actionable,
                            headline = outcome.headline,
                            summary = outcome.summary,
                            recommendationSummary = outcome.recommendationSummary,
                            confidenceSummary = outcome.confidenceSummary,
                            coverageSummary = outcome.coverageSummary,
                            recommendedSessionId = outcome.recommendedSessionId,
                            appliedSettings = outcome.appliedSettings,
                            completedStageCount = outcome.completedStageCount,
                            failedStageCount = outcome.failedStageCount,
                            skippedStageCount = outcome.skippedStageCount,
                            bundleSessionIds = outcome.bundleSessionIds,
                        ),
                ),
            )
            add(
                jsonEntry(
                    name = "stage-index.json",
                    serializer = DiagnosticsArchiveStageIndexPayload.serializer(),
                    value =
                        DiagnosticsArchiveStageIndexPayload(
                            runId = outcome.runId,
                            stages = buildStageIndexEntries(selection),
                        ),
                ),
            )
            add(
                jsonEntry(
                    name = "stage-summaries.json",
                    serializer = DiagnosticsArchiveStageSummariesPayload.serializer(),
                    value =
                        DiagnosticsArchiveStageSummariesPayload(
                            runId = outcome.runId,
                            stages = buildStageIndexEntries(selection),
                        ),
                ),
            )
            selection.compositeStages.forEach { stage ->
                addAll(buildStageEntries(stage, selection))
            }
        }
    }

    private fun encodeManifest(
        target: DiagnosticsArchiveTarget,
        selection: DiagnosticsArchiveSelection,
        sectionStatuses: Map<String, DiagnosticsArchiveSectionStatus>,
    ): String {
        val manifest = buildManifest(target, selection, sectionStatuses)
        val manifestElement = json.encodeToJsonElement(DiagnosticsArchiveManifest.serializer(), manifest)
        val normalizedManifest =
            if (selection.selectedApproachSummary == null && manifestElement is JsonObject) {
                JsonObject(manifestElement + ("selectedApproach" to JsonNull))
            } else {
                manifestElement
            }
        return json.encodeToString(JsonElement.serializer(), normalizedManifest)
    }

    private fun buildManifest(
        target: DiagnosticsArchiveTarget,
        selection: DiagnosticsArchiveSelection,
        sectionStatuses: Map<String, DiagnosticsArchiveSectionStatus>,
    ): DiagnosticsArchiveManifest {
        val summaryDocument = buildSummaryDocument(selection)
        val allEvents = selection.primaryEvents + selection.globalEvents
        val runtimeId = allEvents.latestCorrelation { it.runtimeId }
        val mode = selection.primarySession?.serviceMode ?: allEvents.latestCorrelation { it.mode }
        val policySignature = allEvents.latestCorrelation { it.policySignature }
        val fingerprintHash =
            selection.payload.telemetry
                .firstOrNull()
                ?.telemetryNetworkFingerprintHash
                ?: allEvents.latestCorrelation { it.fingerprintHash }
        val recentWarnings = allEvents.recentWarningPreview()
        val isSingleSession = selection.runType == DiagnosticsArchiveRunType.SINGLE_SESSION
        return DiagnosticsArchiveManifest(
            fileName = target.fileName,
            createdAt = target.createdAt,
            schemaVersion = DiagnosticsArchiveFormat.schemaVersion,
            privacyMode = DiagnosticsArchiveFormat.privacyMode,
            scope = DiagnosticsArchiveFormat.scope,
            runType = selection.runType,
            homeRunId = selection.homeRunId,
            archiveReason = selection.request.reason,
            requestedSessionId = if (isSingleSession) selection.request.requestedSessionId else null,
            selectedSessionId = if (isSingleSession) selection.primarySession?.id else null,
            sessionSelectionStatus = selection.sessionSelectionStatus,
            recommendedSessionId = selection.homeCompositeOutcome?.recommendedSessionId,
            stageIndex = buildStageIndexEntries(selection),
            includedSessionId = selection.primarySession?.id,
            sessionResultCount = selection.primaryResults.size,
            sessionSnapshotCount = selection.primarySnapshots.size,
            contextSnapshotCount = selection.primaryContexts.size,
            sessionEventCount = selection.primaryEvents.size,
            telemetrySampleCount = selection.payload.telemetry.size,
            globalEventCount = selection.globalEvents.size,
            approachCount = selection.payload.approachSummaries.size,
            selectedApproach = selection.selectedApproachSummary,
            networkSummary = selection.latestSnapshotModel?.let(redactor::redact)?.toRedactedSummary(),
            contextSummary =
                (selection.sessionContextModel ?: selection.latestContextModel)
                    ?.let(redactor::redact)
                    ?.toRedactedSummary(),
            latestTelemetrySummary =
                selection.payload.telemetry
                    .firstOrNull()
                    ?.toArchiveTelemetrySummary(),
            runtimeId = runtimeId,
            mode = mode,
            policySignature = policySignature,
            fingerprintHash = fingerprintHash,
            networkScope = fingerprintHash,
            lastFailure = recentWarnings.firstOrNull(),
            lifecycleMilestones = allEvents.lifecycleMilestones(),
            recentNativeWarnings = recentWarnings,
            classifierVersion = summaryDocument.classifierVersion,
            diagnosisCount = summaryDocument.diagnoses.size,
            packVersions = summaryDocument.packVersions,
            buildProvenance = selection.buildProvenance.toSummary(),
            sectionStatusSummary = sectionStatuses,
            integrityAlgorithm = "sha256",
            includedFiles = selection.includedFiles,
            logcatIncluded = selection.logcatSnapshot != null,
            logcatCaptureScope =
                selection.logcatSnapshot?.captureScope
                    ?: LogcatSnapshotCollector.AppVisibleSnapshotScope,
            logcatByteCount = selection.logcatSnapshot?.byteCount ?: 0,
        )
    }

    private fun buildStageEntries(
        stage: DiagnosticsArchiveCompositeStageSelection,
        selection: DiagnosticsArchiveSelection,
    ): List<DiagnosticsArchiveEntry> {
        val prefix = "stages/${stage.stageSummary.stageKey}"
        val snapshotPayload = buildStageSnapshotPayload(stage)
        val contextPayload = buildStageContextPayload(stage)
        val stagePayload = buildStageArchivePayload(stage, selection)
        return buildList {
            add(
                jsonEntry(
                    name = "$prefix/report.json",
                    serializer = DiagnosticsArchivePayload.serializer(),
                    value = stagePayload,
                ),
            )
            add(
                jsonEntry(
                    name = "$prefix/strategy-matrix.json",
                    serializer = StrategyMatrixArchivePayload.serializer(),
                    value =
                        StrategyMatrixArchivePayload(
                            sessionId = stage.session?.id,
                            profileId = stage.session?.profileId,
                            strategyProbeReport = stage.report?.strategyProbeReport,
                        ),
                ),
            )
            add(
                textEntry(
                    name = "$prefix/probe-results.csv",
                    content = csvEntryBuilder.buildProbeResultsCsv(stage.results),
                ),
            )
            add(
                jsonEntry(
                    name = "$prefix/network-snapshots.json",
                    serializer = DiagnosticsArchiveSnapshotPayload.serializer(),
                    value = snapshotPayload,
                ),
            )
            add(
                jsonEntry(
                    name = "$prefix/diagnostic-context.json",
                    serializer = DiagnosticsArchiveContextPayload.serializer(),
                    value = contextPayload,
                ),
            )
            add(
                textEntry(
                    name = "$prefix/native-events.csv",
                    content = buildNativeEventsCsv(stage.events, emptyList()),
                ),
            )
            add(textEntry(name = "$prefix/telemetry.csv", content = buildTelemetryCsv(stagePayload)))
        }
    }

    private fun buildStageSnapshotPayload(
        stage: DiagnosticsArchiveCompositeStageSelection,
    ): DiagnosticsArchiveSnapshotPayload =
        DiagnosticsArchiveSnapshotPayload(
            sessionSnapshots = stage.snapshots.mapNotNull(redactor::decodeNetworkSnapshot).map(redactor::redact),
            latestPassiveSnapshot = null,
        )

    private fun buildStageContextPayload(
        stage: DiagnosticsArchiveCompositeStageSelection,
    ): DiagnosticsArchiveContextPayload =
        DiagnosticsArchiveContextPayload(
            sessionContexts = stage.contexts.mapNotNull(redactor::decodeDiagnosticContext).map(redactor::redact),
            latestPassiveContext = null,
        )

    private fun buildStageArchivePayload(
        stage: DiagnosticsArchiveCompositeStageSelection,
        selection: DiagnosticsArchiveSelection,
    ): DiagnosticsArchivePayload =
        DiagnosticsArchivePayload(
            schemaVersion = DiagnosticsArchiveFormat.schemaVersion,
            scope = DiagnosticsArchiveFormat.scope,
            privacyMode = DiagnosticsArchiveFormat.privacyMode,
            session = stage.session,
            primaryReport = stage.report,
            results = stage.results,
            sessionSnapshots = stage.snapshots.map(redactor::redact),
            sessionContexts = stage.contexts.map(redactor::redact),
            sessionEvents = stage.events,
            latestPassiveSnapshot = null,
            latestPassiveContext = null,
            telemetry = selection.payload.telemetry,
            globalEvents = emptyList(),
            approachSummaries = selection.payload.approachSummaries,
        )

    private fun buildSummaryDocument(selection: DiagnosticsArchiveSelection): DiagnosticsSummaryDocument =
        projector.project(
            session = selection.primarySession,
            report = selection.primaryReport?.toSessionProjection(),
            latestSnapshotModel = selection.latestSnapshotModel?.let(redactor::redact),
            latestContextModel =
                (selection.sessionContextModel ?: selection.latestContextModel)
                    ?.let(redactor::redact),
            latestTelemetry = selection.payload.telemetry.firstOrNull(),
            selectedResults = selection.primaryResults,
            warnings =
                (selection.primaryEvents + selection.globalEvents).filter { event ->
                    event.level.equals("warn", ignoreCase = true) || event.level.equals("error", ignoreCase = true)
                },
        )

    private fun <T> jsonEntry(
        name: String,
        serializer: KSerializer<T>,
        value: T,
    ): DiagnosticsArchiveEntry =
        DiagnosticsArchiveEntry(
            name = name,
            bytes = json.encodeToString(serializer, value).toByteArray(),
        )
}
