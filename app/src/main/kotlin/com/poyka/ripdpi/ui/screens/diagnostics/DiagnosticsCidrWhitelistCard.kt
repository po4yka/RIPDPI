package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsCidrWhitelistState
import com.poyka.ripdpi.activities.DiagnosticsCidrWhitelistToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import java.util.Locale

@Composable
internal fun CidrWhitelistDetectionCard(
    tool: DiagnosticsCidrWhitelistToolUiModel,
    onRun: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    var tracesExpanded by remember(tool.rows) { mutableStateOf(false) }
    RipDpiCard(variant = RipDpiCardVariant.Outlined) {
        StatusIndicator(
            label = tool.state.name.lowercase(Locale.US),
            tone = statusTone(tool.state.tone()),
            pulsing = tool.state.running(),
        )
        androidx.compose.material3.Text(
            text = stringResource(R.string.diagnostics_cidr_whitelist_title),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        androidx.compose.material3.Text(
            text = tool.errorMessage ?: tool.summary,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = if (tool.errorMessage == null) colors.mutedForeground else colors.destructive,
        )
        MetricsRow(metrics = tool.metrics)
        if (tool.rows.isNotEmpty()) {
            val tracesToggleRes =
                if (tracesExpanded) {
                    R.string.diagnostics_cidr_whitelist_hide_traces
                } else {
                    R.string.diagnostics_cidr_whitelist_show_traces
                }
            RipDpiButton(
                text = stringResource(tracesToggleRes),
                enabled = tool.state != DiagnosticsCidrWhitelistState.Running,
                onClick = { tracesExpanded = !tracesExpanded },
                variant = RipDpiButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (tool.rows.isNotEmpty() && tracesExpanded) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                tool.rows.forEach { row ->
                    StatusIndicator(
                        label = "${row.group}: ${row.status}",
                        tone = statusTone(row.tone),
                    )
                    androidx.compose.material3.Text(
                        text = listOf(row.url, row.latency, row.error).filterNotNull().joinToString(" · "),
                        style = RipDpiThemeTokens.type.monoSmall,
                        color = colors.mutedForeground,
                    )
                }
            }
        }
        RipDpiButton(
            text =
                if (tool.state == DiagnosticsCidrWhitelistState.Running) {
                    stringResource(R.string.diagnostics_tool_checking)
                } else {
                    stringResource(R.string.diagnostics_cidr_whitelist_run)
                },
            enabled = tool.state != DiagnosticsCidrWhitelistState.Running,
            onClick = onRun,
            variant = RipDpiButtonVariant.Outline,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun DiagnosticsCidrWhitelistState.tone(): DiagnosticsTone =
    when (this) {
        DiagnosticsCidrWhitelistState.Idle -> DiagnosticsTone.Neutral
        DiagnosticsCidrWhitelistState.Running -> DiagnosticsTone.Info
        DiagnosticsCidrWhitelistState.Complete -> DiagnosticsTone.Positive
        DiagnosticsCidrWhitelistState.Failed -> DiagnosticsTone.Negative
    }

private fun DiagnosticsCidrWhitelistState.running(): Boolean = this == DiagnosticsCidrWhitelistState.Running
