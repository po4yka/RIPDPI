package com.poyka.ripdpi.ui.screens.diagnostics.rkn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsMetricUiModel
import com.poyka.ripdpi.activities.DiagnosticsRknBlockDiagnosisState
import com.poyka.ripdpi.activities.DiagnosticsRknBlockDiagnosisToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsRknBlockTypeUiModel
import com.poyka.ripdpi.activities.DiagnosticsRknTargetUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.screens.diagnostics.statusTone
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import java.util.Locale

@Composable
internal fun RknBlockDiagnosisScreen(
    tool: DiagnosticsRknBlockDiagnosisToolUiModel,
    onRun: () -> Unit,
    onSelfInfoEnabledChange: (Boolean) -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard(variant = RipDpiCardVariant.Outlined) {
        StatusIndicator(
            label = tool.state.name.lowercase(Locale.US),
            tone = statusTone(tool.state.tone()),
            pulsing = tool.state.running(),
        )
        androidx.compose.material3.Text(
            text = tool.headline,
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        androidx.compose.material3.Text(
            text = tool.errorMessage ?: tool.confidenceNote,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = if (tool.errorMessage == null) colors.mutedForeground else colors.destructive,
        )
        androidx.compose.material3.Text(
            text = tool.summary,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = colors.mutedForeground,
        )
        SettingsRow(
            title = stringResource(R.string.rkn_fetch_public_ip_asn_title),
            subtitle =
                if (tool.selfInfoPrivacyOverridden) {
                    stringResource(R.string.diagnostics_rkn_self_info_privacy_suppressed)
                } else {
                    stringResource(R.string.diagnostics_rkn_self_info_subtitle)
                },
            checked = tool.fetchSelfInfoEnabled,
            onCheckedChange = onSelfInfoEnabledChange,
            enabled = tool.state != DiagnosticsRknBlockDiagnosisState.Running && !tool.selfInfoPrivacyOverridden,
        )
        tool.selfInfo?.let { info ->
            SelfInfoCard(info = info)
        }
        RknMetricsRow(metrics = tool.metrics)
        RknBlockTypeRows(blockTypes = tool.blockTypes)
        RknDiagnosisRows(rows = tool.rows)
        RipDpiButton(
            text =
                if (tool.state == DiagnosticsRknBlockDiagnosisState.Running) {
                    stringResource(R.string.diagnostics_tool_checking)
                } else {
                    stringResource(R.string.diagnostics_rkn_run)
                },
            enabled = tool.state != DiagnosticsRknBlockDiagnosisState.Running,
            onClick = onRun,
            variant = RipDpiButtonVariant.Outline,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RknBlockTypeRows(blockTypes: List<DiagnosticsRknBlockTypeUiModel>) {
    val spacing = RipDpiThemeTokens.spacing
    if (blockTypes.isNotEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            blockTypes.forEach { blockType ->
                StatusIndicator(label = "${blockType.label}: ${blockType.count}", tone = statusTone(blockType.tone))
            }
        }
    }
}

@Composable
private fun RknDiagnosisRows(rows: List<DiagnosticsRknTargetUiModel>) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    if (rows.isNotEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            rows.forEach { row ->
                StatusIndicator(label = "${row.group} · ${row.name}: ${row.verdict}", tone = statusTone(row.tone))
                if (row.notes.isNotBlank()) {
                    androidx.compose.material3.Text(
                        text = row.notes,
                        style = RipDpiThemeTokens.type.monoSmall,
                        color = colors.mutedForeground,
                    )
                }
            }
        }
    }
}

@Composable
private fun RknMetricsRow(metrics: List<DiagnosticsMetricUiModel>) {
    val spacing = RipDpiThemeTokens.spacing
    if (metrics.isEmpty()) {
        return
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        metrics.forEach { metric ->
            StatusIndicator(
                label = "${metric.label}: ${metric.value}",
                tone = statusTone(metric.tone),
            )
        }
    }
}

private fun DiagnosticsRknBlockDiagnosisState.tone(): DiagnosticsTone =
    when (this) {
        DiagnosticsRknBlockDiagnosisState.Idle -> DiagnosticsTone.Neutral
        DiagnosticsRknBlockDiagnosisState.Running -> DiagnosticsTone.Info
        DiagnosticsRknBlockDiagnosisState.Complete -> DiagnosticsTone.Positive
        DiagnosticsRknBlockDiagnosisState.Failed -> DiagnosticsTone.Negative
    }

private fun DiagnosticsRknBlockDiagnosisState.running(): Boolean = this == DiagnosticsRknBlockDiagnosisState.Running
