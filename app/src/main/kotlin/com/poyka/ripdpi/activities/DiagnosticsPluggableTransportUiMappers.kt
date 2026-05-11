package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.dpich.PluggableTransportResult
import com.poyka.ripdpi.diagnostics.dpich.PtVerdict
import kotlinx.collections.immutable.toPersistentList
import java.util.Locale

internal fun PluggableTransportResult.toUiModel(
    privacyModeEnabled: Boolean,
): DiagnosticsPluggableTransportToolUiModel =
    DiagnosticsPluggableTransportToolUiModel(
        state = DiagnosticsPluggableTransportState.Complete,
        summary = "Checked obfs4, Snowflake, and meek endpoint reachability.",
        rows =
            listOf(
                "obfs4" to obfs4,
                "snowflake" to snowflake,
                "meek" to meek,
            ).map { (label, verdict) ->
                DiagnosticsPluggableTransportRowUiModel(
                    label = label,
                    verdict = verdict.displayLabel(),
                    detail = verdict.detail(),
                    tone = verdict.tone(),
                )
            }.toPersistentList(),
        privacyModeEnabled = privacyModeEnabled,
    )

private fun PtVerdict.displayLabel(): String =
    this::class
        .simpleName
        ?.removePrefix("Pt")
        ?.replace(UppercaseBoundaryRegex, " $1")
        ?.lowercase(Locale.US)
        ?: "unknown"

private fun PtVerdict.detail(): String =
    when (this) {
        is PtVerdict.PtOk -> detail
        is PtVerdict.PtBridgeBlocked -> reason
        is PtVerdict.PtBrokerBlocked -> reason
        is PtVerdict.PtFrontBlocked -> reason
        is PtVerdict.PtStunBlocked -> reason
        is PtVerdict.PtError -> reason
    }.ifBlank { "no detail" }

private fun PtVerdict.tone(): DiagnosticsTone =
    when (this) {
        is PtVerdict.PtOk -> DiagnosticsTone.Positive
        is PtVerdict.PtError -> DiagnosticsTone.Negative
        else -> DiagnosticsTone.Warning
    }

private val UppercaseBoundaryRegex = Regex("([A-Z])")
