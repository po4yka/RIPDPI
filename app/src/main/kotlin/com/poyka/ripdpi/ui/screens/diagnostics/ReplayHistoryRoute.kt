package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Hilt-injected entry point for the past-replays history screen.
 * Refreshes the in-memory snapshot on every entry so
 * a freshly-completed replay run shows without needing a Flow surface
 * on [com.poyka.ripdpi.diagnostics.replay.ReplayResultStore].
 *
 * @param sessionId identity of this history-viewing session (the nav
 * back-stack entry id). Keying the refresh effect on it keeps the snapshot
 * correct if the same composition instance is reused for another session.
 */
@Composable
fun ReplayHistoryRoute(
    sessionId: String,
    onRunScan: () -> Unit,
    onBack: () -> Unit,
    viewModel: ReplayHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(sessionId) { viewModel.refresh() }
    ReplayHistoryScreen(replays = state, onRunScan = onRunScan, onBack = onBack)
}
