package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.RuntimeConfigurationSelection
import com.poyka.ripdpi.platform.StringResolver

internal fun RuntimeConfigurationSelection.confirmedSelectionLabels(strings: StringResolver): List<String> =
    listOfNotNull(
        when (this.provider) {
            "xray" -> "Xray"
            "native" -> "RIPDPI"
            else -> strings.getString(R.string.diagnostics_field_unknown)
        },
        this.relayKind?.let { kind ->
            when (kind) {
                "warp" -> "WARP"
                "amneziawg" -> "AmneziaWG"
                "vless", "vless_reality" -> "VLESS"
                "shadowsocks" -> "Shadowsocks"
                "ssh" -> "SSH"
                "tor" -> "Tor"
                else -> null
            }
        },
        transport.confirmedTransportLabel(),
    )
