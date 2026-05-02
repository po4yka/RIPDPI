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
import com.poyka.ripdpi.activities.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val bytesPerMegabyte = 1_000_000L
private const val bytesPerKilobyte = 1_000L

@Composable
internal fun TelegramResultCard(
    probe: DiagnosticsProbeResultUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val details = probe.details.associate { it.label to it.value }
    val verdict = details["verdict"] ?: probe.outcome
    val verdictTone = statusTone(probe.tone)

    RipDpiCard(
        modifier = modifier,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Telegram",
                style = RipDpiThemeTokens.type.screenTitle,
                color = colors.foreground,
            )
            StatusIndicator(label = verdict, tone = verdictTone)
        }

        TelegramTransferSection(
            label = stringResource(R.string.diagnostics_telegram_download),
            status = details["downloadStatus"] ?: "unknown",
            avgBps = details["downloadAvgBps"],
            peakBps = details["downloadPeakBps"],
            bytes = details["downloadBytes"],
            durationMs = details["downloadDurationMs"],
            error = details["downloadError"],
        )

        TelegramTransferSection(
            label = stringResource(R.string.diagnostics_telegram_upload),
            status = details["uploadStatus"] ?: "unknown",
            avgBps = details["uploadAvgBps"],
            peakBps = details["uploadPeakBps"],
            bytes = details["uploadBytes"],
            durationMs = details["uploadDurationMs"],
            error = details["uploadError"],
        )

        val dcReachable = details["dcReachable"] ?: "0"
        val dcTotal = details["dcTotal"] ?: "0"
        val dcResults = details["dcResults"]?.split("|")?.filter { it.isNotEmpty() } ?: emptyList()

        SettingsRow(
            title = stringResource(R.string.diagnostics_telegram_data_centers),
            value = stringResource(R.string.diagnostics_telegram_reachable_format, dcReachable, dcTotal),
        )
        if (dcResults.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                dcResults.forEach { dc ->
                    val parts = dc.split(":")
                    val label = parts.getOrNull(0) ?: "?"
                    val ok = parts.getOrNull(1) == "ok"
                    val rtt = parts.getOrNull(2) ?: ""
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        StatusIndicator(
                            label = label,
                            tone = if (ok) StatusIndicatorTone.Active else StatusIndicatorTone.Error,
                        )
                        if (rtt.isNotEmpty()) {
                            Text(
                                text = rtt,
                                style = RipDpiThemeTokens.type.monoSmall,
                                color = colors.mutedForeground,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Suppress("UnusedParameter")
@Composable
private fun TelegramTransferSection(
    label: String,
    status: String,
    avgBps: String?,
    peakBps: String?,
    bytes: String?,
    durationMs: String?,
    error: String?,
) {
    val colors = RipDpiThemeTokens.colors
    val avgSpeed = formatBps(avgBps?.toLongOrNull() ?: 0)
    val peakSpeed = formatBps(peakBps?.toLongOrNull() ?: 0)
    val totalBytes = formatTransferBytes(bytes?.toLongOrNull() ?: 0)
    val tone =
        when (status) {
            "ok" -> StatusIndicatorTone.Active
            "slow", "stalled" -> StatusIndicatorTone.Warning
            else -> StatusIndicatorTone.Error
        }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        StatusIndicator(label = status, tone = tone)
    }
    SettingsRow(title = stringResource(R.string.diagnostics_telegram_avg_speed), value = avgSpeed)
    SettingsRow(title = stringResource(R.string.diagnostics_telegram_peak_speed), value = peakSpeed)
    SettingsRow(title = stringResource(R.string.diagnostics_telegram_transferred), value = totalBytes)
    if (error != null && error != "none") {
        SettingsRow(title = stringResource(R.string.diagnostics_telegram_error), value = error)
    }
}

private fun formatBps(bps: Long): String =
    when {
        bps >= bytesPerMegabyte -> String.format(java.util.Locale.US, "%.1f Mbps", bps / bytesPerMegabyte.toDouble())
        bps >= bytesPerKilobyte -> String.format(java.util.Locale.US, "%.1f Kbps", bps / bytesPerKilobyte.toDouble())
        else -> "$bps Bps"
    }

private fun formatTransferBytes(bytes: Long): String =
    when {
        bytes >= bytesPerMegabyte -> String.format(java.util.Locale.US, "%.1f MB", bytes / bytesPerMegabyte.toDouble())
        bytes >= bytesPerKilobyte -> String.format(java.util.Locale.US, "%.1f KB", bytes / bytesPerKilobyte.toDouble())
        else -> "$bytes B"
    }
