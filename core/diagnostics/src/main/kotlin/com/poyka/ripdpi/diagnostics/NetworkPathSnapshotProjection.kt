package com.poyka.ripdpi.diagnostics

import android.annotation.SuppressLint
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import androidx.annotation.RequiresApi
import com.poyka.ripdpi.data.NetworkPathAssociationActiveDefault
import com.poyka.ripdpi.data.NetworkPathAssociationServiceBinder
import com.poyka.ripdpi.data.NetworkPathAssociationUnknown
import com.poyka.ripdpi.data.NetworkPathObservation
import com.poyka.ripdpi.data.VpnRouteAppRoutingShape
import com.poyka.ripdpi.data.VpnRouteCallbackState
import com.poyka.ripdpi.data.VpnRouteConsistency
import com.poyka.ripdpi.data.VpnRouteEvidence
import com.poyka.ripdpi.data.VpnRouteLifecycleState
import com.poyka.ripdpi.data.VpnRouteOwnerVerification
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
    vpnRouteEvidence: VpnRouteEvidenceSnapshot? = null,
): NetworkPathValidationEvidence {
    if (!permissionAvailable) {
        return NetworkPathValidationEvidence(
            captureStatus = "permission_unavailable",
            vpnRouteEvidence = vpnRouteEvidence,
        )
    }

    val vpn = activePath?.takeIf { it.isVpn }
    val authoritativeUnderlay = underlay.takeIf { it.association == NetworkPathAssociationServiceBinder }
    return NetworkPathValidationEvidence(
        captureStatus = "captured",
        callingDefaultObserverRole = "calling_uid_default",
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
        vpnRouteEvidence = vpnRouteEvidence,
    )
}

internal fun VpnRouteEvidence.toDiagnosticsSnapshot(): VpnRouteEvidenceSnapshot =
    VpnRouteEvidenceSnapshot(
        observerRole = observerRole.takeIf { it == "vpn_owner_service" } ?: "unavailable",
        observerSource = observerSource.takeIf { it == "service_network_callback" } ?: "unavailable",
        lifecycleGeneration = lifecycle?.generation,
        lifecycleState = lifecycle?.state.toWireValue(),
        callbackState = callbackState.toWireValue(),
        callbackRevision = callbackRevision,
        ownerVerification = ownerVerification.toWireValue(),
        evidenceAgeBand = evidenceAgeBand.takeIf(::isSupportedEvidenceAgeBand) ?: "unknown",
        intendedDefaultRouteFamilies = lifecycle?.intendedDefaultRouteFamilies.orEmpty().filterRouteFamilies(),
        observedDefaultRouteFamilies = observedDefaultRouteFamilies.filterRouteFamilies(),
        addressFamilies = lifecycle?.addressFamilies.orEmpty().filterRouteFamilies(),
        dnsServerFamilies = lifecycle?.dnsServerFamilies.orEmpty().filterRouteFamilies(),
        appRoutingShape = lifecycle?.appRoutingShape.toWireValue(),
        configuredAppCount = lifecycle?.configuredAppCount?.let(::boundedNetworkPathCount),
        ownPackageExcluded = lifecycle?.ownPackageExcluded,
        mtuBand = lifecycle?.mtuBand?.takeIf(::isSupportedMtuBand) ?: "unknown",
        metered = lifecycle?.metered,
        appliedTunnelReceiptGeneration = lifecycle?.appliedTunnelReceiptGeneration,
        routeConsistency = routeConsistency.toWireValue(),
        vpnPresent = vpnPresent,
        hasInternet = hasInternet,
        validated = validated,
        captivePortal = captivePortal,
        forwardingOutcome = forwardingOutcome.takeIf(AllowedForwardingOutcomes::contains) ?: "unavailable",
        forwardingLifecycleGeneration = forwardingLifecycleGeneration,
        forwardingTerminal = forwardingTerminal,
    ).sanitizeForArchive()

