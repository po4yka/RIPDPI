package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.data.diagnostics.retryCount
import com.poyka.ripdpi.data.diagnostics.rttBand
import com.poyka.ripdpi.data.diagnostics.winningStrategyFamily
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeOutcome
import com.poyka.ripdpi.diagnostics.LogcatSnapshotCollector
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

internal fun buildStageIndexEntries(selection: DiagnosticsArchiveSelection): List<DiagnosticsArchiveStageIndexEntry> =
    selection.compositeStages.map { stage ->
        stage.toArchiveStageIndexEntry(selection.detectionProvenance())
    }

private fun DiagnosticsArchiveCompositeStageSelection.toArchiveStageIndexEntry(
    detectionProvenance: DiagnosticsArchiveDetectionProvenance?,
): DiagnosticsArchiveStageIndexEntry =
    DiagnosticsArchiveStageIndexEntry(
        stageKey = stageSummary.stageKey,
        stageLabel = redactDiagnosticsArchiveText(stageSummary.stageLabel),
        profileId = stageSummary.profileId,
        pathMode = stageSummary.pathMode.name,
        sessionId = stageSummary.sessionId,
        status =
            if (stageSummary.status.name == "COMPLETED" && session == null) {
                "evidence_unavailable"
            } else {
                stageSummary.status.name.lowercase()
            },
        headline = redactDiagnosticsArchiveText(stageSummary.headline),
        summary = redactDiagnosticsArchiveText(stageSummary.summary),
        recommendationContributor = stageSummary.recommendationContributor,
        sourceSnapshotCount = sourceSnapshotCount,
        includedSnapshotCount = snapshots.size,
        snapshotsTruncated = sourceSnapshotCount > DiagnosticsArchiveFormat.snapshotLimit,
        sourceContextCount = sourceContextCount,
        includedContextCount = contexts.size,
        contextsTruncated = sourceContextCount > DiagnosticsArchiveFormat.snapshotLimit,
        sourceEventCount = sourceEventCount,
        includedEventCount = events.size,
        eventsTruncated = sourceEventCount > DiagnosticsArchiveFormat.sessionEventLimit,
        sourceTelemetryCount = sourceTelemetryCount,
        includedTelemetryCount = telemetry.size,
        telemetryTruncated = sourceTelemetryCount > DiagnosticsArchiveFormat.telemetryLimit,
        detectionProvenance = detectionProvenance?.takeIf { stageSummary.stageKey == it.stageKey },
    )

internal fun DiagnosticsArchiveSelection.detectionProvenance(): DiagnosticsArchiveDetectionProvenance? =
    homeCompositeOutcome
        ?.takeIf(DiagnosticsHomeCompositeOutcome::hasDetectionProvenance)
        ?.let { outcome ->
            val evidenceAvailable = outcome.detectionSignalCount != null
            DiagnosticsArchiveDetectionProvenance(
                stageKey = "detection_signals",
                verdict = outcome.detectionVerdict?.name,
                ruleApplied = outcome.detectionRuleApplied,
                evidenceScopes = outcome.detectionEvidenceScopes.map { it.name },
                evidenceStatus = if (evidenceAvailable) "available" else "unavailable",
                uniqueSignalCount = outcome.detectionSignalCount?.let(::JsonPrimitive) ?: JsonNull,
                localFindingCount = outcome.detectionLocalFindings.size.archiveCountOrNull(evidenceAvailable),
                networkFindingCount = outcome.detectionNetworkFindings.size.archiveCountOrNull(evidenceAvailable),
            )
        }

private fun DiagnosticsHomeCompositeOutcome.hasDetectionProvenance(): Boolean =
    detectionVerdict != null ||
        detectionRuleApplied != null ||
        detectionEvidenceScopes.isNotEmpty()

private fun Int.archiveCountOrNull(evidenceAvailable: Boolean) =
    if (evidenceAvailable) JsonPrimitive(this) else JsonNull

internal fun buildTelemetryCsv(selection: DiagnosticsArchiveSelection): String =
    buildTelemetryCsv(
        payload = selection.payload,
        measurementSnapshot =
            buildMeasurementSnapshot(
                selection = selection,
                strategyProbe = selection.primaryReport?.strategyProbeReport,
                latestTelemetry = selection.payload.telemetry.firstOrNull(),
            ),
    )

internal fun buildTelemetryCsv(payload: DiagnosticsArchivePayload): String =
    buildTelemetryCsv(
        payload = payload,
        measurementSnapshot = DiagnosticsArchiveMeasurementSnapshot(),
    )

