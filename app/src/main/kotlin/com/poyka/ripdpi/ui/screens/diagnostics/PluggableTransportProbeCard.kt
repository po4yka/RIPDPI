package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsPluggableTransportState
import com.poyka.ripdpi.activities.DiagnosticsPluggableTransportToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import java.util.Locale

@Composable
internal fun PluggableTransportProbeCard(
    tool: DiagnosticsPluggableTransportToolUiModel,
    onRun: () -> Unit,
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
            text = stringResource(R.string.diagnostics_pt_reachability_title),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        androidx.compose.material3.Text(
            text = tool.errorMessage ?: tool.summary,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = if (tool.errorMessage == null) colors.mutedForeground else colors.destructive,
        )
        if (tool.rows.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                tool.rows.forEach { row ->
                    StatusIndicator(
                        label = "${row.label}: ${row.verdict}",
                        tone = statusTone(row.tone),
                    )
                    androidx.compose.material3.Text(
                        text = row.detail,
                        style = RipDpiThemeTokens.type.monoSmall,
                        color = colors.mutedForeground,
                    )
                }
            }
        }
        RipDpiButton(
            text =
                if (tool.state == DiagnosticsPluggableTransportState.Running) {
                    stringResource(R.string.diagnostics_tool_checking)
                } else {
                    stringResource(R.string.diagnostics_pt_reachability_run)
                },
            enabled = tool.state != DiagnosticsPluggableTransportState.Running && !tool.privacyModeEnabled,
            onClick = onRun,
            variant = RipDpiButtonVariant.Outline,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun DiagnosticsPluggableTransportState.tone(): DiagnosticsTone =
    when (this) {
        DiagnosticsPluggableTransportState.Idle -> DiagnosticsTone.Neutral
        DiagnosticsPluggableTransportState.Running -> DiagnosticsTone.Info
        DiagnosticsPluggableTransportState.Complete -> DiagnosticsTone.Positive
        DiagnosticsPluggableTransportState.Disabled -> DiagnosticsTone.Neutral
        DiagnosticsPluggableTransportState.Failed -> DiagnosticsTone.Negative
    }

private fun DiagnosticsPluggableTransportState.running(): Boolean = this == DiagnosticsPluggableTransportState.Running
