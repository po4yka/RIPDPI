package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.strategyLaneFamilyLabel
import com.poyka.ripdpi.diagnostics.BypassStrategySignature
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * One labeled row per strategy setting. Protocol, lane and provider names
 * (TCP, QUIC, DNS, HTTP) are technical tokens and stay untranslated.
 */
internal fun BypassStrategySignature.localizedHomeStrategyFacets(
    strings: StringResolver,
): ImmutableList<HomeModeSummaryFacet> =
    buildList {
        fun facet(
            label: String,
            value: String,
        ) = add(HomeModeSummaryFacet(label, value))
        if (protocolToggles.isNotEmpty()) {
            facet(
                strings.getString(R.string.adaptive_split_summary_label_protocols),
                protocolToggles.joinToString(", "),
            )
        }
        facet(
            strings.getString(R.string.host_autolearn_section_title),
            strings.getString(
                when (hostAutolearn) {
                    "command_line" -> R.string.host_autolearn_cli_status_title
                    "enabled" -> R.string.config_local_bypass_enabled
                    else -> R.string.config_local_bypass_disabled
                },
            ),
        )
        // No TCP row: the panel title is the chain summary, which always names the TCP steps.
        quicStrategyFamily?.let {
            facet(
                "QUIC",
                when (it) {
                    "quic_disabled" -> strings.getString(R.string.adaptive_fallback_badge_disabled)
                    else -> strategyLaneFamilyLabel(it)
                },
            )
        }
        dnsStrategyLabel?.let { facet("DNS", it) }
        if (tlsRecordSplitEnabled) {
            facet(
                // Several locales word this as a mid-sentence phrase; as a row label it starts capitalized.
                strings.getString(R.string.blockcheck_bypass_tls_record_split).replaceFirstChar { it.titlecase() },
                strings.getString(R.string.config_local_bypass_enabled),
            )
        }
        routeGroup?.let { facet(strings.getString(R.string.home_stat_route), it) }
    }.toImmutableList()
