package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.BypassApproachKind
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionSession
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticExportRecord
import com.poyka.ripdpi.diagnostics.DiagnosticProfile
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsProfileProjection
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection
import com.poyka.ripdpi.diagnostics.retryCount
import com.poyka.ripdpi.diagnostics.rttBand
import com.poyka.ripdpi.diagnostics.winningStrategyFamily

internal fun DiagnosticsUiFactorySupport.buildOverviewUiModel(
    health: DiagnosticsHealth,
    progress: ScanProgress?,
    latestSession: DiagnosticScanSession?,
    recentAutomaticProbe: DiagnosticsAutomaticProbeCalloutUiModel?,
    latestSnapshot: DiagnosticsNetworkSnapshotUiModel?,
    latestContext: DiagnosticContextModel?,
    currentTelemetry: DiagnosticTelemetrySample?,
    sessions: List<DiagnosticScanSession>,
    nativeEvents: List<DiagnosticEvent>,
    selectedProfile: DiagnosticsProfileOptionUiModel?,
    sessionRows: List<DiagnosticsSessionRowUiModel>,
    rememberedNetworkRows: List<DiagnosticsRememberedNetworkUiModel>,
    warnings: List<DiagnosticsEventUiModel>,
): DiagnosticsOverviewUiModel =
    DiagnosticsOverviewUiModel(
        health = health,
        headline = overviewHeadline(health, progress, latestSession, selectedProfile),
        body = overviewBody(health, latestSnapshot, currentTelemetry),
        activeProfile = selectedProfile,
        recentAutomaticProbe = recentAutomaticProbe,
        latestSnapshot = latestSnapshot,
        latestSession = sessionRows.firstOrNull(),
        contextSummary = latestContext?.let(::toOverviewContextGroup),
        metrics = buildOverviewMetrics(health, sessions, nativeEvents, currentTelemetry),
        warnings = warnings,
        rememberedNetworks = rememberedNetworkRows.take(6),
    )

