package com.poyka.ripdpi.activities

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

enum class DiagnosticsPluggableTransportState {
    Idle,
    Running,
    Complete,
    Disabled,
    Failed,
}

@Immutable
data class DiagnosticsPluggableTransportRowUiModel(
    val label: String,
    val verdict: String,
    val detail: String,
    val tone: DiagnosticsTone,
)

@Stable
data class DiagnosticsPluggableTransportToolUiModel(
    val state: DiagnosticsPluggableTransportState = DiagnosticsPluggableTransportState.Idle,
    val summary: String = "Check public PT endpoint reachability for obfs4, Snowflake, and meek.",
    val rows: ImmutableList<DiagnosticsPluggableTransportRowUiModel> = persistentListOf(),
    val errorMessage: String? = null,
    val privacyModeEnabled: Boolean = false,
)