private fun VpnRouteLifecycleState?.toWireValue(): String =
    when (this) {
        VpnRouteLifecycleState.Intended -> "intended"
        VpnRouteLifecycleState.Established -> "established"
        VpnRouteLifecycleState.BridgeReady -> "bridge_ready"
        VpnRouteLifecycleState.FailClosed -> "fail_closed"
        VpnRouteLifecycleState.Closed -> "closed"
        null -> "unavailable"
    }

private fun VpnRouteCallbackState.toWireValue(): String =
    when (this) {
        VpnRouteCallbackState.Unavailable -> "unavailable"
        VpnRouteCallbackState.Awaiting -> "awaiting"
        VpnRouteCallbackState.TimedOut -> "timed_out"
        VpnRouteCallbackState.Complete -> "complete"
        VpnRouteCallbackState.Lost -> "lost"
    }

private fun VpnRouteOwnerVerification.toWireValue(): String =
    when (this) {
        VpnRouteOwnerVerification.Verified -> "verified"
        VpnRouteOwnerVerification.Unavailable -> "unavailable"
    }

private fun VpnRouteAppRoutingShape?.toWireValue(): String =
    when (this) {
        VpnRouteAppRoutingShape.AllowOnly -> "allow_only"
        VpnRouteAppRoutingShape.Disallow -> "disallow"
        null -> "unavailable"
    }

private fun VpnRouteConsistency.toWireValue(): String =
    when (this) {
        VpnRouteConsistency.Consistent -> "consistent"
        VpnRouteConsistency.Mismatch -> "mismatch"
        VpnRouteConsistency.Unavailable -> "unavailable"
    }

private fun List<String>.filterRouteFamilies(): List<String> =
    filter { it == "ipv4" || it == "ipv6" }.distinct().sorted()

private fun isSupportedEvidenceAgeBand(value: String): Boolean =
    value == "fresh" || value == "recent" || value == "stale" || value == "unknown"

private fun isSupportedMtuBand(value: String): Boolean =
    value == "sub_ipv6_minimum" ||
        value == "reduced" ||
        value == "standard" ||
        value == "jumbo" ||
        value == "unknown"

private val AllowedLifecycleStates =
    setOf("intended", "established", "bridge_ready", "fail_closed", "closed", "unavailable")
private val AllowedCallbackStates = setOf("unavailable", "awaiting", "timed_out", "complete", "lost")
private val AllowedOwnerVerifications = setOf("verified", "unavailable")
private val AllowedAppRoutingShapes = setOf("allow_only", "disallow")
private val AllowedRouteConsistencies = setOf("consistent", "mismatch", "unavailable")
private val AllowedForwardingOutcomes =
    setOf(
        "unavailable",
        "fail_closed",
        "evidence_unavailable",
        "evidence_unavailable_partial",
        "no_flow",
        "tun_ingress_no_upstream",
        "upstream_open_no_payload",
        "outbound_only",
        "tun_return_without_proxy_outbound",
        "proxy_outbound_observed",
        "cross_layer_return_observed",
    )

internal fun VpnRouteEvidenceSnapshot.sanitizeForArchive(): VpnRouteEvidenceSnapshot =
    copy(
        observerRole = observerRole.takeIf { it == "vpn_owner_service" } ?: "unavailable",
        observerSource = observerSource.takeIf { it == "service_network_callback" } ?: "unavailable",
        lifecycleState = lifecycleState.takeIf(AllowedLifecycleStates::contains) ?: "unavailable",
        callbackState = callbackState.takeIf(AllowedCallbackStates::contains) ?: "unavailable",
        ownerVerification = ownerVerification.takeIf(AllowedOwnerVerifications::contains) ?: "unavailable",
        evidenceAgeBand = evidenceAgeBand.takeIf(::isSupportedEvidenceAgeBand) ?: "unknown",
        intendedDefaultRouteFamilies = intendedDefaultRouteFamilies.filterRouteFamilies(),
        observedDefaultRouteFamilies = observedDefaultRouteFamilies.filterRouteFamilies(),
        addressFamilies = addressFamilies.filterRouteFamilies(),
        dnsServerFamilies = dnsServerFamilies.filterRouteFamilies(),
        appRoutingShape = appRoutingShape.takeIf(AllowedAppRoutingShapes::contains) ?: "unavailable",
        configuredAppCount = configuredAppCount?.let(::boundedNetworkPathCount),
        mtuBand = mtuBand.takeIf(::isSupportedMtuBand) ?: "unknown",
        routeConsistency = routeConsistency.takeIf(AllowedRouteConsistencies::contains) ?: "unavailable",
        forwardingOutcome = forwardingOutcome.takeIf(AllowedForwardingOutcomes::contains) ?: "unavailable",
        forwardingLifecycleGeneration =
            forwardingLifecycleGeneration.takeIf { forwardingOutcome in AllowedForwardingOutcomes - "unavailable" },
        forwardingTerminal =
            forwardingTerminal.takeIf {
                forwardingOutcome in AllowedForwardingOutcomes - "unavailable"
            },
    )

