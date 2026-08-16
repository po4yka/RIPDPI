// The self-contained probe tool cards, split out of DiagnosticsToolsSection so that file stays under
// its 1000-line limit. Each card owns one DiagnosticsToolsSection list item and nothing else.
package com.poyka.ripdpi.ui.screens.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsAllowlistSniState
import com.poyka.ripdpi.activities.DiagnosticsAllowlistSniToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsCompatibleSniUiModel
import com.poyka.ripdpi.activities.DiagnosticsCompressionProbeState
import com.poyka.ripdpi.activities.DiagnosticsCompressionProbeToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsDnsAvailabilityState
import com.poyka.ripdpi.activities.DiagnosticsDnsAvailabilityToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsTcp16FatHeaderState
import com.poyka.ripdpi.activities.DiagnosticsTcp16FatHeaderToolUiModel
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import java.util.Locale

@Composable
internal fun Tcp16FatHeaderProbeCard(
    tool: DiagnosticsTcp16FatHeaderToolUiModel,
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
            text = stringResource(R.string.diagnostics_tool_tcp16_title),
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
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                tool.rows.forEach { row ->
                    StatusIndicator(
                        label = "${row.asn}: ${row.detected}/${row.checked} detected",
                        tone = statusTone(row.tone),
                    )
                    androidx.compose.material3.Text(
                        text = "${row.providers} · dead ${row.dead} · errors ${row.errors}",
                        style = RipDpiThemeTokens.type.monoSmall,
                        color = colors.mutedForeground,
                    )
                }
            }
        }
        RipDpiButton(
            text =
                if (tool.state == DiagnosticsTcp16FatHeaderState.Running) {
                    stringResource(R.string.diagnostics_tool_probing)
                } else {
                    stringResource(R.string.diagnostics_tool_tcp16_run)
                },
            enabled = tool.state != DiagnosticsTcp16FatHeaderState.Running,
            onClick = onRun,
            variant = RipDpiButtonVariant.Outline,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun AllowlistSniFinderCard(
    tool: DiagnosticsAllowlistSniToolUiModel,
    onRun: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val context = LocalContext.current
    val clipboardManager =
        remember(context) {
            context.getSystemService(ClipboardManager::class.java)
        }
    RipDpiCard(variant = RipDpiCardVariant.Outlined) {
        StatusIndicator(
            label = tool.state.name.lowercase(Locale.US),
            tone = statusTone(tool.state.tone()),
            pulsing = tool.state.running(),
        )
        androidx.compose.material3.Text(
            text = stringResource(R.string.diagnostics_tool_sni_compatibility_title),
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
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                tool.rows.forEach { row ->
                    StatusIndicator(
                        label = "${row.asn}: ${row.compatibleSnis.size} compatible",
                        tone = statusTone(row.tone),
                    )
                    androidx.compose.material3.Text(
                        text = "${row.provider} · ${row.ip} · tried ${row.triedCount}",
                        style = RipDpiThemeTokens.type.monoSmall,
                        color = colors.mutedForeground,
                    )
                    CompatibleSniValues(row.compatibleSnis, clipboardManager)
                }
            }
        }
        RipDpiButton(
            text =
                if (tool.state == DiagnosticsAllowlistSniState.Running) {
                    stringResource(R.string.diagnostics_tool_checking)
                } else {
                    stringResource(R.string.diagnostics_tool_sni_compatibility_run)
                },
            enabled = tool.enabled && tool.state != DiagnosticsAllowlistSniState.Running,
            onClick = onRun,
            variant = RipDpiButtonVariant.Outline,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CompatibleSniValues(
    compatibleSnis: List<DiagnosticsCompatibleSniUiModel>,
    clipboardManager: ClipboardManager?,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val compatibleSniLabel = stringResource(R.string.clipboard_label_compatible_sni)
    if (compatibleSnis.isEmpty()) {
        androidx.compose.material3.Text(
            text = stringResource(R.string.diagnostics_compatible_sni_empty),
            style = RipDpiThemeTokens.type.secondaryBody,
            color = colors.mutedForeground,
        )
    } else {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            compatibleSnis.forEach { sni ->
                RipDpiChip(
                    text = sni.label,
                    onClick = {
                        clipboardManager?.setPrimaryClip(
                            ClipData.newPlainText(compatibleSniLabel, sni.value),
                        )
                    },
                )
            }
        }
    }
}

@Composable
internal fun DnsAvailabilitySurveyCard(
    tool: DiagnosticsDnsAvailabilityToolUiModel,
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
            text = stringResource(R.string.diagnostics_tool_dns_availability_title),
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
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                tool.rows.forEach { row ->
                    StatusIndicator(
                        label = "${row.name}: ${row.availability}",
                        tone = statusTone(row.tone),
                    )
                    androidx.compose.material3.Text(
                        text = "${row.type} · ${row.latency}",
                        style = RipDpiThemeTokens.type.monoSmall,
                        color = colors.mutedForeground,
                    )
                }
            }
        }
        RipDpiButton(
            text =
                if (tool.state == DiagnosticsDnsAvailabilityState.Running) {
                    stringResource(R.string.diagnostics_tool_surveying)
                } else {
                    stringResource(R.string.diagnostics_tool_dns_availability_run)
                },
            enabled = tool.state != DiagnosticsDnsAvailabilityState.Running,
            onClick = onRun,
            variant = RipDpiButtonVariant.Outline,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun HttpCompressionProbeCard(
    tool: DiagnosticsCompressionProbeToolUiModel,
    onRun: () -> Unit,
    onZstdEnabledChange: (Boolean) -> Unit,
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
            text = stringResource(R.string.diagnostics_tool_http_compression_title),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        androidx.compose.material3.Text(
            text = tool.errorMessage ?: tool.summary,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = if (tool.errorMessage == null) colors.mutedForeground else colors.destructive,
        )
        MetricsRow(metrics = tool.metrics)
        RipDpiSwitch(
            checked = tool.includeZstd,
            onCheckedChange = onZstdEnabledChange,
            enabled = tool.state != DiagnosticsCompressionProbeState.Running,
            label = stringResource(R.string.diagnostics_tool_zstd_codec_label),
        )
        if (tool.rows.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                tool.rows.forEach { row ->
                    StatusIndicator(
                        label = "${row.codec}: ${row.verdict}",
                        tone = statusTone(row.tone),
                    )
                    androidx.compose.material3.Text(
                        text = "${row.compressedBytes} B compressed · ${row.decompressedBytes} B decoded",
                        style = RipDpiThemeTokens.type.monoSmall,
                        color = colors.mutedForeground,
                    )
                }
            }
        }
        RipDpiButton(
            text =
                if (tool.state == DiagnosticsCompressionProbeState.Running) {
                    stringResource(R.string.diagnostics_tool_checking)
                } else {
                    stringResource(R.string.diagnostics_tool_http_compression_run)
                },
            enabled = tool.state != DiagnosticsCompressionProbeState.Running,
            onClick = onRun,
            variant = RipDpiButtonVariant.Outline,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
