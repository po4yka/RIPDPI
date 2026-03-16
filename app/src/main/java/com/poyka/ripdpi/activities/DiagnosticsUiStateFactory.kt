package com.poyka.ripdpi.activities

import android.content.Context
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.DiagnosticSessionDetail
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.ActiveConnectionPolicy

internal class DiagnosticsUiStateFactory(
    context: Context,
    private val support: DiagnosticsUiFactorySupport = DiagnosticsUiFactorySupport(context),
) {
    fun buildUiState(
        profiles: List<DiagnosticProfileEntity>,
        settings: AppSettings,
        progress: ScanProgress?,
        sessions: List<ScanSessionEntity>,
        approachStats: List<BypassApproachSummary>,
        snapshots: List<NetworkSnapshotEntity>,
        contexts: List<DiagnosticContextEntity>,
        telemetry: List<TelemetrySampleEntity>,
        nativeEvents: List<NativeSessionEventEntity>,
        exports: List<ExportRecordEntity>,
        rememberedPolicies: List<RememberedNetworkPolicyEntity>,
        activeConnectionPolicy: ActiveConnectionPolicy?,
        selectedSectionRequest: DiagnosticsSection,
        selectedProfileId: String?,
        selectedApproachMode: DiagnosticsApproachMode,
        selectedProbe: DiagnosticsProbeResultUiModel?,
        selectedEventId: String?,
        sessionPathMode: String?,
        sessionStatus: String?,
        sessionSearch: String,
        eventSource: String?,
        eventSeverity: String?,
        eventSearch: String,
        eventAutoScroll: Boolean,
        selectedSessionDetail: DiagnosticsSessionDetailUiModel?,
        selectedStrategyProbeCandidate: DiagnosticsStrategyProbeCandidateDetailUiModel?,
        selectedApproachDetail: DiagnosticsApproachDetailUiModel?,
        sensitiveSessionDetailsVisible: Boolean,
        archiveActionState: ArchiveActionState,
    ): DiagnosticsUiState {
        val activeProfile = profiles.firstOrNull { it.id == selectedProfileId } ?: profiles.firstOrNull()
        val activeProfileRequest = activeProfile?.let(support::decodeRequest)
        val selectedProfileUi = activeProfile?.let(support::toProfileOptionUiModel)

        val latestSnapshot =
            snapshots.firstOrNull()?.let { support.toNetworkSnapshotUiModel(it, showSensitiveDetails = false) }
        val latestContext =
            (contexts.firstOrNull { it.sessionId == null } ?: contexts.firstOrNull())?.let(support::decodeContext)
        val eventModels = nativeEvents.map(support::toEventUiModel)
        val sessionRows = sessions.map(support::toSessionRowUiModel)

        val latestCompletedSession = sessions.firstOrNull { it.reportJson != null } ?: sessions.firstOrNull()
        val latestProfileSession =
            sessions.firstOrNull { it.profileId == activeProfile?.id && it.reportJson != null }
                ?: sessions.firstOrNull { it.profileId == activeProfile?.id }
                ?: latestCompletedSession
        val latestProfileReport = latestProfileSession?.reportJson?.let(support::decodeReport)
        val latestReport = latestCompletedSession?.reportJson?.let(support::decodeReport)
        val latestReportResults = latestProfileReport?.results?.mapIndexed(support::toProbeResultUiModel).orEmpty()
        val latestResolverRecommendation =
            latestProfileReport?.resolverRecommendation?.let(support::toResolverRecommendationUiModel)
        val latestStrategyProbeReport =
            latestProfileReport?.strategyProbeReport?.let { report ->
                support.toStrategyProbeReportUiModel(
                    report = report,
                    reportResults = latestProfileReport.results,
                    serviceMode = latestProfileSession?.serviceMode,
                )
            }

        val currentTelemetry = telemetry.firstOrNull()
        val health = support.deriveHealth(progress, latestCompletedSession, currentTelemetry, nativeEvents)
        val warnings =
            (support.buildContextWarnings(latestContext) +
                eventModels.filter { it.tone == DiagnosticsTone.Negative || it.tone == DiagnosticsTone.Warning })
                .take(3)
        val rememberedNetworkRows =
            rememberedPolicies.map { policy ->
                support.toRememberedNetworkUiModel(policy, activeConnectionPolicy)
            }

        val selectedSection =
            if (progress != null) {
                DiagnosticsSection.Scan
            } else {
                selectedSectionRequest
            }
        val sessionDetailWithVisibility =
            selectedSessionDetail?.copy(
                sensitiveDetailsVisible = sensitiveSessionDetailsVisible,
            )
        val resolvedSelectedStrategyProbeCandidate =
            selectedStrategyProbeCandidate?.let { candidate ->
                sessionDetailWithVisibility
                    ?.strategyProbeReport
                    ?.candidateDetails
                    ?.get(candidate.id)
                    ?: latestStrategyProbeReport?.candidateDetails?.get(candidate.id)
                    ?: candidate
            }
        val (eventsUi, selectedEvent) =
            support.buildEventsUiModel(
                eventModels = eventModels,
                selectedEventId = selectedEventId,
                eventSource = eventSource,
                eventSeverity = eventSeverity,
                eventSearch = eventSearch,
                eventAutoScroll = eventAutoScroll,
            )

        return DiagnosticsUiState(
            selectedSection = selectedSection,
            overview =
                support.buildOverviewUiModel(
                    health = health,
                    progress = progress,
                    latestSession = latestCompletedSession,
                    latestSnapshot = latestSnapshot,
                    latestContext = latestContext,
                    currentTelemetry = currentTelemetry,
                    sessions = sessions,
                    nativeEvents = nativeEvents,
                    selectedProfile = selectedProfileUi,
                    sessionRows = sessionRows,
                    rememberedNetworkRows = rememberedNetworkRows,
                    warnings = warnings,
                ),
            scan =
                support.buildScanUiModel(
                    profiles = profiles,
                    activeProfile = activeProfile,
                    activeProfileRequest = activeProfileRequest,
                    latestProfileSession = latestProfileSession,
                    latestReportResults = latestReportResults,
                    latestResolverRecommendation = latestResolverRecommendation,
                    latestStrategyProbeReport = latestStrategyProbeReport,
                    progress = progress,
                    rawArgsEnabled = settings.enableCmdSettings,
                ),
            live =
                support.buildLiveUiModel(
                    health = health,
                    telemetry = telemetry,
                    currentTelemetry = currentTelemetry,
                    nativeEvents = nativeEvents,
                    latestSnapshot = latestSnapshot,
                    latestContext = latestContext,
                    eventModels = eventModels,
                ),
            sessions =
                support.buildSessionsUiModel(
                    sessions = sessions,
                    sessionRows = sessionRows,
                    sessionPathMode = sessionPathMode,
                    sessionStatus = sessionStatus,
                    sessionSearch = sessionSearch,
                    selectedSessionDetail = selectedSessionDetail,
                ),
            approaches =
                support.buildApproachesUiModel(
                    approachStats = approachStats,
                    selectedApproachMode = selectedApproachMode,
                    selectedApproachDetail = selectedApproachDetail,
                ),
            events = eventsUi,
            share =
                support.buildShareUiModel(
                    latestCompletedSession = latestCompletedSession,
                    latestSnapshot = latestSnapshot,
                    latestContext = latestContext,
                    currentTelemetry = currentTelemetry,
                    nativeEvents = nativeEvents,
                    latestReport = latestReport,
                    approachStats = approachStats,
                    selectedSessionDetail = selectedSessionDetail,
                    archiveActionState = archiveActionState,
                    exports = exports,
                ),
            selectedSessionDetail = sessionDetailWithVisibility,
            selectedApproachDetail = selectedApproachDetail,
            selectedEvent = selectedEvent,
            selectedProbe = selectedProbe,
            selectedStrategyProbeCandidate = resolvedSelectedStrategyProbeCandidate,
        )
    }

    fun toSessionDetailUiModel(
        detail: DiagnosticSessionDetail,
        showSensitiveDetails: Boolean,
    ): DiagnosticsSessionDetailUiModel {
        val report = detail.session.reportJson?.let(support::decodeReport)
        val probeGroups =
            detail.results
                .mapIndexed(support::toProbeResultUiModel)
                .groupBy { it.probeType }
                .map { (title, items) ->
                    DiagnosticsProbeGroupUiModel(
                        title = title,
                        items = items,
                    )
                }
        return DiagnosticsSessionDetailUiModel(
            session = support.toSessionRowUiModel(detail.session),
            probeGroups = probeGroups,
            snapshots = detail.snapshots.mapNotNull { snapshot ->
                support.toNetworkSnapshotUiModel(
                    snapshot,
                    showSensitiveDetails
                )
            },
            events = detail.events.map(support::toEventUiModel),
            contextGroups =
                detail.context
                    ?.let(support::decodeContext)
                    ?.let { context -> support.toContextUiGroups(context, showSensitiveDetails) }
                    .orEmpty(),
            strategyProbeReport =
                report?.strategyProbeReport?.let { strategyReport ->
                    support.toStrategyProbeReportUiModel(
                        report = strategyReport,
                        reportResults = report.results,
                        serviceMode = detail.session.serviceMode,
                    )
                },
            hasSensitiveDetails = true,
            sensitiveDetailsVisible = showSensitiveDetails,
        )
    }

    fun toApproachDetailUiModel(
        detail: com.poyka.ripdpi.diagnostics.BypassApproachDetail,
    ): DiagnosticsApproachDetailUiModel =
        support.toApproachDetailUiModel(detail)
}
