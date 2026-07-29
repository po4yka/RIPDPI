package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.NetworkPathObservation

internal fun NetworkPathObservation.toRemoteDeviceAcceptanceUnderlay(
    appliedNetworkReceipt: VpnTunnelAppliedNetworkReceipt? = null,
): RemoteDeviceAcceptanceUnderlay =
    RemoteDeviceAcceptanceUnderlay(
        mtuBand = mtuBand,
        hasIpv4Address = "ipv4" in addressFamilies,
        hasIpv6Address = "ipv6" in addressFamilies,
        hasIpv4DefaultRoute = "ipv4" in defaultRouteFamilies,
        hasIpv6DefaultRoute = "ipv6" in defaultRouteFamilies,
        hasIpv4Dns = "ipv4" in dnsServerFamilies,
        hasIpv6Dns = "ipv6" in dnsServerFamilies,
        nat64Advertised = nat64Present,
        appliedTunnelMtuBytes = appliedNetworkReceipt?.appliedTunnelMtu,
        configuredEncapsulationBudgetBytes = appliedNetworkReceipt?.appliedEncapsulationBudgetBytes,
        appliedTunnelMetered = appliedNetworkReceipt?.metered,
        appliedTunnelEgress = appliedNetworkReceipt?.effectiveEgress ?: "unavailable",
    )

internal fun NetworkPathObservation.mandatoryRelayUdpPayloadFamilies(): Set<RelayUdpPayloadFamily> =
    defaultRouteFamilies
        .mapNotNull { family ->
            when (family) {
                "ipv4" -> RelayUdpPayloadFamily.Ipv4
                "ipv6" -> RelayUdpPayloadFamily.Ipv6
                else -> null
            }
        }.toSet()
