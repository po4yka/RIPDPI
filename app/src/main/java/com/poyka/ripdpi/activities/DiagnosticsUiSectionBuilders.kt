package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.data.diagnostics.retryCount
import com.poyka.ripdpi.data.diagnostics.rttBand
import com.poyka.ripdpi.data.diagnostics.winningStrategyFamily
import com.poyka.ripdpi.diagnostics.BypassApproachKind
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.diagnostics.ScanReport
import com.poyka.ripdpi.diagnostics.ShareSummary
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.ActiveConnectionPolicy
import java.util.Locale

internal fun DiagnosticsUiFactorySupport.buildOverviewUiModel(
    health: DiagnosticsHealth,
    progress: ScanProgress?,
    latestSession: ScanSessionEntity?,
    latestSnapshot: DiagnosticsNetworkSnapshotUiModel?,
    latestContext: DiagnosticContextModel?,
    currentTelemetry: TelemetrySampleEntity?,
    sessions: List<ScanSessionEntity>,
    nativeEvents: List<NativeSessionEventEntity>,
    selectedProfile: DiagnosticsProfileOptionUiModel?,
    sessionRows: List<DiagnosticsSessionRowUiModel>,
    rememberedNetworkRows: List<DiagnosticsRememberedNetworkUiModel>,
    warnings: List<DiagnosticsEventUiModel>,
): DiagnosticsOverviewUiModel =
    DiagnosticsOverviewUiModel(
        health = health,
        headline = overviewHeadline(health, progress, latestSession),
        body = overviewBody(health, latestSnapshot, currentTelemetry),
        activeProfile = selectedProfile,
        latestSnapshot = latestSnapshot,
        latestSession = sessionRows.firstOrNull(),
        contextSummary = latestContext?.let(::toOverviewContextGroup),
        metrics = buildOverviewMetrics(health, sessions, nativeEvents, currentTelemetry),
        warnings = warnings,
        rememberedNetworks = rememberedNetworkRows.take(6),
    )

internal fun DiagnosticsUiFactorySupport.buildScanUiModel(
    profiles: List<DiagnosticProfileEntity>,
    activeProfile: DiagnosticProfileEntity?,
    activeProfileRequest: com.poyka.ripdpi.diagnostics.ScanRequest?,
    latestProfileSession: ScanSessionEntity?,
    latestReportResults: List<DiagnosticsProbeResultUiModel>,
    latestResolverRecommendation: DiagnosticsResolverRecommendationUiModel?,
    latestStrategyProbeReport: DiagnosticsStrategyProbeReportUiModel?,
    progress: ScanProgress?,
    rawArgsEnabled: Boolean,
): DiagnosticsScanUiModel {
    val selectedProfile = activeProfile?.let(::toProfileOptionUiModel)
    val strategyProbeSelected = selectedProfile?.isStrategyProbe == true
    val runRawEnabled = progress == null && !(strategyProbeSelected && rawArgsEnabled)
    val runInPathEnabled = progress == null && !strategyProbeSelected
    val workflowLabel =
        if (selectedProfile?.isFullAudit == true) {
            "Automatic audit"
        } else {
            "Automatic probing"
        }
    val runRawHint =
        when {
            strategyProbeSelected && rawArgsEnabled ->
                "$workflowLabel only works with visual RIPDPI settings. Command-line mode is active."

            strategyProbeSelected ->
                "$workflowLabel starts a temporary raw-path RIPDPI runtime and returns a manual recommendation."

            else -> null
        }
    val runInPathHint =
        when {
            strategyProbeSelected ->
                "$workflowLabel is raw-path only because it launches isolated temporary strategy trials."

            else -> null
        }

    return DiagnosticsScanUiModel(
        profiles = profiles.map(::toProfileOptionUiModel),
        selectedProfileId = activeProfile?.id,
        selectedProfile = selectedProfile,
        activePathMode = latestProfileSession?.pathMode?.let(::parsePathMode) ?: ScanPathMode.RAW_PATH,
        activeProgress = progress?.let(::toProgressUiModel),
        latestSession = latestProfileSession?.let(::toSessionRowUiModel),
        latestResults = latestReportResults,
        selectedProfileScopeLabel = toScopeLabel(activeProfileRequest, rawArgsEnabled),
        runRawEnabled = runRawEnabled,
        runInPathEnabled = runInPathEnabled,
        runRawHint = runRawHint,
        runInPathHint = runInPathHint,
        resolverRecommendation = latestResolverRecommendation,
        strategyProbeReport = latestStrategyProbeReport,
        isBusy = progress != null,
    )
}

