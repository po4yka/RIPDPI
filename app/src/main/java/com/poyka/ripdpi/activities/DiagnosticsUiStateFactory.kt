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
        fun buildUiState(
            profiles: List<DiagnosticProfile>,
            settings: AppSettings,
            progress: ScanProgress?,
            sessions: List<DiagnosticScanSession>,
            approachStats: List<BypassApproachSummary>,
            snapshots: List<DiagnosticNetworkSnapshot>,
            contexts: List<DiagnosticContextSnapshot>,
            telemetry: List<DiagnosticTelemetrySample>,
            nativeEvents: List<DiagnosticEvent>,
            exports: List<DiagnosticExportRecord>,
            rememberedPolicies: List<DiagnosticsRememberedPolicy>,
            activeConnectionPolicy: DiagnosticActiveConnectionPolicy?,
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
            scanStartedAt: Long?,
            completedProbes: List<CompletedProbeUiModel> = emptyList(),
        ): DiagnosticsUiState {
            val activeProfile = profiles.firstOrNull { it.id == selectedProfileId } ?: profiles.firstOrNull()
            val activeProfileRequest = activeProfile?.request
            val selectedProfileUi = activeProfile?.let(support::toProfileOptionUiModel)

            val latestSnapshot =
                snapshots.firstOrNull()?.let { support.toNetworkSnapshotUiModel(it, showSensitiveDetails = false) }
            val latestContext =
                (contexts.firstOrNull { it.sessionId == null } ?: contexts.firstOrNull())?.context
            val eventModels = nativeEvents.map(support::toEventUiModel)
            val sessionRows = sessions.map(support::toSessionRowUiModel)

            val latestCompletedSession = sessions.firstOrNull { it.report != null } ?: sessions.firstOrNull()
            val latestProfileSession =
                sessions.firstOrNull { it.profileId == activeProfile?.id && it.report != null }
                    ?: sessions.firstOrNull { it.profileId == activeProfile?.id }
                    ?: latestCompletedSession
            val latestProfileReport = latestProfileSession?.report
            val latestReport = latestCompletedSession?.report
            val latestReportResults = latestProfileReport?.results?.mapIndexed(support::toProbeResultUiModel).orEmpty()
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

            val currentTelemetry = telemetry.firstOrNull()
            val health = support.deriveHealth(progress, latestCompletedSession, currentTelemetry, nativeEvents)
            val warnings =
                (
                    support.buildContextWarnings(latestContext) +
                        eventModels.filter { it.tone == DiagnosticsTone.Negative || it.tone == DiagnosticsTone.Warning }
                ).take(3)
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
                        scanStartedAt = scanStartedAt,
                        completedProbes = completedProbes,
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
