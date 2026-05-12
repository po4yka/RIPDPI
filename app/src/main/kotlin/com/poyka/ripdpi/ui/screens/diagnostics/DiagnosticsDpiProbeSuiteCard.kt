package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val spacing = RipDpiThemeTokens.spacing
    val running = tool.state == DiagnosticsDpiSuiteState.Running

    RipDpiCard(variant = RipDpiCardVariant.Outlined) {
        StatusIndicator(
            label = tool.state.name.lowercase(Locale.US),
            tone = statusTone(tool.state.tone()),
        )
        Text(
            text = "DPI-CH Comprehensive",
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
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                tool.rows.forEach { row ->
                    StatusIndicator(
                        label = "${row.label}: ${row.status}",
                        tone = statusTone(row.tone),
                    )
                    Text(
                        text = row.detail,
                        style = RipDpiThemeTokens.type.monoSmall,
                        color = colors.mutedForeground,
                    )
                    if (row.detailRows.isNotEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(spacing.xs),
                        ) {
                            row.detailRows.forEach { detailRow ->
                                StatusIndicator(
                                    label = detailRow.label,
                                    tone = statusTone(detailRow.tone),
                                )
                                Text(
                                    text = detailRow.detail,
                                    style = RipDpiThemeTokens.type.monoSmall,
                                    color = colors.mutedForeground,
                                )
                            }
                        }
                    }
                }
            }
        }
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
        RipDpiTextField(
            value = tool.customDomainsInput,
            onValueChange = onCustomDomainsChange,
            decoration =
                RipDpiTextFieldDecoration(
                    label = "Custom domains",
                    placeholder = "vk.com, youtube.com",
                ),
            behavior = RipDpiTextFieldBehavior(enabled = !running),
        )
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
                text = "Concurrency ${tool.concurrency}",
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
        RipDpiButton(
            text = if (running) "Cancel suite" else "Run suite",
            enabled = tool.selectedKinds.isNotEmpty(),
            onClick = if (running) onCancel else onRun,
            variant = if (running) RipDpiButtonVariant.Outline else RipDpiButtonVariant.Primary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun DpiProbeKind.label(): String =
    when (this) {
        DpiProbeKind.DNS_INTEGRITY -> "DNS integrity"
        DpiProbeKind.DNS_AVAILABILITY -> "DNS availability"
        DpiProbeKind.DOMAIN_REACHABILITY -> "Domain reachability"
        DpiProbeKind.TCP16 -> "TCP16 fat header"
        DpiProbeKind.WHITELIST_SNI -> "SNI compatibility"
        DpiProbeKind.TELEGRAM -> "Telegram"
        DpiProbeKind.QUIC_H3 -> "QUIC/H3 fingerprint"
        DpiProbeKind.ECH_READINESS -> "ECH readiness"
    }

private fun DiagnosticsDpiSuiteState.tone(): DiagnosticsTone =
    when (this) {
        DiagnosticsDpiSuiteState.Idle -> DiagnosticsTone.Neutral
        DiagnosticsDpiSuiteState.Running -> DiagnosticsTone.Info
        DiagnosticsDpiSuiteState.Complete -> DiagnosticsTone.Positive
        DiagnosticsDpiSuiteState.Cancelled -> DiagnosticsTone.Neutral
        DiagnosticsDpiSuiteState.Failed -> DiagnosticsTone.Negative
    }
