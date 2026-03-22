package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.DiagnosticActiveConnectionPolicy
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
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
import com.poyka.ripdpi.diagnostics.ScanReport
import com.poyka.ripdpi.diagnostics.ScanRequest
import com.poyka.ripdpi.proto.AppSettings
import javax.inject.Inject

internal class DiagnosticsUiStateFactory
    @Inject
    constructor(
        private val support: DiagnosticsUiFactorySupport,
        private val sessionDetailUiMapper: DiagnosticsSessionDetailUiMapper,
    ) {
        fun buildUiState(input: DiagnosticsUiStateInput): DiagnosticsUiState {
            val eventModels = input.nativeEvents.map(support::toEventUiModel)
            val sessionRows = input.sessions.map(support::toSessionRowUiModel)
            val resolvedInput = resolveUiInput(input, eventModels)
            val eventsState = buildEventsState(input, eventModels)
            return buildUiState(
                input = input,
                resolvedInput = resolvedInput,
                sessionRows = sessionRows,
                eventModels = eventModels,
                eventsState = eventsState,
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

        private fun resolveUiInput(
            input: DiagnosticsUiStateInput,
            eventModels: List<DiagnosticsEventUiModel>,
        ): ResolvedDiagnosticsUiInput {
            val activeProfile =
                input.profiles.firstOrNull { it.id == input.selectedProfileId }
                    ?: input.profiles.firstOrNull()
            val latestSnapshot =
                input.snapshots
                    .firstOrNull()
                    ?.let { support.toNetworkSnapshotUiModel(it, showSensitiveDetails = false) }
            val latestContext =
                (input.contexts.firstOrNull { it.sessionId == null } ?: input.contexts.firstOrNull())?.context
            val latestCompletedSession = input.sessions.firstCompletedOrLatest()
            val latestProfileSession = input.sessions.latestSessionForProfile(activeProfile?.id, latestCompletedSession)
            val latestProfileReport = latestProfileSession?.report
            val latestStrategyProbeReport = latestProfileSession.toStrategyProbeReport(latestProfileReport)
            val sessionDetailWithVisibility =
                input.selectedSessionDetail?.copy(
                    sensitiveDetailsVisible = input.sensitiveSessionDetailsVisible,
                )
            return ResolvedDiagnosticsUiInput(
                activeProfile = activeProfile,
                activeProfileRequest = activeProfile?.request,
                selectedProfileUi = activeProfile?.let(support::toProfileOptionUiModel),
                latestSnapshot = latestSnapshot,
                latestContext = latestContext,
                latestCompletedSession = latestCompletedSession,
                latestProfileSession = latestProfileSession,
                latestReport = latestCompletedSession?.report,
                latestReportResults = latestProfileReport?.results?.mapIndexed(support::toProbeResultUiModel).orEmpty(),
                latestResolverRecommendation =
                    latestProfileReport?.resolverRecommendation?.let(support::toResolverRecommendationUiModel),
                latestStrategyProbeReport = latestStrategyProbeReport,
                currentTelemetry = input.telemetry.firstOrNull(),
                health =
                    support.deriveHealth(
                        input.progress,
                        latestCompletedSession,
                        input.telemetry.firstOrNull(),
                        input.nativeEvents,
                    ),
                warnings =
                    buildWarnings(
                        latestContext = latestContext,
                        eventModels = eventModels,
                    ),
                rememberedNetworkRows =
                    input.rememberedPolicies.map { policy ->
                        support.toRememberedNetworkUiModel(policy, input.activeConnectionPolicy)
                    },
                selectedSection =
                    if (input.progress != null) {
                        DiagnosticsSection.Scan
                    } else {
                        input.selectedSectionRequest
                    },
                sessionDetailWithVisibility = sessionDetailWithVisibility,
                selectedStrategyProbeCandidate =
                    resolveSelectedStrategyProbeCandidate(
                        candidate = input.selectedStrategyProbeCandidate,
                        sessionDetailWithVisibility = sessionDetailWithVisibility,
                        latestStrategyProbeReport = latestStrategyProbeReport,
                    ),
            )
        }

        private fun buildUiState(
            input: DiagnosticsUiStateInput,
            resolvedInput: ResolvedDiagnosticsUiInput,
            sessionRows: List<DiagnosticsSessionRowUiModel>,
            eventModels: List<DiagnosticsEventUiModel>,
            eventsState: DiagnosticsEventsState,
        ): DiagnosticsUiState =
            DiagnosticsUiState(
                selectedSection = resolvedInput.selectedSection,
                overview = buildOverviewState(input, resolvedInput, sessionRows),
                scan = buildScanState(input, resolvedInput),
                live = buildLiveState(input, resolvedInput, eventModels),
                sessions = buildSessionsState(input, sessionRows),
                approaches = buildApproachesState(input),
                events = eventsState.model,
                share = buildShareState(input, resolvedInput),
                selectedSessionDetail = resolvedInput.sessionDetailWithVisibility,
                selectedApproachDetail = input.selectedApproachDetail,
                selectedEvent = eventsState.selectedEvent,
                selectedProbe = input.selectedProbe,
                selectedStrategyProbeCandidate = resolvedInput.selectedStrategyProbeCandidate,
            )

        private fun buildOverviewState(
            input: DiagnosticsUiStateInput,
            resolvedInput: ResolvedDiagnosticsUiInput,
            sessionRows: List<DiagnosticsSessionRowUiModel>,
        ): DiagnosticsOverviewUiModel =
            support.buildOverviewUiModel(
                health = resolvedInput.health,
                progress = input.progress,
                latestSession = resolvedInput.latestCompletedSession,
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

        private fun buildScanState(
            input: DiagnosticsUiStateInput,
            resolvedInput: ResolvedDiagnosticsUiInput,
        ): DiagnosticsScanUiModel =
            support.buildScanUiModel(
                profiles = input.profiles,
                activeProfile = resolvedInput.activeProfile,
                activeProfileRequest = resolvedInput.activeProfileRequest,
                latestProfileSession = resolvedInput.latestProfileSession,
                latestReportResults = resolvedInput.latestReportResults,
                latestResolverRecommendation = resolvedInput.latestResolverRecommendation,
                latestStrategyProbeReport = resolvedInput.latestStrategyProbeReport,
                progress = input.progress,
                rawArgsEnabled = input.settings.enableCmdSettings,
                scanStartedAt = input.scanStartedAt,
                completedProbes = input.completedProbes,
            )

        private fun buildLiveState(
            input: DiagnosticsUiStateInput,
            resolvedInput: ResolvedDiagnosticsUiInput,
            eventModels: List<DiagnosticsEventUiModel>,
        ): DiagnosticsLiveUiModel =
            support.buildLiveUiModel(
                health = resolvedInput.health,
                telemetry = input.telemetry,
                currentTelemetry = resolvedInput.currentTelemetry,
                nativeEvents = input.nativeEvents,
                latestSnapshot = resolvedInput.latestSnapshot,
                latestContext = resolvedInput.latestContext,
                eventModels = eventModels,
            )

        private fun buildSessionsState(
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

        private fun buildApproachesState(input: DiagnosticsUiStateInput): DiagnosticsApproachesUiModel =
            support.buildApproachesUiModel(
                approachStats = input.approachStats,
                selectedApproachMode = input.selectedApproachMode,
                selectedApproachDetail = input.selectedApproachDetail,
            )

        private fun buildShareState(
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

        private fun buildEventsState(
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

        private fun buildWarnings(
            latestContext: DiagnosticContextModel?,
            eventModels: List<DiagnosticsEventUiModel>,
        ): List<DiagnosticsEventUiModel> =
            (
                support.buildContextWarnings(latestContext) +
                    eventModels.filter { it.tone == DiagnosticsTone.Negative || it.tone == DiagnosticsTone.Warning }
            ).take(3)

        private fun resolveSelectedStrategyProbeCandidate(
            candidate: DiagnosticsStrategyProbeCandidateDetailUiModel?,
            sessionDetailWithVisibility: DiagnosticsSessionDetailUiModel?,
            latestStrategyProbeReport: DiagnosticsStrategyProbeReportUiModel?,
        ): DiagnosticsStrategyProbeCandidateDetailUiModel? =
            candidate?.let { selected ->
                sessionDetailWithVisibility
                    ?.strategyProbeReport
                    ?.candidateDetails
                    ?.get(selected.id)
                    ?: latestStrategyProbeReport?.candidateDetails?.get(selected.id)
                    ?: selected
            }

        private fun List<DiagnosticScanSession>.firstCompletedOrLatest(): DiagnosticScanSession? =
            firstOrNull { it.report != null } ?: firstOrNull()

        private fun List<DiagnosticScanSession>.latestSessionForProfile(
            profileId: String?,
            fallbackSession: DiagnosticScanSession?,
        ): DiagnosticScanSession? =
            firstOrNull { it.profileId == profileId && it.report != null }
                ?: firstOrNull { it.profileId == profileId }
                ?: fallbackSession

        private fun DiagnosticScanSession?.toStrategyProbeReport(
            latestProfileReport: ScanReport?,
        ): DiagnosticsStrategyProbeReportUiModel? =
            latestProfileReport?.strategyProbeReport?.let { report ->
                support.toStrategyProbeReportUiModel(
                    report = report,
                    reportResults = latestProfileReport.results,
                    serviceMode = this?.serviceMode,
                )
            }
    }

private data class ResolvedDiagnosticsUiInput(
    val activeProfile: DiagnosticProfile?,
    val activeProfileRequest: ScanRequest?,
    val selectedProfileUi: DiagnosticsProfileOptionUiModel?,
    val latestSnapshot: DiagnosticsNetworkSnapshotUiModel?,
    val latestContext: DiagnosticContextModel?,
    val latestCompletedSession: DiagnosticScanSession?,
    val latestProfileSession: DiagnosticScanSession?,
    val latestReport: ScanReport?,
    val latestReportResults: List<DiagnosticsProbeResultUiModel>,
    val latestResolverRecommendation: DiagnosticsResolverRecommendationUiModel?,
    val latestStrategyProbeReport: DiagnosticsStrategyProbeReportUiModel?,
    val currentTelemetry: DiagnosticTelemetrySample?,
    val health: DiagnosticsHealth,
    val warnings: List<DiagnosticsEventUiModel>,
    val rememberedNetworkRows: List<DiagnosticsRememberedNetworkUiModel>,
    val selectedSection: DiagnosticsSection,
    val sessionDetailWithVisibility: DiagnosticsSessionDetailUiModel?,
    val selectedStrategyProbeCandidate: DiagnosticsStrategyProbeCandidateDetailUiModel?,
)

private data class DiagnosticsEventsState(
    val model: DiagnosticsEventsUiModel,
    val selectedEvent: DiagnosticsEventUiModel?,
)

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
