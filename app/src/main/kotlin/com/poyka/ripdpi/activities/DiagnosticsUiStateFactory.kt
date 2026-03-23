package com.poyka.ripdpi.activities

import com.poyka.ripdpi.BuildConfig
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
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsProfileProjection
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection
import com.poyka.ripdpi.proto.AppSettings
import javax.inject.Inject

internal class DiagnosticsUiStateFactory
    @Inject
    constructor(
        private val support: DiagnosticsUiFactorySupport,
        private val sessionDetailUiMapper: DiagnosticsSessionDetailUiMapper,
    ) {
        private var buildSequence = 0L

        fun buildUiState(input: DiagnosticsUiStateInput): DiagnosticsUiState {
            var eventMappingDurationNs = 0L
            var resolveDurationNs = 0L
            var overviewDurationNs = 0L
            var scanDurationNs = 0L
            var liveDurationNs = 0L
            var sessionsDurationNs = 0L
            var approachesDurationNs = 0L
            var eventsDurationNs = 0L
            var shareDurationNs = 0L
            val buildStartedAtNs = System.nanoTime()

            val eventModels =
                measureDuration(record = { duration ->
                    eventMappingDurationNs = duration
                }) {
                    input.nativeEvents.map(support::toEventUiModel)
                }
            val sessionRows = input.sessions.map(support::toSessionRowUiModel)
            val resolvedInput =
                measureDuration(record = { duration ->
                    resolveDurationNs = duration
                }) {
                    resolveUiInput(input, eventModels)
                }
            val eventsState =
                measureDuration(record = { duration ->
                    eventsDurationNs = duration
                }) {
                    buildEventsState(input, eventModels)
                }
            val overview =
                measureDuration(record = { duration ->
                    overviewDurationNs = duration
                }) {
                    buildOverviewState(input, resolvedInput, sessionRows)
                }
            val scan =
                measureDuration(record = { duration ->
                    scanDurationNs = duration
                }) {
                    buildScanState(input, resolvedInput)
                }
            val live =
                measureDuration(record = { duration ->
                    liveDurationNs = duration
                }) {
                    buildLiveState(input)
                }
            val sessions =
                measureDuration(record = { duration ->
                    sessionsDurationNs = duration
                }) {
                    buildSessionsState(input, sessionRows)
                }
            val approaches =
                measureDuration(record = { duration ->
                    approachesDurationNs = duration
                }) {
                    buildApproachesState(input)
                }
            val share =
                measureDuration(record = { duration ->
                    shareDurationNs = duration
                }) {
                    buildShareState(input, resolvedInput)
                }

            return DiagnosticsUiState(
                selectedSection = resolvedInput.selectedSection,
                overview = overview,
                scan = scan,
                live = live,
                sessions = sessions,
                approaches = approaches,
                events = eventsState.model,
                share = share,
                selectedSessionDetail = resolvedInput.sessionDetailWithVisibility,
                selectedApproachDetail = input.selectedApproachDetail,
                selectedEvent = eventsState.selectedEvent,
                selectedProbe = input.selectedProbe,
                selectedStrategyProbeCandidate = resolvedInput.selectedStrategyProbeCandidate,
                performance =
                    if (BuildConfig.DEBUG) {
                        DiagnosticsPerformanceUiModel(
                            buildSequence = ++buildSequence,
                            totalDurationMillis = nanosToMillis(System.nanoTime() - buildStartedAtNs),
                            eventMappingDurationMillis = nanosToMillis(eventMappingDurationNs),
                            resolveDurationMillis = nanosToMillis(resolveDurationNs),
                            overviewDurationMillis = nanosToMillis(overviewDurationNs),
                            scanDurationMillis = nanosToMillis(scanDurationNs),
                            liveDurationMillis = nanosToMillis(liveDurationNs),
                            sessionsDurationMillis = nanosToMillis(sessionsDurationNs),
                            approachesDurationMillis = nanosToMillis(approachesDurationNs),
                            eventsDurationMillis = nanosToMillis(eventsDurationNs),
                            shareDurationMillis = nanosToMillis(shareDurationNs),
                            telemetryCount = input.telemetry.size,
                            nativeEventCount = input.nativeEvents.size,
                            sessionCount = input.sessions.size,
                        )
                    } else {
                        null
                    },
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
            phase: String,
            target: String,
            outcome: String,
            pathMode: ScanPathMode,
            scanKind: ScanKind,
        ): CompletedProbeUiModel =
            CompletedProbeUiModel(
                target = target,
                outcome = outcome,
                tone =
                    when (scanKind) {
                        ScanKind.STRATEGY_PROBE -> strategyProgressTone(outcome)

                        ScanKind.CONNECTIVITY ->
                            phaseToConnectivityProbeType(phase)
                                ?.let { probeType -> support.core.toneForProbeOutcome(probeType, pathMode, outcome) }
                                ?: DiagnosticsTone.Neutral
                    },
            )

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
                latestReportResults =
                    latestProfileReport
                        ?.results
                        ?.mapIndexed { index, result ->
                            support.toProbeResultUiModel(
                                index = index,
                                pathMode = support.parsePathMode(latestProfileSession.pathMode),
                                result = result,
                            )
                        }.orEmpty(),
                latestResolverRecommendation =
                    latestProfileReport?.resolverRecommendation?.let(support::toResolverRecommendationUiModel),
                latestStrategyProbeReport = latestStrategyProbeReport,
                currentTelemetry = input.currentTelemetry,
                health =
                    support.deriveHealth(
                        input.progress,
                        latestCompletedSession,
                        input.currentTelemetry,
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
                activeScanPathMode = input.activeScanPathMode,
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
        ): DiagnosticsLiveUiModel =
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
            latestProfileReport: DiagnosticsSessionProjection?,
        ): DiagnosticsStrategyProbeReportUiModel? =
            latestProfileReport?.strategyProbeReport?.let { report ->
                support.toStrategyProbeReportUiModel(
                    report = report,
                    reportResults = latestProfileReport.results,
                    serviceMode = this?.serviceMode,
                )
            }

        private inline fun <T> measureDuration(
            record: (Long) -> Unit,
            block: () -> T,
        ): T {
            val startedAt = System.nanoTime()
            return block().also { record(System.nanoTime() - startedAt) }
        }

        private fun nanosToMillis(durationNs: Long): Double = durationNs / 1_000_000.0
    }

