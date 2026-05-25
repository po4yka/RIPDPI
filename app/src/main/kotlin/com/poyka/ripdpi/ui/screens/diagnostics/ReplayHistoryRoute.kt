package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Hilt-injected entry point for the past-replays history screen
 * (G010 P4.8). Refreshes the in-memory snapshot on every entry so
 * a freshly-completed replay run shows without needing a Flow surface
 * on [com.poyka.ripdpi.diagnostics.replay.ReplayResultStore].
 */
@Composable
fun ReplayHistoryRoute(viewModel: ReplayHistoryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    ReplayHistoryScreen(replays = state)
}