internal fun DiagnosticsUiFactorySupport.buildLiveUiModel(
    health: DiagnosticsHealth,
    telemetry: List<TelemetrySampleEntity>,
    currentTelemetry: TelemetrySampleEntity?,
    nativeEvents: List<NativeSessionEventEntity>,
    latestSnapshot: DiagnosticsNetworkSnapshotUiModel?,
    latestContext: DiagnosticContextModel?,
    eventModels: List<DiagnosticsEventUiModel>,
): DiagnosticsLiveUiModel =
    DiagnosticsLiveUiModel(
        statusLabel = currentTelemetry?.connectionState ?: "Idle",
        freshnessLabel =
            currentTelemetry?.createdAt?.let { "Updated ${formatTimestamp(it)}" }
                ?: "No live telemetry",
        headline = buildLiveHeadline(health, currentTelemetry, nativeEvents),
        body = buildLiveBody(currentTelemetry, nativeEvents),
        networkLabel = currentTelemetry?.networkType,
        modeLabel = currentTelemetry?.activeMode,
        signalLabel = buildLiveSignalLabel(currentTelemetry),
        eventSummaryLabel = buildLiveEventSummaryLabel(nativeEvents),
        highlights = buildLiveHighlights(currentTelemetry, nativeEvents),
        metrics = buildLiveMetrics(currentTelemetry, nativeEvents),
        trends = buildLiveTrends(telemetry),
        snapshot = latestSnapshot,
        contextGroups = latestContext?.let(::toLiveContextGroups).orEmpty(),
        passiveEvents = eventModels.take(8),
    )

internal fun DiagnosticsUiFactorySupport.buildSessionsUiModel(
    sessions: List<ScanSessionEntity>,
    sessionRows: List<DiagnosticsSessionRowUiModel>,
    sessionPathMode: String?,
    sessionStatus: String?,
    sessionSearch: String,
    selectedSessionDetail: DiagnosticsSessionDetailUiModel?,
): DiagnosticsSessionsUiModel {
    val filteredSessions =
        sessionRows.filter { session ->
            (sessionPathMode == null || session.pathMode == sessionPathMode) &&
                (sessionStatus == null || session.status.equals(sessionStatus, ignoreCase = true)) &&
                session.matchesQuery(sessionSearch)
        }
    return DiagnosticsSessionsUiModel(
        filters =
            DiagnosticsSessionFiltersUiModel(
                pathMode = sessionPathMode,
                status = sessionStatus,
                query = sessionSearch,
            ),
        sessions = filteredSessions,
        pathModes = sessions.map { it.pathMode }.distinct(),
        statuses = sessions.map { it.status }.distinct(),
        focusedSessionId = selectedSessionDetail?.session?.id,
    )
}

internal fun DiagnosticsUiFactorySupport.buildApproachesUiModel(
    approachStats: List<BypassApproachSummary>,
    selectedApproachMode: DiagnosticsApproachMode,
    selectedApproachDetail: DiagnosticsApproachDetailUiModel?,
): DiagnosticsApproachesUiModel {
    val selectedApproachKind =
        when (selectedApproachMode) {
            DiagnosticsApproachMode.Profiles -> BypassApproachKind.Profile
            DiagnosticsApproachMode.Strategies -> BypassApproachKind.Strategy
        }
    val rows =
        approachStats
            .filter { it.approachId.kind == selectedApproachKind }
            .map { summary -> toApproachRowUiModel(summary, selectedApproachMode) }
    return DiagnosticsApproachesUiModel(
        selectedMode = selectedApproachMode,
        rows = rows,
        focusedApproachId = selectedApproachDetail?.approach?.id,
    )
}