internal fun NetworkPathValidationEvidence.sanitizeVpnRouteEvidenceForArchive(): NetworkPathValidationEvidence =
    copy(
        callingDefaultObserverRole =
            callingDefaultObserverRole.takeIf { it == "calling_uid_default" } ?: "unavailable",
        vpnRouteEvidence = vpnRouteEvidence?.sanitizeForArchive(),
    )

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
    vpnRouteEvidence: VpnRouteEvidenceSnapshot? = null,
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
        vpnRouteEvidence = vpnRouteEvidence,
    )

internal fun sanitizeAuthoritativeUnderlayObservation(observation: NetworkPathObservation): NetworkPathObservation =
    observation.takeIf { it.association == NetworkPathAssociationServiceBinder } ?: NetworkPathObservation()

internal fun projectActiveVpnObservation(
    capabilities: NetworkCapabilities?,
    linkProperties: LinkProperties?,
    generation: Long?,
    platformSdk: Int = Build.VERSION.SDK_INT,
): NetworkPathObservation {
    if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true || generation == null) {
        return NetworkPathObservation()
    }
    return projectPathObservation(
        capabilities = capabilities,
        linkProperties = linkProperties,
        association = NetworkPathAssociationActiveDefault,
        generation = generation,
        platformSdk = platformSdk,
    )
}

private fun projectPathObservation(
    capabilities: NetworkCapabilities,
    linkProperties: LinkProperties?,
    association: String,
    generation: Long,
    platformSdk: Int,
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
            if (platformSdk >= Build.VERSION_CODES.P) {
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
        nat64Present = linkProperties.networkPathNat64Present(platformSdk),
        privateDnsCategory = linkProperties.networkPathPrivateDnsCategory(platformSdk),
        mtuBand = networkPathMtuBand(linkProperties.networkPathMtu(platformSdk)),
        upstreamBandwidthBand = networkPathBandwidthBand(capabilities.linkUpstreamBandwidthKbps),
        downstreamBandwidthBand = networkPathBandwidthBand(capabilities.linkDownstreamBandwidthKbps),
    )
}

private fun LinkProperties?.networkPathNat64Present(platformSdk: Int): Boolean =
    this != null &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        platformSdk >= Build.VERSION_CODES.R &&
        nat64PresentOnR()

@RequiresApi(Build.VERSION_CODES.R)
private fun LinkProperties.nat64PresentOnR(): Boolean = nat64Prefix != null

private fun LinkProperties?.networkPathMtu(platformSdk: Int): Int? =
    if (this != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && platformSdk >= Build.VERSION_CODES.Q) {
        mtuOnQ()
    } else {
        null
    }

@RequiresApi(Build.VERSION_CODES.Q)
private fun LinkProperties.mtuOnQ(): Int = mtu

private fun List<InetAddress>.networkPathFamilies(): List<String> =
    mapNotNull { address ->
        when (address) {
            is Inet4Address -> "ipv4"
            is Inet6Address -> "ipv6"
            else -> null
        }
    }.distinct().sorted()

private fun LinkProperties?.networkPathPrivateDnsCategory(platformSdk: Int): String =
    when {
        this == null ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
            platformSdk < Build.VERSION_CODES.P -> "unknown"

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
