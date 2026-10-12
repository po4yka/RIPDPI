package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.rkn.RknAggregateVerdict
import com.poyka.ripdpi.diagnostics.rkn.RknConfidence
import com.poyka.ripdpi.diagnostics.rkn.RknVerdict
import com.poyka.ripdpi.platform.StringResolver

internal fun RknAggregateVerdict.localizedHeadline(strings: StringResolver): String =
    strings.getString(
        when (this) {
            RknAggregateVerdict.InconclusiveControlDown -> R.string.diagnostics_rkn_headline_control_down
            RknAggregateVerdict.InconclusiveAllTimeout -> R.string.diagnostics_rkn_headline_all_timeout
            RknAggregateVerdict.NotBlockedOrVpn -> R.string.diagnostics_rkn_headline_not_blocked
            is RknAggregateVerdict.InBlockedZoneHigh -> R.string.diagnostics_rkn_headline_high
            RknAggregateVerdict.InBlockedZoneMedium -> R.string.diagnostics_rkn_headline_medium
            RknAggregateVerdict.PartialBlocks -> R.string.diagnostics_rkn_headline_partial
        },
    )

internal fun RknAggregateVerdict.localizedConfidenceNote(strings: StringResolver): String =
    when (this) {
        RknAggregateVerdict.InconclusiveControlDown -> {
            strings.getString(R.string.diagnostics_rkn_note_control_down)
        }

        RknAggregateVerdict.InconclusiveAllTimeout -> {
            strings.getString(R.string.diagnostics_rkn_note_all_timeout)
        }

        RknAggregateVerdict.NotBlockedOrVpn -> {
            strings.getString(R.string.diagnostics_rkn_note_not_blocked)
        }

        is RknAggregateVerdict.InBlockedZoneHigh -> {
            strings.getString(R.string.diagnostics_rkn_note_high_format, highConfidenceBlocked, effectiveTotal)
        }

        RknAggregateVerdict.InBlockedZoneMedium -> {
            strings.getString(R.string.diagnostics_rkn_note_medium)
        }

        RknAggregateVerdict.PartialBlocks -> {
            strings.getString(R.string.diagnostics_rkn_note_partial)
        }
    }

internal fun RknVerdict.localizedLabel(
    confidence: RknConfidence,
    strings: StringResolver,
): String {
    val label =
        strings.getString(
            when (this) {
                RknVerdict.OK -> R.string.diagnostics_rkn_verdict_ok
                RknVerdict.TIMEOUT, RknVerdict.DOWN -> R.string.diagnostics_rkn_verdict_down
                RknVerdict.UNKNOWN -> R.string.diagnostics_rkn_verdict_unknown
                RknVerdict.DNS_BLOCK -> R.string.diagnostics_rkn_verdict_dns
                RknVerdict.TCP_RESET -> R.string.diagnostics_rkn_verdict_tcp_reset
                RknVerdict.TLS_BLOCK -> R.string.diagnostics_rkn_verdict_tls_dpi
                RknVerdict.HTTP_STUB -> R.string.diagnostics_rkn_verdict_http_stub
            },
        )
    return when (this) {
        RknVerdict.OK, RknVerdict.TIMEOUT, RknVerdict.DOWN, RknVerdict.UNKNOWN -> {
            label
        }

        else -> {
            when (confidence) {
                RknConfidence.HIGH -> label
                RknConfidence.MEDIUM -> strings.getString(R.string.diagnostics_rkn_verdict_likely_format, label)
                else -> strings.getString(R.string.diagnostics_rkn_verdict_uncertain_format, label)
            }
        }
    }
}
