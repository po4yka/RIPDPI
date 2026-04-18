package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.diagnostics.DiagnosticActiveConnectionPolicy
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionSession
import com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

private const val DiagnosticsStateSubscriptionMillis = 5_000L
private const val DiagnosticsRememberedPolicyLimit = 64

internal class DiagnosticsUiStateAssembler
    @Inject
    constructor(
        private val uiStateFactory: DiagnosticsUiStateFactory,
    ) {
        @Suppress("LongMethod")
        fun assemble(
            scope: CoroutineScope,
            interactionDependencies: DiagnosticsInteractionDependencies,
            contextDependencies: DiagnosticsContextDependencies,
            selectionState: StateFlow<SelectionState>,
            filterState: StateFlow<FilterState>,
            sessionDetailState: StateFlow<SessionDetailState>,
            scanLifecycleState: StateFlow<ScanLifecycleState>,
        ): StateFlow<DiagnosticsUiState> {
            val liveData =
                combine(
                    interactionDependencies.diagnosticsTimelineSource.telemetry,
                    interactionDependencies.diagnosticsTimelineSource.nativeEvents,
                    interactionDependencies.diagnosticsTimelineSource.activeScanProgress,
                    interactionDependencies.diagnosticsTimelineSource.snapshots,
                    interactionDependencies.diagnosticsTimelineSource.contexts,
                ) { telemetry, nativeEvents, progress, snapshots, contexts ->
                    LiveDataSnapshot(
                        activeConnectionSession = null,
                        currentTelemetry = null,
                        telemetry = telemetry,
                        nativeEvents = nativeEvents,
                        progress = progress,
                        snapshots = snapshots,
                        contexts = contexts,
                        liveTelemetry = emptyList(),
                        liveNativeEvents = emptyList(),
                        liveSnapshots = emptyList(),
                        liveContexts = emptyList(),
                    )
                }.stateIn(
                    scope,
                    SharingStarted.WhileSubscribed(DiagnosticsStateSubscriptionMillis),
                    LiveDataSnapshot.EMPTY,
                )

            val currentTelemetryData =
                combine(
                    contextDependencies.serviceStateStore.status,
                    contextDependencies.serviceStateStore.telemetry,
                    interactionDependencies.diagnosticsTimelineSource.activeConnectionSession,
                ) { (status, mode), telemetry, activeConnectionSession ->
                    buildCurrentServiceTelemetry(
                        status = status,
                        mode = mode,
                        telemetry = telemetry,
                        activeConnectionSession = activeConnectionSession,
                    )
                }.stateIn(
                    scope,
                    SharingStarted.WhileSubscribed(DiagnosticsStateSubscriptionMillis),
                    null,
                )

            val liveRuntimeData =
                combine(
                    interactionDependencies.diagnosticsTimelineSource.activeConnectionSession,
                    interactionDependencies.diagnosticsTimelineSource.liveSnapshots,
                    interactionDependencies.diagnosticsTimelineSource.liveContexts,
                    interactionDependencies.diagnosticsTimelineSource.liveTelemetry,
                    interactionDependencies.diagnosticsTimelineSource.liveNativeEvents,
                ) { activeConnectionSession, liveSnapshots, liveContexts, liveTelemetry, liveNativeEvents ->
                    LiveRuntimeSnapshot(
                        activeConnectionSession = activeConnectionSession,
                        liveSnapshots = liveSnapshots,
                        liveContexts = liveContexts,
                        liveTelemetry = liveTelemetry,
                        liveNativeEvents = liveNativeEvents,
                    )
                }.stateIn(
                    scope,
                    SharingStarted.WhileSubscribed(DiagnosticsStateSubscriptionMillis),
                    LiveRuntimeSnapshot.EMPTY,
                )

            val combinedLiveData =
                combine(liveData, liveRuntimeData, currentTelemetryData) { live, runtime, currentTelemetry ->
                    live.copy(
                        activeConnectionSession = runtime.activeConnectionSession,
                        currentTelemetry = currentTelemetry,
                        liveTelemetry = runtime.liveTelemetry,
                        liveNativeEvents = runtime.liveNativeEvents,
                        liveSnapshots = runtime.liveSnapshots,
                        liveContexts = runtime.liveContexts,
                    )
                }.stateIn(
                    scope,
                    SharingStarted.WhileSubscribed(DiagnosticsStateSubscriptionMillis),
                    LiveDataSnapshot.EMPTY,
                )

            val scanData =
                combine(
                    interactionDependencies.diagnosticsTimelineSource.profiles,
                    interactionDependencies.diagnosticsTimelineSource.sessions,
                    interactionDependencies.diagnosticsTimelineSource.approachStats,
                    interactionDependencies.diagnosticsTimelineSource.exports,
                ) { profiles, sessions, approachStats, exports ->
                    ScanDataSnapshot(profiles, sessions, approachStats, exports)
                }.stateIn(
                    scope,
                    SharingStarted.WhileSubscribed(DiagnosticsStateSubscriptionMillis),
                    ScanDataSnapshot.EMPTY,
                )

            val configData =
                combine(
                    contextDependencies.appSettingsRepository.settings,
                    contextDependencies.rememberedPolicySource.observePolicies(
                        limit = DiagnosticsRememberedPolicyLimit,
                    ),
                    contextDependencies.serviceStateStore.status,
                    contextDependencies.activeConnectionPolicySource.activePolicies,
                ) { settings, rememberedPolicies, serviceStatus, activePolicies ->
                    val (_, activeMode) = serviceStatus
                    val connectionPolicy =
                        activePolicies[activeMode]
                            ?: activePolicies.values.maxByOrNull(DiagnosticActiveConnectionPolicy::appliedAt)
                    ConfigSnapshot(settings, rememberedPolicies, connectionPolicy)
                }.stateIn(
                    scope,
                    SharingStarted.WhileSubscribed(DiagnosticsStateSubscriptionMillis),
                    ConfigSnapshot(
                        settings = AppSettingsSerializer.defaultValue,
                        rememberedPolicies = emptyList(),
                        activeConnectionPolicy = null,
                    ),
                )

            val combinedData =
                combine(combinedLiveData, scanData, configData) { live, scan, config ->
                    Triple(live, scan, config)
                }.stateIn(
                    scope,
                    SharingStarted.WhileSubscribed(DiagnosticsStateSubscriptionMillis),
                    Triple(LiveDataSnapshot.EMPTY, ScanDataSnapshot.EMPTY, configData.value),
                )

            val combinedUi =
                combine(
                    selectionState,
                    filterState,
                    sessionDetailState,
                    scanLifecycleState,
                ) { selection, filter, sessionDetail, scanLifecycle ->
                    UiControlState(selection, filter, sessionDetail, scanLifecycle)
                }.stateIn(
                    scope,
                    SharingStarted.WhileSubscribed(DiagnosticsStateSubscriptionMillis),
                    UiControlState(
                        selection = SelectionState(),
                        filter = FilterState(),
                        sessionDetail = SessionDetailState(),
                        scanLifecycle = ScanLifecycleState(),
                    ),
                )

            return combine(combinedData, combinedUi) { (live, scan, config), ui ->
                uiStateFactory.buildUiState(
                    DiagnosticsUiStateInput(
                        profiles = scan.profiles,
                        settings = config.settings,
                        progress = live.progress,
                        sessions = scan.sessions,
                        approachStats = scan.approachStats,
                        snapshots = live.snapshots,
                        contexts = live.contexts,
                        currentTelemetry = live.currentTelemetry,
                        telemetry = live.telemetry,
                        nativeEvents = live.nativeEvents,
                        activeConnectionSession = live.activeConnectionSession,
                        liveSnapshots = live.liveSnapshots,
                        liveContexts = live.liveContexts,
                        liveTelemetry = live.liveTelemetry,
                        liveNativeEvents = live.liveNativeEvents,
                        exports = scan.exports,
                        rememberedPolicies = config.rememberedPolicies,
                        activeConnectionPolicy = config.activeConnectionPolicy,
                        selectedSectionRequest = ui.selection.selectedSectionRequest,
                        selectedProfileId = ui.selection.selectedProfileId,
                        selectedApproachMode = ui.selection.selectedApproachMode,
                        selectedProbe = ui.selection.selectedProbe,
                        selectedEventId = ui.selection.selectedEventId,
                        sessionPathMode = ui.filter.sessionPathModeFilter,
                        sessionStatus = ui.filter.sessionStatusFilter,
                        sessionSearch = ui.filter.sessionSearch,
                        eventSource = ui.filter.eventSourceFilter,
                        eventSeverity = ui.filter.eventSeverityFilter,
                        eventSearch = ui.filter.eventSearch,
                        eventAutoScroll = ui.filter.eventAutoScroll,
                        selectedSessionDetail = ui.sessionDetail.selectedSessionDetail,
                        selectedStrategyProbeCandidate = ui.selection.selectedStrategyProbeCandidate,
                        selectedApproachDetail = ui.selection.selectedApproachDetail,
                        sensitiveSessionDetailsVisible = ui.sessionDetail.sensitiveSessionDetailsVisible,
                        archiveActionState = ui.scanLifecycle.archiveActionState,
                        scanStartedAt = ui.scanLifecycle.scanStartedAt,
                        activeScanPathMode = ui.scanLifecycle.activeScanPathMode,
                        completedProbes = ui.scanLifecycle.accumulatedProbes,
                        candidateTimeline = ui.scanLifecycle.accumulatedStrategyCandidates,
                        dnsBaselineStatus = ui.scanLifecycle.dnsBaselineStatus,
                        dpiFailureClass = ui.scanLifecycle.dpiFailureClass,
                        hiddenProbeConflictDialog = ui.scanLifecycle.hiddenProbeConflictDialog,
                        queuedManualScanRequest = ui.scanLifecycle.queuedManualScanRequest,
                    ),
                )
            }.stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(DiagnosticsStateSubscriptionMillis),
                initialValue = DiagnosticsUiState(),
            )
        }
    }

