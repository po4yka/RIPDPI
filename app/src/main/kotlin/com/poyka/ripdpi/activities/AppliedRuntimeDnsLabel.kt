package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.RuntimeConfigurationDns
import com.poyka.ripdpi.platform.StringResolver

internal fun RuntimeConfigurationDns.confirmedDnsLabel(strings: StringResolver): String =
    listOfNotNull(
        strings.getString(
            if (mode ==
                com.poyka.ripdpi.data.DnsModeEncrypted
            ) {
                R.string.dns_mode_doh
            } else {
                R.string.dns_mode_plain
            },
        ),
        protocol?.let { protocol ->
            when (protocol) {
                "doh" -> strings.getString(R.string.dns_protocol_doh)
                "dot" -> strings.getString(R.string.dns_protocol_dot)
                "dnscrypt" -> strings.getString(R.string.dns_protocol_dnscrypt)
                else -> null
            }
        },
    ).joinToString(" · ")