private fun buildTelemetryCsv(
    payload: DiagnosticsArchivePayload,
    measurementSnapshot: DiagnosticsArchiveMeasurementSnapshot,
): String =
    buildString {
        appendLine(
            "createdAt,activeMode,connectionState,networkType,publicIp,failureClass," +
                "lastFailureClass,lastFallbackAction," +
                "proxyTelemetryState,proxyTelemetryMessage,relayTelemetryState,relayTelemetryMessage," +
                "warpTelemetryState,warpTelemetryMessage,tunnelTelemetryState,tunnelTelemetryMessage," +
                "telemetryNetworkFingerprintHash,winningTcpStrategyFamily,winningQuicStrategyFamily," +
                "winningStrategyFamily,networkIdentityBucket,targetBucket,recommendedTcpEmitterTier," +
                "recommendedQuicEmitterTier,acceptanceMatrixCoveragePercent,winnerCoveragePercent," +
                "detectabilityBudgetState,missingRuntimeCapabilities,proxyRttBand,resolverRttBand," +
                "rttBand,proxyRouteRetryCount," +
                "tunnelRecoveryRetryCount,retryCount,resolverId,resolverProtocol," +
                "resolverEndpoint,resolverLatencyMs,dnsFailuresTotal,resolverFallbackActive," +
                "resolverFallbackReason,networkHandoverClass,txPackets,txBytes,rxPackets,rxBytes,relayProtocolKind",
        )
        payload.telemetry.map { it.redactForArchive() }.forEach { sample ->
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
                    sample.proxyTelemetryState,
                    sample.proxyTelemetryMessage.orEmpty(),
                    sample.relayTelemetryState,
                    sample.relayTelemetryMessage.orEmpty(),
                    sample.warpTelemetryState,
                    sample.warpTelemetryMessage.orEmpty(),
                    sample.tunnelTelemetryState,
                    sample.tunnelTelemetryMessage.orEmpty(),
                    archiveFingerprintProjection(sample.telemetryNetworkFingerprintHash).orEmpty(),
                    sample.winningTcpStrategyFamily.orEmpty(),
                    sample.winningQuicStrategyFamily.orEmpty(),
                    sample.winningStrategyFamily().orEmpty(),
                    measurementSnapshot.networkIdentityBucket,
                    measurementSnapshot.targetBucket,
                    measurementSnapshot.recommendedTcpEmitterTier.orEmpty(),
                    measurementSnapshot.recommendedQuicEmitterTier.orEmpty(),
                    measurementSnapshot.acceptanceMetrics.matrixCoveragePercent ?: 0,
                    measurementSnapshot.acceptanceMetrics.winnerCoveragePercent ?: 0,
                    if (measurementSnapshot.rolloutGateAssessment.results.any {
                            it.id == "detectability_budget" &&
                                it.passed
                        }
                    ) {
                        "pass"
                    } else {
                        "fail"
                    },
                    measurementSnapshot.capabilitySnapshot.inferredUnavailableCapabilities.joinToString("|"),
                    sample.proxyRttBand,
                    sample.resolverRttBand,
                    sample.rttBand(),
                    sample.proxyRouteRetryCount,
                    sample.tunnelRecoveryRetryCount,
                    sample.retryCount(),
                    sample.resolverId.orEmpty(),
                    sample.resolverProtocol.orEmpty(),
                    if (sample.resolverEndpoint.isNullOrBlank()) "" else "redacted",
                    sample.resolverLatencyMs ?: 0,
                    sample.dnsFailuresTotal,
                    sample.resolverFallbackActive,
                    sample.resolverFallbackReason.orEmpty(),
                    sample.networkHandoverClass.orEmpty(),
                    sample.txPackets,
                    sample.txBytes,
                    sample.rxPackets,
                    sample.rxBytes,
                    sample.relayProtocolKind.orEmpty(),
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
                    csvField(redactDiagnosticsArchiveText(event.message)),
                    csvField(event.createdAt),
                    csvField(event.runtimeId?.let(::redactDiagnosticsArchiveText).orEmpty()),
                    csvField(event.mode.orEmpty()),
                    csvField(archiveStableCorrelatorProjection(event.policySignature).orEmpty()),
                    csvField(archiveFingerprintProjection(event.fingerprintHash).orEmpty()),
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
                    csvField(redactDiagnosticsArchiveText(event.message)),
                    csvField(event.createdAt),
                    csvField(event.runtimeId?.let(::redactDiagnosticsArchiveText).orEmpty()),
                    csvField(event.mode.orEmpty()),
                    csvField(archiveStableCorrelatorProjection(event.policySignature).orEmpty()),
                    csvField(archiveFingerprintProjection(event.fingerprintHash).orEmpty()),
                    csvField(event.subsystem.orEmpty()),
                ).joinToString(","),
            )
        }
    }

