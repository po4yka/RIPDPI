package com.poyka.ripdpi.ui.screens.diagnostics

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
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
import com.poyka.ripdpi.platform.AndroidStringResolver
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.formatTelegramThroughput
import com.poyka.ripdpi.ui.screens.diagnostics.state.parseTelegramDcResult
import com.poyka.ripdpi.ui.screens.diagnostics.state.telegramDcPathDetails
import com.poyka.ripdpi.ui.screens.diagnostics.state.telegramStatusLabelRes
import com.poyka.ripdpi.ui.screens.diagnostics.state.telegramWebSocketDetails
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

internal fun DiagnosticsProbeResultUiModel.isTelegramAvailabilityProbe(): Boolean = probeType == "telegram_availability"

@Composable
internal fun TelegramResultCard(
    probe: DiagnosticsProbeResultUiModel,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val context = LocalContext.current
    val strings = remember(context) { AndroidStringResolver(context) }
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
            status = details["downloadStatus"] ?: "not_run",
            avgBps = details["downloadAvgBps"],
            peakBps = details["downloadPeakBps"],
            bytes = details["downloadBytes"],
            error = details["downloadError"],
        )

        TelegramTransferSection(
            label = stringResource(R.string.diagnostics_telegram_upload),
            status = details["uploadStatus"] ?: "not_run",
            avgBps = details["uploadAvgBps"],
            peakBps = details["uploadPeakBps"],
            bytes = details["uploadBytes"],
            error = details["uploadError"],
        )

        TelegramConnectivitySection(details, strings)
        TelegramStatusRow(
            label = stringResource(R.string.diagnostics_telegram_ws_handshake),
            status = details["wsTunnelStatus"] ?: "not_run",
            detail = strings.telegramWebSocketDetails(details).joinToString(" · "),
        )
        details["wsTunnelError"]?.takeUnless { it.isEmpty() || it == "none" }?.let { error ->
            SettingsRow(title = stringResource(R.string.diagnostics_telegram_error), value = error)
        }
    }
}

@Composable
private fun TelegramConnectivitySection(
    details: Map<String, String>,
    strings: StringResolver,
) {
    val dcReachable = details["dcReachable"]?.toIntOrNull()
    val dcAttempted = details["dcTotal"]?.toIntOrNull()
    val dcPlanned = details["dcPlanned"]?.toIntOrNull()
    val dcSummary =
        if (dcReachable != null && dcAttempted != null && dcAttempted > 0) {
            stringResource(
                R.string.diagnostics_telegram_reachable_format,
                dcReachable.toString(),
                dcAttempted.toString(),
            )
        } else {
            stringResource(R.string.diagnostics_matrix_stage_not_run)
        }
    SettingsRow(
        title =
            stringResource(
                if (dcPlanned !=
                    null
                ) {
                    R.string.diagnostics_telegram_tcp_endpoints
                } else {
                    R.string.diagnostics_telegram_data_centers
                },
            ),
        subtitle =
            if (dcAttempted != null && dcPlanned != null) {
                if (dcPlanned > dcAttempted && dcAttempted > 0) {
                    stringResource(R.string.diagnostics_telegram_unmeasured_format, dcPlanned - dcAttempted)
                } else {
                    null
                }
            } else {
                null
            },
        value = dcSummary,
    )
    details["dcResults"]?.split('|')?.mapNotNull(::parseTelegramDcResult)?.forEach { dc ->
        val detail = listOfNotNull(dc.endpointOrPorts) + strings.telegramDcPathDetails(dc)
        TelegramStatusRow(
            label = dc.label,
            status = dc.status,
            detail = detail.joinToString(" · "),
        )
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
    val locale = LocalConfiguration.current.locales[0]
    TelegramStatusRow(label, status)
    avgBps?.toLongOrNull()?.let {
        SettingsRow(
            title = stringResource(R.string.diagnostics_telegram_avg_speed),
            value = formatTelegramThroughput(it, locale),
        )
    }
    peakBps?.toLongOrNull()?.let {
        SettingsRow(
            title = stringResource(R.string.diagnostics_telegram_peak_speed),
            value = formatTelegramThroughput(it, locale),
        )
    }
    bytes?.toLongOrNull()?.let {
        SettingsRow(
            title = stringResource(R.string.diagnostics_telegram_transferred),
            value = Formatter.formatShortFileSize(LocalContext.current, it),
        )
    }
    if (error != null && error != "none") {
        SettingsRow(title = stringResource(R.string.diagnostics_telegram_error), value = error)
    }
}

@Composable
private fun TelegramStatusRow(
    label: String,
    status: String,
    detail: String? = null,
) {
    val colors = RipDpiThemeTokens.colors
    val tone =
        when (status) {
            "ok" -> StatusIndicatorTone.Active
            "slow", "stalled", "partial" -> StatusIndicatorTone.Warning
            "unknown", "not_run", "capability_skipped", "cancelled", "deadline_exceeded" -> StatusIndicatorTone.Idle
            else -> StatusIndicatorTone.Error
        }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        StatusIndicator(label = telegramStatusCaption(status), tone = tone)
    }
    if (detail != null) {
        Text(
            text = detail,
            style = RipDpiThemeTokens.type.monoSmall,
            color = colors.mutedForeground,
        )
    }
}

@Composable
private fun telegramStatusCaption(status: String): String =
    telegramStatusLabelRes(status)?.let { stringResource(it) } ?: status
