package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.activities.DiagnosticsIpv4WhitelistState
import com.poyka.ripdpi.activities.DiagnosticsIpv4WhitelistToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import java.util.Locale

@Composable
internal fun Ipv4WhitelistSubnetDiscoveryCard(
    tool: DiagnosticsIpv4WhitelistToolUiModel,
    onCache: () -> Unit,
    onCheck: () -> Unit,
    onSaveCsv: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val isRunning = tool.state == DiagnosticsIpv4WhitelistState.Running
    var resultsExpanded by remember(tool.rows) { mutableStateOf(false) }

    RipDpiCard(variant = RipDpiCardVariant.Outlined) {
        StatusIndicator(
            label = tool.state.name.lowercase(Locale.US),
            tone = statusTone(tool.state.tone()),
        )
        Text(
            text = "IPv4 whitelist subnet discovery",
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(
            text = tool.errorMessage ?: tool.summary,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = if (tool.errorMessage == null) colors.mutedForeground else colors.destructive,
        )
        MetricsRow(metrics = tool.metrics)
        if (tool.rows.isNotEmpty()) {
            RipDpiButton(
                text = if (resultsExpanded) "Hide subnet results" else "Show subnet results",
                enabled = !isRunning,
                onClick = { resultsExpanded = !resultsExpanded },
                variant = RipDpiButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (tool.rows.isNotEmpty() && resultsExpanded) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                tool.rows.forEach { row ->
                    StatusIndicator(
                        label = "${row.provider}: ${row.cidr}",
                        tone = statusTone(row.tone),
                    )
                    Text(
                        text = "alive ${row.alive} · ${row.verdict}",
                        style = RipDpiThemeTokens.type.monoSmall,
                        color = colors.mutedForeground,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RipDpiButton(
                text = if (isRunning) "Caching..." else "Cache subnets",
                enabled = !isRunning,
                onClick = onCache,
                variant = RipDpiButtonVariant.Outline,
                modifier = Modifier.weight(1f),
            )
            RipDpiButton(
                text = if (isRunning) "Checking..." else "Check cached",
                enabled = !isRunning,
                onClick = onCheck,
                variant = RipDpiButtonVariant.Outline,
                modifier = Modifier.weight(1f),
            )
        }
        RipDpiButton(
            text = "Save CSV",
            enabled = !isRunning && tool.csv.isNotBlank(),
            onClick = onSaveCsv,
            variant = RipDpiButtonVariant.Outline,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun DiagnosticsIpv4WhitelistState.tone(): DiagnosticsTone =
    when (this) {
        DiagnosticsIpv4WhitelistState.Idle -> DiagnosticsTone.Neutral
        DiagnosticsIpv4WhitelistState.Running -> DiagnosticsTone.Info
        DiagnosticsIpv4WhitelistState.Complete -> DiagnosticsTone.Positive
        DiagnosticsIpv4WhitelistState.Failed -> DiagnosticsTone.Negative
    }
