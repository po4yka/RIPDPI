package com.poyka.ripdpi.activities

import androidx.compose.runtime.Stable
import com.poyka.ripdpi.diagnostics.dpi.DpiProbeKind
import com.poyka.ripdpi.diagnostics.dpi.SuiteVerdict
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableSet

enum class DiagnosticsDpiSuiteState {
    Idle,
    Running,
    Complete,
    Cancelled,
    Failed,
}

data class DiagnosticsDpiSuiteProbeRowUiModel(
    val kind: DpiProbeKind,
    val label: String,
    val status: String,
    val detail: String,
    val tone: DiagnosticsTone,
)

@Stable
data class DiagnosticsDpiSuiteToolUiModel(
    val state: DiagnosticsDpiSuiteState = DiagnosticsDpiSuiteState.Idle,
    val summary: String = "Run selected DPI probes as one aggregate suite.",
    val selectedKinds: ImmutableSet<DpiProbeKind> = DpiProbeKind.entries.toImmutableSet(),
    val customDomainsInput: String = "",
    val concurrency: Int = 100,
    val aggregateVerdict: SuiteVerdict? = null,
    val metrics: ImmutableList<DiagnosticsMetricUiModel> = persistentListOf(),
    val rows: ImmutableList<DiagnosticsDpiSuiteProbeRowUiModel> = persistentListOf(),
    val errorMessage: String? = null,
)
