package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.dpich.PluggableTransportResult
import com.poyka.ripdpi.diagnostics.dpich.PtVerdict
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.collections.immutable.toPersistentList
import java.util.Locale

internal fun PluggableTransportResult.toUiModel(
    privacyModeEnabled: Boolean,
    stringResolver: StringResolver,
): DiagnosticsPluggableTransportToolUiModel =
    DiagnosticsPluggableTransportToolUiModel(
        state = DiagnosticsPluggableTransportState.Complete,
        summary = stringResolver.getString(R.string.diagnostics_pt_complete),
        rows =
            listOf(
                stringResolver.getString(R.string.diagnostics_pt_label_obfs4) to obfs4,
                stringResolver.getString(R.string.diagnostics_pt_label_snowflake) to snowflake,
                stringResolver.getString(R.string.diagnostics_pt_label_meek) to meek,
            ).map { (label, verdict) ->
                DiagnosticsPluggableTransportRowUiModel(
                    label = label,
                    verdict = verdict.displayLabel(stringResolver),
                    detail = verdict.detail(stringResolver),
                    tone = verdict.tone(),
                )
            }.toPersistentList(),
        privacyModeEnabled = privacyModeEnabled,
    )

private fun PtVerdict.displayLabel(stringResolver: StringResolver): String =
    this::class
        .simpleName
        ?.removePrefix("Pt")
        ?.replace(UppercaseBoundaryRegex, " $1")
        ?.lowercase(Locale.US)
        ?: stringResolver.getString(R.string.diagnostics_value_unknown)

private fun PtVerdict.detail(stringResolver: StringResolver): String =
    when (this) {
        is PtVerdict.PtOk -> detail
        is PtVerdict.PtBridgeBlocked -> reason
        is PtVerdict.PtBrokerBlocked -> reason
        is PtVerdict.PtFrontBlocked -> reason
        is PtVerdict.PtStunBlocked -> reason
        is PtVerdict.PtError -> reason
    }.ifBlank { stringResolver.getString(R.string.diagnostics_pt_no_detail) }

private fun PtVerdict.tone(): DiagnosticsTone =
    when (this) {
        is PtVerdict.PtOk -> DiagnosticsTone.Positive
        is PtVerdict.PtError -> DiagnosticsTone.Negative
        else -> DiagnosticsTone.Warning
    }

private val UppercaseBoundaryRegex = Regex("([A-Z])")
