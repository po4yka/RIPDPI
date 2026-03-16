package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.data.diagnostics.retryCount
import com.poyka.ripdpi.data.diagnostics.rttBand
import com.poyka.ripdpi.data.diagnostics.winningStrategyFamily
import com.poyka.ripdpi.diagnostics.BypassApproachKind
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.diagnostics.ScanReport
import com.poyka.ripdpi.diagnostics.ShareSummary
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
            context.getString(R.string.diagnostics_scan_automatic_audit)
        } else {
            context.getString(R.string.diagnostics_scan_automatic_probing)
        }
    val runRawHint =
        when {
            strategyProbeSelected && rawArgsEnabled ->
                context.getString(R.string.diagnostics_scan_cli_active_format, workflowLabel)

            strategyProbeSelected ->
                context.getString(R.string.diagnostics_scan_raw_path_format, workflowLabel)

            else -> null
        }
    val runInPathHint =
        when {
            strategyProbeSelected ->
                context.getString(R.string.diagnostics_scan_raw_only_format, workflowLabel)

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
        statusLabel = currentTelemetry?.connectionState ?: context.getString(R.string.diagnostics_metric_idle),
        freshnessLabel =
            currentTelemetry?.createdAt?.let {
                context.getString(
                    R.string.diagnostics_live_updated_format,
                    formatTimestamp(it)
                )
            }
                ?: context.getString(R.string.diagnostics_live_no_telemetry),
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
                        append("\n\n")
                        append(
                            context.getString(
                                R.string.diagnostics_share_approach_format,
                                summary.displayName,
                                summary.verificationState
                            )
                        )
                    }
            },
        metrics =
            sharePreview.compactMetrics.map { DiagnosticsMetricUiModel(it.label, it.value) } +
                listOfNotNull(
                    approachStats
                        .firstOrNull { it.approachId.kind == BypassApproachKind.Strategy }
                        ?.let { summary ->
                            DiagnosticsMetricUiModel(
                                label = context.getString(R.string.diagnostics_metric_approach),
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
        add(
            DiagnosticsMetricUiModel(
                label = context.getString(R.string.diagnostics_metric_sessions),
                value = sessions.size.toString()
            )
        )
        add(
            DiagnosticsMetricUiModel(
                label = context.getString(R.string.diagnostics_metric_events),
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
            add(
                DiagnosticsMetricUiModel(
                    label = context.getString(R.string.diagnostics_metric_tx),
                    value = formatBytes(sample.txBytes),
                    tone = DiagnosticsTone.Info
                )
            )
            add(
                DiagnosticsMetricUiModel(
                    label = context.getString(R.string.diagnostics_metric_rx),
                    value = formatBytes(sample.rxBytes),
                    tone = DiagnosticsTone.Info
                )
            )
        }
    }

private fun DiagnosticsUiFactorySupport.overviewHeadline(
    health: DiagnosticsHealth,
    progress: ScanProgress?,
    latestSession: ScanSessionEntity?,
): String =
    when {
        progress != null -> context.getString(R.string.diagnostics_headline_scan_active)
        latestSession == null -> context.getString(R.string.diagnostics_headline_no_data)
        health == DiagnosticsHealth.Degraded -> context.getString(R.string.diagnostics_headline_degraded)
        health == DiagnosticsHealth.Attention -> context.getString(R.string.diagnostics_headline_attention)
        health == DiagnosticsHealth.Healthy -> context.getString(R.string.diagnostics_headline_healthy)
        else -> context.getString(R.string.diagnostics_headline_waiting)
    }

private fun DiagnosticsUiFactorySupport.overviewBody(
    health: DiagnosticsHealth,
    latestSnapshot: DiagnosticsNetworkSnapshotUiModel?,
    telemetry: TelemetrySampleEntity?,
): String =
    when (health) {
        DiagnosticsHealth.Healthy ->
            context.getString(R.string.diagnostics_body_healthy)

        DiagnosticsHealth.Attention ->
            context.getString(R.string.diagnostics_body_attention)

        DiagnosticsHealth.Degraded ->
            context.getString(R.string.diagnostics_body_degraded)

        DiagnosticsHealth.Idle ->
            latestSnapshot?.subtitle ?: telemetry?.connectionState ?: context.getString(R.string.diagnostics_body_idle)
    }

private fun DiagnosticsUiFactorySupport.buildLiveMetrics(
    telemetry: TelemetrySampleEntity?,
    events: List<NativeSessionEventEntity>,
): List<DiagnosticsMetricUiModel> =
    buildList {
        if (telemetry != null) {
            add(
                DiagnosticsMetricUiModel(
                    label = context.getString(R.string.diagnostics_metric_network),
                    value = telemetry.networkType
                )
            )
            add(
                DiagnosticsMetricUiModel(
                    label = context.getString(R.string.diagnostics_metric_mode),
                    value = telemetry.activeMode ?: context.getString(R.string.diagnostics_metric_idle)
                )
            )
            telemetry.lastFailureClass?.let { failureClass ->
                add(
                    DiagnosticsMetricUiModel(
                        label = context.getString(R.string.diagnostics_metric_latest_native_failure),
                        value = failureClass,
                        tone = DiagnosticsTone.Warning
                    )
                )
            }
            telemetry.lastFallbackAction?.let { fallbackAction ->
                add(
                    DiagnosticsMetricUiModel(
                        label = context.getString(R.string.diagnostics_metric_fallback_action),
                        value = fallbackAction,
                        tone = DiagnosticsTone.Info
                    )
                )
            }
            telemetry.failureClass?.let { failureClass ->
                add(
                    DiagnosticsMetricUiModel(
                        label = context.getString(R.string.diagnostics_metric_failure_class),
                        value = failureClass,
                        tone = DiagnosticsTone.Warning
                    )
                )
            }
            telemetry.winningStrategyFamily()?.let { winningStrategy ->
                add(
                    DiagnosticsMetricUiModel(
                        label = context.getString(R.string.diagnostics_metric_winning_strategy),
                        value = winningStrategy,
                        tone = DiagnosticsTone.Positive
                    )
                )
            }
            add(
                DiagnosticsMetricUiModel(
                    label = context.getString(R.string.diagnostics_metric_rtt_band),
                    value = telemetry.rttBand(),
                    tone = DiagnosticsTone.Info
                )
            )
            add(
                DiagnosticsMetricUiModel(
                    label = context.getString(R.string.diagnostics_metric_retries),
                    value = telemetry.retryCount().toString(),
                    tone = if (telemetry.retryCount() > 0) DiagnosticsTone.Warning else DiagnosticsTone.Neutral,
                ),
            )
            telemetry.resolverId?.let { resolverId ->
                add(
                    DiagnosticsMetricUiModel(
                        label = context.getString(R.string.diagnostics_metric_resolver),
                        value = listOfNotNull(resolverId, telemetry.resolverProtocol).joinToString(" · "),
                        tone = DiagnosticsTone.Info,
                    ),
                )
            }
            telemetry.resolverLatencyMs?.let { latency ->
                add(
                    DiagnosticsMetricUiModel(
                        label = context.getString(R.string.diagnostics_metric_dns_latency),
                        value = context.getString(R.string.diagnostics_metric_dns_latency_format, latency),
                        tone = DiagnosticsTone.Info
                    )
                )
            }
            if (telemetry.dnsFailuresTotal > 0) {
                add(
                    DiagnosticsMetricUiModel(
                        label = context.getString(R.string.diagnostics_metric_dns_failures),
                        value = telemetry.dnsFailuresTotal.toString(),
                        tone = DiagnosticsTone.Warning
                    )
                )
            }
            add(
                DiagnosticsMetricUiModel(
                    label = context.getString(R.string.diagnostics_metric_tx_packets),
                    value = telemetry.txPackets.toString(),
                    tone = DiagnosticsTone.Info
                )
            )
            add(
                DiagnosticsMetricUiModel(
                    label = context.getString(R.string.diagnostics_metric_rx_packets),
                    value = telemetry.rxPackets.toString(),
                    tone = DiagnosticsTone.Info
                )
            )
        }
        add(
            DiagnosticsMetricUiModel(
                label = context.getString(R.string.diagnostics_metric_warnings),
                value = events.count { it.level.equals("warn", ignoreCase = true) }.toString(),
                tone = DiagnosticsTone.Warning,
            ),
        )
        add(
            DiagnosticsMetricUiModel(
                label = context.getString(R.string.diagnostics_metric_errors),
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
            add(
                DiagnosticsMetricUiModel(
                    label = context.getString(R.string.diagnostics_metric_tx),
                    value = formatBytes(it.txBytes),
                    tone = DiagnosticsTone.Info
                )
            )
            add(
                DiagnosticsMetricUiModel(
                    label = context.getString(R.string.diagnostics_metric_rx),
                    value = formatBytes(it.rxBytes),
                    tone = DiagnosticsTone.Positive
                )
            )
            it.winningStrategyFamily()?.let { winningStrategy ->
                add(
                    DiagnosticsMetricUiModel(
                        label = context.getString(R.string.diagnostics_metric_strategy),
                        value = winningStrategy,
                        tone = DiagnosticsTone.Positive
                    )
                )
            }
            add(
                DiagnosticsMetricUiModel(
                    label = context.getString(R.string.diagnostics_metric_rtt),
                    value = it.rttBand(),
                    tone = DiagnosticsTone.Info
                )
            )
            add(
                DiagnosticsMetricUiModel(
                    label = context.getString(R.string.diagnostics_metric_retries),
                    value = it.retryCount().toString(),
                    tone = if (it.retryCount() > 0) DiagnosticsTone.Warning else DiagnosticsTone.Neutral,
                ),
            )
            if (it.resolverFallbackActive) {
                add(
                    DiagnosticsMetricUiModel(
                        label = context.getString(R.string.diagnostics_metric_resolver_fallback),
                        value = it.resolverFallbackReason
                            ?: context.getString(R.string.diagnostics_metric_resolver_fallback_active),
                        tone = DiagnosticsTone.Warning,
                    ),
                )
            }
            add(
                DiagnosticsMetricUiModel(
                    label = context.getString(R.string.diagnostics_metric_packets),
                    value = (it.txPackets + it.rxPackets).toString(),
                    tone = DiagnosticsTone.Neutral
                )
            )
        }
        add(
            DiagnosticsMetricUiModel(
                label = context.getString(R.string.diagnostics_metric_warnings),
                value = warningCount.toString(),
                tone = if (warningCount > 0) DiagnosticsTone.Warning else DiagnosticsTone.Neutral,
            ),
        )
        add(
            DiagnosticsMetricUiModel(
                label = context.getString(R.string.diagnostics_metric_errors),
                value = errorCount.toString(),
                tone = if (errorCount > 0) DiagnosticsTone.Negative else DiagnosticsTone.Neutral,
            ),
        )
    }
}

private fun DiagnosticsUiFactorySupport.buildLiveTrends(
    telemetry: List<TelemetrySampleEntity>,
): List<DiagnosticsSparklineUiModel> {
    val samples = telemetry.take(24).reversed()
    if (samples.isEmpty()) {
        return emptyList()
    }
    return listOf(
        DiagnosticsSparklineUiModel(
            label = context.getString(R.string.diagnostics_sparkline_tx_bytes),
            values = samples.map { it.txBytes.toFloat() },
            tone = DiagnosticsTone.Info
        ),
        DiagnosticsSparklineUiModel(
            label = context.getString(R.string.diagnostics_sparkline_rx_bytes),
            values = samples.map { it.rxBytes.toFloat() },
            tone = DiagnosticsTone.Positive
        ),
        DiagnosticsSparklineUiModel(
            label = context.getString(R.string.diagnostics_sparkline_errors),
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

private fun DiagnosticsUiFactorySupport.buildLiveHeadline(
    health: DiagnosticsHealth,
    telemetry: TelemetrySampleEntity?,
    events: List<NativeSessionEventEntity>,
): String {
    val surfacedEvent =
        events.firstOrNull { it.level.equals("error", ignoreCase = true) }
            ?: events.firstOrNull { it.level.equals("warn", ignoreCase = true) }
    return when {
        surfacedEvent?.level?.equals(
            "error",
            ignoreCase = true
        ) == true -> context.getString(R.string.diagnostics_live_headline_error)

        health == DiagnosticsHealth.Attention -> context.getString(R.string.diagnostics_live_headline_attention)
        telemetry == null -> context.getString(R.string.diagnostics_live_headline_standby)
        telemetry.connectionState.equals(
            "running",
            ignoreCase = true
        ) -> context.getString(R.string.diagnostics_live_headline_traffic, telemetry.networkType)

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
    telemetry ?: return context.getString(R.string.diagnostics_live_body_waiting)
    if (telemetry.lastFailureClass != null || telemetry.lastFallbackAction != null) {
        return listOfNotNull(telemetry.lastFailureClass, telemetry.lastFallbackAction).joinToString(" · ")
    }
    telemetry.failureClass?.let { return context.getString(R.string.diagnostics_live_failure_class_format, it) }
    telemetry.resolverFallbackReason?.let {
        return context.getString(
            R.string.diagnostics_live_dns_override_format,
            it
        )
    }
    telemetry.networkHandoverClass?.let { return context.getString(R.string.diagnostics_live_handover_format, it) }
    telemetry.winningStrategyFamily()
        ?.let { return context.getString(R.string.diagnostics_live_winning_strategy_format, it) }
    val totalBytes = formatBytes(telemetry.txBytes + telemetry.rxBytes)
    val packetCount = telemetry.txPackets + telemetry.rxPackets
    val modeLabel = telemetry.activeMode ?: context.getString(R.string.diagnostics_metric_idle)
    return context.getString(R.string.diagnostics_live_mode_summary_format, modeLabel, totalBytes, packetCount)
}

private fun DiagnosticsUiFactorySupport.buildLiveSignalLabel(
    telemetry: TelemetrySampleEntity?,
): String =
    telemetry?.let {
        context.getString(
            R.string.diagnostics_live_signal_format,
            formatBytes(it.txBytes),
            formatBytes(it.rxBytes)
        )
    }
        ?: context.getString(R.string.diagnostics_live_no_transfer)

private fun DiagnosticsUiFactorySupport.buildLiveEventSummaryLabel(events: List<NativeSessionEventEntity>): String {
    val warningCount = events.count { it.level.equals("warn", ignoreCase = true) }
    val errorCount = events.count { it.level.equals("error", ignoreCase = true) }
    return when {
        errorCount > 0 && warningCount > 0 ->
            context.getString(
                R.string.diagnostics_live_errors_and_warnings_format,
                errorCount,
                pluralSuffix(errorCount),
                warningCount,
                pluralSuffix(warningCount)
            )

        errorCount > 0 ->
            context.getString(R.string.diagnostics_live_errors_format, errorCount, pluralSuffix(errorCount))

        warningCount > 0 ->
            context.getString(R.string.diagnostics_live_warnings_format, warningCount, pluralSuffix(warningCount))

        events.isNotEmpty() ->
            context.getString(R.string.diagnostics_live_info_events_format, events.size, pluralSuffix(events.size))

        else -> context.getString(R.string.diagnostics_live_feed_quiet)
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
            appendLine(context.getString(R.string.diagnostics_share_archive_line))
            appendLine(context.getString(R.string.diagnostics_share_telemetry_line))
            appendLine(context.getString(R.string.diagnostics_share_redaction_line))
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
        title = context.getString(R.string.diagnostics_share_title),
        body = body.ifBlank { context.getString(R.string.diagnostics_share_no_session) },
        compactMetrics =
            listOfNotNull(
                latestSession?.pathMode?.let {
                    com.poyka.ripdpi.diagnostics.SummaryMetric(
                        context.getString(R.string.diagnostics_share_metric_path),
                        it
                    )
                },
                telemetry?.networkType?.let {
                    com.poyka.ripdpi.diagnostics.SummaryMetric(
                        context.getString(R.string.diagnostics_share_metric_network),
                        it
                    )
                },
                latestContext?.service?.activeMode?.let {
                    com.poyka.ripdpi.diagnostics.SummaryMetric(
                        context.getString(R.string.diagnostics_share_metric_mode),
                        it
                    )
                },
                latestContext?.device?.appVersionName?.let {
                    com.poyka.ripdpi.diagnostics.SummaryMetric(
                        context.getString(
                            R.string.diagnostics_share_metric_app
                        ), it
                    )
                },
                telemetry?.txBytes?.let {
                    com.poyka.ripdpi.diagnostics.SummaryMetric(
                        context.getString(R.string.diagnostics_metric_tx),
                        formatBytes(it)
                    )
                },
                telemetry?.rxBytes?.let {
                    com.poyka.ripdpi.diagnostics.SummaryMetric(
                        context.getString(R.string.diagnostics_metric_rx),
                        formatBytes(it)
                    )
                },
            ),
    )
}
