package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProxyProfile

internal fun List<ProxyProfile>.preferTcpReserves(): List<ProxyProfile> = sortedBy { if (it.isTcpReserve()) 0 else 1 }

private fun ProxyProfile.isTcpReserve(): Boolean =
    when (this) {
        is ProxyProfile.Vless, is ProxyProfile.VlessReality, is ProxyProfile.Trojan,
        is ProxyProfile.Shadowsocks, is ProxyProfile.AnyTls, is ProxyProfile.Ssh,
        -> true

        is ProxyProfile.Mieru -> protocol == "tcp"

        is ProxyProfile.Naive -> !quic

        else -> false
    }
