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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
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
            pulsing = tool.state.running(),
        )
        Text(
            text = stringResource(R.string.diagnostics_ipv4_whitelist_title),
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
            val resultsToggleRes =
                if (resultsExpanded) {
                    R.string.diagnostics_ipv4_whitelist_hide_results
                } else {
                    R.string.diagnostics_ipv4_whitelist_show_results
                }
            RipDpiButton(
                text = stringResource(resultsToggleRes),
                enabled = !isRunning,
                onClick = { resultsExpanded = !resultsExpanded },
                variant = RipDpiButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (tool.rows.isNotEmpty() && resultsExpanded) {
            Ipv4WhitelistResultsColumn(tool = tool)
        }
        Ipv4WhitelistActionButtons(isRunning = isRunning, onCache = onCache, onCheck = onCheck)
        RipDpiButton(
            text = stringResource(R.string.diagnostics_ipv4_whitelist_save_csv),
            enabled = !isRunning && tool.csv.isNotBlank(),
            onClick = onSaveCsv,
            variant = RipDpiButtonVariant.Outline,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Ipv4WhitelistResultsColumn(tool: DiagnosticsIpv4WhitelistToolUiModel) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
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

@Composable
private fun Ipv4WhitelistActionButtons(
    isRunning: Boolean,
    onCache: () -> Unit,
    onCheck: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RipDpiButton(
            text =
                if (isRunning) {
                    stringResource(R.string.diagnostics_ipv4_whitelist_caching)
                } else {
                    stringResource(R.string.diagnostics_ipv4_whitelist_cache)
                },
            enabled = !isRunning,
            onClick = onCache,
            variant = RipDpiButtonVariant.Outline,
            modifier = Modifier.weight(1f),
        )
        RipDpiButton(
            text =
                if (isRunning) {
                    stringResource(R.string.diagnostics_tool_checking)
                } else {
                    stringResource(R.string.diagnostics_ipv4_whitelist_check_cached)
                },
            enabled = !isRunning,
            onClick = onCheck,
            variant = RipDpiButtonVariant.Outline,
            modifier = Modifier.weight(1f),
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

private fun DiagnosticsIpv4WhitelistState.running(): Boolean = this == DiagnosticsIpv4WhitelistState.Running
