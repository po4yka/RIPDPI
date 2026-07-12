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
    val detailRows: ImmutableList<DiagnosticsDpiSuiteProbeDetailUiModel> = persistentListOf(),
)

data class DiagnosticsDpiSuiteProbeDetailUiModel(
    val label: String,
    val detail: String,
    val tone: DiagnosticsTone,
)

@Stable
data class DiagnosticsDpiSuiteToolUiModel(
    val state: DiagnosticsDpiSuiteState = DiagnosticsDpiSuiteState.Idle,
    val summary: String = "",
    val selectedKinds: ImmutableSet<DpiProbeKind> =
        DpiProbeKind.entries
            .filterNot { kind -> kind == DpiProbeKind.ECH_READINESS }
            .toImmutableSet(),
    val customDomainsInput: String = "",
    val concurrency: Int = 100,
    val aggregateVerdict: SuiteVerdict? = null,
    val metrics: ImmutableList<DiagnosticsMetricUiModel> = persistentListOf(),
    val rows: ImmutableList<DiagnosticsDpiSuiteProbeRowUiModel> = persistentListOf(),
    val errorMessage: String? = null,
)