internal fun buildCurrentServiceTelemetry(
    status: AppStatus,
    mode: Mode,
    telemetry: ServiceTelemetrySnapshot,
    activeConnectionSession: DiagnosticConnectionSession?,
): DiagnosticTelemetrySample? {
    if (!hasCurrentServiceTelemetry(status, telemetry, activeConnectionSession)) {
        return null
    }

    val createdAt =
        listOfNotNull(
            telemetry.updatedAt.takeIf { it > 0L },
            telemetry.lastFailureAt,
            activeConnectionSession?.updatedAt?.takeIf { it > 0L },
            telemetry.serviceStartedAt,
        ).maxOrNull() ?: 0L
    return DiagnosticTelemetrySample(
        id = "service-state:${activeConnectionSession?.id ?: mode.name.lowercase()}:$createdAt",
        sessionId = null,
        connectionSessionId = activeConnectionSession?.id,
        activeMode = telemetry.mode?.name ?: activeConnectionSession?.serviceMode ?: mode.name,
        connectionState = resolveCurrentConnectionState(status, activeConnectionSession),
        networkType = activeConnectionSession?.networkType ?: UnknownCurrentNetworkType,
        publicIp = activeConnectionSession?.publicIp,
        failureClass = telemetry.runtimeFieldTelemetry.failureClass?.wireValue,
        telemetryNetworkFingerprintHash = telemetry.runtimeFieldTelemetry.telemetryNetworkFingerprintHash,
        winningTcpStrategyFamily = telemetry.runtimeFieldTelemetry.winningTcpStrategyFamily,
        winningQuicStrategyFamily = telemetry.runtimeFieldTelemetry.winningQuicStrategyFamily,
        proxyRttBand = telemetry.runtimeFieldTelemetry.proxyRttBand.wireValue,
        resolverRttBand = telemetry.runtimeFieldTelemetry.resolverRttBand.wireValue,
        proxyRouteRetryCount = telemetry.runtimeFieldTelemetry.proxyRouteRetryCount,
        tunnelRecoveryRetryCount = telemetry.runtimeFieldTelemetry.tunnelRecoveryRetryCount,
        resolverId = telemetry.tunnelTelemetry.resolverId,
        resolverProtocol = telemetry.tunnelTelemetry.resolverProtocol,
        resolverEndpoint = telemetry.tunnelTelemetry.resolverEndpoint,
        resolverLatencyMs = telemetry.tunnelTelemetry.resolverLatencyMs,
        dnsFailuresTotal = telemetry.tunnelTelemetry.dnsFailuresTotal,
        resolverFallbackActive = telemetry.tunnelTelemetry.resolverFallbackActive,
        resolverFallbackReason = telemetry.tunnelTelemetry.resolverFallbackReason,
        networkHandoverClass = telemetry.tunnelTelemetry.networkHandoverClass,
        networkHandoverState = telemetry.networkHandoverState,
        lastFailureClass = telemetry.proxyTelemetry.lastFailureClass,
        lastFallbackAction = telemetry.proxyTelemetry.lastFallbackAction,
        txPackets = telemetry.tunnelStats.txPackets,
        txBytes = telemetry.tunnelStats.txBytes,
        rxPackets = telemetry.tunnelStats.rxPackets,
        rxBytes = telemetry.tunnelStats.rxBytes,
        createdAt = createdAt,
    )
}

internal fun hasCurrentServiceTelemetry(
    status: AppStatus,
    telemetry: ServiceTelemetrySnapshot,
    activeConnectionSession: DiagnosticConnectionSession?,
): Boolean =
    status == AppStatus.Running ||
        activeConnectionSession != null ||
        telemetry.updatedAt > 0L ||
        telemetry.serviceStartedAt != null ||
        telemetry.restartCount > 0 ||
        telemetry.lastFailureAt != null ||
        telemetry.tunnelStats.txPackets > 0L ||
        telemetry.tunnelStats.txBytes > 0L ||
        telemetry.tunnelStats.rxPackets > 0L ||
        telemetry.tunnelStats.rxBytes > 0L

internal fun resolveCurrentConnectionState(
    status: AppStatus,
    activeConnectionSession: DiagnosticConnectionSession?,
): String =
    when (status) {
        AppStatus.Running -> {
            activeConnectionSession?.connectionState ?: AppStatus.Running.name
        }

        AppStatus.Halted -> {
            if (activeConnectionSession?.connectionState.equals("Failed", ignoreCase = true)) {
                "Failed"
            } else {
                "Stopped"
            }
        }
    }

private const val UnknownCurrentNetworkType = "unknown"
