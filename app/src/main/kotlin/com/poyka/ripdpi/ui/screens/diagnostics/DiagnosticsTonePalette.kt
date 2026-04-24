package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsHealth
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.indicators.RipDpiMetricTone
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone

@Composable
internal fun DiagnosticsSection.label(): String =
    when (this) {
        DiagnosticsSection.Dashboard -> stringResource(R.string.diagnostics_dashboard_section)
        DiagnosticsSection.Scan -> stringResource(R.string.diagnostics_scan_section)
        DiagnosticsSection.Tools -> stringResource(R.string.diagnostics_tools_section)
    }

@Composable
internal fun DiagnosticsHealth.displayLabel(): String =
    when (this) {
        DiagnosticsHealth.Healthy -> stringResource(R.string.diagnostics_health_healthy)
        DiagnosticsHealth.Attention -> stringResource(R.string.diagnostics_health_attention)
        DiagnosticsHealth.Degraded -> stringResource(R.string.diagnostics_health_degraded)
        DiagnosticsHealth.Idle -> stringResource(R.string.diagnostics_health_idle)
    }

internal fun DiagnosticsHealth.statusTone(): StatusIndicatorTone =
    when (this) {
        DiagnosticsHealth.Healthy -> StatusIndicatorTone.Active
        DiagnosticsHealth.Attention -> StatusIndicatorTone.Warning
        DiagnosticsHealth.Degraded -> StatusIndicatorTone.Error
        DiagnosticsHealth.Idle -> StatusIndicatorTone.Idle
    }

internal fun statusTone(tone: DiagnosticsTone): StatusIndicatorTone =
    when (tone) {
        DiagnosticsTone.Positive -> StatusIndicatorTone.Active
        DiagnosticsTone.Warning -> StatusIndicatorTone.Warning
        DiagnosticsTone.Negative -> StatusIndicatorTone.Error
        DiagnosticsTone.Neutral, DiagnosticsTone.Info -> StatusIndicatorTone.Idle
    }

internal fun metricTone(tone: DiagnosticsTone): RipDpiMetricTone =
    when (tone) {
        DiagnosticsTone.Positive -> RipDpiMetricTone.Positive
        DiagnosticsTone.Warning -> RipDpiMetricTone.Warning
        DiagnosticsTone.Negative -> RipDpiMetricTone.Negative
        DiagnosticsTone.Info -> RipDpiMetricTone.Info
        DiagnosticsTone.Neutral -> RipDpiMetricTone.Neutral
    }

@Composable
internal fun warningBannerTone(health: DiagnosticsHealth): WarningBannerTone =
    when (health) {
        DiagnosticsHealth.Healthy -> WarningBannerTone.Info
        DiagnosticsHealth.Attention -> WarningBannerTone.Warning
        DiagnosticsHealth.Degraded -> WarningBannerTone.Error
        DiagnosticsHealth.Idle -> WarningBannerTone.Restricted
    }

internal fun liveHeroTone(health: DiagnosticsHealth): RipDpiMetricTone =
    when (health) {
        DiagnosticsHealth.Healthy -> RipDpiMetricTone.Positive
        DiagnosticsHealth.Attention -> RipDpiMetricTone.Warning
        DiagnosticsHealth.Degraded -> RipDpiMetricTone.Negative
        DiagnosticsHealth.Idle -> RipDpiMetricTone.Accent
    }
