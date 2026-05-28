package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.lifecycle.ViewModel
import com.poyka.ripdpi.diagnostics.replay.ReplayProbeResult
import com.poyka.ripdpi.diagnostics.replay.ReplayResultStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * Surfaces the [ReplayResultStore] ring buffer to the past-replays
 * history screen. The store is filled by [ReplayFailureViewModel] on
 * terminal events.
 *
 * State is refreshed on every [refresh] call — the store has no
 * observable surface (it's a synchronized snapshot), so we re-read on
 * each composition entry. This is cheap (in-memory list copy of at
 * most [ReplayResultStore.MaxEntries]) and avoids adding a Flow
 * surface on the store solely for this screen.
 */
@HiltViewModel
class ReplayHistoryViewModel
    @Inject
    constructor(
        private val replayResultStore: ReplayResultStore,
    ) : ViewModel() {
        private val mutableUiState =
            MutableStateFlow<ImmutableList<ReplayProbeResult>>(persistentListOf())
        val uiState: StateFlow<ImmutableList<ReplayProbeResult>> = mutableUiState.asStateFlow()

        init {
            refresh()
        }

        fun refresh() {
            mutableUiState.value = replayResultStore.recent().toPersistentList()
        }
    }
