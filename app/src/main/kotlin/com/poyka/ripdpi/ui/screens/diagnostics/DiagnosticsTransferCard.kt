package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsTransferRunUiModel
import com.poyka.ripdpi.activities.DiagnosticsTransferUiModel
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun DiagnosticsTransferCard(
    transfer: DiagnosticsTransferUiModel,
    modifier: Modifier = Modifier,
) {
    RipDpiCard(modifier = modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.DiagnosticsTransferCard)) {
        Text(
            text = stringResource(R.string.diagnostics_transfer_title),
            modifier = Modifier.fillMaxWidth(),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        TransferText(transfer.target)
        TransferText(stringResource(R.string.diagnostics_transfer_observation))
        if (transfer.networkScopeUnverified) {
            TransferText(stringResource(R.string.diagnostics_transfer_scope_unverified))
        }
        transfer.connectionStages?.let { DiagnosticsConnectionStageCard(it) }
        transfer.runs.forEach { run -> TransferRun(run) }
    }
}

@Composable
private fun TransferRun(run: DiagnosticsTransferRunUiModel) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
    ) {
        TransferText(stringResource(R.string.diagnostics_transfer_run, run.runIndex, run.runCount))
        TransferText(
            run.expectedBodyByteCount?.let { expected ->
                stringResource(R.string.diagnostics_transfer_bytes_known, run.receivedBodyByteCount, expected)
            } ?: stringResource(R.string.diagnostics_transfer_bytes_unknown, run.receivedBodyByteCount),
        )
        TransferText(stringResource(R.string.diagnostics_transfer_elapsed, run.elapsedMs))
        TransferText(stringResource(R.string.diagnostics_transfer_first, transferTime(run.firstBodyByteMs)))
        TransferText(stringResource(R.string.diagnostics_transfer_last, transferTime(run.lastBodyProgressMs)))
        TransferText(stringResource(transferReasonResource(run.terminationReason)))
        if (run.terminationReason != null) {
            TransferText(
                stringResource(
                    if (run.responseComplete) {
                        R.string.diagnostics_transfer_complete
                    } else {
                        R.string.diagnostics_transfer_partial
                    },
                ),
            )
        }
        if (run.windowComplete) {
            TransferText(stringResource(R.string.diagnostics_transfer_window_complete))
        }
        TransferTimeline(run)
    }
}

@Composable
private fun TransferTimeline(run: DiagnosticsTransferRunUiModel) {
    var expanded by rememberSaveable(run.runIndex) { mutableStateOf(false) }
    if (run.samples.isNotEmpty()) {
        TransferMeasuredTimeline(run)
        RipDpiButton(
            text =
                stringResource(
                    if (expanded) {
                        R.string.diagnostics_transfer_hide_timeline
                    } else {
                        R.string.diagnostics_transfer_show_timeline
                    },
                ),
            onClick = { expanded = !expanded },
            variant = RipDpiButtonVariant.Secondary,
            wrapLabel = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (expanded) {
            Column(
                modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.DiagnosticsTransferTimeline),
                verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
            ) {
                TransferText(stringResource(R.string.diagnostics_transfer_timeline_explanation))
                run.samples.forEach { sample ->
                    TransferText(
                        stringResource(R.string.diagnostics_transfer_sample, sample.elapsedMs, sample.bodyByteCount),
                    )
                }
            }
        }
    }
}

@Composable
private fun transferTime(value: Long?): String =
    value?.let { stringResource(R.string.diagnostics_transfer_ms, it) }
        ?: stringResource(R.string.diagnostics_transfer_unobserved)

@Composable
private fun TransferText(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = RipDpiThemeTokens.type.secondaryBody,
        color = RipDpiThemeTokens.colors.foreground,
    )
}

@StringRes
internal fun transferReasonResource(reason: String?): Int =
    when (reason) {
        null -> R.string.diagnostics_transfer_running
        "content_length_complete" -> R.string.diagnostics_transfer_stop_length
        "chunked_complete" -> R.string.diagnostics_transfer_stop_chunked
        "eof_complete" -> R.string.diagnostics_transfer_stop_eof
        "early_eof" -> R.string.diagnostics_transfer_stop_early_eof
        "window_limit" -> R.string.diagnostics_transfer_stop_window
        "idle_timeout" -> R.string.diagnostics_transfer_stop_idle
        "reset" -> R.string.diagnostics_transfer_stop_reset
        "read_error" -> R.string.diagnostics_transfer_stop_read
        "invalid_framing" -> R.string.diagnostics_transfer_stop_framing
        "cancelled" -> R.string.diagnostics_transfer_stop_cancelled
        "deadline" -> R.string.diagnostics_transfer_stop_deadline
        "setup_error" -> R.string.diagnostics_transfer_stop_setup
        "http_error" -> R.string.diagnostics_transfer_stop_http
        else -> R.string.diagnostics_transfer_unobserved
    }

@Composable
private fun TransferMeasuredTimeline(run: DiagnosticsTransferRunUiModel) {
    val first = run.samples.first()
    val last = run.samples.last()
    val firstLabel = stringResource(R.string.diagnostics_transfer_sample, first.elapsedMs, first.bodyByteCount)
    val lastLabel = stringResource(R.string.diagnostics_transfer_sample, last.elapsedMs, last.bodyByteCount)
    val description =
        stringResource(R.string.diagnostics_transfer_timeline_explanation) + " " + firstLabel + " → " + lastLabel
    val ink = RipDpiThemeTokens.colors.foreground
    val divider = RipDpiThemeTokens.colors.divider
    val maxTime = run.elapsedMs.coerceAtLeast(1)
    val maxBytes = run.receivedBodyByteCount.coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(72.dp).semantics { contentDescription = description }) {
        val inset = 4.dp.toPx()
        val width = (size.width - 2 * inset).coerceAtLeast(0f)
        val height = (size.height - 2 * inset).coerceAtLeast(0f)
        drawLine(divider, Offset(inset, size.height - inset), Offset(size.width - inset, size.height - inset))
        // Each dot is an observed sample. No expected length or transfer rate is inferred.
        run.samples.forEach { sample ->
            drawCircle(
                ink,
                2.dp.toPx(),
                Offset(
                    inset + width * sample.elapsedMs.toFloat() / maxTime,
                    size.height - inset - height * sample.bodyByteCount.toFloat() / maxBytes,
                ),
            )
        }
    }
    TransferText(firstLabel + " → " + lastLabel)
}