internal fun DiagnosticsUiFactorySupport.buildScanUiModel(
    profiles: List<DiagnosticProfile>,
    activeProfile: DiagnosticProfile?,
    activeProfileRequest: DiagnosticsProfileProjection?,
    latestProfileSession: DiagnosticScanSession?,
    activeScanPathMode: ScanPathMode?,
    latestReportResults: List<DiagnosticsProbeResultUiModel>,
    latestResolverRecommendation: DiagnosticsResolverRecommendationUiModel?,
    latestStrategyProbeReport: DiagnosticsStrategyProbeReportUiModel?,
    progress: ScanProgress?,
    rawArgsEnabled: Boolean,
    scanStartedAt: Long?,
    completedProbes: List<CompletedProbeUiModel> = emptyList(),
    candidateTimeline: List<StrategyCandidateTimelineEntryUiModel> = emptyList(),
    dnsBaselineStatus: DnsBaselineStatus? = null,
    dpiFailureClass: DpiFailureClass? = null,
    vpnPermissionDisabled: Boolean = false,
    hiddenProbeConflictDialog: HiddenProbeConflictDialogState? = null,
    queuedManualScanRequest: QueuedManualScanRequest? = null,
): DiagnosticsScanUiModel {
    val selectedProfile = activeProfile?.let(::toProfileOptionUiModel)
    val strategyProbeSelected = selectedProfile?.isStrategyProbe == true
    val runRawEnabled = progress == null && !(strategyProbeSelected && rawArgsEnabled)
    val runInPathEnabled = progress == null && !strategyProbeSelected
    val workflowRestriction =
        when {
            strategyProbeSelected && rawArgsEnabled -> {
                val workflowLabel =
                    if (selectedProfile.isFullAudit) {
                        context.getString(R.string.diagnostics_scan_automatic_audit)
                    } else {
                        context.getString(R.string.diagnostics_scan_automatic_probing)
                    }
                DiagnosticsWorkflowRestrictionUiModel(
                    reason = DiagnosticsWorkflowRestrictionReasonUiModel.COMMAND_LINE_MODE_ACTIVE,
                    title =
                        if (selectedProfile.isFullAudit) {
                            context.getString(R.string.diagnostics_audit_unavailable_title)
                        } else {
                            context.getString(R.string.diagnostics_probe_unavailable_title)
                        },
                    body =
                        context.getString(
                            R.string.diagnostics_scan_workflow_blocked_command_line_body_format,
                            workflowLabel,
                            context.getString(R.string.use_command_line_settings),
                        ),
                    actionLabel = context.getString(R.string.diagnostics_scan_open_advanced_settings),
                    actionKind = DiagnosticsWorkflowRestrictionActionKindUiModel.OPEN_ADVANCED_SETTINGS,
                )
            }

            strategyProbeSelected && vpnPermissionDisabled -> {
                val vpnWorkflowLabel =
                    if (selectedProfile.isFullAudit) {
                        context.getString(R.string.diagnostics_scan_automatic_audit)
                    } else {
                        context.getString(R.string.diagnostics_scan_automatic_probing)
                    }
                DiagnosticsWorkflowRestrictionUiModel(
                    reason = DiagnosticsWorkflowRestrictionReasonUiModel.VPN_PERMISSION_DISABLED,
                    title = context.getString(R.string.diagnostics_scan_vpn_permission_warning_title),
                    body =
                        context.getString(
                            R.string.diagnostics_scan_vpn_permission_warning_body_format,
                            vpnWorkflowLabel,
                        ),
                    actionLabel = context.getString(R.string.diagnostics_scan_grant_vpn_permission),
                    actionKind = DiagnosticsWorkflowRestrictionActionKindUiModel.OPEN_VPN_PERMISSION,
                )
            }

            else -> {
                null
            }
        }
    val workflowLabel =
        if (selectedProfile?.isFullAudit == true) {
            context.getString(R.string.diagnostics_scan_automatic_audit)
        } else {
            context.getString(R.string.diagnostics_scan_automatic_probing)
        }
    val runRawHint =
        when {
            strategyProbeSelected -> {
                context.getString(R.string.diagnostics_scan_raw_path_format, workflowLabel)
            }

            else -> {
                null
            }
        }
    val runInPathHint =
        when {
            strategyProbeSelected -> {
                context.getString(R.string.diagnostics_scan_raw_only_format, workflowLabel)
            }

            else -> {
                null
            }
        }

    return DiagnosticsScanUiModel(
        profiles = profiles.map(::toProfileOptionUiModel),
        selectedProfileId = activeProfile?.id,
        selectedProfile = selectedProfile,
        activePathMode =
            activeScanPathMode ?: latestProfileSession?.pathMode?.let(::parsePathMode) ?: ScanPathMode.RAW_PATH,
        activeProgress =
            progress?.let { p ->
                toProgressUiModel(
                    progress = p,
                    scanKind = selectedProfile?.kind ?: ScanKind.CONNECTIVITY,
                    isFullAudit = selectedProfile?.isFullAudit == true,
                    scanStartedAt = scanStartedAt ?: System.currentTimeMillis(),
                    completedProbes = completedProbes,
                    candidateTimeline = candidateTimeline,
                    dnsBaselineStatus = dnsBaselineStatus,
                    dpiFailureClass = dpiFailureClass,
                )
            },
        latestSession = latestProfileSession?.let(::toSessionRowUiModel),
        diagnoses =
            latestProfileSession
                ?.report
                ?.diagnoses
                ?.map(::toDiagnosisUiModel)
                .orEmpty(),
        latestResults = latestReportResults,
        selectedProfileScopeLabel = toScopeLabel(activeProfileRequest, rawArgsEnabled),
        runRawEnabled = runRawEnabled,
        runInPathEnabled = runInPathEnabled,
        runRawHint = runRawHint,
        runInPathHint = runInPathHint,
        workflowRestriction = workflowRestriction,
        resolverRecommendation = latestResolverRecommendation,
        strategyProbeReport = latestStrategyProbeReport,
        hiddenProbeConflictDialog = hiddenProbeConflictDialog,
        queuedManualScanRequest = queuedManualScanRequest,
        isBusy = progress != null,
    )
}

