package com.poyka.ripdpi.services

internal data class VpnUpstreamNetworkBindingAudit(
    val readsActiveNetwork: Boolean,
    val activeNetworkAvailable: Boolean,
    val usesSetUnderlyingNetworks: Boolean = true,
    val usesBindSocket: Boolean = false,
    val usesAllowBypass: Boolean = false,
) {
    val bindsActiveNetwork: Boolean
        get() = readsActiveNetwork && activeNetworkAvailable

    fun logSummary(): String =
        "read_active_network=$readsActiveNetwork, active_network_available=$activeNetworkAvailable, " +
            "set_underlying_networks=$usesSetUnderlyingNetworks, bind_socket=$usesBindSocket, " +
            "allow_bypass=$usesAllowBypass, binds_active_network=$bindsActiveNetwork"
}

internal fun resolveVpnUpstreamNetworkBindingAudit(
    hasNetworkStatePermission: Boolean,
    activeNetworkAvailable: Boolean,
): VpnUpstreamNetworkBindingAudit =
    VpnUpstreamNetworkBindingAudit(
        readsActiveNetwork = hasNetworkStatePermission,
        activeNetworkAvailable = activeNetworkAvailable,
    )
