package com.poyka.ripdpi.activities

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

enum class DiagnosticsCidrWhitelistState {
    Idle,
    Running,
    Complete,
    Failed,
}

@Immutable
data class DiagnosticsCidrWhitelistTraceUiModel(
    val group: String,
    val url: String,
    val status: String,
    val latency: String,
    val error: String?,
    val tone: DiagnosticsTone,
)

@Stable
data class DiagnosticsCidrWhitelistToolUiModel(
    val state: DiagnosticsCidrWhitelistState = DiagnosticsCidrWhitelistState.Idle,
    val summary: String = "",
    val metrics: ImmutableList<DiagnosticsMetricUiModel> = persistentListOf(),
    val rows: ImmutableList<DiagnosticsCidrWhitelistTraceUiModel> = persistentListOf(),
    val errorMessage: String? = null,
)
