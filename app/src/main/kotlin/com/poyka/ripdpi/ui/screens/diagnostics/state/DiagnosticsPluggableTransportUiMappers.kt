package com.poyka.ripdpi.ui.screens.diagnostics.state

import androidx.annotation.StringRes
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.dpich.PluggableTransportResult
import com.poyka.ripdpi.diagnostics.dpich.PtProbeStatus
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.collections.immutable.toPersistentList

internal fun PluggableTransportResult.toUiModel(
    privacyModeEnabled: Boolean,
    stringResolver: StringResolver,
): DiagnosticsPluggableTransportToolUiModel =
    DiagnosticsPluggableTransportToolUiModel(
        state = DiagnosticsPluggableTransportState.Complete,
        summary = stringResolver.getString(R.string.diagnostics_pt_complete),
        summaryResId = R.string.diagnostics_pt_complete,
        rows =
            listOf(
                R.string.diagnostics_pt_label_obfs4 to obfs4Tcp,
                R.string.diagnostics_pt_label_snowflake to snowflakeBroker,
                R.string.diagnostics_pt_label_stun to snowflakeStun,
                R.string.diagnostics_pt_label_meek to meekHead,
            ).map { (labelResId, trace) ->
                val statusResId = trace.status.displayLabelResId()
                DiagnosticsPluggableTransportRowUiModel(
                    label = stringResolver.getString(labelResId),
                    status = statusResId?.let { stringResolver.getString(it) } ?: "HTTP ${trace.httpStatusCode}",
                    detail = trace.target.ifBlank { stringResolver.getString(R.string.diagnostics_pt_no_detail) },
                    tone = trace.status.tone(),
                    statusResId = statusResId,
                    labelResId = labelResId,
                    detailResId = R.string.diagnostics_pt_no_detail.takeIf { trace.target.isBlank() },
                )
            }.toPersistentList(),
        privacyModeEnabled = privacyModeEnabled,
    )

@StringRes
private fun PtProbeStatus.displayLabelResId(): Int? =
    when (this) {
        PtProbeStatus.CONNECTED -> R.string.diagnostics_local_value_connected
        PtProbeStatus.HTTP_RESPONSE -> null
        PtProbeStatus.BINDING_RESPONSE -> R.string.diagnostics_pt_status_binding_response
        PtProbeStatus.NOT_CONFIGURED -> R.string.diagnostics_pt_status_not_configured
        PtProbeStatus.TIMEOUT -> R.string.diagnostics_stages_timed_out
        PtProbeStatus.NETWORK_ERROR -> R.string.diagnostics_pt_status_network_error
        PtProbeStatus.INVALID_RESPONSE -> R.string.diagnostics_pt_status_invalid_response
        PtProbeStatus.INVALID_TARGET -> R.string.diagnostics_pt_status_invalid_target
    }

private fun PtProbeStatus.tone(): DiagnosticsTone =
    when (this) {
        PtProbeStatus.CONNECTED, PtProbeStatus.BINDING_RESPONSE -> DiagnosticsTone.Positive
        PtProbeStatus.HTTP_RESPONSE, PtProbeStatus.NOT_CONFIGURED -> DiagnosticsTone.Neutral
        else -> DiagnosticsTone.Warning
    }
