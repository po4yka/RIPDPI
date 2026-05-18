package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticNetworkSnapshot
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample
import javax.inject.Inject

private const val ConnectionSampleArtifactKind = "connection_sample"

internal class DiagnosticsOverviewUiStateFactory
    @Inject
    constructor(
        private val support: DiagnosticsUiFactorySupport,
    ) {
        fun build(
            input: DiagnosticsUiStateInput,
            resolvedInput: ResolvedDiagnosticsUiInput,
            sessionRows: List<DiagnosticsSessionRowUiModel>,
        ): DiagnosticsOverviewUiModel =
            support.buildOverviewUiModel(
                health = resolvedInput.health,
                progress = input.progress,
                latestSession = resolvedInput.latestCompletedSession,
                recentAutomaticProbe = resolvedInput.recentAutomaticProbe,
                latestSnapshot = resolvedInput.latestSnapshot,
                latestContext = resolvedInput.latestContext,
                currentTelemetry = resolvedInput.currentTelemetry,
                sessions = input.sessions,
                nativeEvents = input.nativeEvents,
                selectedProfile = resolvedInput.selectedProfileUi,
                sessionRows = sessionRows,
                rememberedNetworkRows = resolvedInput.rememberedNetworkRows,
                warnings = resolvedInput.warnings,
            )
    }

internal class DiagnosticsScanUiStateFactory
    @Inject
    constructor(
        private val support: DiagnosticsUiFactorySupport,
    ) {
        fun build(
            input: DiagnosticsUiStateInput,
            resolvedInput: ResolvedDiagnosticsUiInput,
        ): DiagnosticsScanUiModel =
            support.buildScanUiModel(
                BuildScanUiModelParams(
                    profiles = resolvedInput.visibleProfiles,
                    omittedProfileCount = resolvedInput.omittedProfileCount,
                    activeProfile = resolvedInput.activeProfile,
                    activeProfileRequest = resolvedInput.activeProfileRequest,
                    latestProfileSession = resolvedInput.latestProfileSession,
                    activeScanPathMode = input.activeScanPathMode,
                    latestReportResults = resolvedInput.latestReportResults,
                    latestResolverRecommendation = resolvedInput.latestResolverRecommendation,
                    latestStrategyProbeReport = resolvedInput.latestStrategyProbeReport,
                    progress = input.progress,
                    rawArgsEnabled = input.settings.enableCmdSettings,
                    serviceStatus = input.serviceStatus,
                    vpnPermissionDisabled =
                        resolvedInput.latestContext
                            ?.permissions
                            ?.vpnPermissionState == "disabled",
                    scanStartedAt = input.scanStartedAt,
                    completedProbes = input.completedProbes,
                    candidateTimeline = input.candidateTimeline,
                    dnsBaselineStatus = input.dnsBaselineStatus,
                    dpiFailureClass = input.dpiFailureClass,
                    networkContext = buildScanNetworkContext(input.liveSnapshots.firstOrNull(), input.currentTelemetry),
                    hiddenProbeConflictDialog = input.hiddenProbeConflictDialog,
                    sensitiveProfileConsentDialog = input.sensitiveProfileConsentDialog,
                    queuedManualScanRequest = input.queuedManualScanRequest,
                ),
            )

        private fun buildScanNetworkContext(
            snapshot: DiagnosticNetworkSnapshot?,
            telemetry: DiagnosticTelemetrySample?,
        ): ScanNetworkContextUiModel? {
            val net = snapshot?.snapshot ?: return null
            val transport = net.transport.replaceFirstChar { it.uppercase() }
            val signalLabel =
                net.wifiDetails?.rssiDbm?.let { "$it dBm" }
                    ?: net.cellularDetails?.signalDbm?.let { "$it dBm" }
            val resolverLabel =
                telemetry?.let { t ->
                    val id = t.resolverId?.replaceFirstChar { it.uppercase() }
                    val proto = t.resolverProtocol?.uppercase()
                    if (id != null && proto != null) "$id $proto" else id ?: proto
                }
            return ScanNetworkContextUiModel(
                transport = transport,
                signalLabel = signalLabel,
                resolverLabel = resolverLabel,
                validated = net.networkValidated,
            )
        }
    }