internal fun DiagnosticsUiFactorySupport.buildEventsUiModel(
    eventModels: List<DiagnosticsEventUiModel>,
    selectedEventId: String?,
    eventSource: String?,
    eventSeverity: String?,
    eventSearch: String,
    eventAutoScroll: Boolean,
): Pair<DiagnosticsEventsUiModel, DiagnosticsEventUiModel?> {
    val filteredEvents =
        eventModels.filter { event ->
            (eventSource == null || event.source.equals(eventSource, ignoreCase = true)) &&
                (eventSeverity == null || event.severity.equals(eventSeverity, ignoreCase = true)) &&
                event.matchesQuery(eventSearch)
        }
    val selectedEvent = filteredEvents.firstOrNull { it.id == selectedEventId }
    return DiagnosticsEventsUiModel(
        filters =
            DiagnosticsEventFiltersUiModel(
                source = eventSource,
                severity = eventSeverity,
                search = eventSearch,
                autoScroll = eventAutoScroll,
            ),
        events = filteredEvents,
        availableSources = eventModels.map { it.source }.distinct(),
        availableSeverities = eventModels.map { it.severity }.distinct(),
        focusedEventId = selectedEvent?.id,
    ) to selectedEvent
}

internal fun DiagnosticsUiFactorySupport.buildShareUiModel(
    latestCompletedSession: ScanSessionEntity?,
    latestSnapshot: DiagnosticsNetworkSnapshotUiModel?,
    latestContext: DiagnosticContextModel?,
    currentTelemetry: TelemetrySampleEntity?,
    nativeEvents: List<NativeSessionEventEntity>,
    latestReport: ScanReport?,
    approachStats: List<BypassApproachSummary>,
    selectedSessionDetail: DiagnosticsSessionDetailUiModel?,
    archiveActionState: ArchiveActionState,
    exports: List<ExportRecordEntity>,
): DiagnosticsShareUiModel {
    val sharePreview =
        buildSharePreview(
            latestSession = latestCompletedSession,
            latestSnapshot = latestSnapshot,
            latestContext = latestContext,
            telemetry = currentTelemetry,
            nativeEvents = nativeEvents,
            latestReport = latestReport,
        )
    return DiagnosticsShareUiModel(
        targetSessionId = selectedSessionDetail?.session?.id ?: latestCompletedSession?.id,
        previewTitle = sharePreview.title,
        previewBody =
            buildString {
                append(sharePreview.body)
                approachStats
                    .firstOrNull { it.approachId.kind == BypassApproachKind.Strategy }
                    ?.let { summary ->
                        append("\n\nArchive includes approach analytics for ")
                        append(summary.displayName)
                        append(" with ")
                        append(summary.verificationState)
                        append(" validation and runtime health context.")
                    }
            },
        metrics =
            sharePreview.compactMetrics.map { DiagnosticsMetricUiModel(it.label, it.value) } +
                listOfNotNull(
                    approachStats
                        .firstOrNull { it.approachId.kind == BypassApproachKind.Strategy }
                        ?.let { summary ->
                            DiagnosticsMetricUiModel(
                                label = "Approach",
                                value = summary.displayName,
                                tone = summary.toDiagnosticsTone(),
                            )
                        },
                ),
        latestArchiveFileName = archiveActionState.latestArchiveFileName ?: exports.firstOrNull()?.fileName,
        archiveStateMessage = archiveActionState.message,
        archiveStateTone = archiveActionState.tone,
        isArchiveBusy = archiveActionState.isBusy,
    )
}

internal fun DiagnosticsUiFactorySupport.deriveHealth(
    progress: ScanProgress?,
    latestSession: ScanSessionEntity?,
    latestTelemetry: TelemetrySampleEntity?,
    nativeEvents: List<NativeSessionEventEntity>,
): DiagnosticsHealth {
    if (progress != null) {
        return DiagnosticsHealth.Attention
    }
    if (latestSession == null && latestTelemetry == null && nativeEvents.isEmpty()) {
        return DiagnosticsHealth.Idle
    }
    if (nativeEvents.any { it.level.equals("error", ignoreCase = true) }) {
        return DiagnosticsHealth.Degraded
    }
    if (latestSession?.status?.contains("failed", ignoreCase = true) == true) {
        return DiagnosticsHealth.Degraded
    }
    if (nativeEvents.any { it.level.equals("warn", ignoreCase = true) }) {
        return DiagnosticsHealth.Attention
    }
    return DiagnosticsHealth.Healthy
}

