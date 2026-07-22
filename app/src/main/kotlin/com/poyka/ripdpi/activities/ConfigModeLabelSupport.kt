package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.platform.StringResolver

internal fun Mode.startSenderName(stringResolver: StringResolver): String =
    stringResolver.getString(
        when (this) {
            Mode.VPN -> R.string.home_mode_vpn
            Mode.Proxy -> R.string.home_mode_proxy
        },
    )