internal class DiagnosticsLiveUiStateFactory
    @Inject
    constructor(
        private val support: DiagnosticsUiFactorySupport,
    ) {
        fun build(input: DiagnosticsUiStateInput): DiagnosticsLiveUiModel =
            support.buildLiveUiModel(
                activeConnectionSession = input.activeConnectionSession,
                telemetry = input.liveTelemetry,
                currentTelemetry = input.liveTelemetry.firstOrNull(),
                nativeEvents = input.liveNativeEvents,
                latestSnapshot =
                    input.liveSnapshots
                        .firstOrNull { it.snapshotKind == ConnectionSampleArtifactKind }
                        ?.let { support.toNetworkSnapshotUiModel(it, showSensitiveDetails = false) },
                latestContext =
                    input.liveContexts
                        .firstOrNull { it.contextKind == ConnectionSampleArtifactKind }
                        ?.context,
            )
    }

internal class DiagnosticsSessionsUiStateFactory
    @Inject
    constructor(
        private val support: DiagnosticsUiFactorySupport,
    ) {
        fun mapRows(sessions: List<DiagnosticScanSession>): List<DiagnosticsSessionRowUiModel> =
            sessions.map(support::toSessionRowUiModel)

        fun build(
            input: DiagnosticsUiStateInput,
            sessionRows: List<DiagnosticsSessionRowUiModel>,
        ): DiagnosticsSessionsUiModel =
            support.buildSessionsUiModel(
                sessions = input.sessions,
                sessionRows = sessionRows,
                sessionPathMode = input.sessionPathMode,
                sessionStatus = input.sessionStatus,
                sessionSearch = input.sessionSearch,
                selectedSessionDetail = input.selectedSessionDetail,
            )
    }

internal class DiagnosticsApproachesUiStateFactory
    @Inject
    constructor(
        private val support: DiagnosticsUiFactorySupport,
    ) {
        fun build(input: DiagnosticsUiStateInput): DiagnosticsApproachesUiModel =
            support.buildApproachesUiModel(
                approachStats = input.approachStats,
                selectedApproachMode = input.selectedApproachMode,
                selectedApproachDetail = input.selectedApproachDetail,
            )
    }

internal class DiagnosticsEventsUiStateFactory
    @Inject
    constructor(
        private val support: DiagnosticsUiFactorySupport,
    ) {
        fun mapEvents(events: List<DiagnosticEvent>): List<DiagnosticsEventUiModel> =
            events.map(support::toEventUiModel)

        fun build(
            input: DiagnosticsUiStateInput,
            eventModels: List<DiagnosticsEventUiModel>,
        ): DiagnosticsEventsState {
            val (model, selectedEvent) =
                support.buildEventsUiModel(
                    eventModels = eventModels,
                    selectedEventId = input.selectedEventId,
                    eventSource = input.eventSource,
                    eventSeverity = input.eventSeverity,
                    eventSearch = input.eventSearch,
                    eventAutoScroll = input.eventAutoScroll,
                )
            return DiagnosticsEventsState(model = model, selectedEvent = selectedEvent)
        }
    }

internal class DiagnosticsShareUiStateFactory
    @Inject
    constructor(
        private val support: DiagnosticsUiFactorySupport,
    ) {
        fun build(
            input: DiagnosticsUiStateInput,
            resolvedInput: ResolvedDiagnosticsUiInput,
        ): DiagnosticsShareUiModel =
            support.buildShareUiModel(
                latestCompletedSession = resolvedInput.latestCompletedSession,
                latestSnapshot = resolvedInput.latestSnapshot,
                latestContext = resolvedInput.latestContext,
                currentTelemetry = resolvedInput.currentTelemetry,
                nativeEvents = input.nativeEvents,
                latestReport = resolvedInput.latestReport,
                approachStats = input.approachStats,
                selectedSessionDetail = input.selectedSessionDetail,
                archiveActionState = input.archiveActionState,
                exports = input.exports,
            )
    }
