package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.NetworkPathObservation

internal fun NetworkPathObservation.toRemoteDeviceAcceptanceUnderlay(): RemoteDeviceAcceptanceUnderlay =
    RemoteDeviceAcceptanceUnderlay(
        mtuBand = mtuBand,
        hasIpv4Address = "ipv4" in addressFamilies,
        hasIpv6Address = "ipv6" in addressFamilies,
        hasIpv4DefaultRoute = "ipv4" in defaultRouteFamilies,
        hasIpv6DefaultRoute = "ipv6" in defaultRouteFamilies,
        hasIpv4Dns = "ipv4" in dnsServerFamilies,
        hasIpv6Dns = "ipv6" in dnsServerFamilies,
        nat64Advertised = nat64Present,
    )

internal fun RemoteDeviceAcceptanceUnderlay.relayUdpPayloadFamilies(): Set<RelayUdpPayloadFamily> =
    buildSet {
        if (hasIpv4Address || hasIpv4DefaultRoute || hasIpv4Dns) add(RelayUdpPayloadFamily.Ipv4)
        if (hasIpv6Address || hasIpv6DefaultRoute || hasIpv6Dns) add(RelayUdpPayloadFamily.Ipv6)
        if (isEmpty()) add(RelayUdpPayloadFamily.Ipv4)
    }
