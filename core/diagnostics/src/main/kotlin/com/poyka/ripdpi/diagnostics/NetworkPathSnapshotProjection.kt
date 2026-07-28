package com.poyka.ripdpi.diagnostics

import android.annotation.SuppressLint
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import com.poyka.ripdpi.data.NetworkPathAssociationActiveDefault
import com.poyka.ripdpi.data.NetworkPathAssociationServiceBinder
import com.poyka.ripdpi.data.NetworkPathAssociationUnknown
import com.poyka.ripdpi.data.NetworkPathObservation
import com.poyka.ripdpi.data.boundedNetworkPathCount
import com.poyka.ripdpi.data.networkPathBandwidthBand
import com.poyka.ripdpi.data.networkPathCountWasTruncated
import com.poyka.ripdpi.data.networkPathMtuBand
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

internal data class NetworkPathCapabilities(
    val transport: String,
    val isVpn: Boolean,
    val hasInternet: Boolean,
    val validated: Boolean,
    val captivePortal: Boolean,
)

internal fun resolvePathValidationEvidence(
    permissionAvailable: Boolean,
    activePath: NetworkPathCapabilities?,
    underlay: NetworkPathObservation,
): NetworkPathValidationEvidence {
    if (!permissionAvailable) {
        return NetworkPathValidationEvidence(captureStatus = "permission_unavailable")
    }

    val vpn = activePath?.takeIf { it.isVpn }
    val authoritativeUnderlay = underlay.takeIf { it.association == NetworkPathAssociationServiceBinder }
    return NetworkPathValidationEvidence(
        captureStatus = "captured",
        underlayAssociation = authoritativeUnderlay?.association ?: NetworkPathAssociationUnknown,
        underlayGeneration = authoritativeUnderlay?.generation,
        underlayPresent = authoritativeUnderlay?.let { true },
        underlayTransport = authoritativeUnderlay?.transport,
        underlayInternet = authoritativeUnderlay?.hasInternet,
        underlayValidated = authoritativeUnderlay?.validated,
        underlayCaptivePortal = authoritativeUnderlay?.captivePortal,
        vpnAssociation = vpn?.let { NetworkPathAssociationActiveDefault } ?: NetworkPathAssociationUnknown,
        vpnPresent = vpn != null,
        vpnInternet = vpn?.hasInternet,
        vpnValidated = vpn?.validated,
        vpnCaptivePortal = vpn?.captivePortal,
    )
}

internal fun NetworkCapabilities.toNetworkPathCapabilities(): NetworkPathCapabilities =
    NetworkPathCapabilities(
        transport = resolveNetworkTransport(this),
        isVpn = hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        hasInternet = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        validated = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        captivePortal = hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
    )

@SuppressLint("MissingPermission")
internal fun captureCurrentPathValidationEvidence(
    connectivityManager: ConnectivityManager,
    permissionAvailable: Boolean,
    underlay: NetworkPathObservation,
): NetworkPathValidationEvidence =
    resolvePathValidationEvidence(
        permissionAvailable = permissionAvailable,
        activePath =
            if (permissionAvailable) {
                connectivityManager.activeNetwork
                    ?.let(connectivityManager::getNetworkCapabilities)
                    ?.toNetworkPathCapabilities()
            } else {
                null
            },
        underlay = underlay,
    )

internal fun sanitizeAuthoritativeUnderlayObservation(observation: NetworkPathObservation): NetworkPathObservation =
    observation.takeIf { it.association == NetworkPathAssociationServiceBinder } ?: NetworkPathObservation()

internal fun projectActiveVpnObservation(
    capabilities: NetworkCapabilities?,
    linkProperties: LinkProperties?,
    generation: Long?,
): NetworkPathObservation {
    if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true || generation == null) {
        return NetworkPathObservation()
    }
    return projectPathObservation(
        capabilities = capabilities,
        linkProperties = linkProperties,
        association = NetworkPathAssociationActiveDefault,
        generation = generation,
    )
}

private fun projectPathObservation(
    capabilities: NetworkCapabilities,
    linkProperties: LinkProperties?,
    association: String,
    generation: Long,
): NetworkPathObservation {
    val addresses = linkProperties?.linkAddresses.orEmpty()
    val routes = linkProperties?.routes.orEmpty()
    val dnsServers = linkProperties?.dnsServers.orEmpty()
    return NetworkPathObservation(
        association = association,
        generation = generation,
        transport =
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                "vpn"
            } else {
                resolveNetworkTransport(capabilities)
            },
        hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        captivePortal = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
        metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        roaming = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING),
        suspended = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED),
        congested =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED)
            } else {
                null
            },
        restricted = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED),
        addressFamilies = addresses.map { it.address }.networkPathFamilies(),
        defaultRouteFamilies = routes.filter { it.isDefaultRoute }.map { it.destination.address }.networkPathFamilies(),
        dnsServerFamilies = dnsServers.networkPathFamilies(),
        addressCount = boundedNetworkPathCount(addresses.size),
        routeCount = boundedNetworkPathCount(routes.size),
        dnsServerCount = boundedNetworkPathCount(dnsServers.size),
        countsTruncated =
            networkPathCountWasTruncated(addresses.size) ||
                networkPathCountWasTruncated(routes.size) ||
                networkPathCountWasTruncated(dnsServers.size),
        nat64Present = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && linkProperties?.nat64Prefix != null,
        privateDnsCategory = linkProperties.networkPathPrivateDnsCategory(),
        mtuBand = networkPathMtuBand(linkProperties?.mtu),
        upstreamBandwidthBand = networkPathBandwidthBand(capabilities.linkUpstreamBandwidthKbps),
        downstreamBandwidthBand = networkPathBandwidthBand(capabilities.linkDownstreamBandwidthKbps),
    )
}

private fun List<InetAddress>.networkPathFamilies(): List<String> =
    mapNotNull { address ->
        when (address) {
            is Inet4Address -> "ipv4"
            is Inet6Address -> "ipv6"
            else -> null
        }
    }.distinct().sorted()

private fun LinkProperties?.networkPathPrivateDnsCategory(): String =
    when {
        this == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> "unknown"
        !isPrivateDnsActive -> "inactive"
        privateDnsServerName.isNullOrBlank() -> "opportunistic"
        else -> "strict"
    }

internal class ActiveVpnPathGenerationTracker {
    private var lastKey: Any? = null
    private var lastShape: NetworkPathObservation? = null
    private var generation = 0L

    @Synchronized
    fun generationFor(
        key: Any,
        shape: NetworkPathObservation,
    ): Long {
        if (key != lastKey || shape != lastShape) {
            generation += 1
            lastKey = key
            lastShape = shape
        }
        return generation
    }
}
