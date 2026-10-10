package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.strategyLaneFamilyLabel
import com.poyka.ripdpi.diagnostics.BypassStrategySignature
import com.poyka.ripdpi.platform.StringResolver

internal fun BypassStrategySignature.localizedHomeStrategyLabel(strings: StringResolver): String =
    buildList {
        add("$mode $chainSummary")
        add(protocolToggles.joinToString("/"))
        add(
            if (hostAutolearn == "command_line") {
                strings.getString(R.string.host_autolearn_cli_status_title)
            } else {
                val state =
                    when (hostAutolearn) {
                        "enabled" -> R.string.config_local_bypass_enabled
                        else -> R.string.config_local_bypass_disabled
                    }
                "${strings.getString(R.string.host_autolearn_section_title)} ${strings.getString(state)}"
            },
        )
        val lanes =
            listOfNotNull(
                tcpStrategyFamily?.let { "TCP ${strategyLaneFamilyLabel(it)}" },
                quicStrategyFamily?.let {
                    val label =
                        when (it) {
                            "quic_disabled" -> strings.getString(R.string.adaptive_fallback_badge_disabled)
                            else -> strategyLaneFamilyLabel(it)
                        }
                    "QUIC $label"
                },
                dnsStrategyLabel?.let { "DNS $it" },
            )
        if (lanes.isNotEmpty()) add(lanes.joinToString(" / "))
        if (tlsRecordSplitEnabled) add(strings.getString(R.string.blockcheck_bypass_tls_record_split))
        routeGroup?.let { add("${strings.getString(R.string.home_stat_route)} $it") }
    }.joinToString(" · ")