internal fun DiagnosticsUiFactorySupport.buildLiveUiModel(
    activeConnectionSession: DiagnosticConnectionSession?,
    telemetry: List<DiagnosticTelemetrySample>,
    currentTelemetry: DiagnosticTelemetrySample?,
    nativeEvents: List<DiagnosticEvent>,
    latestSnapshot: DiagnosticsNetworkSnapshotUiModel?,
    latestContext: DiagnosticContextModel?,
): DiagnosticsLiveUiModel {
    val health = deriveLiveHealth(activeConnectionSession, currentTelemetry, nativeEvents)
    return DiagnosticsLiveUiModel(
        health = health,
        statusLabel =
            currentTelemetry?.connectionState
                ?: activeConnectionSession?.connectionState
                ?: context.getString(R.string.diagnostics_metric_idle),
        statusTone =
            liveStatusTone(
                currentTelemetry?.connectionState ?: activeConnectionSession?.connectionState,
            ),
        freshnessLabel =
            currentTelemetry?.createdAt?.let {
                context.getString(
                    R.string.diagnostics_live_updated_format,
                    formatTimestamp(it),
                )
            }
                ?: context.getString(R.string.diagnostics_live_no_telemetry),
        headline = buildLiveHeadline(health, currentTelemetry, nativeEvents),
        body = buildLiveBody(currentTelemetry, nativeEvents),
        networkLabel = currentTelemetry?.networkType ?: activeConnectionSession?.networkType,
        modeLabel = currentTelemetry?.activeMode ?: activeConnectionSession?.serviceMode,
        signalLabel = buildLiveSignalLabel(currentTelemetry),
        eventSummaryLabel = buildLiveEventSummaryLabel(nativeEvents),
        highlights = buildLiveHighlights(currentTelemetry, nativeEvents),
        metrics = buildLiveMetrics(currentTelemetry),
        trends = buildLiveTrends(telemetry),
        snapshot = latestSnapshot,
        contextGroups = latestContext?.let(::toLiveContextGroups).orEmpty(),
        passiveEvents = nativeEvents.take(8).map(::toEventUiModel),
    )
}

internal fun DiagnosticsUiFactorySupport.buildSessionsUiModel(
    sessions: List<DiagnosticScanSession>,
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
    latestCompletedSession: DiagnosticScanSession?,
    latestSnapshot: DiagnosticsNetworkSnapshotUiModel?,
    latestContext: DiagnosticContextModel?,
    currentTelemetry: DiagnosticTelemetrySample?,
    nativeEvents: List<DiagnosticEvent>,
    latestReport: DiagnosticsSessionProjection?,
    approachStats: List<BypassApproachSummary>,
    selectedSessionDetail: DiagnosticsSessionDetailUiModel?,
    archiveActionState: ArchiveActionState,
    exports: List<DiagnosticExportRecord>,
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
                                summary.verificationState,
                            ),
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
    latestSession: DiagnosticScanSession?,
    latestTelemetry: DiagnosticTelemetrySample?,
    nativeEvents: List<DiagnosticEvent>,
): DiagnosticsHealth {
    val hasError = nativeEvents.any { it.level.equals("error", ignoreCase = true) }
    val hasWarning = nativeEvents.any { it.level.equals("warn", ignoreCase = true) }
    val latestSessionBucket =
        latestSession
            ?.report
            ?.results
            ?.takeIf { it.isNotEmpty() }
            ?.let { results ->
                core.aggregateBucketForProbeResults(parsePathMode(latestSession.pathMode), results)
            }
    return when {
        progress != null -> {
            DiagnosticsHealth.Attention
        }

        latestSession == null && latestTelemetry == null && nativeEvents.isEmpty() -> {
            DiagnosticsHealth.Idle
        }

        hasError -> {
            DiagnosticsHealth.Degraded
        }

        latestSessionBucket == com.poyka.ripdpi.diagnostics.DiagnosticsOutcomeBucket.Failed -> {
            DiagnosticsHealth.Degraded
        }

        latestSession?.status.equals("failed", ignoreCase = true) -> {
            DiagnosticsHealth.Degraded
        }

        hasWarning -> {
            DiagnosticsHealth.Attention
        }

        latestSessionBucket == com.poyka.ripdpi.diagnostics.DiagnosticsOutcomeBucket.Attention ||
            latestSessionBucket == com.poyka.ripdpi.diagnostics.DiagnosticsOutcomeBucket.Inconclusive -> {
            DiagnosticsHealth.Attention
        }

        else -> {
            DiagnosticsHealth.Healthy
        }
    }
}

