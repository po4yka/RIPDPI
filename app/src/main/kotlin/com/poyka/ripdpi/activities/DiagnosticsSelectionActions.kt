package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.BypassApproachKind
import com.poyka.ripdpi.diagnostics.DiagnosticsScanStartRejectedException
import com.poyka.ripdpi.diagnostics.DiagnosticsScanStartRejectionReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

internal class DiagnosticsSelectionActions(
    private val mutations: DiagnosticsMutationRunner,
    private val selection: MutableStateFlow<SelectionState>,
    private val sessionDetail: MutableStateFlow<SessionDetailState>,
) {
    init {
        // Focus Scan once when a scan starts. A derived "Scan while running" section would ignore
        // tab taps during the scan and move the pager away from the results when it ends.
        mutations.launch {
            diagnosticsTimelineSource.activeScanProgress
                .map { it != null }
                .distinctUntilChanged()
                .filter { it }
                .collect { selectSection(DiagnosticsSection.Scan) }
        }
    }

    // A detail still loading when another is picked, closed, or the section changes is stale: drop its result.
    private var detailRequest = 0

    fun selectSection(section: DiagnosticsSection) {
        // Open details belong to the tab they came from; keeping them would pile up beside the new tab's list.
        if (section != selection.value.selectedSectionRequest) {
            detailRequest++
            sessionDetail.update { SessionDetailState() }
            selection.update {
                it.copy(
                    selectedSectionRequest = section,
                    selectedApproachDetail = null,
                    selectedProbe = null,
                    selectedEventId = null,
                    selectedStrategyProbeCandidate = null,
                )
            }
        }
    }

    fun updateSelectiveMatrixHosts(input: String) {
        if ((
                mutations.diagnosticsTimelineSource.activeScanProgress.value != null ||
                    mutations.diagnosticsScanController.hasActiveHomeRun()
            )
        ) {
            return
        }
        selection.update { it.copy(selectiveMatrixHostsInput = input) }
    }

    fun selectProfile(profileId: String) {
        if ((
                mutations.diagnosticsTimelineSource.activeScanProgress.value != null ||
                    mutations.diagnosticsScanController.hasActiveHomeRun()
            )
        ) {
            return
        }
        mutations.launch {
            if (diagnosticsTimelineSource.activeScanProgress.value != null ||
                diagnosticsScanController.hasActiveHomeRun()
            ) {
                return@launch
            }
            try {
                diagnosticsScanController.setActiveProfile(profileId)
                selection.update { it.copy(selectedProfileId = profileId) }
            } catch (rejected: DiagnosticsScanStartRejectedException) {
                if (rejected.reason != DiagnosticsScanStartRejectionReason.ScanAlreadyActive) throw rejected
            }
        }
    }

    fun selectSession(sessionId: String) {
        val request = ++detailRequest
        mutations.launch {
            loadSessionDetail(
                sessionId = sessionId,
                showSensitiveDetails = false,
                selection = selection,
                sessionDetail = sessionDetail,
                isStale = { request != detailRequest },
            )
            if (request != detailRequest) return@launch
            // A session picked from a list replaces any other open detail, so the wide-window pane shows it.
            selection.update { it.copy(selectedApproachDetail = null, selectedEventId = null, selectedProbe = null) }
        }
    }

    fun selectApproachMode(mode: DiagnosticsApproachMode) {
        detailRequest++
        selection.update { it.copy(selectedApproachMode = mode, selectedApproachDetail = null) }
    }

    fun selectApproach(approachId: String) {
        val request = ++detailRequest
        mutations.launch {
            val detail =
                diagnosticsDetailLoader.loadApproachDetail(
                    kind =
                        when (selection.value.selectedApproachMode) {
                            DiagnosticsApproachMode.Profiles -> BypassApproachKind.Profile
                            DiagnosticsApproachMode.Strategies -> BypassApproachKind.Strategy
                        },
                    id = approachId,
                )
            if (request != detailRequest) return@launch
            sessionDetail.update { SessionDetailState() }
            selection.update {
                it.copy(
                    selectedApproachDetail = detail,
                    selectedEventId = null,
                    selectedProbe = null,
                    selectedStrategyProbeCandidate = null,
                )
            }
        }
    }

    fun dismissSessionDetail() {
        detailRequest++
        sessionDetail.update { SessionDetailState() }
        selection.update { it.copy(selectedProbe = null, selectedStrategyProbeCandidate = null) }
    }

    fun dismissApproachDetail() {
        detailRequest++
        selection.update { it.copy(selectedApproachDetail = null) }
    }

    fun selectEvent(eventId: String) {
        // Keeps the session an event row was opened from, so closing the event returns to it.
        selection.update {
            it.copy(
                selectedEventId = eventId,
                selectedApproachDetail = null,
                selectedProbe = null,
                selectedStrategyProbeCandidate = null,
            )
        }
    }

    fun dismissEventDetail() {
        selection.update { it.copy(selectedEventId = null) }
    }

    fun selectProbe(probe: DiagnosticsProbeResultUiModel) {
        selection.update { it.copy(selectedProbe = probe, selectedStrategyProbeCandidate = null) }
    }

    fun dismissProbeDetail() {
        selection.update { it.copy(selectedProbe = null) }
    }

    fun selectStrategyProbeCandidate(detail: DiagnosticsStrategyProbeCandidateDetailUiModel) {
        selection.update { it.copy(selectedStrategyProbeCandidate = detail, selectedProbe = null) }
    }

    fun dismissStrategyProbeCandidate() {
        selection.update { it.copy(selectedStrategyProbeCandidate = null) }
    }

    fun toggleSensitiveSessionDetails() {
        val nextValue = !sessionDetail.value.sensitiveSessionDetailsVisible
        sessionDetail.update { it.copy(sensitiveSessionDetailsVisible = nextValue) }
        val sessionId =
            sessionDetail.value.selectedSessionDetail
                ?.session
                ?.id ?: return
        // Not a new pick, so the counter stays; a dismiss or another pick during the reload makes it stale.
        val request = detailRequest
        mutations.launch {
            loadSessionDetail(
                sessionId = sessionId,
                showSensitiveDetails = nextValue,
                selection = selection,
                sessionDetail = sessionDetail,
                isStale = { request != detailRequest },
            )
        }
    }
}