internal fun csvField(value: Any?): String =
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
    val appLog: Boolean,
    val startupJournal: Boolean,
)

private fun DiagnosticsArchiveSelection.rootNativeEventsTruncated(): Boolean =
    sourceCounts.primarySession.events > DiagnosticsArchiveFormat.sessionEventLimit ||
        rootSourceCounts.globalEvents > DiagnosticsArchiveFormat.globalEventLimit

private fun DiagnosticsArchiveSelection.anyNativeEventsTruncated(): Boolean =
    rootNativeEventsTruncated() ||
        compositeStages.any { it.sourceEventCount > DiagnosticsArchiveFormat.sessionEventLimit }

private fun DiagnosticsArchiveSelection.anyTelemetryTruncated(): Boolean =
    rootSourceCounts.telemetrySamples > DiagnosticsArchiveFormat.telemetryLimit ||
        compositeStages.any { it.sourceTelemetryCount > DiagnosticsArchiveFormat.telemetryLimit }

private fun DiagnosticsArchiveSelection.anySnapshotsTruncated(): Boolean =
    rootSourceCounts.primarySnapshots > DiagnosticsArchiveFormat.snapshotLimit ||
        compositeStages.any { it.sourceSnapshotCount > DiagnosticsArchiveFormat.snapshotLimit }

private fun DiagnosticsArchiveSelection.anyContextsTruncated(): Boolean =
    rootSourceCounts.primaryContexts > DiagnosticsArchiveFormat.snapshotLimit ||
        compositeStages.any { it.sourceContextCount > DiagnosticsArchiveFormat.snapshotLimit }

internal fun buildSectionStatuses(
    selection: DiagnosticsArchiveSelection,
): Map<String, DiagnosticsArchiveSectionStatus> {
    val truncationFlags =
        SectionTruncationFlags(
            telemetry = selection.rootSourceCounts.telemetrySamples > DiagnosticsArchiveFormat.telemetryLimit,
            nativeEvents = selection.rootNativeEventsTruncated(),
            snapshots = selection.rootSourceCounts.primarySnapshots > DiagnosticsArchiveFormat.snapshotLimit,
            contexts = selection.rootSourceCounts.primaryContexts > DiagnosticsArchiveFormat.snapshotLimit,
            logcat = selection.logcatSnapshot?.truncated == true,
            appLog = selection.fileLogSnapshot?.truncated == true,
            startupJournal = selection.startupJournalSnapshot?.truncated == true,
        )
    return buildMap {
        selection.includedFiles.forEach { fileName ->
            val compositeStage =
                selection.compositeStages.firstOrNull { stage ->
                    fileName.startsWith("stages/${stage.stageSummary.stageKey}/")
                }
            put(
                fileName,
                if (fileName == "execution-plan.json" && selection.primaryReport?.executionPlan == null) {
                    DiagnosticsArchiveSectionStatus.UNAVAILABLE
                } else if (
                    compositeStage != null &&
                    fileName.endsWith("/execution-plan.json") &&
                    compositeStage.report?.executionPlan == null
                ) {
                    DiagnosticsArchiveSectionStatus.UNAVAILABLE
                } else if (compositeStage != null) {
                    sectionStatusForFileName(
                        fileName = fileName,
                        flags =
                            SectionTruncationFlags(
                                telemetry =
                                    compositeStage.sourceTelemetryCount > DiagnosticsArchiveFormat.telemetryLimit,
                                nativeEvents =
                                    compositeStage.sourceEventCount > DiagnosticsArchiveFormat.sessionEventLimit,
                                snapshots =
                                    compositeStage.sourceSnapshotCount > DiagnosticsArchiveFormat.snapshotLimit,
                                contexts =
                                    compositeStage.sourceContextCount > DiagnosticsArchiveFormat.snapshotLimit,
                                logcat = false,
                                appLog = false,
                                startupJournal = false,
                            ),
                    )
                } else {
                    if (fileName == "app-log.txt" && truncationFlags.appLog) {
                        DiagnosticsArchiveSectionStatus.TRUNCATED
                    } else {
                        sectionStatusForFileName(fileName, truncationFlags)
                    }
                },
            )
        }
    }
}

