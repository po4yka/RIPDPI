package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.data.diagnostics.retryCount
import com.poyka.ripdpi.data.diagnostics.rttBand
import com.poyka.ripdpi.data.diagnostics.winningStrategyFamily
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeStageSummary
import com.poyka.ripdpi.diagnostics.LogcatSnapshotCollector

internal fun buildStageIndexEntries(selection: DiagnosticsArchiveSelection): List<DiagnosticsArchiveStageIndexEntry> =
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
                    sample.proxyTelemetryState,
                    sample.proxyTelemetryMessage.orEmpty(),
                    sample.relayTelemetryState,
                    sample.relayTelemetryMessage.orEmpty(),
                    sample.warpTelemetryState,
                    sample.warpTelemetryMessage.orEmpty(),
                    sample.tunnelTelemetryState,
                    sample.tunnelTelemetryMessage.orEmpty(),
                    sample.telemetryNetworkFingerprintHash.orEmpty(),
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
)

internal fun buildSectionStatuses(
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

internal fun buildCompleteness(
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
            if (snapshotDecodeFailures > 0) add("snapshot_decode_failed_count:$snapshotDecodeFailures")
            if (contextDecodeFailures > 0) add("context_decode_failed_count:$contextDecodeFailures")
            if (selection.buildProvenance.gitCommit == "unavailable") add("git_commit_unavailable")
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
