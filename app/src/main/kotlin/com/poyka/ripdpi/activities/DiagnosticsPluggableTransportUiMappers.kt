package com.poyka.ripdpi.activities

import androidx.annotation.StringRes
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.dpich.PluggableTransportResult
import com.poyka.ripdpi.diagnostics.dpich.PtVerdict
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
                stringResolver.getString(R.string.diagnostics_pt_label_obfs4) to obfs4,
                stringResolver.getString(R.string.diagnostics_pt_label_snowflake) to snowflake,
                stringResolver.getString(R.string.diagnostics_pt_label_meek) to meek,
            ).map { (label, verdict) ->
                val verdictResId = verdict.displayLabelResId()
                val rawDetail = verdict.rawDetail()
                DiagnosticsPluggableTransportRowUiModel(
                    label = label,
                    verdict = stringResolver.getString(verdictResId),
                    detail = rawDetail.ifBlank { stringResolver.getString(R.string.diagnostics_pt_no_detail) },
                    tone = verdict.tone(),
                    verdictResId = verdictResId,
                    detailResId = R.string.diagnostics_pt_no_detail.takeIf { rawDetail.isBlank() },
                )
            }.toPersistentList(),
        privacyModeEnabled = privacyModeEnabled,
    )

@StringRes
private fun PtVerdict.displayLabelResId(): Int =
    when (this) {
        is PtVerdict.PtOk -> R.string.diagnostics_pt_verdict_ok
        is PtVerdict.PtBridgeBlocked -> R.string.diagnostics_pt_verdict_bridge_blocked
        is PtVerdict.PtBrokerBlocked -> R.string.diagnostics_pt_verdict_broker_blocked
        is PtVerdict.PtFrontBlocked -> R.string.diagnostics_pt_verdict_front_blocked
        is PtVerdict.PtStunBlocked -> R.string.diagnostics_pt_verdict_stun_blocked
        is PtVerdict.PtError -> R.string.diagnostics_pt_verdict_error
    }

private fun PtVerdict.rawDetail(): String =
    when (this) {
        is PtVerdict.PtOk -> detail
        is PtVerdict.PtBridgeBlocked -> reason
        is PtVerdict.PtBrokerBlocked -> reason
        is PtVerdict.PtFrontBlocked -> reason
        is PtVerdict.PtStunBlocked -> reason
        is PtVerdict.PtError -> reason
    }

private fun PtVerdict.tone(): DiagnosticsTone =
    when (this) {
        is PtVerdict.PtOk -> DiagnosticsTone.Positive
        is PtVerdict.PtError -> DiagnosticsTone.Negative
        else -> DiagnosticsTone.Warning
    }
