package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.diagnostics.DeveloperAnalyticsPayload
import com.poyka.ripdpi.diagnostics.DiagnosticsSummaryProjector
import com.poyka.ripdpi.diagnostics.DiagnosticsSummaryTextRenderer
import com.poyka.ripdpi.diagnostics.LogcatSnapshotCollector
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSummaryDocument
import com.poyka.ripdpi.diagnostics.toRedactedSummary
import com.poyka.ripdpi.diagnostics.toSessionProjection
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
    private val csvEntryBuilder = DiagnosticsArchiveCsvEntryBuilder(json, redactor)
    private val attemptsEntryBuilder = DiagnosticsArchiveAttemptsEntryBuilder(json)

    @Suppress("detekt.LongMethod")
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
                    name = "execution-plan.json",
                    serializer = ExecutionPlanArchivePayload.serializer(),
                    value =
                        ExecutionPlanArchivePayload(
                            sessionId = selection.primarySession?.id,
                            profileId = selection.primarySession?.profileId,
                            executionPlan = redactor.redact(selection.primaryReport)?.executionPlan,
                        ),
                ),
            )
            add(
                attemptsEntryBuilder.build(
                    name = "attempts.jsonl",
                    sessionId = selection.primarySession?.id,
                    profileId = selection.primarySession?.profileId,
                    report = redactor.redact(selection.primaryReport),
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
                            strategyProbeReport = redactor.redact(selection.primaryReport)?.strategyProbeReport,
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
                    value = buildRuntimeConfig(selection, redactor),
                ),
            )
            add(
                jsonEntry(
                    name = "analysis.json",
                    serializer = DiagnosticsArchiveAnalysisPayload.serializer(),
                    value = buildAnalysis(selection, redactor),
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
                DiagnosticsArchiveEntry(
                    name = "developer-analytics.json",
                    bytes =
                        json
                            .encodeToString(
                                JsonElement.serializer(),
                                DeveloperAnalyticsAllowListFilter.filterToJson(developerAnalytics, json),
                            ).toByteArray(),
                ),
            )
        }

    internal fun buildRedactedPayload(selection: DiagnosticsArchiveSelection): DiagnosticsArchivePayload =
        selection.payload.copy(
            session = redactSession(selection.payload.session),
            primaryReport = redactor.redact(selection.primaryReport),
            results = selection.payload.results.map(redactor::redact),
            sessionSnapshots = selection.payload.sessionSnapshots.map(redactor::redact),
            sessionContexts = selection.payload.sessionContexts.map(redactor::redact),
            sessionEvents = selection.payload.sessionEvents.map(redactor::redact),
            latestPassiveSnapshot = selection.payload.latestPassiveSnapshot?.let(redactor::redact),
            latestPassiveContext = selection.payload.latestPassiveContext?.let(redactor::redact),
            telemetry = selection.payload.telemetry.map { it.redactForArchive() },
            globalEvents = selection.payload.globalEvents.map(redactor::redact),
            approachSummaries =
                selection.payload.approachSummaries.mapIndexed { index, summary ->
                    summary.projectForArchive(index)
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
            archiveFingerprintProjection(
                selection.payload.telemetry
                    .firstOrNull()
                    ?.telemetryNetworkFingerprintHash
                    ?: allEvents.latestCorrelation { it.fingerprintHash },
            )
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
                    add("selectedSession=${selection.selectedSessionLabel()}")
                    selection.homeRunId?.let { add("homeRunId=$it") }
                    selection.homeCompositeOutcome?.recommendedSessionId?.let { add("recommendedSession=$it") }
                    selection.homeCompositeOutcome?.let { outcome ->
                        add("stageCount=${outcome.stageSummaries.size}")
                        add("completedStageCount=${outcome.completedStageCount}")
                        add("failedStageCount=${outcome.failedStageCount}")
                        outcome.connectivityAssessment?.let { assessment ->
                            add("connectivityAssessment=${assessment.assessmentCode.name.lowercase()}")
                            add("connectivityConfidence=${assessment.confidence}")
                            add("connectivityControlCount=${assessment.rawPathEvidence.controls.size}")
                            add("connectivityAffectedTargetCount=${assessment.affectedTargets.size}")
                            add(
                                "connectivityNextAction=" +
                                    redactDiagnosticsArchiveText(assessment.recommendedNextAction),
                            )
                        }
                    }
                    runtimeId?.let { add("runtimeId=${redactDiagnosticsArchiveText(it)}") }
                    mode?.let { add("mode=$it") }
                    policySignature?.let { add("policySignature=${archiveStableCorrelatorProjection(it)}") }
                    fingerprintHash?.let {
                        add("fingerprintHash=$it")
                        add("networkScope=$it")
                    }
                    recentWarnings.firstOrNull()?.let { add("lastFailure=$it") }
                    allEvents.lifecycleMilestones().forEach { add("lifecycle=$it") }
                    selection.selectedApproachProjection()?.let {
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

    private fun DiagnosticsArchiveSelection.selectedSessionLabel(): String =
        primarySession?.id
            ?: if (sessionSelectionStatus == DiagnosticsArchiveSessionSelectionStatus.UNAVAILABLE) {
                "unavailable"
            } else {
                "latest-live"
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
                            fingerprintHash = archiveFingerprintProjection(outcome.fingerprintHash),
                            actionable = outcome.actionable,
                            headline = redactDiagnosticsArchiveText(outcome.headline),
                            summary = redactDiagnosticsArchiveText(outcome.summary),
                            recommendationSummary = outcome.recommendationSummary?.let(::redactDiagnosticsArchiveText),
                            confidenceSummary = outcome.confidenceSummary?.let(::redactDiagnosticsArchiveText),
                            coverageSummary = outcome.coverageSummary?.let(::redactDiagnosticsArchiveText),
                            recommendedSessionId = outcome.recommendedSessionId,
                            appliedSettings =
                                outcome.appliedSettings.map { setting ->
                                    setting.copy(
                                        label = redactDiagnosticsArchiveText(setting.label),
                                        value = redactDiagnosticsArchiveText(setting.value),
                                    )
                                },
                            completedStageCount = outcome.completedStageCount,
                            failedStageCount = outcome.failedStageCount,
                            skippedStageCount = outcome.skippedStageCount,
                            bundleSessionIds = outcome.bundleSessionIds,
                            connectivityAssessment = redactor.redact(outcome.connectivityAssessment),
                            internetLossReproAction = redactor.redact(outcome.internetLossReproAction),
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
            archiveFingerprintProjection(
                selection.payload.telemetry
                    .firstOrNull()
                    ?.telemetryNetworkFingerprintHash
                    ?: allEvents.latestCorrelation { it.fingerprintHash },
            )
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
            selectedApproach = selection.selectedApproachProjection(),
            networkSummary = selection.latestSnapshotModel?.let(redactor::redact)?.toRedactedSummary(),
            contextSummary =
                selectArchiveRuntimeContext(selection)
                    .context
                    ?.let(redactor::redact)
                    ?.toRedactedSummary(),
            latestTelemetrySummary =
                selection.payload.telemetry
                    .firstOrNull()
                    ?.redactForArchive()
                    ?.toArchiveTelemetrySummary(),
            runtimeId = runtimeId?.let(::redactDiagnosticsArchiveText),
            mode = mode,
            policySignature = archiveStableCorrelatorProjection(policySignature),
            fingerprintHash = fingerprintHash,
            networkScope = fingerprintHash,
            lastFailure = recentWarnings.firstOrNull(),
            lifecycleMilestones = allEvents.lifecycleMilestones(),
            recentNativeWarnings = recentWarnings,
            classifierVersion = summaryDocument.classifierVersion,
            diagnosisCount = summaryDocument.diagnoses.size,
            packVersions = summaryDocument.packVersions,
            connectivityAssessment = redactor.redact(selection.homeCompositeOutcome?.connectivityAssessment),
            internetLossReproAction = redactor.redact(selection.homeCompositeOutcome?.internetLossReproAction),
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
                    name = "$prefix/execution-plan.json",
                    serializer = ExecutionPlanArchivePayload.serializer(),
                    value =
                        ExecutionPlanArchivePayload(
                            sessionId = stage.session?.id,
                            profileId = stage.session?.profileId,
                            executionPlan = redactor.redact(stage.report)?.executionPlan,
                        ),
                ),
            )
            add(
                attemptsEntryBuilder.build(
                    name = "$prefix/attempts.jsonl",
                    sessionId = stage.session?.id,
                    profileId = stage.session?.profileId,
                    report = redactor.redact(stage.report),
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
                            strategyProbeReport = redactor.redact(stage.report)?.strategyProbeReport,
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
                    content =
                        buildNativeEventsCsv(
                            stage.events.take(DiagnosticsArchiveFormat.sessionEventLimit),
                            emptyList(),
                        ),
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
            session = redactSession(stage.session),
            primaryReport = redactor.redact(stage.report),
            results = stage.results.map(redactor::redact),
            sessionSnapshots = stage.snapshots.map(redactor::redact),
            sessionContexts = stage.contexts.map(redactor::redact),
            sessionEvents =
                stage.events
                    .take(DiagnosticsArchiveFormat.sessionEventLimit)
                    .map(redactor::redact),
            latestPassiveSnapshot = null,
            latestPassiveContext = null,
            telemetry =
                stage.telemetry.map { it.redactForArchive() },
            globalEvents = emptyList(),
            approachSummaries =
                selection.payload.approachSummaries.mapIndexed { index, summary ->
                    summary.projectForArchive(index)
                },
        )

    private fun buildSummaryDocument(selection: DiagnosticsArchiveSelection): DiagnosticsSummaryDocument =
        projector.project(
            session = selection.primarySession,
            report = redactor.redact(selection.primaryReport)?.toSessionProjection(),
            latestSnapshotModel = selection.latestSnapshotModel?.let(redactor::redact),
            latestContextModel =
                selectArchiveRuntimeContext(selection)
                    .context
                    ?.let(redactor::redact),
            latestTelemetry =
                selection.payload.telemetry
                    .firstOrNull()
                    ?.redactForArchive(),
            selectedResults = selection.primaryResults.map(redactor::redact),
            warnings =
                (selection.primaryEvents + selection.globalEvents)
                    .filter { event ->
                        event.level.equals("warn", ignoreCase = true) || event.level.equals("error", ignoreCase = true)
                    }.map(redactor::redact),
        )

    private fun redactSession(session: ScanSessionEntity?): ScanSessionEntity? =
        session?.copy(
            approachProfileName = session.approachProfileName?.let(::redactDiagnosticsArchiveText),
            strategyLabel = session.strategyLabel?.let(::redactDiagnosticsArchiveText),
            strategyJson = null,
            summary = redactDiagnosticsArchiveText(session.summary),
            reportJson = null,
            triggerClassification = session.triggerClassification?.let(::redactDiagnosticsArchiveText),
            triggerPreviousFingerprintHash = null,
            triggerCurrentFingerprintHash = null,
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
