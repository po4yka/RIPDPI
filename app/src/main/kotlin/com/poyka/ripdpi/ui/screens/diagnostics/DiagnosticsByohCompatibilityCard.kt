package com.poyka.ripdpi.ui.screens.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsByohCompatibilityState
import com.poyka.ripdpi.activities.DiagnosticsByohCompatibilityToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsByohDomainUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
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

@Composable
internal fun ByohCompatibilityCard(
    tool: DiagnosticsByohCompatibilityToolUiModel,
    onDstIpChange: (String) -> Unit,
    onUrlPathChange: (String) -> Unit,
    onSyntheticFixtureEnabledChange: (Boolean) -> Unit,
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
        )
        Text(
            text = stringResource(R.string.diagnostics_byoh_title),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(
            text = tool.errorMessage ?: tool.summary,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = if (tool.errorMessage == null) colors.mutedForeground else colors.destructive,
        )
        Text(
            text = tool.requirementsSummary,
            style = RipDpiThemeTokens.type.caption,
            color = colors.mutedForeground,
        )
        RipDpiTextField(
            value = tool.dstIp,
            onValueChange = onDstIpChange,
            decoration =
                RipDpiTextFieldDecoration(
                    label = stringResource(R.string.diagnostics_byoh_destination_ip_label),
                    placeholder = "203.0.113.10",
                ),
            behavior = RipDpiTextFieldBehavior(enabled = tool.state != DiagnosticsByohCompatibilityState.Running),
        )
        RipDpiTextField(
            value = tool.urlPath,
            onValueChange = onUrlPathChange,
            decoration =
                RipDpiTextFieldDecoration(
                    label = stringResource(R.string.diagnostics_byoh_url_path_label),
                    placeholder = "/1MB.bin",
                ),
            behavior = RipDpiTextFieldBehavior(enabled = tool.state != DiagnosticsByohCompatibilityState.Running),
        )
        RipDpiSwitch(
            checked = tool.useSyntheticFixture,
            onCheckedChange = onSyntheticFixtureEnabledChange,
            enabled = tool.state != DiagnosticsByohCompatibilityState.Running,
            label = stringResource(R.string.diagnostics_byoh_synthetic_fixture_label),
        )
        MetricsRow(metrics = tool.metrics)
        ByohCompatibilityRows(rows = tool.rows)
        ByohCopyCsvButton(csvExport = tool.csvExport, clipboardManager = clipboardManager)
        RipDpiButton(
            text =
                if (tool.state == DiagnosticsByohCompatibilityState.Running) {
                    stringResource(R.string.diagnostics_tool_checking)
                } else {
                    stringResource(R.string.diagnostics_byoh_run)
                },
            enabled = tool.state != DiagnosticsByohCompatibilityState.Running && tool.dstIp.isNotBlank(),
            onClick = onRun,
            variant = RipDpiButtonVariant.Outline,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ByohCompatibilityRows(rows: List<DiagnosticsByohDomainUiModel>) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    if (rows.isNotEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            rows.forEach { row ->
                StatusIndicator(label = "${row.domain}: ${row.verdict}", tone = statusTone(row.tone))
                Text(
                    text = "${row.bytesReceived} B received",
                    style = RipDpiThemeTokens.type.monoSmall,
                    color = colors.mutedForeground,
                )
            }
        }
    }
}

@Composable
private fun ByohCopyCsvButton(
    csvExport: String,
    clipboardManager: ClipboardManager?,
) {
    if (csvExport.isNotBlank()) {
        val csvLabel = stringResource(R.string.clipboard_label_byoh_compatibility_csv)
        RipDpiButton(
            text = stringResource(R.string.diagnostics_byoh_copy_csv),
            onClick = {
                clipboardManager?.setPrimaryClip(
                    ClipData.newPlainText(csvLabel, csvExport),
                )
            },
            variant = RipDpiButtonVariant.Outline,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun DiagnosticsByohCompatibilityState.tone(): DiagnosticsTone =
    when (this) {
        DiagnosticsByohCompatibilityState.Idle -> DiagnosticsTone.Neutral
        DiagnosticsByohCompatibilityState.Running -> DiagnosticsTone.Info
        DiagnosticsByohCompatibilityState.Complete -> DiagnosticsTone.Positive
        DiagnosticsByohCompatibilityState.Failed -> DiagnosticsTone.Negative
    }
