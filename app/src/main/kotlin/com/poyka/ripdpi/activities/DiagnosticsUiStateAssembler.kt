package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
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
        fun assemble(
            scope: CoroutineScope,
            interactionDependencies: DiagnosticsInteractionDependencies,
            contextDependencies: DiagnosticsContextDependencies,
            selectionState: StateFlow<SelectionState>,
            filterState: StateFlow<FilterState>,
            sessionDetailState: StateFlow<SessionDetailState>,
            scanLifecycleState: StateFlow<ScanLifecycleState>,
        ): StateFlow<DiagnosticsUiState> {
            val data =
                assembleDiagnosticsDataState(
                    scope = scope,
                    interactionDependencies = interactionDependencies,
                    contextDependencies = contextDependencies,
                )
            val controls =
                assembleControlState(
                    scope = scope,
                    selectionState = selectionState,
                    filterState = filterState,
                    sessionDetailState = sessionDetailState,
                    scanLifecycleState = scanLifecycleState,
                )

            return combine(data, controls) { diagnosticsData, controls ->
                uiStateFactory.buildUiState(
                    buildInput(
                        diagnosticsData = diagnosticsData,
                        controls = controls,
                    ),
                )
            }.stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(DiagnosticsStateSubscriptionMillis),
                initialValue = DiagnosticsUiState(),
            )
        }

        private fun assembleDiagnosticsDataState(
            scope: CoroutineScope,
            interactionDependencies: DiagnosticsInteractionDependencies,
            contextDependencies: DiagnosticsContextDependencies,
        ): StateFlow<DiagnosticsAssemblyData> {
            val live = assembleLiveDataState(scope, interactionDependencies, contextDependencies)
            val scan = assembleScanDataState(scope, interactionDependencies)
            val config = assembleConfigState(scope, contextDependencies)
            return combine(live, scan, config) { liveData, scanData, configData ->
                DiagnosticsAssemblyData(
                    live = liveData,
                    scan = scanData,
                    config = configData,
                )
            }.stateIn(
                scope,
                SharingStarted.WhileSubscribed(DiagnosticsStateSubscriptionMillis),
                DiagnosticsAssemblyData(
                    live = LiveDataSnapshot.EMPTY,
                    scan = ScanDataSnapshot.EMPTY,
                    config = config.value,
                ),
            )
        }

        private fun assembleLiveDataState(
            scope: CoroutineScope,
            interactionDependencies: DiagnosticsInteractionDependencies,
            contextDependencies: DiagnosticsContextDependencies,
        ): StateFlow<LiveDataSnapshot> {
            val timeline = interactionDependencies.diagnosticsTimelineSource
            val archivedTelemetry = assembleArchivedTelemetryState(scope, interactionDependencies)
            val liveRuntime = assembleLiveRuntimeState(scope, interactionDependencies)
            val currentTelemetry =
                combine(
                    contextDependencies.serviceStateStore.status,
                    contextDependencies.serviceStateStore.telemetry,
                    timeline.activeConnectionSession,
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

            return combine(archivedTelemetry, liveRuntime, currentTelemetry) { archived, runtime, current ->
                archived.copy(
                    activeConnectionSession = runtime.activeConnectionSession,
                    currentTelemetry = current,
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
        }

        private fun assembleArchivedTelemetryState(
            scope: CoroutineScope,
            interactionDependencies: DiagnosticsInteractionDependencies,
        ): StateFlow<LiveDataSnapshot> {
            val timeline = interactionDependencies.diagnosticsTimelineSource
            return combine(
                timeline.telemetry,
                timeline.nativeEvents,
                timeline.activeScanProgress,
                timeline.snapshots,
                timeline.contexts,
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
        }

        private fun assembleLiveRuntimeState(
            scope: CoroutineScope,
            interactionDependencies: DiagnosticsInteractionDependencies,
        ): StateFlow<LiveRuntimeSnapshot> {
            val timeline = interactionDependencies.diagnosticsTimelineSource
            return combine(
                timeline.activeConnectionSession,
                timeline.liveSnapshots,
                timeline.liveContexts,
                timeline.liveTelemetry,
                timeline.liveNativeEvents,
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
        }

        private fun assembleScanDataState(
            scope: CoroutineScope,
            interactionDependencies: DiagnosticsInteractionDependencies,
        ): StateFlow<ScanDataSnapshot> {
            val timeline = interactionDependencies.diagnosticsTimelineSource
            return combine(
                timeline.profiles,
                timeline.sessions,
                timeline.approachStats,
                timeline.exports,
            ) { profiles, sessions, approachStats, exports ->
                ScanDataSnapshot(profiles, sessions, approachStats, exports)
            }.stateIn(
                scope,
                SharingStarted.WhileSubscribed(DiagnosticsStateSubscriptionMillis),
                ScanDataSnapshot.EMPTY,
            )
        }

        private fun assembleConfigState(
            scope: CoroutineScope,
            contextDependencies: DiagnosticsContextDependencies,
        ): StateFlow<ConfigSnapshot> =
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
                    selectActiveConnectionPolicy(
                        serviceMode = activeMode,
                        activePolicies = activePolicies,
                    )
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

        private fun assembleControlState(
            scope: CoroutineScope,
            selectionState: StateFlow<SelectionState>,
            filterState: StateFlow<FilterState>,
            sessionDetailState: StateFlow<SessionDetailState>,
            scanLifecycleState: StateFlow<ScanLifecycleState>,
        ): StateFlow<UiControlState> =
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

        private fun buildInput(
            diagnosticsData: DiagnosticsAssemblyData,
            controls: UiControlState,
        ): DiagnosticsUiStateInput {
            val live = diagnosticsData.live
            val scan = diagnosticsData.scan
            val config = diagnosticsData.config
            return DiagnosticsUiStateInput(
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
                selectedSectionRequest = controls.selection.selectedSectionRequest,
                selectedProfileId =
                    controls.selection.selectedProfileId
                        ?: config.settings.diagnosticsActiveProfileId.takeIf { it.isNotBlank() },
                selectedApproachMode = controls.selection.selectedApproachMode,
                selectedProbe = controls.selection.selectedProbe,
                selectedEventId = controls.selection.selectedEventId,
                sessionPathMode = controls.filter.sessionPathModeFilter,
                sessionStatus = controls.filter.sessionStatusFilter,
                sessionSearch = controls.filter.sessionSearch,
                eventSource = controls.filter.eventSourceFilter,
                eventSeverity = controls.filter.eventSeverityFilter,
                eventSearch = controls.filter.eventSearch,
                eventAutoScroll = controls.filter.eventAutoScroll,
                selectedSessionDetail = controls.sessionDetail.selectedSessionDetail,
                selectedStrategyProbeCandidate = controls.selection.selectedStrategyProbeCandidate,
                selectedApproachDetail = controls.selection.selectedApproachDetail,
                sensitiveSessionDetailsVisible = controls.sessionDetail.sensitiveSessionDetailsVisible,
                archiveActionState = controls.scanLifecycle.archiveActionState,
                scanStartedAt = controls.scanLifecycle.scanStartedAt,
                activeScanPathMode = controls.scanLifecycle.activeScanPathMode,
                completedProbes = controls.scanLifecycle.accumulatedProbes,
                candidateTimeline = controls.scanLifecycle.accumulatedStrategyCandidates,
                dnsBaselineStatus = controls.scanLifecycle.dnsBaselineStatus,
                dpiFailureClass = controls.scanLifecycle.dpiFailureClass,
                hiddenProbeConflictDialog = controls.scanLifecycle.hiddenProbeConflictDialog,
                sensitiveProfileConsentDialog = controls.scanLifecycle.sensitiveProfileConsentDialog,
                queuedManualScanRequest = controls.scanLifecycle.queuedManualScanRequest,
            )
        }
    }

private data class DiagnosticsAssemblyData(
    val live: LiveDataSnapshot,
    val scan: ScanDataSnapshot,
    val config: ConfigSnapshot,
)

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
        proxyTelemetryState = telemetry.proxyTelemetryStatus.state.wireValue,
        proxyTelemetryMessage = telemetry.proxyTelemetryStatus.message,
        relayTelemetryState = telemetry.relayTelemetryStatus.state.wireValue,
        relayTelemetryMessage = telemetry.relayTelemetryStatus.message,
        warpTelemetryState = telemetry.warpTelemetryStatus.state.wireValue,
        warpTelemetryMessage = telemetry.warpTelemetryStatus.message,
        tunnelTelemetryState = telemetry.tunnelTelemetryStatus.state.wireValue,
        tunnelTelemetryMessage = telemetry.tunnelTelemetryStatus.message,
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