internal fun DiagnosticsUiFactorySupport.deriveLiveHealth(
    activeConnectionSession: DiagnosticConnectionSession?,
    latestTelemetry: DiagnosticTelemetrySample?,
    nativeEvents: List<DiagnosticEvent>,
): DiagnosticsHealth {
    if (activeConnectionSession == null) {
        return DiagnosticsHealth.Idle
    }

    val hasError = nativeEvents.any { it.level.equals("error", ignoreCase = true) }
    val hasWarning = nativeEvents.any { it.level.equals("warn", ignoreCase = true) }
    return when {
        hasError -> DiagnosticsHealth.Degraded
        activeConnectionSession.connectionState.equals("failed", ignoreCase = true) -> DiagnosticsHealth.Degraded
        activeConnectionSession.health.equals("degraded", ignoreCase = true) -> DiagnosticsHealth.Degraded
        latestTelemetry?.connectionState.equals("failed", ignoreCase = true) -> DiagnosticsHealth.Degraded
        hasWarning -> DiagnosticsHealth.Attention
        latestTelemetry == null -> DiagnosticsHealth.Idle
        else -> DiagnosticsHealth.Healthy
    }
}

private fun DiagnosticsUiFactorySupport.buildOverviewMetrics(
    health: DiagnosticsHealth,
    sessions: List<DiagnosticScanSession>,
    nativeEvents: List<DiagnosticEvent>,
    currentTelemetry: DiagnosticTelemetrySample?,
): List<DiagnosticsMetricUiModel> =
    buildList {
        add(
            DiagnosticsMetricUiModel(
                label = context.getString(R.string.diagnostics_metric_sessions),
                value = sessions.size.toString(),
            ),
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
                    tone = DiagnosticsTone.Info,
                ),
            )
            add(
                DiagnosticsMetricUiModel(
                    label = context.getString(R.string.diagnostics_metric_rx),
                    value = formatBytes(sample.rxBytes),
                    tone = DiagnosticsTone.Info,
                ),
            )
        }
    }

private fun DiagnosticsUiFactorySupport.overviewHeadline(
    health: DiagnosticsHealth,
    progress: ScanProgress?,
    latestSession: DiagnosticScanSession?,
    selectedProfile: DiagnosticsProfileOptionUiModel? = null,
): String =
    when {
        progress != null && selectedProfile?.isStrategyProbe == true -> {
            context.getString(R.string.diagnostics_headline_probe_active)
        }

        progress != null -> {
            context.getString(R.string.diagnostics_headline_scan_active)
        }

        latestSession == null -> {
            context.getString(R.string.diagnostics_headline_no_data)
        }

        health == DiagnosticsHealth.Degraded -> {
            context.getString(R.string.diagnostics_headline_degraded)
        }

        health == DiagnosticsHealth.Attention -> {
            context.getString(R.string.diagnostics_headline_attention)
        }

        health == DiagnosticsHealth.Healthy -> {
            context.getString(R.string.diagnostics_headline_healthy)
        }

        else -> {
            context.getString(R.string.diagnostics_headline_waiting)
        }
    }

