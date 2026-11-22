package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsProbeResultUiModel

internal val DiagnosticsProbeResultUiModel.isEchTransferComparison: Boolean
    get() =
        probeType == "throughput_window" &&
            (outcome == "ech_transfer_comparison_recorded" || outcome == "ech_transfer_comparison_inconclusive")

internal data class EchTransferRow(
    @StringRes val mode: Int,
    @StringRes val status: Int,
    val receivedBytes: Long?,
)

private val echTransferModes =
    listOf(
        "tls13" to R.string.diagnostics_stages_lane_tls13,
        "echGrease" to R.string.diagnostics_ech_transfer_grease,
        "ech" to R.string.diagnostics_stages_lane_ech,
    )

internal fun DiagnosticsProbeResultUiModel.echTransferRows(): List<EchTransferRow> {
    if (!isEchTransferComparison) return emptyList()

    val values = details.associate { it.label to it.value }
    return echTransferModes.map { (prefix, mode) ->
        EchTransferRow(
            mode = mode,
            status = echTransferStatusResource(values["${prefix}Outcome"]),
            receivedBytes = values["${prefix}Bytes"]?.toLongOrNull()?.takeIf { it >= 0 },
        )
    }
}

@StringRes
private fun echTransferStatusResource(outcome: String?): Int =
    when (outcome) {
        "verified" -> R.string.diagnostics_verified_download_complete
        "ech_config_missing" -> R.string.diagnostics_ech_transfer_config_missing
        "ech_config_error" -> R.string.diagnostics_dpi_suite_ech_config_unusable
        "connection_failed" -> R.string.diagnostics_transfer_stop_setup
        "tls_failed" -> R.string.diagnostics_replay_error_tls_handshake_failed
        "body_timeout" -> R.string.diagnostics_ech_transfer_body_timeout
        "response_timeout" -> R.string.diagnostics_ech_transfer_response_timeout
        "early_eof" -> R.string.diagnostics_verified_download_early_eof
        "size_mismatch" -> R.string.diagnostics_verified_download_size_mismatch
        "hash_mismatch" -> R.string.diagnostics_verified_download_hash_mismatch
        "http_failed" -> R.string.diagnostics_ech_transfer_http_failed
        "not_run" -> R.string.diagnostics_stages_not_reached
        else -> R.string.diagnostics_stages_unknown
    }

@Composable
internal fun EchTransferRows(probe: DiagnosticsProbeResultUiModel) {
    val rows = remember(probe) { probe.echTransferRows() }
    rows.forEachIndexed { index, row ->
        SettingsRow(
            title = stringResource(row.mode),
            subtitle = stringResource(row.status),
            value = row.receivedBytes?.let { stringResource(R.string.diagnostics_stages_bytes, it) },
            showDivider = index != rows.lastIndex,
        )
    }
}
