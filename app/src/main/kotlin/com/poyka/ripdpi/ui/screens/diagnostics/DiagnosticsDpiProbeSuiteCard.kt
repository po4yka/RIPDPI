package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsDpiSuiteProbeDetailUiModel
import com.poyka.ripdpi.activities.DiagnosticsDpiSuiteState
import com.poyka.ripdpi.activities.DiagnosticsDpiSuiteToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.diagnostics.dpi.DpiProbeKind
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import java.util.Locale

private const val ConcurrencyStep = 10

@Composable
internal fun DpiProbeSuiteCard(
    tool: DiagnosticsDpiSuiteToolUiModel,
    onProbeEnabledChange: (DpiProbeKind, Boolean) -> Unit,
    onCustomDomainsChange: (String) -> Unit,
    onConcurrencyDelta: (Int) -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val running = tool.state == DiagnosticsDpiSuiteState.Running

    RipDpiCard(variant = RipDpiCardVariant.Outlined) {
        StatusIndicator(
            label = tool.state.name.lowercase(Locale.US),
            tone = statusTone(tool.state.tone()),
        )
        Text(
            text = stringResource(R.string.diagnostics_dpi_suite_title),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(
            text = tool.errorMessage ?: tool.summary,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = if (tool.errorMessage == null) colors.mutedForeground else colors.destructive,
        )
        MetricsRow(metrics = tool.metrics)
        DpiProbeSuiteRows(tool = tool)
        DpiProbeSuiteKindToggles(tool = tool, running = running, onProbeEnabledChange = onProbeEnabledChange)
        RipDpiTextField(
            value = tool.customDomainsInput,
            onValueChange = onCustomDomainsChange,
            decoration =
                RipDpiTextFieldDecoration(
                    label = stringResource(R.string.diagnostics_dpi_suite_custom_domains_label),
                    placeholder = "vk.com, youtube.com",
                ),
            behavior = RipDpiTextFieldBehavior(enabled = !running),
        )
        DpiProbeSuiteConcurrencyRow(tool = tool, running = running, onConcurrencyDelta = onConcurrencyDelta)
        RipDpiButton(
            text =
                if (running) {
                    stringResource(R.string.diagnostics_dpi_suite_cancel)
                } else {
                    stringResource(R.string.diagnostics_dpi_suite_run)
                },
            enabled = tool.selectedKinds.isNotEmpty(),
            onClick = if (running) onCancel else onRun,
            variant = if (running) RipDpiButtonVariant.Outline else RipDpiButtonVariant.Primary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DpiProbeSuiteRows(tool: DiagnosticsDpiSuiteToolUiModel) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    if (tool.rows.isNotEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            tool.rows.forEach { row ->
                StatusIndicator(label = "${row.label}: ${row.status}", tone = statusTone(row.tone))
                Text(text = row.detail, style = RipDpiThemeTokens.type.monoSmall, color = colors.mutedForeground)
                DpiProbeSuiteDetailRows(row.detailRows)
            }
        }
    }
}

@Composable
private fun DpiProbeSuiteDetailRows(detailRows: List<DiagnosticsDpiSuiteProbeDetailUiModel>) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    if (detailRows.isNotEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            detailRows.forEach { detailRow ->
                StatusIndicator(label = detailRow.label, tone = statusTone(detailRow.tone))
                Text(text = detailRow.detail, style = RipDpiThemeTokens.type.monoSmall, color = colors.mutedForeground)
            }
        }
    }
}

@Composable
private fun DpiProbeSuiteKindToggles(
    tool: DiagnosticsDpiSuiteToolUiModel,
    running: Boolean,
    onProbeEnabledChange: (DpiProbeKind, Boolean) -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        DpiProbeKind.entries.forEach { kind ->
            RipDpiSwitch(
                checked = kind in tool.selectedKinds,
                onCheckedChange = { checked -> onProbeEnabledChange(kind, checked) },
                enabled = !running,
                label = kind.label(),
            )
        }
    }
}

@Composable
private fun DpiProbeSuiteConcurrencyRow(
    tool: DiagnosticsDpiSuiteToolUiModel,
    running: Boolean,
    onConcurrencyDelta: (Int) -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RipDpiButton(
            text = "-",
            onClick = { onConcurrencyDelta(-ConcurrencyStep) },
            enabled = !running,
            variant = RipDpiButtonVariant.Outline,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.diagnostics_dpi_suite_concurrency, tool.concurrency),
            style = RipDpiThemeTokens.type.secondaryBody,
            color = colors.foreground,
            modifier = Modifier.weight(2f),
        )
        RipDpiButton(
            text = "+",
            onClick = { onConcurrencyDelta(ConcurrencyStep) },
            enabled = !running,
            variant = RipDpiButtonVariant.Outline,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DpiProbeKind.label(): String =
    stringResource(
        when (this) {
            DpiProbeKind.DNS_INTEGRITY -> R.string.diagnostics_tool_dns_integrity_title
            DpiProbeKind.DNS_AVAILABILITY -> R.string.diagnostics_tool_dns_availability_title
            DpiProbeKind.DOMAIN_REACHABILITY -> R.string.diagnostics_tool_domain_reachability_title
            DpiProbeKind.TCP16 -> R.string.diagnostics_dpi_probe_kind_tcp16
            DpiProbeKind.WHITELIST_SNI -> R.string.diagnostics_tool_sni_compatibility_title
            DpiProbeKind.TELEGRAM -> R.string.connection_health_destination_telegram
            DpiProbeKind.QUIC_H3 -> R.string.diagnostics_dpi_probe_kind_quic_h3
            DpiProbeKind.ECH_READINESS -> R.string.diagnostics_dpi_probe_kind_ech
        },
    )

private fun DiagnosticsDpiSuiteState.tone(): DiagnosticsTone =
    when (this) {
        DiagnosticsDpiSuiteState.Idle -> DiagnosticsTone.Neutral
        DiagnosticsDpiSuiteState.Running -> DiagnosticsTone.Info
        DiagnosticsDpiSuiteState.Complete -> DiagnosticsTone.Positive
        DiagnosticsDpiSuiteState.Cancelled -> DiagnosticsTone.Neutral
        DiagnosticsDpiSuiteState.Failed -> DiagnosticsTone.Negative
    }