private fun DiagnosticsUiFactorySupport.overviewBody(
    health: DiagnosticsHealth,
    latestSnapshot: DiagnosticsNetworkSnapshotUiModel?,
    telemetry: DiagnosticTelemetrySample?,
): String =
    when (health) {
        DiagnosticsHealth.Healthy -> {
            context.getString(R.string.diagnostics_body_healthy)
        }

        DiagnosticsHealth.Attention -> {
            context.getString(R.string.diagnostics_body_attention)
        }

        DiagnosticsHealth.Degraded -> {
            context.getString(R.string.diagnostics_body_degraded)
        }

        DiagnosticsHealth.Idle -> {
            latestSnapshot?.subtitle ?: telemetry?.connectionState ?: context.getString(R.string.diagnostics_body_idle)
        }
    }

private fun DiagnosticsUiFactorySupport.buildLiveMetrics(
    telemetry: DiagnosticTelemetrySample?,
): List<DiagnosticsMetricUiModel> = telemetry?.let { buildTelemetryLiveMetrics(it) }.orEmpty()

private fun DiagnosticsUiFactorySupport.buildTelemetryLiveMetrics(
    telemetry: DiagnosticTelemetrySample,
): List<DiagnosticsMetricUiModel> {
    val retryCount = telemetry.retryCount()
    return buildList {
        fun addWarningMetric(
            labelRes: Int,
            value: String,
        ) {
            add(
                DiagnosticsMetricUiModel(
                    label = context.getString(labelRes),
                    value = value,
                    tone = DiagnosticsTone.Warning,
                ),
            )
        }

        fun addInfoMetric(
            labelRes: Int,
            value: String,
        ) {
            add(
                DiagnosticsMetricUiModel(
                    label = context.getString(labelRes),
                    value = value,
                    tone = DiagnosticsTone.Info,
                ),
            )
        }

        add(
            DiagnosticsMetricUiModel(
                context.getString(R.string.diagnostics_metric_network),
                telemetry.networkType,
            ),
        )
        add(
            DiagnosticsMetricUiModel(
                context.getString(R.string.diagnostics_metric_mode),
                telemetry.activeMode ?: context.getString(R.string.diagnostics_metric_idle),
            ),
        )
        telemetry.lastFailureClass?.let { addWarningMetric(R.string.diagnostics_metric_latest_native_failure, it) }
        telemetry.lastFallbackAction?.let { addInfoMetric(R.string.diagnostics_metric_fallback_action, it) }
        telemetry.failureClass?.let { addWarningMetric(R.string.diagnostics_metric_failure_class, it) }
        telemetry.winningStrategyFamily()?.let {
            add(
                DiagnosticsMetricUiModel(
                    context.getString(R.string.diagnostics_metric_winning_strategy),
                    it,
                    DiagnosticsTone.Positive,
                ),
            )
        }
        add(
            DiagnosticsMetricUiModel(
                context.getString(R.string.diagnostics_metric_rtt_band),
                telemetry.rttBand(),
                DiagnosticsTone.Info,
            ),
        )
        add(
            DiagnosticsMetricUiModel(
                context.getString(R.string.diagnostics_metric_retries),
                retryCount.toString(),
                if (retryCount > 0) DiagnosticsTone.Warning else DiagnosticsTone.Neutral,
            ),
        )
        telemetry.resolverId?.let { resolverId ->
            add(
                DiagnosticsMetricUiModel(
                    context.getString(R.string.diagnostics_metric_resolver),
                    listOfNotNull(resolverId, telemetry.resolverProtocol).joinToString(" · "),
                    DiagnosticsTone.Info,
                ),
            )
        }
        telemetry.resolverLatencyMs?.let { latency ->
            addInfoMetric(
                R.string.diagnostics_metric_dns_latency,
                context.getString(R.string.diagnostics_metric_dns_latency_format, latency),
            )
        }
        if (telemetry.dnsFailuresTotal > 0) {
            addWarningMetric(R.string.diagnostics_metric_dns_failures, telemetry.dnsFailuresTotal.toString())
        }
        addInfoMetric(R.string.diagnostics_metric_tx_packets, telemetry.txPackets.toString())
        addInfoMetric(R.string.diagnostics_metric_rx_packets, telemetry.rxPackets.toString())
    }
}

