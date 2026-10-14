package com.poyka.ripdpi.activities

import androidx.annotation.StringRes
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
    @param:StringRes val verdictResId: Int? = null,
    @param:StringRes val detailResId: Int? = null,
)

@Stable
data class DiagnosticsPluggableTransportToolUiModel(
    val state: DiagnosticsPluggableTransportState = DiagnosticsPluggableTransportState.Idle,
    val summary: String = "",
    val rows: ImmutableList<DiagnosticsPluggableTransportRowUiModel> = persistentListOf(),
    val errorMessage: String? = null,
    val privacyModeEnabled: Boolean = false,
    @param:StringRes val summaryResId: Int? = null,
)