private fun DiagnosticsUiFactorySupport.buildOverviewMetrics(
    health: DiagnosticsHealth,
    sessions: List<ScanSessionEntity>,
    nativeEvents: List<NativeSessionEventEntity>,
    currentTelemetry: TelemetrySampleEntity?,
): List<DiagnosticsMetricUiModel> =
    buildList {
        add(DiagnosticsMetricUiModel(label = "Sessions", value = sessions.size.toString()))
        add(
            DiagnosticsMetricUiModel(
                label = "Events",
                value = nativeEvents.size.toString(),
                tone =
                    when (health) {
                        DiagnosticsHealth.Degraded -> DiagnosticsTone.Negative
                        DiagnosticsHealth.Attention -> DiagnosticsTone.Warning
                        DiagnosticsHealth.Healthy -> DiagnosticsTone.Positive
                        DiagnosticsHealth.Idle -> DiagnosticsTone.Neutral
                    },
            ),
        )
        currentTelemetry?.let { sample ->
            add(DiagnosticsMetricUiModel(label = "TX", value = formatBytes(sample.txBytes), tone = DiagnosticsTone.Info))
            add(DiagnosticsMetricUiModel(label = "RX", value = formatBytes(sample.rxBytes), tone = DiagnosticsTone.Info))
        }
    }

private fun overviewHeadline(
    health: DiagnosticsHealth,
    progress: ScanProgress?,
    latestSession: ScanSessionEntity?,
): String =
    when {
        progress != null -> "Diagnostics scan is active"
        latestSession == null -> "No diagnostics captured yet"
        health == DiagnosticsHealth.Degraded -> "The network path needs attention"
        health == DiagnosticsHealth.Attention -> "Diagnostics are reporting mixed signals"
        health == DiagnosticsHealth.Healthy -> "Current telemetry looks stable"
        else -> "Waiting for the next diagnostics session"
    }

private fun overviewBody(
    health: DiagnosticsHealth,
    latestSnapshot: DiagnosticsNetworkSnapshotUiModel?,
    telemetry: TelemetrySampleEntity?,
): String =
    when (health) {
        DiagnosticsHealth.Healthy ->
            "Passive telemetry and the latest report do not show critical failures. Review live counters for drift."

        DiagnosticsHealth.Attention ->
            "Warnings or partial failures were detected. Compare the latest scan outcome with the current live monitor."

        DiagnosticsHealth.Degraded ->
            "Recent events or scan outcomes indicate blocking, failures, or transport instability."

        DiagnosticsHealth.Idle ->
            latestSnapshot?.subtitle ?: telemetry?.connectionState ?: "Start RIPDPI or run a scan to populate diagnostics analytics."
    }

