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
import com.poyka.ripdpi.activities.DiagnosticsByohCompatibilityState
import com.poyka.ripdpi.activities.DiagnosticsByohCompatibilityToolUiModel
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
            text = "Bring-your-own-host compatibility",
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
                    label = "Destination IP",
                    placeholder = "203.0.113.10",
                ),
            behavior = RipDpiTextFieldBehavior(enabled = tool.state != DiagnosticsByohCompatibilityState.Running),
        )
        RipDpiTextField(
            value = tool.urlPath,
            onValueChange = onUrlPathChange,
            decoration =
                RipDpiTextFieldDecoration(
                    label = "URL path",
                    placeholder = "/1MB.bin",
                ),
            behavior = RipDpiTextFieldBehavior(enabled = tool.state != DiagnosticsByohCompatibilityState.Running),
        )
        RipDpiSwitch(
            checked = tool.useSyntheticFixture,
            onCheckedChange = onSyntheticFixtureEnabledChange,
            enabled = tool.state != DiagnosticsByohCompatibilityState.Running,
            label = "Synthetic fixture",
        )
        MetricsRow(metrics = tool.metrics)
        if (tool.rows.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                tool.rows.forEach { row ->
                    StatusIndicator(
                        label = "${row.domain}: ${row.verdict}",
                        tone = statusTone(row.tone),
                    )
                    Text(
                        text = "${row.bytesReceived} B received",
                        style = RipDpiThemeTokens.type.monoSmall,
                        color = colors.mutedForeground,
                    )
                }
            }
        }
        if (tool.csvExport.isNotBlank()) {
            RipDpiButton(
                text = "Copy CSV",
                onClick = {
                    clipboardManager?.setPrimaryClip(
                        ClipData.newPlainText("BYOH compatibility CSV", tool.csvExport),
                    )
                },
                variant = RipDpiButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        RipDpiButton(
            text =
                if (tool.state == DiagnosticsByohCompatibilityState.Running) {
                    "Checking..."
                } else {
                    "Run host compatibility"
                },
            enabled = tool.state != DiagnosticsByohCompatibilityState.Running && tool.dstIp.isNotBlank(),
            onClick = onRun,
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
