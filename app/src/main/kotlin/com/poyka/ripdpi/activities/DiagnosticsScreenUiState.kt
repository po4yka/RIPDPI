package com.poyka.ripdpi.activities

import androidx.compose.runtime.Immutable

/** Only the sections and dialogs rendered by Diagnostics; history stays with its owning state. */
@Immutable
data class DiagnosticsScreenUiState(
    val selectedSection: DiagnosticsSection = DiagnosticsSection.Dashboard,
    val overview: DiagnosticsOverviewUiModel = DiagnosticsOverviewUiModel(),
    val scan: DiagnosticsScanUiModel = DiagnosticsScanUiModel(),
    val live: DiagnosticsLiveUiModel = DiagnosticsLiveUiModel(),
    val approaches: DiagnosticsApproachesUiModel = DiagnosticsApproachesUiModel(),
    val share: DiagnosticsShareUiModel = DiagnosticsShareUiModel(),
    val selectedSessionDetail: DiagnosticsSessionDetailUiModel? = null,
    val selectedApproachDetail: DiagnosticsApproachDetailUiModel? = null,
    val selectedEvent: DiagnosticsEventUiModel? = null,
    val selectedProbe: DiagnosticsProbeResultUiModel? = null,
    val selectedStrategyProbeCandidate: DiagnosticsStrategyProbeCandidateDetailUiModel? = null,
    val performance: DiagnosticsPerformanceUiModel? = null,
    val uiPersona: String = "advanced",
    val homeDiagnostics: HomeDiagnosticsUiState = HomeDiagnosticsUiState(),
)

fun DiagnosticsUiState.toScreenUiState(): DiagnosticsScreenUiState =
    DiagnosticsScreenUiState(
        selectedSection = selectedSection,
        overview = overview,
        scan = scan,
        live = live,
        approaches = approaches,
        share = share,
        selectedSessionDetail = selectedSessionDetail,
        selectedApproachDetail = selectedApproachDetail,
        selectedEvent = selectedEvent,
        selectedProbe = selectedProbe,
        selectedStrategyProbeCandidate = selectedStrategyProbeCandidate,
        performance = performance,
        uiPersona = uiPersona,
    )

/** Value comparisons happen in the single background projection collector, never in composition. */
internal fun DiagnosticsUiState.reuseUnchangedSections(previous: DiagnosticsUiState?): DiagnosticsUiState {
    previous ?: return this
    return copy(
        overview = reuse(previous.overview, overview),
        scan = reuse(previous.scan, scan),
        live = reuse(previous.live, live),
        sessions = reuse(previous.sessions, sessions),
        approaches = reuse(previous.approaches, approaches),
        events = reuse(previous.events, events),
        share = reuse(previous.share, share),
        selectedSessionDetail = reuse(previous.selectedSessionDetail, selectedSessionDetail),
        selectedApproachDetail = reuse(previous.selectedApproachDetail, selectedApproachDetail),
        selectedEvent = reuse(previous.selectedEvent, selectedEvent),
        selectedProbe = reuse(previous.selectedProbe, selectedProbe),
        selectedStrategyProbeCandidate = reuse(previous.selectedStrategyProbeCandidate, selectedStrategyProbeCandidate),
    )
}

private fun <T> reuse(
    previous: T,
    current: T,
): T = if (previous == current) previous else current
