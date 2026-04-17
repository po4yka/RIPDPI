package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.data.diagnostics.retryCount
import com.poyka.ripdpi.data.diagnostics.rttBand
import com.poyka.ripdpi.data.diagnostics.winningStrategyFamily
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Named

private const val SuccessRatePercentScale = 100
private const val TlsErrorSampleLimit = 5

class DiagnosticsArchiveRenderer
    @Inject
    constructor(
        private val redactor: DiagnosticsArchiveRedactor,
        private val projector: DiagnosticsSummaryProjector,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        internal fun render(
            target: DiagnosticsArchiveTarget,
            selection: DiagnosticsArchiveSelection,
            developerAnalytics: DeveloperAnalyticsPayload = DeveloperAnalyticsPayload(),
        ): List<DiagnosticsArchiveEntry> {
            val snapshotPayload = buildSnapshotPayload(selection)
            val contextPayload = buildContextPayload(selection)
            val sectionStatuses = buildSectionStatuses(selection)
            val completeness =
                buildCompleteness(
                    selection = selection,
                    sectionStatuses = sectionStatuses,
                    snapshotPayload = snapshotPayload,
                    contextPayload = contextPayload,
                )
            val compositeEntries =
                if (selection.runType == DiagnosticsArchiveRunType.HOME_COMPOSITE) {
                    buildCompositeEntries(selection)
                } else {
                    emptyList()
                }
            val baseEntries =
                buildCoreEntries(
                    target = target,
                    selection = selection,
                    sectionStatuses = sectionStatuses,
                    snapshotPayload = snapshotPayload,
                    contextPayload = contextPayload,
                    completeness = completeness,
                    compositeEntries = compositeEntries,
                    developerAnalytics = developerAnalytics,
                )
            return baseEntries +
                jsonEntry(
                    name = "integrity.json",
                    serializer = DiagnosticsArchiveIntegrityPayload.serializer(),
                    value = buildIntegrityPayload(target, baseEntries),
                )
        }

        private fun buildCoreEntries(
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
                addAll(
                    buildJsonEntries(
                        target = target,
                        selection = selection,
                        sectionStatuses = sectionStatuses,
                        snapshotPayload = snapshotPayload,
                        contextPayload = contextPayload,
                        completeness = completeness,
                        compositeEntries = compositeEntries,
                        developerAnalytics = developerAnalytics,
                    ),
                )
                addAll(
                    buildCsvEntries(
                        selection = selection,
                    ),
                )
            }

        private fun buildJsonEntries(
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

        private fun buildCsvEntries(selection: DiagnosticsArchiveSelection): List<DiagnosticsArchiveEntry> =
            buildList {
                add(textEntry(name = "probe-results.csv", content = buildProbeResultsCsv(selection.primaryResults)))
                add(
                    textEntry(
                        name = "native-events.csv",
                        content = buildNativeEventsCsv(selection.primaryEvents, selection.globalEvents),
                    ),
                )
                add(textEntry(name = "telemetry.csv", content = buildTelemetryCsv(selection.payload)))
                selection.logcatSnapshot?.let { snapshot ->
                    add(DiagnosticsArchiveEntry(name = "logcat.txt", bytes = snapshot.content.toByteArray()))
                }
                selection.fileLogSnapshot?.let { content ->
                    add(DiagnosticsArchiveEntry(name = "app-log.txt", bytes = content.toByteArray()))
                }
                selection.pcapFiles.forEach { pcapFile ->
                    add(DiagnosticsArchiveEntry(name = pcapFile.name, bytes = pcapFile.readBytes()))
                }
            }

        private fun buildRedactedPayload(selection: DiagnosticsArchiveSelection): DiagnosticsArchivePayload =
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

        private fun buildSnapshotPayload(selection: DiagnosticsArchiveSelection): DiagnosticsArchiveSnapshotPayload =
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

        private fun buildContextPayload(selection: DiagnosticsArchiveSelection): DiagnosticsArchiveContextPayload =
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

        private fun <T> jsonEntry(
            name: String,
            serializer: kotlinx.serialization.KSerializer<T>,
            value: T,
        ): DiagnosticsArchiveEntry =
            DiagnosticsArchiveEntry(
                name = name,
                bytes = json.encodeToString(serializer, value).toByteArray(),
            )

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

        private fun buildCompositeEntries(selection: DiagnosticsArchiveSelection): List<DiagnosticsArchiveEntry> {
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
                add(textEntry(name = "$prefix/probe-results.csv", content = buildProbeResultsCsv(stage.results)))
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

        private fun buildSummaryDocument(selection: DiagnosticsArchiveSelection) =
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

        internal fun buildProbeResultsCsv(results: List<ProbeResultEntity>): String =
            buildString {
                appendLine("sessionId,probeType,target,outcome,probeRetryCount,createdAt,detailJson")
                results.forEach { result ->
                    appendLine(
                        listOf(
                            csvField(result.sessionId),
                            csvField(result.probeType),
                            csvField(result.target),
                            csvField(result.outcome),
                            csvField(result.probeRetryCount().orEmpty()),
                            csvField(result.createdAt),
                            csvField(result.detailJson),
                        ).joinToString(","),
                    )
                }
            }

        private fun ProbeResultEntity.probeRetryCount(): String? =
            runCatching {
                json.decodeFromString(ListSerializer(ProbeDetail.serializer()), detailJson)
            }.getOrNull()?.let(::deriveProbeRetryCount)?.toString()
    }

// ---------------------------------------------------------------------------
// File-level private functions (not part of the class — reduces LargeClass / TooManyFunctions)
// ---------------------------------------------------------------------------

private fun textEntry(
    name: String,
    content: String,
): DiagnosticsArchiveEntry = DiagnosticsArchiveEntry(name = name, bytes = content.toByteArray())

private fun buildArchiveProvenance(
    target: DiagnosticsArchiveTarget,
    selection: DiagnosticsArchiveSelection,
): DiagnosticsArchiveProvenancePayload {
    val allEvents = selection.primaryEvents + selection.globalEvents
    val context = selection.sessionContextModel ?: selection.latestContextModel
    val runtimeProvenance =
        DiagnosticsArchiveRuntimeProvenance(
            runtimeId = allEvents.latestCorrelation { it.runtimeId },
            mode = selection.primarySession?.serviceMode ?: allEvents.latestCorrelation { it.mode },
            policySignature = allEvents.latestCorrelation { it.policySignature },
            fingerprintHash =
                selection.payload.telemetry
                    .firstOrNull()
                    ?.telemetryNetworkFingerprintHash
                    ?: allEvents.latestCorrelation { it.fingerprintHash },
            networkScope =
                selection.payload.telemetry
                    .firstOrNull()
                    ?.telemetryNetworkFingerprintHash
                    ?: allEvents.latestCorrelation { it.fingerprintHash },
            androidVersion = context?.device?.androidVersion,
            apiLevel = context?.device?.apiLevel,
            primaryAbi = context?.device?.primaryAbi,
            locale = context?.device?.locale,
            timezone = context?.device?.timezone,
        )
    return DiagnosticsArchiveProvenancePayload(
        runType = selection.runType,
        homeRunId = selection.homeRunId,
        archiveReason = selection.request.reason,
        requestedAt = selection.request.requestedAt,
        createdAt = target.createdAt,
        requestedSessionId =
            if (selection.runType == DiagnosticsArchiveRunType.SINGLE_SESSION) {
                selection.request.requestedSessionId
            } else {
                null
            },
        selectedSessionId = selection.primarySession?.id,
        bundleSessionIds = selection.homeCompositeOutcome?.bundleSessionIds.orEmpty(),
        sessionSelectionStatus = selection.sessionSelectionStatus,
        triggerMetadata =
            selection.primarySession?.let {
                DiagnosticsArchiveTriggerMetadata(
                    launchOrigin = it.launchOrigin,
                    triggerType = it.triggerType,
                    triggerClassification = it.triggerClassification,
                    triggerOccurredAt = it.triggerOccurredAt,
                )
            },
        buildProvenance = selection.buildProvenance,
        runtimeProvenance = runtimeProvenance,
    )
}

private fun buildRuntimeConfig(selection: DiagnosticsArchiveSelection): DiagnosticsArchiveRuntimeConfigPayload {
    val context = selection.sessionContextModel ?: selection.latestContextModel
    val snapshot = selection.latestSnapshotModel
    val telemetry = selection.payload.telemetry.firstOrNull()
    val serviceConfig = resolveServiceConfig(context?.service, selection.primarySession?.profileId)
    val resolverConfig = resolveResolverConfig(telemetry)
    val networkConfig = resolveNetworkConfig(snapshot)
    val envConfig = resolveEnvironmentConfig(context?.environment, context?.permissions)
    return DiagnosticsArchiveRuntimeConfigPayload(
        configuredMode = serviceConfig.configuredMode,
        activeMode = serviceConfig.activeMode,
        serviceStatus = serviceConfig.serviceStatus,
        selectedProfileId = serviceConfig.selectedProfileId,
        selectedProfileName = serviceConfig.selectedProfileName,
        configSource = serviceConfig.configSource,
        desyncMethod = serviceConfig.desyncMethod,
        chainSummary = serviceConfig.chainSummary,
        routeGroup = serviceConfig.routeGroup,
        restartCount = serviceConfig.restartCount,
        sessionUptimeMs = serviceConfig.sessionUptimeMs,
        hostAutolearnEnabled = serviceConfig.hostAutolearnEnabled,
        learnedHostCount = serviceConfig.learnedHostCount,
        penalizedHostCount = serviceConfig.penalizedHostCount,
        blockedHostCount = serviceConfig.blockedHostCount,
        lastBlockSignal = serviceConfig.lastBlockSignal,
        lastBlockProvider = serviceConfig.lastBlockProvider,
        lastAutolearnHost = serviceConfig.lastAutolearnHost,
        lastAutolearnGroup = serviceConfig.lastAutolearnGroup,
        lastAutolearnAction = serviceConfig.lastAutolearnAction,
        lastNativeErrorHeadline = serviceConfig.lastNativeErrorHeadline,
        resolverId = resolverConfig.resolverId,
        resolverProtocol = resolverConfig.resolverProtocol,
        resolverEndpoint = resolverConfig.resolverEndpoint,
        resolverLatencyMs = resolverConfig.resolverLatencyMs,
        resolverFallbackActive = resolverConfig.resolverFallbackActive,
        resolverFallbackReason = resolverConfig.resolverFallbackReason,
        networkHandoverClass = resolverConfig.networkHandoverClass,
        transport = networkConfig.transport,
        privateDnsMode = networkConfig.privateDnsMode,
        mtu = networkConfig.mtu,
        networkValidated = networkConfig.networkValidated,
        captivePortalDetected = networkConfig.captivePortalDetected,
        batterySaverState = envConfig.batterySaverState,
        powerSaveModeState = envConfig.powerSaveModeState,
        dataSaverState = envConfig.dataSaverState,
        batteryOptimizationState = envConfig.batteryOptimizationState,
        vpnPermissionState = envConfig.vpnPermissionState,
        notificationPermissionState = envConfig.notificationPermissionState,
        networkMeteredState = envConfig.networkMeteredState,
        roamingState = envConfig.roamingState,
        commandLineSettingsEnabled = selection.appSettings.enableCmdSettings,
        commandLineArgsHash =
            selection.appSettings
                .takeIf { it.enableCmdSettings }
                ?.cmdArgs
                ?.takeIf { it.isNotBlank() }
                ?.let(::sha256Hex),
        effectiveStrategySignature = selection.effectiveStrategySignature,
    )
}

private data class ResolvedServiceConfig(
    val configuredMode: String = "unavailable",
    val activeMode: String = "unavailable",
    val serviceStatus: String = "unavailable",
    val selectedProfileId: String = "unavailable",
    val selectedProfileName: String = "unavailable",
    val configSource: String = "unavailable",
    val desyncMethod: String = "unavailable",
    val chainSummary: String = "unavailable",
    val routeGroup: String = "unavailable",
    val restartCount: Int = 0,
    val sessionUptimeMs: Long? = null,
    val hostAutolearnEnabled: String = "unavailable",
    val learnedHostCount: Int = 0,
    val penalizedHostCount: Int = 0,
    val blockedHostCount: Int = 0,
    val lastBlockSignal: String = "unavailable",
    val lastBlockProvider: String = "unavailable",
    val lastAutolearnHost: String = "unavailable",
    val lastAutolearnGroup: String = "unavailable",
    val lastAutolearnAction: String = "unavailable",
    val lastNativeErrorHeadline: String = "unavailable",
)

private fun resolveServiceConfig(
    service: ServiceContextModel?,
    fallbackProfileId: String?,
): ResolvedServiceConfig =
    if (service == null) {
        ResolvedServiceConfig(selectedProfileId = fallbackProfileId ?: "unavailable")
    } else {
        ResolvedServiceConfig(
            configuredMode = service.configuredMode,
            activeMode = service.activeMode,
            serviceStatus = service.serviceStatus,
            selectedProfileId = service.selectedProfileId,
            selectedProfileName = service.selectedProfileName,
            configSource = service.configSource,
            desyncMethod = service.desyncMethod,
            chainSummary = service.chainSummary,
            routeGroup = service.routeGroup,
            restartCount = service.restartCount,
            sessionUptimeMs = service.sessionUptimeMs,
            hostAutolearnEnabled = service.hostAutolearnEnabled,
            learnedHostCount = service.learnedHostCount,
            penalizedHostCount = service.penalizedHostCount,
            blockedHostCount = service.blockedHostCount,
            lastBlockSignal = service.lastBlockSignal,
            lastBlockProvider = service.lastBlockProvider,
            lastAutolearnHost = service.lastAutolearnHost,
            lastAutolearnGroup = service.lastAutolearnGroup,
            lastAutolearnAction = service.lastAutolearnAction,
            lastNativeErrorHeadline = service.lastNativeErrorHeadline,
        )
    }

private data class ResolvedResolverConfig(
    val resolverId: String = "unavailable",
    val resolverProtocol: String = "unavailable",
    val resolverEndpoint: String = "unavailable",
    val resolverLatencyMs: Long? = null,
    val resolverFallbackActive: Boolean = false,
    val resolverFallbackReason: String = "unavailable",
    val networkHandoverClass: String = "unavailable",
)

private fun resolveResolverConfig(telemetry: TelemetrySampleEntity?): ResolvedResolverConfig =
    if (telemetry == null) {
        ResolvedResolverConfig()
    } else {
        ResolvedResolverConfig(
            resolverId = telemetry.resolverId ?: "unavailable",
            resolverProtocol = telemetry.resolverProtocol ?: "unavailable",
            resolverEndpoint = telemetry.resolverEndpoint ?: "unavailable",
            resolverLatencyMs = telemetry.resolverLatencyMs,
            resolverFallbackActive = telemetry.resolverFallbackActive,
            resolverFallbackReason = telemetry.resolverFallbackReason ?: "unavailable",
            networkHandoverClass = telemetry.networkHandoverClass ?: "unavailable",
        )
    }

private data class ResolvedNetworkConfig(
    val transport: String = "unavailable",
    val privateDnsMode: String = "unavailable",
    val mtu: Int? = null,
    val networkValidated: Boolean? = null,
    val captivePortalDetected: Boolean? = null,
)

private fun resolveNetworkConfig(snapshot: NetworkSnapshotModel?): ResolvedNetworkConfig =
    if (snapshot == null) {
        ResolvedNetworkConfig()
    } else {
        ResolvedNetworkConfig(
            transport = snapshot.transport,
            privateDnsMode = snapshot.privateDnsMode,
            mtu = snapshot.mtu,
            networkValidated = snapshot.networkValidated,
            captivePortalDetected = snapshot.captivePortalDetected,
        )
    }

private data class ResolvedEnvironmentConfig(
    val batterySaverState: String = "unavailable",
    val powerSaveModeState: String = "unavailable",
    val dataSaverState: String = "unavailable",
    val batteryOptimizationState: String = "unavailable",
    val vpnPermissionState: String = "unavailable",
    val notificationPermissionState: String = "unavailable",
    val networkMeteredState: String = "unavailable",
    val roamingState: String = "unavailable",
)

private fun resolveEnvironmentConfig(
    environment: EnvironmentContextModel?,
    permissions: PermissionContextModel?,
): ResolvedEnvironmentConfig =
    ResolvedEnvironmentConfig(
        batterySaverState = environment?.batterySaverState ?: "unavailable",
        powerSaveModeState = environment?.powerSaveModeState ?: "unavailable",
        dataSaverState = permissions?.dataSaverState ?: "unavailable",
        batteryOptimizationState = permissions?.batteryOptimizationState ?: "unavailable",
        vpnPermissionState = permissions?.vpnPermissionState ?: "unavailable",
        notificationPermissionState = permissions?.notificationPermissionState ?: "unavailable",
        networkMeteredState = environment?.networkMeteredState ?: "unavailable",
        roamingState = environment?.roamingState ?: "unavailable",
    )

private fun buildAnalysis(selection: DiagnosticsArchiveSelection): DiagnosticsArchiveAnalysisPayload {
    val telemetry = selection.payload.telemetry.sortedBy { it.createdAt }
    val failureSamples =
        telemetry.filter {
            !it.failureClass.isNullOrBlank() ||
                !it.lastFailureClass.isNullOrBlank() ||
                !it.lastFallbackAction.isNullOrBlank()
        }
    val failureEvents =
        (selection.primaryEvents + selection.globalEvents)
            .filter {
                it.level.equals("warn", ignoreCase = true) || it.level.equals("error", ignoreCase = true)
            }.sortedBy { it.createdAt }
    val latestTelemetry = selection.payload.telemetry.firstOrNull()
    val strategyProbe = selection.primaryReport?.strategyProbeReport
    val observations = selection.primaryReport?.observations.orEmpty()
    return DiagnosticsArchiveAnalysisPayload(
        failureEnvelope =
            DiagnosticsArchiveFailureEnvelope(
                firstFailureTimestamp =
                    listOfNotNull(
                        failureSamples.firstOrNull()?.createdAt,
                        failureEvents.firstOrNull()?.createdAt,
                    ).minOrNull(),
                lastFailureTimestamp =
                    listOfNotNull(
                        failureSamples.lastOrNull()?.createdAt,
                        failureEvents.lastOrNull()?.createdAt,
                    ).maxOrNull(),
                latestFailureClass =
                    latestTelemetry?.failureClass
                        ?: latestTelemetry?.lastFailureClass
                        ?: failureEvents.lastOrNull()?.message,
                lastFallbackAction = latestTelemetry?.lastFallbackAction,
                retryCounters =
                    DiagnosticsArchiveRetryCounters(
                        proxyRouteRetryCount = latestTelemetry?.proxyRouteRetryCount ?: 0,
                        tunnelRecoveryRetryCount = latestTelemetry?.tunnelRecoveryRetryCount ?: 0,
                        totalRetryCount = latestTelemetry?.retryCount() ?: 0,
                    ),
                failureClassTransitions =
                    (
                        failureSamples.flatMap { sample ->
                            listOfNotNull(sample.failureClass, sample.lastFailureClass)
                        } +
                            failureEvents.map { event -> "native:${event.source}:${event.message}" }
                    ).distinctConsecutive(),
            ),
        strategyExecutionDetail =
            DiagnosticsArchiveStrategyExecutionDetail(
                suiteId = strategyProbe?.suiteId,
                completionKind = strategyProbe?.completionKind?.name,
                tcpCandidates =
                    strategyProbe
                        ?.tcpCandidates
                        ?.map { candidate ->
                            candidate.toExecutionDetail(
                                lane = "tcp",
                                observations = observations,
                            )
                        }.orEmpty(),
                quicCandidates =
                    strategyProbe
                        ?.quicCandidates
                        ?.map { candidate ->
                            candidate.toExecutionDetail(
                                lane = "quic",
                                observations = observations,
                            )
                        }.orEmpty(),
            ),
        recommendationTrace = buildRecommendationTrace(selection),
    )
}

private fun buildRecommendationEvidence(
    strategyProbe: StrategyProbeReport?,
    resolver: ResolverRecommendation?,
    assessment: StrategyProbeAuditAssessment?,
    selection: DiagnosticsArchiveSelection,
): List<String> {
    val recommendation = strategyProbe?.recommendation
    return buildList {
        recommendation?.rationale?.takeIf(String::isNotBlank)?.let { add(it) }
        resolver?.rationale?.takeIf(String::isNotBlank)?.let { add("resolver:$it") }
        assessment
            ?.confidence
            ?.rationale
            ?.takeIf(String::isNotBlank)
            ?.let { add(it) }
        addAll(assessment?.confidence?.warnings.orEmpty())
        strategyProbe?.targetSelection?.cohortLabel?.let { add("targetCohort=$it") }
        recommendation?.tcpCandidateLabel?.let { add("tcpWinner=$it") }
        recommendation?.quicCandidateLabel?.let { add("quicWinner=$it") }
        val allTcpCandidatesFailed =
            strategyProbe != null &&
                strategyProbe.tcpCandidates.isNotEmpty() &&
                strategyProbe.tcpCandidates.none { it.succeededTargets > 0 && !it.skipped }
        if (allTcpCandidatesFailed) {
            add("strategy_adequacy:all_tcp_candidates_failed")
        }
        val blockedBootstraps =
            selection.primaryResults
                .filter {
                    it.probeType == "tcp_fat_header" &&
                        it.outcome in setOf("tcp_reset", "tcp_16kb_blocked")
                }.map { it.target }
        if (blockedBootstraps.isNotEmpty()) {
            add("blocked_bootstrap_ips:${blockedBootstraps.joinToString(",")}")
        }
    }
}

private fun buildRecommendationTrace(selection: DiagnosticsArchiveSelection): DiagnosticsArchiveRecommendationTrace? {
    val strategyProbe = selection.primaryReport?.strategyProbeReport
    val resolver = selection.primaryReport?.resolverRecommendation
    if (strategyProbe == null && resolver == null) {
        return null
    }
    val assessment = strategyProbe?.auditAssessment
    val recommendation = strategyProbe?.recommendation
    val evidence = buildRecommendationEvidence(strategyProbe, resolver, assessment, selection)
    return DiagnosticsArchiveRecommendationTrace(
        selectedApproach =
            selection.selectedApproachSummary?.displayName
                ?: recommendation?.tcpCandidateLabel
                ?: resolver?.selectedResolverId
                ?: "unavailable",
        selectedStrategy =
            recommendation
                ?.let { "${it.tcpCandidateLabel} + ${it.quicCandidateLabel}" }
                ?: "unavailable",
        quicLayoutFamily = recommendation?.quicCandidateLayoutFamily,
        selectedResolver =
            recommendation?.dnsStrategyLabel
                ?: resolver?.selectedResolverId,
        confidenceLevel = assessment?.confidence?.level?.name,
        confidenceScore = assessment?.confidence?.score,
        coveragePercent = assessment?.coverage?.matrixCoveragePercent,
        winnerCoveragePercent = assessment?.coverage?.winnerCoveragePercent,
        targetCohort = strategyProbe?.targetSelection?.cohortLabel,
        evidence = evidence.distinct(),
    )
}

private fun buildSectionStatuses(
    selection: DiagnosticsArchiveSelection,
): Map<String, DiagnosticsArchiveSectionStatus> {
    val truncationFlags =
        SectionTruncationFlags(
            telemetry = selection.sourceCounts.telemetrySamples >= DiagnosticsArchiveFormat.telemetryLimit,
            nativeEvents = selection.sourceCounts.nativeEvents >= DiagnosticsArchiveFormat.globalEventLimit,
            snapshots = selection.sourceCounts.snapshots >= DiagnosticsArchiveFormat.snapshotLimit,
            contexts = selection.sourceCounts.contexts >= DiagnosticsArchiveFormat.snapshotLimit,
            logcat = (selection.logcatSnapshot?.byteCount ?: 0) >= LogcatSnapshotCollector.MAX_LOGCAT_BYTES,
        )
    return buildMap {
        selection.includedFiles.forEach { fileName ->
            put(fileName, sectionStatusForFileName(fileName, truncationFlags))
        }
    }
}

private fun buildCompleteness(
    selection: DiagnosticsArchiveSelection,
    sectionStatuses: Map<String, DiagnosticsArchiveSectionStatus>,
    snapshotPayload: DiagnosticsArchiveSnapshotPayload,
    contextPayload: DiagnosticsArchiveContextPayload,
): DiagnosticsArchiveCompletenessPayload {
    val snapshotDecodeFailures =
        selection.primarySnapshots.size +
            if (selection.latestPassiveSnapshot != null) {
                1
            } else {
                0 -
                    (
                        snapshotPayload.sessionSnapshots.size +
                            if (snapshotPayload.latestPassiveSnapshot != null) 1 else 0
                    )
            }
    val contextDecodeFailures =
        selection.primaryContexts.size +
            if (selection.latestPassiveContext != null) {
                1
            } else {
                0 -
                    (
                        contextPayload.sessionContexts.size +
                            if (contextPayload.latestPassiveContext != null) 1 else 0
                    )
            }
    val collectionWarnings =
        buildList {
            addAll(selection.collectionWarnings)
            if (snapshotDecodeFailures > 0) {
                add("snapshot_decode_failed_count:$snapshotDecodeFailures")
            }
            if (contextDecodeFailures > 0) {
                add("context_decode_failed_count:$contextDecodeFailures")
            }
            if (selection.buildProvenance.gitCommit == "unavailable") {
                add("git_commit_unavailable")
            }
            selection.buildProvenance.nativeLibraries
                .filter { it.version == "unavailable" }
                .forEach { add("native_library_version_unavailable:${it.name}") }
        }
    return DiagnosticsArchiveCompletenessPayload(
        sectionStatuses = sectionStatuses,
        appliedLimits =
            DiagnosticsArchiveAppliedLimits(
                telemetrySamples = DiagnosticsArchiveFormat.telemetryLimit,
                nativeEvents = DiagnosticsArchiveFormat.globalEventLimit,
                snapshots = DiagnosticsArchiveFormat.snapshotLimit,
                logcatBytes = LogcatSnapshotCollector.MAX_LOGCAT_BYTES,
            ),
        sourceCounts = selection.sourceCounts,
        includedCounts =
            DiagnosticsArchiveSourceCounts(
                telemetrySamples = selection.payload.telemetry.size,
                nativeEvents = selection.primaryEvents.size + selection.globalEvents.size,
                snapshots =
                    snapshotPayload.sessionSnapshots.size +
                        if (snapshotPayload.latestPassiveSnapshot != null) 1 else 0,
                contexts =
                    contextPayload.sessionContexts.size +
                        if (contextPayload.latestPassiveContext != null) 1 else 0,
                sessionResults = selection.primaryResults.size,
                sessionSnapshots = snapshotPayload.sessionSnapshots.size,
                sessionContexts = contextPayload.sessionContexts.size,
                sessionEvents = selection.primaryEvents.size,
            ),
        collectionWarnings = collectionWarnings,
        truncation =
            DiagnosticsArchiveTruncation(
                telemetrySamples =
                    selection.sourceCounts.telemetrySamples >= DiagnosticsArchiveFormat.telemetryLimit,
                nativeEvents = selection.sourceCounts.nativeEvents >= DiagnosticsArchiveFormat.globalEventLimit,
                snapshots = selection.sourceCounts.snapshots >= DiagnosticsArchiveFormat.snapshotLimit,
                contexts = selection.sourceCounts.contexts >= DiagnosticsArchiveFormat.snapshotLimit,
                logcat = (selection.logcatSnapshot?.byteCount ?: 0) >= LogcatSnapshotCollector.MAX_LOGCAT_BYTES,
            ),
    )
}

private fun buildIntegrityPayload(
    target: DiagnosticsArchiveTarget,
    entries: List<DiagnosticsArchiveEntry>,
): DiagnosticsArchiveIntegrityPayload =
    DiagnosticsArchiveIntegrityPayload(
        hashAlgorithm = "sha256",
        schemaVersion = DiagnosticsArchiveFormat.schemaVersion,
        generatedAt = target.createdAt,
        files =
            entries.map { entry ->
                DiagnosticsArchiveIntegrityFileEntry(
                    name = entry.name,
                    byteCount = entry.bytes.size,
                    sha256 = sha256Hex(entry.bytes),
                )
            },
    )

private fun buildStageIndexEntries(selection: DiagnosticsArchiveSelection): List<DiagnosticsArchiveStageIndexEntry> =
    selection.compositeStages.map { stage ->
        stage.stageSummary.toArchiveStageIndexEntry()
    }

private fun DiagnosticsHomeCompositeStageSummary.toArchiveStageIndexEntry(): DiagnosticsArchiveStageIndexEntry =
    DiagnosticsArchiveStageIndexEntry(
        stageKey = stageKey,
        stageLabel = stageLabel,
        profileId = profileId,
        pathMode = pathMode.name,
        sessionId = sessionId,
        status = status.name.lowercase(),
        headline = headline,
        summary = summary,
        recommendationContributor = recommendationContributor,
    )

internal fun buildTelemetryCsv(payload: DiagnosticsArchivePayload): String =
    buildString {
        appendLine(
            "createdAt,activeMode,connectionState,networkType,publicIp,failureClass," +
                "lastFailureClass,lastFallbackAction," +
                "telemetryNetworkFingerprintHash,winningTcpStrategyFamily,winningQuicStrategyFamily," +
                "winningStrategyFamily,proxyRttBand,resolverRttBand,rttBand,proxyRouteRetryCount," +
                "tunnelRecoveryRetryCount,retryCount,resolverId,resolverProtocol," +
                "resolverEndpoint,resolverLatencyMs,dnsFailuresTotal,resolverFallbackActive," +
                "resolverFallbackReason,networkHandoverClass,txPackets,txBytes,rxPackets,rxBytes",
        )
        payload.telemetry.forEach { sample ->
            appendLine(
                listOf(
                    sample.createdAt,
                    sample.activeMode.orEmpty(),
                    sample.connectionState,
                    sample.networkType,
                    if (sample.publicIp.isNullOrEmpty()) "" else "redacted",
                    sample.failureClass.orEmpty(),
                    sample.lastFailureClass.orEmpty(),
                    sample.lastFallbackAction.orEmpty(),
                    sample.telemetryNetworkFingerprintHash.orEmpty(),
                    sample.winningTcpStrategyFamily.orEmpty(),
                    sample.winningQuicStrategyFamily.orEmpty(),
                    sample.winningStrategyFamily().orEmpty(),
                    sample.proxyRttBand,
                    sample.resolverRttBand,
                    sample.rttBand(),
                    sample.proxyRouteRetryCount,
                    sample.tunnelRecoveryRetryCount,
                    sample.retryCount(),
                    sample.resolverId.orEmpty(),
                    sample.resolverProtocol.orEmpty(),
                    sample.resolverEndpoint.orEmpty(),
                    sample.resolverLatencyMs ?: 0,
                    sample.dnsFailuresTotal,
                    sample.resolverFallbackActive,
                    sample.resolverFallbackReason.orEmpty(),
                    sample.networkHandoverClass.orEmpty(),
                    sample.txPackets,
                    sample.txBytes,
                    sample.rxPackets,
                    sample.rxBytes,
                ).joinToString(","),
            )
        }
    }

internal fun buildNativeEventsCsv(
    primaryEvents: List<NativeSessionEventEntity>,
    globalEvents: List<NativeSessionEventEntity>,
): String =
    buildString {
        appendLine(
            "scope,sessionId,source,level,message," +
                "createdAt,runtimeId,mode,policySignature,fingerprintHash,subsystem",
        )
        primaryEvents.forEach { event ->
            appendLine(
                listOf(
                    csvField("session"),
                    csvField(event.sessionId.orEmpty()),
                    csvField(event.source),
                    csvField(event.level),
                    csvField(event.message),
                    csvField(event.createdAt),
                    csvField(event.runtimeId.orEmpty()),
                    csvField(event.mode.orEmpty()),
                    csvField(event.policySignature.orEmpty()),
                    csvField(event.fingerprintHash.orEmpty()),
                    csvField(event.subsystem.orEmpty()),
                ).joinToString(","),
            )
        }
        globalEvents.forEach { event ->
            appendLine(
                listOf(
                    csvField("global"),
                    csvField(event.sessionId.orEmpty()),
                    csvField(event.source),
                    csvField(event.level),
                    csvField(event.message),
                    csvField(event.createdAt),
                    csvField(event.runtimeId.orEmpty()),
                    csvField(event.mode.orEmpty()),
                    csvField(event.policySignature.orEmpty()),
                    csvField(event.fingerprintHash.orEmpty()),
                    csvField(event.subsystem.orEmpty()),
                ).joinToString(","),
            )
        }
    }

private fun csvField(value: Any?): String =
    buildString {
        append('"')
        append(value?.toString().orEmpty().replace("\"", "\"\""))
        append('"')
    }

private data class SectionTruncationFlags(
    val telemetry: Boolean,
    val nativeEvents: Boolean,
    val snapshots: Boolean,
    val contexts: Boolean,
    val logcat: Boolean,
)

private fun sectionStatusForFileName(
    fileName: String,
    flags: SectionTruncationFlags,
): DiagnosticsArchiveSectionStatus =
    when (fileName) {
        "summary.txt",
        "manifest.json",
        "report.json",
        "home-analysis.json",
        "stage-index.json",
        "stage-summaries.json",
        -> {
            DiagnosticsArchiveSectionStatus.REDACTED
        }

        "network-snapshots.json" -> {
            if (flags.snapshots) {
                DiagnosticsArchiveSectionStatus.TRUNCATED
            } else {
                DiagnosticsArchiveSectionStatus.REDACTED
            }
        }

        "diagnostic-context.json" -> {
            if (flags.contexts) {
                DiagnosticsArchiveSectionStatus.TRUNCATED
            } else {
                DiagnosticsArchiveSectionStatus.REDACTED
            }
        }

        "telemetry.csv" -> {
            if (flags.telemetry) {
                DiagnosticsArchiveSectionStatus.TRUNCATED
            } else {
                DiagnosticsArchiveSectionStatus.INCLUDED
            }
        }

        "native-events.csv" -> {
            if (flags.nativeEvents) {
                DiagnosticsArchiveSectionStatus.TRUNCATED
            } else {
                DiagnosticsArchiveSectionStatus.INCLUDED
            }
        }

        "logcat.txt" -> {
            if (flags.logcat) {
                DiagnosticsArchiveSectionStatus.TRUNCATED
            } else {
                DiagnosticsArchiveSectionStatus.INCLUDED
            }
        }

        else -> {
            stageSectionStatusForFileName(fileName, flags)
        }
    }

private fun stageSectionStatusForFileName(
    fileName: String,
    flags: SectionTruncationFlags,
): DiagnosticsArchiveSectionStatus =
    when {
        fileName.endsWith("/report.json") -> {
            DiagnosticsArchiveSectionStatus.REDACTED
        }

        fileName.endsWith("/network-snapshots.json") -> {
            if (flags.snapshots) {
                DiagnosticsArchiveSectionStatus.TRUNCATED
            } else {
                DiagnosticsArchiveSectionStatus.REDACTED
            }
        }

        fileName.endsWith("/diagnostic-context.json") -> {
            if (flags.contexts) {
                DiagnosticsArchiveSectionStatus.TRUNCATED
            } else {
                DiagnosticsArchiveSectionStatus.REDACTED
            }
        }

        fileName.endsWith("/telemetry.csv") -> {
            if (flags.telemetry) {
                DiagnosticsArchiveSectionStatus.TRUNCATED
            } else {
                DiagnosticsArchiveSectionStatus.INCLUDED
            }
        }

        fileName.endsWith("/native-events.csv") -> {
            if (flags.nativeEvents) {
                DiagnosticsArchiveSectionStatus.TRUNCATED
            } else {
                DiagnosticsArchiveSectionStatus.INCLUDED
            }
        }

        else -> {
            DiagnosticsArchiveSectionStatus.INCLUDED
        }
    }

private fun List<NativeSessionEventEntity>.latestCorrelation(selector: (NativeSessionEventEntity) -> String?): String? =
    asSequence()
        .sortedByDescending(NativeSessionEventEntity::createdAt)
        .mapNotNull(selector)
        .firstOrNull()

private fun List<NativeSessionEventEntity>.lifecycleMilestones(limit: Int = 6): List<String> =
    asSequence()
        .sortedByDescending(NativeSessionEventEntity::createdAt)
        .filter { event ->
            val subsystem = (event.subsystem ?: event.source).lowercase()
            val message = event.message.lowercase()
            subsystem in setOf("service", "proxy", "tunnel", "diagnostics") &&
                (
                    message.contains("started") ||
                        message.contains("stopped") ||
                        message.contains("stop requested") ||
                        message.contains("listener started") ||
                        message.contains("listener stopped")
                )
        }.take(limit)
        .map { event -> "${event.subsystem ?: event.source}: ${event.message}" }
        .toList()

private fun List<NativeSessionEventEntity>.recentWarningPreview(limit: Int = 5): List<String> =
    asSequence()
        .sortedByDescending(NativeSessionEventEntity::createdAt)
        .filter { event ->
            event.level.equals("warn", ignoreCase = true) || event.level.equals("error", ignoreCase = true)
        }.take(limit)
        .map { event -> "${event.subsystem ?: event.source}: ${event.message}" }
        .toList()

private fun BypassApproachSummary.successRateLabel(): String =
    validatedSuccessRate?.let { rate ->
        "${(rate * SuccessRatePercentScale).toInt()}%"
    } ?: "unverified"

private fun DiagnosticsArchiveBuildProvenance.toSummary(): DiagnosticsArchiveBuildProvenanceSummary =
    DiagnosticsArchiveBuildProvenanceSummary(
        applicationId = applicationId,
        appVersionName = appVersionName,
        appVersionCode = appVersionCode,
        buildType = buildType,
        gitCommit = gitCommit,
        nativeLibraries = nativeLibraries.map { "${it.name}:${it.version}" },
    )

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
                if (skipped) {
                    add(rationale)
                }
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
        if (result.lastOrNull() != value) {
            result += value
        }
    }
    return result
}

private fun sha256Hex(value: String): String = sha256Hex(value.toByteArray())

private fun sha256Hex(value: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
