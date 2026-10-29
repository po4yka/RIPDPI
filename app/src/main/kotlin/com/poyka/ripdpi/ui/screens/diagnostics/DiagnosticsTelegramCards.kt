package com.poyka.ripdpi.ui.screens.diagnostics

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.activities.formatTelegramThroughput
import com.poyka.ripdpi.activities.telegramStatusLabelRes
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun TelegramResultCard(
    probe: DiagnosticsProbeResultUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val details = remember(probe.details) { probe.details.associate { it.label to it.value } }
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
                text = stringResource(R.string.connection_health_destination_telegram),
                style = RipDpiThemeTokens.type.screenTitle,
                color = colors.foreground,
            )
            StatusIndicator(label = telegramStatusCaption(verdict), tone = verdictTone)
        }

        TelegramTransferSection(
            label = stringResource(R.string.diagnostics_telegram_download),
            status = details["downloadStatus"] ?: "unknown",
            avgBps = details["downloadAvgBps"],
            peakBps = details["downloadPeakBps"],
            bytes = details["downloadBytes"],
            error = details["downloadError"],
        )

        TelegramTransferSection(
            label = stringResource(R.string.diagnostics_telegram_upload),
            status = details["uploadStatus"] ?: "unknown",
            avgBps = details["uploadAvgBps"],
            peakBps = details["uploadPeakBps"],
            bytes = details["uploadBytes"],
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

@Composable
private fun TelegramTransferSection(
    label: String,
    status: String,
    avgBps: String?,
    peakBps: String?,
    bytes: String?,
    error: String?,
) {
    val colors = RipDpiThemeTokens.colors
    val locale = LocalConfiguration.current.locales[0]
    val avgSpeed = formatTelegramThroughput(avgBps?.toLongOrNull() ?: 0, locale)
    val peakSpeed = formatTelegramThroughput(peakBps?.toLongOrNull() ?: 0, locale)
    val totalBytes = Formatter.formatShortFileSize(LocalContext.current, bytes?.toLongOrNull() ?: 0)
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
        StatusIndicator(label = telegramStatusCaption(status), tone = tone)
    }
    SettingsRow(title = stringResource(R.string.diagnostics_telegram_avg_speed), value = avgSpeed)
    SettingsRow(title = stringResource(R.string.diagnostics_telegram_peak_speed), value = peakSpeed)
    SettingsRow(title = stringResource(R.string.diagnostics_telegram_transferred), value = totalBytes)
    if (error != null && error != "none") {
        SettingsRow(title = stringResource(R.string.diagnostics_telegram_error), value = error)
    }
}

@Composable
private fun telegramStatusCaption(status: String): String =
    telegramStatusLabelRes(status)?.let { stringResource(it) } ?: status
