package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsProbeResultUiModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

internal val DiagnosticsProbeResultUiModel.isHttp3TransferComparison: Boolean
    get() =
        probeType == "throughput_window" &&
            (outcome == "http3_transfer_comparison_recorded" || outcome == "http3_transfer_comparison_inconclusive")

internal val DiagnosticsProbeResultUiModel.isVerifiedTransferComparison: Boolean
    get() = isEchTransferComparison || isHttp3TransferComparison || volumeTransferLegTitleResource() != null

internal data class Http3TransferLegUiModel(
    @StringRes val protocol: Int,
    @StringRes val stage: Int,
    @StringRes val status: Int,
    val receivedBytes: Long?,
)

internal data class Http3TransferComparisonUiModel(
    val legs: ImmutableList<Http3TransferLegUiModel>,
    @StringRes val difference: Int,
)

private val transferProtocols =
    listOf(
        "tcp" to R.string.diagnostics_http3_transfer_tcp,
        "http3" to R.string.diagnostics_http3_title,
    )

internal fun DiagnosticsProbeResultUiModel.http3TransferComparison(): Http3TransferComparisonUiModel? {
    if (!isHttp3TransferComparison) return null

    val values = details.associate { it.label to it.value }
    val legs =
        transferProtocols.map { (prefix, protocol) ->
            Http3TransferLegUiModel(
                protocol = protocol,
                stage = transferComparisonStageResource(values["${prefix}Stage"]),
                status = transferComparisonStatusResource(values["${prefix}Outcome"]),
                receivedBytes = values["${prefix}Bytes"]?.toLongOrNull()?.takeIf { it >= 0 },
            )
        }
    val difference =
        when {
            outcome == "http3_transfer_comparison_inconclusive" -> R.string.diagnostics_matrix_inconclusive
            values["differenceStage"] == "none" -> R.string.diagnostics_http3_transfer_no_difference
            else -> transferComparisonStageResource(values["differenceStage"])
        }
    return Http3TransferComparisonUiModel(legs.toImmutableList(), difference)
}

@StringRes
private fun transferComparisonStageResource(stage: String?): Int =
    when (stage) {
        "connect" -> R.string.diagnostics_http3_transfer_connect
        "tls" -> R.string.diagnostics_stages_tls
        "request" -> R.string.diagnostics_http3_request
        "headers" -> R.string.diagnostics_stages_headers
        "body" -> R.string.diagnostics_stages_body
        "integrity" -> R.string.diagnostics_http3_transfer_integrity
        "complete" -> R.string.diagnostics_transfer_complete
        "not_run" -> R.string.diagnostics_stages_not_reached
        else -> R.string.diagnostics_stages_unknown
    }

@Composable
internal fun VerifiedTransferComparisonRows(probe: DiagnosticsProbeResultUiModel) {
    if (probe.isEchTransferComparison) EchTransferRows(probe)
    val comparison = remember(probe) { probe.http3TransferComparison() } ?: return
    comparison.legs.forEach { leg ->
        SettingsRow(
            title = stringResource(leg.protocol),
            subtitle =
                stringResource(
                    R.string.diagnostics_stages_state,
                    stringResource(leg.stage),
                    stringResource(leg.status),
                ),
            value = leg.receivedBytes?.let { stringResource(R.string.diagnostics_stages_bytes, it) },
            showDivider = true,
        )
    }
    SettingsRow(
        title = stringResource(R.string.diagnostics_http3_transfer_difference),
        value = stringResource(comparison.difference),
        showDivider = false,
    )
}
