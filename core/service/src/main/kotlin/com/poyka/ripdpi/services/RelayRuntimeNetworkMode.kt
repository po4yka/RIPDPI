package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RelaySocketProtection
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig

/** Service-owned network context applied after persisted relay profile resolution. */
internal enum class RelayRuntimeNetworkMode {
    Proxy,
    Vpn,
}

internal fun ResolvedRipDpiRelayConfig.withNetworkMode(mode: RelayRuntimeNetworkMode): ResolvedRipDpiRelayConfig =
    copy(
        socketProtection =
            when (mode) {
                RelayRuntimeNetworkMode.Proxy -> RelaySocketProtection.Inactive
                RelayRuntimeNetworkMode.Vpn -> RelaySocketProtection.VpnRequired
            },
    )
