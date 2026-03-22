package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.DiagnosticActiveConnectionPolicy
import com.poyka.ripdpi.diagnostics.DiagnosticContextSnapshot
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticExportRecord
import com.poyka.ripdpi.diagnostics.DiagnosticNetworkSnapshot
import com.poyka.ripdpi.diagnostics.DiagnosticProfile
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticSessionDetail
import com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample
import com.poyka.ripdpi.diagnostics.DiagnosticsRememberedPolicy
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.proto.AppSettings
import javax.inject.Inject

internal class DiagnosticsUiStateFactory
    @Inject
    constructor(
        private val support: DiagnosticsUiFactorySupport,
        private val sessionDetailUiMapper: DiagnosticsSessionDetailUiMapper,
    ) {
        fun buildUiState(input: DiagnosticsUiStateInput): DiagnosticsUiState {
            val activeProfile =
                input.profiles.firstOrNull { it.id == input.selectedProfileId }
                    ?: input.profiles.firstOrNull()
            val activeProfileRequest = activeProfile?.request
            val selectedProfileUi = activeProfile?.let(support::toProfileOptionUiModel)

            val latestSnapshot =
                input.snapshots
                    .firstOrNull()
                    ?.let { support.toNetworkSnapshotUiModel(it, showSensitiveDetails = false) }
            val latestContext =
                (input.contexts.firstOrNull { it.sessionId == null } ?: input.contexts.firstOrNull())?.context
            val eventModels = input.nativeEvents.map(support::toEventUiModel)
            val sessionRows = input.sessions.map(support::toSessionRowUiModel)

            val latestCompletedSession =
                input.sessions.firstOrNull { it.report != null } ?: input.sessions.firstOrNull()
            val latestProfileSession =
                input.sessions.firstOrNull { it.profileId == activeProfile?.id && it.report != null }
                    ?: input.sessions.firstOrNull { it.profileId == activeProfile?.id }
                    ?: latestCompletedSession
            val latestProfileReport = latestProfileSession?.report
            val latestReport = latestCompletedSession?.report
            val latestReportResults =
                latestProfileReport?.results?.mapIndexed(support::toProbeResultUiModel).orEmpty()
            val latestResolverRecommendation =
                latestProfileReport?.resolverRecommendation?.let(support::toResolverRecommendationUiModel)
            val latestStrategyProbeReport =
                latestProfileReport?.strategyProbeReport?.let { report ->
                    support.toStrategyProbeReportUiModel(
                        report = report,
                        reportResults = latestProfileReport.results,
                        serviceMode = latestProfileSession.serviceMode,
                    )
                }

            val currentTelemetry = input.telemetry.firstOrNull()
            val health =
                support.deriveHealth(
                    input.progress,
                    latestCompletedSession,
                    currentTelemetry,
                    input.nativeEvents,
                )
            val warnings =
                (
                    support.buildContextWarnings(latestContext) +
                        eventModels.filter { it.tone == DiagnosticsTone.Negative || it.tone == DiagnosticsTone.Warning }
                ).take(3)
            val rememberedNetworkRows =
                input.rememberedPolicies.map { policy ->
                    support.toRememberedNetworkUiModel(policy, input.activeConnectionPolicy)
                }

            val selectedSection =
                if (input.progress != null) {
                    DiagnosticsSection.Scan
                } else {
                    input.selectedSectionRequest
                }
            val sessionDetailWithVisibility =
                input.selectedSessionDetail?.copy(
                    sensitiveDetailsVisible = input.sensitiveSessionDetailsVisible,
                )
            val resolvedSelectedStrategyProbeCandidate =
                input.selectedStrategyProbeCandidate?.let { candidate ->
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
                    selectedEventId = input.selectedEventId,
                    eventSource = input.eventSource,
                    eventSeverity = input.eventSeverity,
                    eventSearch = input.eventSearch,
                    eventAutoScroll = input.eventAutoScroll,
                )

            return DiagnosticsUiState(
                selectedSection = selectedSection,
                overview =
                    support.buildOverviewUiModel(
                        health = health,
                        progress = input.progress,
                        latestSession = latestCompletedSession,
                        latestSnapshot = latestSnapshot,
                        latestContext = latestContext,
                        currentTelemetry = currentTelemetry,
                        sessions = input.sessions,
                        nativeEvents = input.nativeEvents,
                        selectedProfile = selectedProfileUi,
                        sessionRows = sessionRows,
                        rememberedNetworkRows = rememberedNetworkRows,
                        warnings = warnings,
                    ),
                scan =
                    support.buildScanUiModel(
                        profiles = input.profiles,
                        activeProfile = activeProfile,
                        activeProfileRequest = activeProfileRequest,
                        latestProfileSession = latestProfileSession,
                        latestReportResults = latestReportResults,
                        latestResolverRecommendation = latestResolverRecommendation,
                        latestStrategyProbeReport = latestStrategyProbeReport,
                        progress = input.progress,
                        rawArgsEnabled = input.settings.enableCmdSettings,
                        scanStartedAt = input.scanStartedAt,
                        completedProbes = input.completedProbes,
                    ),
                live =
                    support.buildLiveUiModel(
                        health = health,
                        telemetry = input.telemetry,
                        currentTelemetry = currentTelemetry,
                        nativeEvents = input.nativeEvents,
                        latestSnapshot = latestSnapshot,
                        latestContext = latestContext,
                        eventModels = eventModels,
                    ),
                sessions =
                    support.buildSessionsUiModel(
                        sessions = input.sessions,
                        sessionRows = sessionRows,
                        sessionPathMode = input.sessionPathMode,
                        sessionStatus = input.sessionStatus,
                        sessionSearch = input.sessionSearch,
                        selectedSessionDetail = input.selectedSessionDetail,
                    ),
                approaches =
                    support.buildApproachesUiModel(
                        approachStats = input.approachStats,
                        selectedApproachMode = input.selectedApproachMode,
                        selectedApproachDetail = input.selectedApproachDetail,
                    ),
                events = eventsUi,
                share =
                    support.buildShareUiModel(
                        latestCompletedSession = latestCompletedSession,
                        latestSnapshot = latestSnapshot,
                        latestContext = latestContext,
                        currentTelemetry = currentTelemetry,
                        nativeEvents = input.nativeEvents,
                        latestReport = latestReport,
                        approachStats = input.approachStats,
                        selectedSessionDetail = input.selectedSessionDetail,
                        archiveActionState = input.archiveActionState,
                        exports = input.exports,
                    ),
                selectedSessionDetail = sessionDetailWithVisibility,
                selectedApproachDetail = input.selectedApproachDetail,
                selectedEvent = selectedEvent,
                selectedProbe = input.selectedProbe,
                selectedStrategyProbeCandidate = resolvedSelectedStrategyProbeCandidate,
            )
        }

        fun toSessionDetailUiModel(
            detail: DiagnosticSessionDetail,
            showSensitiveDetails: Boolean,
        ): DiagnosticsSessionDetailUiModel =
            sessionDetailUiMapper.toSessionDetailUiModel(
                detail = detail,
                showSensitiveDetails = showSensitiveDetails,
            )

        fun toApproachDetailUiModel(
            detail: com.poyka.ripdpi.diagnostics.BypassApproachDetail,
        ): DiagnosticsApproachDetailUiModel = support.toApproachDetailUiModel(detail)

        fun toCompletedProbeUiModel(
            target: String,
            outcome: String,
        ): CompletedProbeUiModel =
            CompletedProbeUiModel(target = target, outcome = outcome, tone = support.toneForOutcome(outcome))
    }

