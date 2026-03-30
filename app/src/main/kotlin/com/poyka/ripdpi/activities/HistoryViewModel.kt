package com.poyka.ripdpi.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.diagnostics.DiagnosticsBootstrapper
import com.poyka.ripdpi.diagnostics.DiagnosticsHistorySource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel
    @Inject
    internal constructor(
        diagnosticsHistorySource: DiagnosticsHistorySource,
        historyDetailLoader: HistoryDetailLoader,
        private val diagnosticsBootstrapper: DiagnosticsBootstrapper,
        historyUiStateFactory: HistoryUiStateFactory,
    ) : ViewModel() {
        private var initialized = false
        private val selectedSectionRequest = MutableStateFlow(HistorySection.Connections)
        private val connectionFilters = MutableStateFlow(HistoryConnectionFilterState())
        private val diagnosticsFilters = MutableStateFlow(HistoryDiagnosticsFilterState())
        private val eventFilters = MutableStateFlow(HistoryEventFilterState())
        private val detailState = MutableStateFlow(HistoryDetailState())
        private val refreshing = MutableStateFlow(false)

        private val mutations = HistoryMutationRunner(scope = viewModelScope)

        private val connectionActions =
            HistoryConnectionActions(
                mutations = mutations,
                connectionFilters = connectionFilters,
                detailState = detailState,
                loadConnectionDetail = historyDetailLoader::loadConnectionDetail,
            )

        private val diagnosticsActions =
            HistoryDiagnosticsActions(
                mutations = mutations,
                diagnosticsFilters = diagnosticsFilters,
                detailState = detailState,
                loadSessionDetail = historyDetailLoader::loadDiagnosticsDetail,
            )

        private val eventActions =
            HistoryEventActions(
                eventFilters = eventFilters,
                detailState = detailState,
            )

        private val repositorySnapshot =
            combine(
                diagnosticsHistorySource.observeConnectionSessions(),
                diagnosticsHistorySource.observeDiagnosticsSessions(),
                diagnosticsHistorySource.observeNativeEvents(),
            ) { connectionSessions, scanSessions, nativeEvents ->
                HistoryRepositorySnapshot(
                    connectionSessions = connectionSessions,
                    scanSessions = scanSessions,
                    nativeEvents = nativeEvents,
                )
            }

        val uiState: StateFlow<HistoryUiState> =
            combine(
                repositorySnapshot,
                selectedSectionRequest,
                connectionFilters,
                diagnosticsFilters,
                eventFilters,
                detailState,
                refreshing,
            ) { values ->
                historyUiStateFactory
                    .buildUiState(
                        repositorySnapshot = values[0] as HistoryRepositorySnapshot,
                        selectedSection = values[1] as HistorySection,
                        connectionFilters = values[2] as HistoryConnectionFilterState,
                        diagnosticsFilters = values[3] as HistoryDiagnosticsFilterState,
                        eventFilters = values[4] as HistoryEventFilterState,
                        detailState = values[5] as HistoryDetailState,
                    ).copy(isRefreshing = values[6] as Boolean)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = HistoryUiState(),
            )

        fun initialize() {
            if (initialized) {
                return
            }
            initialized = true
            viewModelScope.launch {
                diagnosticsBootstrapper.initialize()
            }
        }

        fun refresh() {
            viewModelScope.launch {
                refreshing.value = true
                diagnosticsBootstrapper.initialize()
                delay(REFRESH_SETTLE_DELAY_MS)
                refreshing.value = false
            }
        }

        fun selectSection(section: HistorySection) {
            selectedSectionRequest.value = section
        }

        fun setConnectionModeFilter(mode: String?) = connectionActions.setModeFilter(mode)

        fun setConnectionStatusFilter(status: String?) = connectionActions.setStatusFilter(status)

        fun setConnectionSearch(query: String) = connectionActions.setSearch(query)

        fun clearConnectionFilters() = connectionActions.clearFilters()

        fun setDiagnosticsPathModeFilter(pathMode: String?) = diagnosticsActions.setPathModeFilter(pathMode)

        fun setDiagnosticsStatusFilter(status: String?) = diagnosticsActions.setStatusFilter(status)

        fun setDiagnosticsSearch(query: String) = diagnosticsActions.setSearch(query)

        fun clearDiagnosticsFilters() = diagnosticsActions.clearFilters()

        fun toggleEventFilter(
            source: String? = null,
            severity: String? = null,
        ) = eventActions.toggleFilter(source, severity)

        fun setEventSearch(query: String) = eventActions.setSearch(query)

        fun clearEventFilters() = eventActions.clearFilters()

        fun setEventAutoScroll(enabled: Boolean) = eventActions.setAutoScroll(enabled)

        fun selectConnection(sessionId: String) = connectionActions.selectConnection(sessionId)

        fun dismissConnectionDetail() = connectionActions.dismissDetail()

        fun selectDiagnosticsSession(sessionId: String) = diagnosticsActions.selectSession(sessionId)

        fun dismissDiagnosticsDetail() = diagnosticsActions.dismissDetail()

        fun selectEvent(eventId: String) = eventActions.selectEvent(eventId)

        fun dismissEventDetail() = eventActions.dismissDetail()

        private companion object {
            const val REFRESH_SETTLE_DELAY_MS = 300L
        }
    }
