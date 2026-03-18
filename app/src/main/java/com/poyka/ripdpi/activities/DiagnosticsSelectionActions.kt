package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.BypassApproachKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class DiagnosticsSelectionActions(
    private val mutations: DiagnosticsMutationRunner,
    private val selection: MutableStateFlow<SelectionState>,
    private val sessionDetail: MutableStateFlow<SessionDetailState>,
    private val filters: MutableStateFlow<FilterState>,
) {
    fun selectSection(section: DiagnosticsSection) {
        selection.update { it.copy(selectedSectionRequest = section) }
    }

    fun selectProfile(profileId: String) {
        selection.update { it.copy(selectedProfileId = profileId) }
        mutations.launch {
            diagnosticsManager.setActiveProfile(profileId)
        }
    }

    fun selectSession(sessionId: String) {
        mutations.launch {
            loadSessionDetail(sessionId, showSensitiveDetails = false)
        }
    }

    fun selectApproachMode(mode: DiagnosticsApproachMode) {
        selection.update { it.copy(selectedApproachMode = mode, selectedApproachDetail = null) }
    }

    fun selectApproach(approachId: String) {
        mutations.launch {
            val detail =
                diagnosticsManager.loadApproachDetail(
                    kind =
                        when (selection.value.selectedApproachMode) {
                            DiagnosticsApproachMode.Profiles -> BypassApproachKind.Profile
                            DiagnosticsApproachMode.Strategies -> BypassApproachKind.Strategy
                        },
                    id = approachId,
                )
            selection.update { it.copy(selectedApproachDetail = uiStateFactory.toApproachDetailUiModel(detail)) }
        }
    }

    fun dismissSessionDetail() {
        sessionDetail.update { SessionDetailState() }
        selection.update { it.copy(selectedProbe = null, selectedStrategyProbeCandidate = null) }
    }

    fun dismissApproachDetail() {
        selection.update { it.copy(selectedApproachDetail = null) }
    }

    fun selectEvent(eventId: String) {
        selection.update { it.copy(selectedEventId = eventId) }
    }

    fun dismissEventDetail() {
        selection.update { it.copy(selectedEventId = null) }
    }

    fun selectProbe(probe: DiagnosticsProbeResultUiModel) {
        selection.update { it.copy(selectedProbe = probe) }
    }

    fun dismissProbeDetail() {
        selection.update { it.copy(selectedProbe = null) }
    }

    fun selectStrategyProbeCandidate(detail: DiagnosticsStrategyProbeCandidateDetailUiModel) {
        selection.update { it.copy(selectedStrategyProbeCandidate = detail) }
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
        mutations.launch {
            loadSessionDetail(sessionId, showSensitiveDetails = nextValue)
        }
    }

    fun setSessionPathModeFilter(pathMode: String?) {
        filters.update { it.copy(sessionPathModeFilter = toggleValue(it.sessionPathModeFilter, pathMode)) }
    }

    fun setSessionStatusFilter(status: String?) {
        filters.update { it.copy(sessionStatusFilter = toggleValue(it.sessionStatusFilter, status)) }
    }

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

    internal suspend fun DiagnosticsMutationRunner.loadSessionDetail(
        sessionId: String,
        showSensitiveDetails: Boolean,
    ) {
        val detail = diagnosticsManager.loadSessionDetail(sessionId)
        selection.update { it.copy(selectedStrategyProbeCandidate = null) }
        sessionDetail.value =
            SessionDetailState(
                selectedSessionDetail =
                    uiStateFactory.toSessionDetailUiModel(
                        detail = detail,
                        showSensitiveDetails = showSensitiveDetails,
                    ),
                sensitiveSessionDetailsVisible = showSensitiveDetails,
            )
    }
}

private fun toggleValue(
    current: String?,
    next: String?,
): String? = if (current == next) null else next