internal fun buildCompleteness(
    selection: DiagnosticsArchiveSelection,
    sectionStatuses: Map<String, DiagnosticsArchiveSectionStatus>,
    snapshotPayload: DiagnosticsArchiveSnapshotPayload,
    contextPayload: DiagnosticsArchiveContextPayload,
): DiagnosticsArchiveCompletenessPayload {
    val decodeFailures = selection.decodeFailures(snapshotPayload, contextPayload)
    val relayCompleteness = selection.relayTraceCompleteness()
    return DiagnosticsArchiveCompletenessPayload(
        sectionStatuses = sectionStatuses,
        appliedLimits =
            DiagnosticsArchiveAppliedLimits(
                telemetrySamples = DiagnosticsArchiveFormat.telemetryLimit,
                nativeEvents = DiagnosticsArchiveFormat.globalEventLimit,
                snapshots = DiagnosticsArchiveFormat.snapshotLimit,
                logcatBytes = LogcatSnapshotCollector.MAX_LOGCAT_BYTES,
                appLogBytes = com.poyka.ripdpi.diagnostics.FileLogWriter.MAX_LOG_FILE_BYTES,
                startupJournalBytes = 32 * 1024,
            ),
        sourceCounts = selection.sourceCounts,
        includedCounts = selection.includedCounts(snapshotPayload, contextPayload),
        relayAttemptTraces = relayCompleteness.trace,
        collectionWarnings = selection.completenessWarnings(decodeFailures),
        reasons = selection.completenessReasons(decodeFailures, relayCompleteness),
        truncation = selection.truncation(),
    )
}

private data class DiagnosticsArchiveDecodeFailures(
    val snapshotCount: Int,
    val contextCount: Int,
)

private data class DiagnosticsArchiveRelayCompleteness(
    val trace: DiagnosticsArchiveRelayTraceCompleteness,
    val retainedEvents: List<NativeSessionEventEntity>,
    val sequenceGaps: List<DiagnosticsArchiveRelaySequenceGap>,
)

private fun DiagnosticsArchiveSelection.decodeFailures(
    snapshotPayload: DiagnosticsArchiveSnapshotPayload,
    contextPayload: DiagnosticsArchiveContextPayload,
): DiagnosticsArchiveDecodeFailures {
    val sourceSnapshotIds =
        (primarySnapshots + runtimeSnapshots + listOfNotNull(latestPassiveSnapshot))
            .map { it.id }
            .toSet()
    val sourceContextCount =
        primaryContexts.size +
            if (latestPassiveContext != null) 1 else 0
    val includedContextCount =
        contextPayload.sessionContexts.size +
            if (contextPayload.latestPassiveContext != null) 1 else 0
    return DiagnosticsArchiveDecodeFailures(
        snapshotCount = (sourceSnapshotIds - snapshotPayload.includedSourceIds).size,
        contextCount = sourceContextCount - includedContextCount,
    )
}

private fun DiagnosticsArchiveSelection.relayTraceCompleteness(): DiagnosticsArchiveRelayCompleteness {
    val relaySourceEvents = (primaryEvents + globalEvents).distinctBy { it.id }
    val relayTraceEvents = selectRelayAttemptTraceEvents(primaryEvents, globalEvents)
    val droppedEventsByConnection =
        (payload.telemetry + compositeStages.flatMap { it.telemetry })
            .filter { !it.connectionSessionId.isNullOrBlank() }
            .groupBy { requireNotNull(it.connectionSessionId) }
            .mapValues { (_, samples) -> samples.maxOfOrNull { it.relayNativeEventsDropped } ?: 0 }
    val sequenceGaps = buildRelaySequenceGaps(relayTraceEvents, relaySourceEvents, droppedEventsByConnection)
    return DiagnosticsArchiveRelayCompleteness(
        trace =
            DiagnosticsArchiveRelayTraceCompleteness(
                retainedEventCount = relayTraceEvents.size,
                droppedEventCount = droppedEventsByConnection.values.sum(),
                retainedDecisionCount = relaySourceEvents.count { event -> event.subsystem == "relay_health_decision" },
                unsupportedAttemptCount = countUnsupportedRelayAttempts(relaySourceEvents, relayTraceEvents),
                sequenceGaps = sequenceGaps,
            ),
        retainedEvents = relayTraceEvents,
        sequenceGaps = sequenceGaps,
    )
}