private fun DiagnosticsUiFactorySupport.buildLiveMetrics(
    telemetry: TelemetrySampleEntity?,
    events: List<NativeSessionEventEntity>,
): List<DiagnosticsMetricUiModel> =
    buildList {
        if (telemetry != null) {
            add(DiagnosticsMetricUiModel(label = "Network", value = telemetry.networkType))
            add(DiagnosticsMetricUiModel(label = "Mode", value = telemetry.activeMode ?: "Idle"))
            telemetry.lastFailureClass?.let { failureClass ->
                add(DiagnosticsMetricUiModel(label = "Latest native failure", value = failureClass, tone = DiagnosticsTone.Warning))
            }
            telemetry.lastFallbackAction?.let { fallbackAction ->
                add(DiagnosticsMetricUiModel(label = "Fallback action", value = fallbackAction, tone = DiagnosticsTone.Info))
            }
            telemetry.failureClass?.let { failureClass ->
                add(DiagnosticsMetricUiModel(label = "Failure class", value = failureClass, tone = DiagnosticsTone.Warning))
            }
            telemetry.winningStrategyFamily()?.let { winningStrategy ->
                add(DiagnosticsMetricUiModel(label = "Winning strategy", value = winningStrategy, tone = DiagnosticsTone.Positive))
            }
            add(DiagnosticsMetricUiModel(label = "RTT band", value = telemetry.rttBand(), tone = DiagnosticsTone.Info))
            add(
                DiagnosticsMetricUiModel(
                    label = "Retries",
                    value = telemetry.retryCount().toString(),
                    tone = if (telemetry.retryCount() > 0) DiagnosticsTone.Warning else DiagnosticsTone.Neutral,
                ),
            )
            telemetry.resolverId?.let { resolverId ->
                add(
                    DiagnosticsMetricUiModel(
                        label = "Resolver",
                        value = listOfNotNull(resolverId, telemetry.resolverProtocol).joinToString(" · "),
                        tone = DiagnosticsTone.Info,
                    ),
                )
            }
            telemetry.resolverLatencyMs?.let { latency ->
                add(DiagnosticsMetricUiModel(label = "DNS latency", value = "$latency ms", tone = DiagnosticsTone.Info))
            }
            if (telemetry.dnsFailuresTotal > 0) {
                add(DiagnosticsMetricUiModel(label = "DNS failures", value = telemetry.dnsFailuresTotal.toString(), tone = DiagnosticsTone.Warning))
            }
            add(DiagnosticsMetricUiModel(label = "TX packets", value = telemetry.txPackets.toString(), tone = DiagnosticsTone.Info))
            add(DiagnosticsMetricUiModel(label = "RX packets", value = telemetry.rxPackets.toString(), tone = DiagnosticsTone.Info))
        }
        add(
            DiagnosticsMetricUiModel(
                label = "Warnings",
                value = events.count { it.level.equals("warn", ignoreCase = true) }.toString(),
                tone = DiagnosticsTone.Warning,
            ),
        )
        add(
            DiagnosticsMetricUiModel(
                label = "Errors",
                value = events.count { it.level.equals("error", ignoreCase = true) }.toString(),
                tone = DiagnosticsTone.Negative,
            ),
        )
    }

private fun DiagnosticsUiFactorySupport.buildLiveHighlights(
    telemetry: TelemetrySampleEntity?,
    events: List<NativeSessionEventEntity>,
): List<DiagnosticsMetricUiModel> {
    val warningCount = events.count { it.level.equals("warn", ignoreCase = true) }
    val errorCount = events.count { it.level.equals("error", ignoreCase = true) }
    return buildList {
        telemetry?.let {
            add(DiagnosticsMetricUiModel(label = "TX", value = formatBytes(it.txBytes), tone = DiagnosticsTone.Info))
            add(DiagnosticsMetricUiModel(label = "RX", value = formatBytes(it.rxBytes), tone = DiagnosticsTone.Positive))
            it.winningStrategyFamily()?.let { winningStrategy ->
                add(DiagnosticsMetricUiModel(label = "Strategy", value = winningStrategy, tone = DiagnosticsTone.Positive))
            }
            add(DiagnosticsMetricUiModel(label = "RTT", value = it.rttBand(), tone = DiagnosticsTone.Info))
            add(
                DiagnosticsMetricUiModel(
                    label = "Retries",
                    value = it.retryCount().toString(),
                    tone = if (it.retryCount() > 0) DiagnosticsTone.Warning else DiagnosticsTone.Neutral,
                ),
            )
            if (it.resolverFallbackActive) {
                add(
                    DiagnosticsMetricUiModel(
                        label = "Resolver fallback",
                        value = it.resolverFallbackReason ?: "Active",
                        tone = DiagnosticsTone.Warning,
                    ),
                )
            }
            add(DiagnosticsMetricUiModel(label = "Packets", value = (it.txPackets + it.rxPackets).toString(), tone = DiagnosticsTone.Neutral))
        }
        add(
            DiagnosticsMetricUiModel(
                label = "Warnings",
                value = warningCount.toString(),
                tone = if (warningCount > 0) DiagnosticsTone.Warning else DiagnosticsTone.Neutral,
            ),
        )
        add(
            DiagnosticsMetricUiModel(
                label = "Errors",
                value = errorCount.toString(),
                tone = if (errorCount > 0) DiagnosticsTone.Negative else DiagnosticsTone.Neutral,
            ),
        )
    }
}

