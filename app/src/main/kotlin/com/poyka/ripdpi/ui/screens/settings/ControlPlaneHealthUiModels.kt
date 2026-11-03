package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

enum class ControlPlaneHealthSeverityUiModel {
    Info,
    Warning,
    Error,
}

@Immutable
data class ControlPlaneHealthItemUiModel(
    val label: String,
    val summary: String,
    val severity: ControlPlaneHealthSeverityUiModel,
)

@Immutable
data class ControlPlaneHealthSummaryUiModel(
    val title: String,
    val summary: String,
    val items: ImmutableList<ControlPlaneHealthItemUiModel>,
    val actionLabel: String,
    val severity: ControlPlaneHealthSeverityUiModel,
)
