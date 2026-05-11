package com.poyka.ripdpi.activities

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

enum class DiagnosticsIpv4WhitelistState {
    Idle,
    Running,
    Complete,
    Failed,
}

@Immutable
data class DiagnosticsIpv4WhitelistSubnetUiModel(
    val provider: String,
    val cidr: String,
    val alive: String,
    val verdict: String,
    val tone: DiagnosticsTone,
)

@Stable
data class DiagnosticsIpv4WhitelistToolUiModel(
    val state: DiagnosticsIpv4WhitelistState = DiagnosticsIpv4WhitelistState.Idle,
    val summary: String = "Fetch provider subnets and sample cached /24 ranges for whitelist behavior.",
    val metrics: ImmutableList<DiagnosticsMetricUiModel> = persistentListOf(),
    val rows: ImmutableList<DiagnosticsIpv4WhitelistSubnetUiModel> = persistentListOf(),
    val csv: String = "",
    val errorMessage: String? = null,
)