private fun DiagnosticsUiFactorySupport.buildLiveHighlights(
    telemetry: DiagnosticTelemetrySample?,
    events: List<DiagnosticEvent>,
): List<DiagnosticsMetricUiModel> {
    val warningCount = events.count { it.level.equals("warn", ignoreCase = true) }
    val errorCount = events.count { it.level.equals("error", ignoreCase = true) }
    return buildList {
        telemetry?.let {
            add(
                DiagnosticsMetricUiModel(
                    label = context.getString(R.string.diagnostics_metric_tx),
                    value = formatBytes(it.txBytes),
                    tone = DiagnosticsTone.Info,
                ),
            )
            add(
                DiagnosticsMetricUiModel(
                    label = context.getString(R.string.diagnostics_metric_rx),
                    value = formatBytes(it.rxBytes),
                    tone = DiagnosticsTone.Positive,
                ),
            )
            it.winningStrategyFamily()?.let { winningStrategy ->
                add(
                    DiagnosticsMetricUiModel(
                        label = context.getString(R.string.diagnostics_metric_strategy),
                        value = winningStrategy,
                        tone = DiagnosticsTone.Positive,
                    ),
                )
            }
            add(
                DiagnosticsMetricUiModel(
                    label = context.getString(R.string.diagnostics_metric_rtt),
                    value = it.rttBand(),
                    tone = DiagnosticsTone.Info,
                ),
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
                        value =
                            it.resolverFallbackReason
                                ?: context.getString(R.string.diagnostics_metric_resolver_fallback_active),
                        tone = DiagnosticsTone.Warning,
                    ),
                )
            }
            add(
                DiagnosticsMetricUiModel(
                    label = context.getString(R.string.diagnostics_metric_packets),
                    value = (it.txPackets + it.rxPackets).toString(),
                    tone = DiagnosticsTone.Neutral,
                ),
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
    telemetry: List<DiagnosticTelemetrySample>,
): List<DiagnosticsSparklineUiModel> {
    val samples = telemetry.take(24).reversed()
    if (samples.isEmpty()) {
        return emptyList()
    }
    return listOf(
        DiagnosticsSparklineUiModel(
            label = context.getString(R.string.diagnostics_sparkline_tx_bytes),
            values = samples.map { it.txBytes.toFloat() },
            tone = DiagnosticsTone.Info,
        ),
        DiagnosticsSparklineUiModel(
            label = context.getString(R.string.diagnostics_sparkline_rx_bytes),
            values = samples.map { it.rxBytes.toFloat() },
            tone = DiagnosticsTone.Positive,
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
    telemetry: DiagnosticTelemetrySample?,
    events: List<DiagnosticEvent>,
): String {
    val surfacedEvent =
        events.firstOrNull { it.level.equals("error", ignoreCase = true) }
            ?: events.firstOrNull { it.level.equals("warn", ignoreCase = true) }
    return when {
        surfacedEvent?.level?.equals(
            "error",
            ignoreCase = true,
        ) == true -> context.getString(R.string.diagnostics_live_headline_error)

        health == DiagnosticsHealth.Degraded -> context.getString(R.string.diagnostics_live_headline_error)

        telemetry == null -> context.getString(R.string.diagnostics_live_headline_standby)

        health == DiagnosticsHealth.Attention -> context.getString(R.string.diagnostics_live_headline_attention)

        telemetry.connectionState.equals(
            "running",
            ignoreCase = true,
        ) -> context.getString(R.string.diagnostics_live_headline_traffic, telemetry.networkType)

        else -> telemetry.connectionState.replaceFirstChar { it.uppercase() }
    }
}

private fun DiagnosticsUiFactorySupport.liveStatusTone(connectionState: String?): DiagnosticsTone =
    when {
        connectionState.equals("running", ignoreCase = true) -> DiagnosticsTone.Positive
        connectionState.equals("failed", ignoreCase = true) -> DiagnosticsTone.Negative
        connectionState.isNullOrBlank() -> DiagnosticsTone.Neutral
        else -> DiagnosticsTone.Neutral
    }

private fun DiagnosticsUiFactorySupport.buildLiveBody(
    telemetry: DiagnosticTelemetrySample?,
    events: List<DiagnosticEvent>,
): String {
    val surfacedEvent =
        events.firstOrNull { it.level.equals("error", ignoreCase = true) }
            ?: events.firstOrNull { it.level.equals("warn", ignoreCase = true) }
    return when {
        surfacedEvent != null -> {
            surfacedEvent.message
        }

        telemetry == null -> {
            context.getString(R.string.diagnostics_live_body_waiting)
        }

        telemetry.lastFailureClass != null || telemetry.lastFallbackAction != null -> {
            listOfNotNull(telemetry.lastFailureClass, telemetry.lastFallbackAction).joinToString(" · ")
        }

        telemetry.failureClass != null -> {
            context.getString(R.string.diagnostics_live_failure_class_format, telemetry.failureClass)
        }

        telemetry.resolverFallbackReason != null -> {
            context.getString(R.string.diagnostics_live_dns_override_format, telemetry.resolverFallbackReason)
        }

        telemetry.networkHandoverClass != null -> {
            context.getString(R.string.diagnostics_live_handover_format, telemetry.networkHandoverClass)
        }

        telemetry.winningStrategyFamily() != null -> {
            context.getString(
                R.string.diagnostics_live_winning_strategy_format,
                telemetry.winningStrategyFamily(),
            )
        }

        else -> {
            val totalBytes = formatBytes(telemetry.txBytes + telemetry.rxBytes)
            val packetCount = telemetry.txPackets + telemetry.rxPackets
            val modeLabel = telemetry.activeMode ?: context.getString(R.string.diagnostics_metric_idle)
            context.getString(R.string.diagnostics_live_mode_summary_format, modeLabel, totalBytes, packetCount)
        }
    }
}

private fun DiagnosticsUiFactorySupport.buildLiveSignalLabel(telemetry: DiagnosticTelemetrySample?): String =
    telemetry?.let {
        context.getString(
            R.string.diagnostics_live_signal_format,
            formatBytes(it.txBytes),
            formatBytes(it.rxBytes),
        )
    }
        ?: context.getString(R.string.diagnostics_live_no_transfer)

private fun DiagnosticsUiFactorySupport.buildLiveEventSummaryLabel(events: List<DiagnosticEvent>): String {
    val warningCount = events.count { it.level.equals("warn", ignoreCase = true) }
    val errorCount = events.count { it.level.equals("error", ignoreCase = true) }
    return when {
        errorCount > 0 && warningCount > 0 -> {
            context.getString(
                R.string.diagnostics_live_errors_and_warnings_format,
                errorCount,
                pluralSuffix(errorCount),
                warningCount,
                pluralSuffix(warningCount),
            )
        }

        errorCount > 0 -> {
            context.getString(R.string.diagnostics_live_errors_format, errorCount, pluralSuffix(errorCount))
        }

        warningCount > 0 -> {
            context.getString(R.string.diagnostics_live_warnings_format, warningCount, pluralSuffix(warningCount))
        }

        events.isNotEmpty() -> {
            context.getString(R.string.diagnostics_live_info_events_format, events.size, pluralSuffix(events.size))
        }

        else -> {
            context.getString(R.string.diagnostics_live_feed_quiet)
        }
    }
}

private fun pluralSuffix(count: Int): String = if (count == 1) "" else "s"