private fun DiagnosticsArchiveSelection.completenessWarnings(
    decodeFailures: DiagnosticsArchiveDecodeFailures,
): List<String> =
    buildList {
        addAll(collectionWarnings)
        if (decodeFailures.snapshotCount > 0) add("snapshot_decode_failed_count:${decodeFailures.snapshotCount}")
        if (decodeFailures.contextCount > 0) add("context_decode_failed_count:${decodeFailures.contextCount}")
        if (buildProvenance.gitCommit == "unavailable") add("git_commit_unavailable")
        buildProvenance.nativeLibraries
            .filter { it.version == "unavailable" }
            .forEach { add("native_library_version_unavailable:${it.name}") }
    }

private fun DiagnosticsArchiveSelection.completenessReasons(
    decodeFailures: DiagnosticsArchiveDecodeFailures,
    relayCompleteness: DiagnosticsArchiveRelayCompleteness,
): List<DiagnosticsArchiveCompletenessReason> =
    buildList {
        if (decodeFailures.snapshotCount > 0) {
            add(DiagnosticsArchiveCompletenessReason("snapshots", "decode_failed", decodeFailures.snapshotCount))
        }
        if (decodeFailures.contextCount > 0) {
            add(DiagnosticsArchiveCompletenessReason("contexts", "decode_failed", decodeFailures.contextCount))
        }
        if (buildProvenance.gitCommit == "unavailable") {
            add(DiagnosticsArchiveCompletenessReason("build_provenance", "git_commit_unavailable"))
        }
        buildProvenance.nativeLibraries
            .count { it.version == "unavailable" }
            .takeIf { it > 0 }
            ?.let { count ->
                add(
                    DiagnosticsArchiveCompletenessReason(
                        "build_provenance",
                        "native_version_unavailable",
                        count,
                    ),
                )
            }
        if (relayCompleteness.retainedEvents.isNotEmpty() && relayCompleteness.sequenceGaps.isNotEmpty()) {
            add(DiagnosticsArchiveCompletenessReason("relay_attempt_traces", "sequence_gap"))
        }
        if (relayCompleteness.trace.unsupportedAttemptCount > 0) {
            add(
                DiagnosticsArchiveCompletenessReason(
                    "relay_attempt_traces",
                    "unsupported_attempt",
                    relayCompleteness.trace.unsupportedAttemptCount,
                ),
            )
        }
    }

private fun DiagnosticsArchiveSelection.includedCounts(
    snapshotPayload: DiagnosticsArchiveSnapshotPayload,
    contextPayload: DiagnosticsArchiveContextPayload,
) = DiagnosticsArchiveScopedCounts(
    archiveWide =
        DiagnosticsArchiveArchiveWideCounts(
            telemetrySamples =
                (payload.telemetry + compositeStages.flatMap { it.telemetry }).distinctBy { it.id }.size,
            nativeEvents =
                (primaryEvents + globalEvents + compositeStages.flatMap { it.events }).distinctBy { it.id }.size,
            snapshots =
                (snapshotPayload.includedSourceIds + compositeStages.flatMap { it.snapshots }.map { it.id }).size,
            contexts =
                (primaryContexts + listOfNotNull(latestPassiveContext) + compositeStages.flatMap { it.contexts })
                    .distinctBy { it.id }
                    .size,
        ),
    primarySession =
        DiagnosticsArchivePrimarySessionCounts(
            results = primaryResults.size,
            snapshots = snapshotPayload.sessionSnapshots.size,
            contexts = contextPayload.sessionContexts.size,
            events = primaryEvents.size,
        ),
)

private fun DiagnosticsArchiveSelection.truncation() =
    DiagnosticsArchiveTruncation(
        telemetrySamples = anyTelemetryTruncated(),
        nativeEvents = anyNativeEventsTruncated(),
        snapshots = anySnapshotsTruncated(),
        contexts = anyContextsTruncated(),
        logcat = logcatSnapshot?.truncated == true,
        appLog = fileLogSnapshot?.truncated == true,
        startupJournal = startupJournalSnapshot?.truncated == true,
    )

internal fun buildIntegrityPayload(
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

private fun sectionStatusForFileName(
    fileName: String,
    flags: SectionTruncationFlags,
): DiagnosticsArchiveSectionStatus =
    when (fileName) {
        "summary.txt",
        "manifest.json",
        "report.json",
        "execution-plan.json",
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

        "native-events.csv", "relay-attempt-traces.jsonl", "relay-health-decisions.jsonl" -> {
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

        "startup-journal.txt" -> {
            if (flags.startupJournal) {
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

        fileName.endsWith("/execution-plan.json") -> {
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