private data class ResolvedDiagnosticsUiInput(
    val activeProfile: DiagnosticProfile?,
    val activeProfileRequest: DiagnosticsProfileProjection?,
    val selectedProfileUi: DiagnosticsProfileOptionUiModel?,
    val latestSnapshot: DiagnosticsNetworkSnapshotUiModel?,
    val latestContext: DiagnosticContextModel?,
    val latestCompletedSession: DiagnosticScanSession?,
    val latestProfileSession: DiagnosticScanSession?,
    val latestReport: DiagnosticsSessionProjection?,
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
    val currentTelemetry: DiagnosticTelemetrySample?,
    val telemetry: List<DiagnosticTelemetrySample>,
    val nativeEvents: List<DiagnosticEvent>,
    val activeConnectionSession: com.poyka.ripdpi.diagnostics.DiagnosticConnectionSession?,
    val liveSnapshots: List<DiagnosticNetworkSnapshot>,
    val liveContexts: List<DiagnosticContextSnapshot>,
    val liveTelemetry: List<DiagnosticTelemetrySample>,
    val liveNativeEvents: List<DiagnosticEvent>,
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
    val activeScanPathMode: ScanPathMode?,
    val completedProbes: List<CompletedProbeUiModel> = emptyList(),
)

private fun phaseToConnectivityProbeType(phase: String): String? =
    when (phase) {
        "environment" -> "network_environment"
        "dns" -> "dns_integrity"
        "reachability" -> "domain_reachability"
        "quic" -> "quic_reachability"
        "tcp" -> "tcp_fat_header"
        "service" -> "service_reachability"
        "circumvention" -> "circumvention_reachability"
        "telegram" -> "telegram_availability"
        "throughput" -> "throughput_window"
        else -> null
    }

private fun strategyProgressTone(outcome: String): DiagnosticsTone =
    when {
        outcome.equals("success", ignoreCase = true) -> DiagnosticsTone.Positive
        outcome.equals("partial", ignoreCase = true) -> DiagnosticsTone.Warning
        outcome.equals("skipped", ignoreCase = true) || outcome.equals("not_applicable", ignoreCase = true) -> {
            DiagnosticsTone.Neutral
        }
        else -> DiagnosticsTone.Negative
    }

private const val ConnectionSampleArtifactKind = "connection_sample"
