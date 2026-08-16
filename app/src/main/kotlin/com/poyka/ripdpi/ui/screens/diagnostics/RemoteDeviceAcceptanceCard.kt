package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.services.RemoteDeviceAcceptanceReport
import com.poyka.ripdpi.services.RemoteDeviceAcceptanceStatus
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun RemoteDeviceAcceptanceCard(
    report: RemoteDeviceAcceptanceReport,
    onRun: () -> Unit,
    onShare: () -> Unit,
) {
    val running = report.status == RemoteDeviceAcceptanceStatus.Running
    RipDpiCard(
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsDeviceAcceptanceCard),
        variant = RipDpiCardVariant.Outlined,
    ) {
        StatusIndicator(
            label = report.status.displayLabel(),
            tone = statusTone(report.status.toDiagnosticsTone()),
        )
        androidx.compose.material3.Text(
            text = stringResource(R.string.diagnostics_device_gate_title),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        androidx.compose.material3.Text(
            text = stringResource(R.string.diagnostics_device_gate_body),
            style = RipDpiThemeTokens.type.secondaryBody,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        androidx.compose.material3.Text(
            text = report.deviceSummary(),
            style = RipDpiThemeTokens.type.monoSmall,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        RemoteDeviceAcceptanceSteps(report)
        RipDpiButton(
            text =
                if (report.status == RemoteDeviceAcceptanceStatus.Idle) {
                    stringResource(R.string.diagnostics_device_gate_run)
                } else {
                    stringResource(R.string.diagnostics_device_gate_rerun)
                },
            enabled = !running,
            loading = running,
            onClick = onRun,
            variant = RipDpiButtonVariant.Primary,
            modifier = Modifier.fillMaxWidth(),
        )
        RipDpiButton(
            text = stringResource(R.string.diagnostics_device_gate_share),
            enabled = report.status !in setOf(RemoteDeviceAcceptanceStatus.Idle, RemoteDeviceAcceptanceStatus.Running),
            onClick = onShare,
            variant = RipDpiButtonVariant.Outline,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RemoteDeviceAcceptanceSteps(report: RemoteDeviceAcceptanceReport) {
    if (report.status == RemoteDeviceAcceptanceStatus.Idle) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
    ) {
        report.steps.forEach { step ->
            val detail =
                listOfNotNull(
                    step.status.displayLabel(),
                    step.durationMs?.let { "${it}ms" },
                    step.errorClass,
                ).joinToString(" · ")
            androidx.compose.material3.Text(
                text = "${step.id}: $detail",
                style = RipDpiThemeTokens.type.monoSmall,
                color =
                    if (step.status == RemoteDeviceAcceptanceStatus.Fail) {
                        RipDpiThemeTokens.colors.destructive
                    } else {
                        RipDpiThemeTokens.colors.mutedForeground
                    },
            )
        }
    }
}

private fun RemoteDeviceAcceptanceStatus.toDiagnosticsTone(): DiagnosticsTone =
    when (this) {
        RemoteDeviceAcceptanceStatus.Pass -> DiagnosticsTone.Positive
        RemoteDeviceAcceptanceStatus.Fail -> DiagnosticsTone.Negative
        RemoteDeviceAcceptanceStatus.Incomplete -> DiagnosticsTone.Warning
        else -> DiagnosticsTone.Neutral
    }

private fun RemoteDeviceAcceptanceReport.deviceSummary(): String =
    "${device.model} · CSC ${device.csc} · API ${device.api} · ${device.abi} · $transportKind"

/**
 * User-facing name for an acceptance status.
 *
 * `wireValue` is the serialization contract and stays English on purpose; splicing it into the card
 * showed raw protocol tokens to all nine locales.
 */
@Composable
private fun RemoteDeviceAcceptanceStatus.displayLabel(): String =
    when (this) {
        RemoteDeviceAcceptanceStatus.Idle -> stringResource(R.string.diagnostics_device_gate_status_idle)
        RemoteDeviceAcceptanceStatus.Running -> stringResource(R.string.diagnostics_device_gate_status_running)
        RemoteDeviceAcceptanceStatus.Incomplete -> stringResource(R.string.diagnostics_device_gate_status_incomplete)
        RemoteDeviceAcceptanceStatus.Pass -> stringResource(R.string.diagnostics_device_gate_status_pass)
        RemoteDeviceAcceptanceStatus.Fail -> stringResource(R.string.diagnostics_device_gate_status_fail)
    }