internal data class DiagnosticsUiStateInput(
    val profiles: List<DiagnosticProfile>,
    val settings: AppSettings,
    val progress: ScanProgress?,
    val sessions: List<DiagnosticScanSession>,
    val approachStats: List<BypassApproachSummary>,
    val snapshots: List<DiagnosticNetworkSnapshot>,
    val contexts: List<DiagnosticContextSnapshot>,
    val telemetry: List<DiagnosticTelemetrySample>,
    val nativeEvents: List<DiagnosticEvent>,
    val exports: List<DiagnosticExportRecord>,
    val rememberedPolicies: List<DiagnosticsRememberedPolicy>,
    val activeConnectionPolicy: DiagnosticActiveConnectionPolicy?,
    val selectedSectionRequest: DiagnosticsSection,
    val selectedProfileId: String?,
    val selectedApproachMode: DiagnosticsApproachMode,
    val selectedProbe: DiagnosticsProbeResultUiModel?,
    val selectedEventId: String?,
    val sessionPathMode: String?,
    val sessionStatus: String?,
    val sessionSearch: String,
    val eventSource: String?,
    val eventSeverity: String?,
    val eventSearch: String,
    val eventAutoScroll: Boolean,
    val selectedSessionDetail: DiagnosticsSessionDetailUiModel?,
    val selectedStrategyProbeCandidate: DiagnosticsStrategyProbeCandidateDetailUiModel?,
    val selectedApproachDetail: DiagnosticsApproachDetailUiModel?,
    val sensitiveSessionDetailsVisible: Boolean,
    val archiveActionState: ArchiveActionState,
    val scanStartedAt: Long?,
    val completedProbes: List<CompletedProbeUiModel> = emptyList(),
)
