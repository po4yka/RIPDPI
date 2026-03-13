package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsHealth
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

internal data class MetricPalette(
    val container: androidx.compose.ui.graphics.Color,
    val content: androidx.compose.ui.graphics.Color,
)

@Composable
internal fun DiagnosticsSection.label(): String =
    when (this) {
        DiagnosticsSection.Overview -> stringResource(R.string.diagnostics_overview_section)
        DiagnosticsSection.Scan -> stringResource(R.string.diagnostics_scan_section)
        DiagnosticsSection.Live -> stringResource(R.string.diagnostics_monitor_section)
        DiagnosticsSection.Approaches -> stringResource(R.string.diagnostics_approaches_title)
        DiagnosticsSection.Share -> stringResource(R.string.diagnostics_share_section)
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

@Composable
internal fun warningBannerTone(health: DiagnosticsHealth): WarningBannerTone =
    when (health) {
        DiagnosticsHealth.Healthy -> WarningBannerTone.Info
        DiagnosticsHealth.Attention -> WarningBannerTone.Warning
        DiagnosticsHealth.Degraded -> WarningBannerTone.Error
        DiagnosticsHealth.Idle -> WarningBannerTone.Restricted
    }

@Composable
internal fun liveHeroPalette(health: DiagnosticsHealth): MetricPalette {
    val colors = RipDpiThemeTokens.colors
    return when (health) {
        DiagnosticsHealth.Healthy ->
            MetricPalette(
                container = colors.muted,
                content = colors.success,
            )

        DiagnosticsHealth.Attention ->
            MetricPalette(
                container = colors.warningContainer,
                content = colors.warning,
            )

        DiagnosticsHealth.Degraded ->
            MetricPalette(
                container = colors.destructiveContainer,
                content = colors.destructive,
            )

        DiagnosticsHealth.Idle ->
            MetricPalette(
                container = colors.accent,
                content = colors.foreground,
            )
    }
}

@Composable
internal fun metricPalette(tone: DiagnosticsTone): MetricPalette {
    val colors = RipDpiThemeTokens.colors
    return when (tone) {
        DiagnosticsTone.Positive ->
            MetricPalette(
                container = colors.muted,
                content = colors.success,
            )

        DiagnosticsTone.Warning ->
            MetricPalette(
                container = colors.warningContainer,
                content = colors.warning,
            )

        DiagnosticsTone.Negative ->
            MetricPalette(
                container = colors.destructiveContainer,
                content = colors.destructive,
            )

        DiagnosticsTone.Info ->
            MetricPalette(
                container = colors.infoContainer,
                content = colors.info,
            )

        DiagnosticsTone.Neutral ->
            MetricPalette(
                container = colors.inputBackground,
                content = colors.foreground,
            )
    }
}
