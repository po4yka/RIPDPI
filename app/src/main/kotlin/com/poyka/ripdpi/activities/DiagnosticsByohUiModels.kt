package com.poyka.ripdpi.activities

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

enum class DiagnosticsByohCompatibilityState {
    Idle,
    Running,
    Complete,
    Failed,
}

@Immutable
data class DiagnosticsByohDomainUiModel(
    val domain: String,
    val verdict: String,
    val bytesReceived: String,
    val tone: DiagnosticsTone,
)

@Stable
data class DiagnosticsByohCompatibilityToolUiModel(
    val state: DiagnosticsByohCompatibilityState = DiagnosticsByohCompatibilityState.Idle,
    val summary: String = "Advanced - requires a user-controlled HTTPS server.",
    val requirementsSummary: String =
        "Configure a host you control to answer HTTPS on the destination IP and serve at least 128 KiB.",
    val dstIp: String = "",
    val urlPath: String = "/1MB.bin",
    val useSyntheticFixture: Boolean = true,
    val metrics: ImmutableList<DiagnosticsMetricUiModel> = persistentListOf(),
    val rows: ImmutableList<DiagnosticsByohDomainUiModel> = persistentListOf(),
    val csvExport: String = "",
    val errorMessage: String? = null,
)