private fun toggleValue(
    current: String?,
    next: String?,
): String? = if (current == next) null else next

internal suspend fun DiagnosticsMutationRunner.loadSessionDetail(
    sessionId: String,
    showSensitiveDetails: Boolean,
    selection: MutableStateFlow<SelectionState>,
    sessionDetail: MutableStateFlow<SessionDetailState>,
    isStale: () -> Boolean = { false },
) {
    val detail = diagnosticsDetailLoader.loadSessionDetail(sessionId)
    if (isStale()) return
    selection.update { it.copy(selectedStrategyProbeCandidate = null) }
    sessionDetail.value =
        SessionDetailState(
            selectedSessionDetail = detail,
            sensitiveSessionDetailsVisible = showSensitiveDetails,
        )
}

internal class DiagnosticsFilterActions(
    private val filters: MutableStateFlow<FilterState>,
) {
    fun setSessionSearch(query: String) {
        filters.update { it.copy(sessionSearch = query) }
    }

    fun toggleEventFilter(
        source: String? = null,
        severity: String? = null,
    ) {
        filters.update {
            var updated = it
            if (source != null) {
                updated = updated.copy(eventSourceFilter = toggleValue(it.eventSourceFilter, source))
            }
            if (severity != null) {
                updated = updated.copy(eventSeverityFilter = toggleValue(it.eventSeverityFilter, severity))
            }
            updated
        }
    }

    fun setEventSearch(query: String) {
        filters.update { it.copy(eventSearch = query) }
    }

    fun setEventAutoScroll(enabled: Boolean) {
        filters.update { it.copy(eventAutoScroll = enabled) }
    }
}