private fun buildLiveTrends(
    telemetry: List<TelemetrySampleEntity>,
): List<DiagnosticsSparklineUiModel> {
    val samples = telemetry.take(24).reversed()
    if (samples.isEmpty()) {
        return emptyList()
    }
    return listOf(
        DiagnosticsSparklineUiModel(label = "TX bytes", values = samples.map { it.txBytes.toFloat() }, tone = DiagnosticsTone.Info),
        DiagnosticsSparklineUiModel(label = "RX bytes", values = samples.map { it.rxBytes.toFloat() }, tone = DiagnosticsTone.Positive),
        DiagnosticsSparklineUiModel(
            label = "Errors",
            values =
                samples.map { sample ->
                    if (sample.connectionState.equals("running", ignoreCase = true)) {
                        0f
                    } else {
                        1f
                    }
                },
            tone = DiagnosticsTone.Warning,
        ),
    )
}

private fun buildLiveHeadline(
    health: DiagnosticsHealth,
    telemetry: TelemetrySampleEntity?,
    events: List<NativeSessionEventEntity>,
): String {
    val surfacedEvent =
        events.firstOrNull { it.level.equals("error", ignoreCase = true) }
            ?: events.firstOrNull { it.level.equals("warn", ignoreCase = true) }
    return when {
        surfacedEvent?.level?.equals("error", ignoreCase = true) == true -> "Runtime needs intervention"
        health == DiagnosticsHealth.Attention -> "Runtime requires a closer look"
        telemetry == null -> "Live monitor standing by"
        telemetry.connectionState.equals("running", ignoreCase = true) -> "Traffic is moving through ${telemetry.networkType}"
        else -> telemetry.connectionState.replaceFirstChar { it.uppercase() }
    }
}

private fun DiagnosticsUiFactorySupport.buildLiveBody(
    telemetry: TelemetrySampleEntity?,
    events: List<NativeSessionEventEntity>,
): String {
    val surfacedEvent =
        events.firstOrNull { it.level.equals("error", ignoreCase = true) }
            ?: events.firstOrNull { it.level.equals("warn", ignoreCase = true) }
    if (surfacedEvent != null) {
        return surfacedEvent.message
    }
    telemetry ?: return "Continuous monitor is waiting for an active RIPDPI session."
    if (telemetry.lastFailureClass != null || telemetry.lastFallbackAction != null) {
        return listOfNotNull(telemetry.lastFailureClass, telemetry.lastFallbackAction).joinToString(" · ")
    }
    telemetry.failureClass?.let { return "Latest failure class: $it" }
    telemetry.resolverFallbackReason?.let { return "Encrypted DNS override active: $it" }
    telemetry.networkHandoverClass?.let { return "Recent network handover detected: $it" }
    telemetry.winningStrategyFamily()?.let { return "Winning strategy family: $it" }
    val totalBytes = formatBytes(telemetry.txBytes + telemetry.rxBytes)
    val packetCount = telemetry.txPackets + telemetry.rxPackets
    val modeLabel = telemetry.activeMode ?: "Idle"
    return "$modeLabel mode · $totalBytes transferred · $packetCount packets observed"
}

private fun DiagnosticsUiFactorySupport.buildLiveSignalLabel(
    telemetry: TelemetrySampleEntity?,
): String =
    telemetry?.let { "${formatBytes(it.txBytes)} sent · ${formatBytes(it.rxBytes)} received" }
        ?: "No transfer observed yet"

private fun buildLiveEventSummaryLabel(events: List<NativeSessionEventEntity>): String {
    val warningCount = events.count { it.level.equals("warn", ignoreCase = true) }
    val errorCount = events.count { it.level.equals("error", ignoreCase = true) }
    return when {
        errorCount > 0 && warningCount > 0 ->
            "$errorCount error${pluralSuffix(errorCount)} · $warningCount warning${pluralSuffix(warningCount)} in runtime feed"

        errorCount > 0 ->
            "$errorCount error${pluralSuffix(errorCount)} in runtime feed"

        warningCount > 0 ->
            "$warningCount warning${pluralSuffix(warningCount)} in runtime feed"

        events.isNotEmpty() ->
            "${events.size} informational event${pluralSuffix(events.size)} in runtime feed"

        else -> "Runtime feed is quiet"
    }
}

