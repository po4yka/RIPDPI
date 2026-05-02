package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.BypassApproachKind
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import kotlinx.collections.immutable.toImmutableList

internal fun DiagnosticsUiFactorySupport.buildApproachesUiModel(
    approachStats: List<BypassApproachSummary>,
    selectedApproachMode: DiagnosticsApproachMode,
    selectedApproachDetail: DiagnosticsApproachDetailUiModel?,
): DiagnosticsApproachesUiModel {
    val selectedApproachKind =
        when (selectedApproachMode) {
            DiagnosticsApproachMode.Profiles -> BypassApproachKind.Profile
            DiagnosticsApproachMode.Strategies -> BypassApproachKind.Strategy
        }
    val rows =
        approachStats
            .filter { it.approachId.kind == selectedApproachKind }
            .map { summary -> toApproachRowUiModel(summary, selectedApproachMode) }
    return DiagnosticsApproachesUiModel(
        selectedMode = selectedApproachMode,
        rows = rows.toImmutableList(),
        focusedApproachId = selectedApproachDetail?.approach?.id,
    )
}