private fun pluralSuffix(count: Int): String = if (count == 1) "" else "s"

private fun DiagnosticsUiFactorySupport.buildSharePreview(
    latestSession: ScanSessionEntity?,
    latestSnapshot: DiagnosticsNetworkSnapshotUiModel?,
    latestContext: DiagnosticContextModel?,
    telemetry: TelemetrySampleEntity?,
    nativeEvents: List<NativeSessionEventEntity>,
    latestReport: ScanReport?,
): ShareSummary {
    val warningHeadline =
        nativeEvents.firstOrNull {
            it.level.equals("warn", ignoreCase = true) || it.level.equals("error", ignoreCase = true)
        }
    val body =
        buildString {
            appendLine("Archive includes the focused diagnostics session in full.")
            appendLine("It also adds recent live telemetry and global runtime events.")
            appendLine("Summary and manifest redact support context and network identity fields, while raw report data stays intact.")
            latestSession?.let {
                appendLine("Session ${it.id.take(8)} · ${it.pathMode} · ${it.status}")
            }
            latestSnapshot?.let {
                appendLine("Network ${it.subtitle}")
            }
            latestContext?.let {
                appendLine("Context ${it.service.activeMode.lowercase(Locale.US)} · ${it.device.manufacturer} ${it.device.model} · Android ${it.device.androidVersion}")
                appendLine("Permissions ${it.permissions.vpnPermissionState} VPN · ${it.permissions.notificationPermissionState} notifications")
            }
            telemetry?.let {
                appendLine("Live ${it.connectionState.lowercase(Locale.US)} · ${it.networkType}")
            }
            latestReport?.let { report ->
                appendLine("${report.results.size} probe results in the latest report")
                report.results.firstOrNull { it.probeType == "telegram_availability" }?.let { tg ->
                    val tgDetails = tg.details.associate { it.key to it.value }
                    appendLine("Telegram: ${tgDetails["verdict"] ?: tg.outcome}")
                    tgDetails["downloadAvgBps"]?.toLongOrNull()?.let { bps ->
                        appendLine("  Download: ${formatBps(bps)} avg, ${formatBytes(tgDetails["downloadBytes"]?.toLongOrNull() ?: 0)}")
                    }
                    tgDetails["uploadAvgBps"]?.toLongOrNull()?.let { bps ->
                        appendLine("  Upload: ${formatBps(bps)} avg, ${formatBytes(tgDetails["uploadBytes"]?.toLongOrNull() ?: 0)}")
                    }
                    appendLine("  DCs: ${tgDetails["dcReachable"] ?: "?"}/${tgDetails["dcTotal"] ?: "?"} reachable")
                }
            }
            warningHeadline?.let {
                appendLine("Top warning: ${it.message}")
            }
        }.trim()
    return ShareSummary(
        title = "RIPDPI diagnostics",
        body = body.ifBlank { "Select a diagnostics session to generate a summary." },
        compactMetrics =
            listOfNotNull(
                latestSession?.pathMode?.let { com.poyka.ripdpi.diagnostics.SummaryMetric("Path", it) },
                telemetry?.networkType?.let { com.poyka.ripdpi.diagnostics.SummaryMetric("Network", it) },
                latestContext?.service?.activeMode?.let { com.poyka.ripdpi.diagnostics.SummaryMetric("Mode", it) },
                latestContext?.device?.appVersionName?.let { com.poyka.ripdpi.diagnostics.SummaryMetric("App", it) },
                telemetry?.txBytes?.let { com.poyka.ripdpi.diagnostics.SummaryMetric("TX", formatBytes(it)) },
                telemetry?.rxBytes?.let { com.poyka.ripdpi.diagnostics.SummaryMetric("RX", formatBytes(it)) },
            ),
    )
}
