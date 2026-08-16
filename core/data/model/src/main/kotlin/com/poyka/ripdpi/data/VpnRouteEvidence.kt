package com.poyka.ripdpi.data

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val VpnRouteFamilyIpv4 = "ipv4"
const val VpnRouteFamilyIpv6 = "ipv6"
const val VpnRouteObservationConvergenceMillis = 5_000L

@Serializable
enum class VpnRouteLifecycleState {
    @SerialName("intended")
    Intended,

    @SerialName("established")
    Established,

    @SerialName("bridge_ready")
    BridgeReady,

    @SerialName("fail_closed")
    FailClosed,

    @SerialName("closed")
    Closed,
}

@Serializable
enum class VpnRouteAppRoutingShape {
    @SerialName("allow_only")
    AllowOnly,

    @SerialName("disallow")
    Disallow,
}

@Serializable
enum class VpnRouteCallbackState {
    @SerialName("unavailable")
    Unavailable,

    @SerialName("awaiting")
    Awaiting,

    @SerialName("timed_out")
    TimedOut,

    @SerialName("complete")
    Complete,

    @SerialName("lost")
    Lost,
}

@Serializable
enum class VpnRouteOwnerVerification {
    @SerialName("verified")
    Verified,

    @SerialName("unavailable")
    Unavailable,
}

@Serializable
enum class VpnRouteConsistency {
    @SerialName("consistent")
    Consistent,

    @SerialName("mismatch")
    Mismatch,

    @SerialName("unavailable")
    Unavailable,
}

@Serializable
data class VpnRouteLifecycleReceipt(
    val generation: Long,
    val state: VpnRouteLifecycleState,
    val intendedDefaultRouteFamilies: List<String>,
    val addressFamilies: List<String>,
    val dnsServerFamilies: List<String>,
    val appRoutingShape: VpnRouteAppRoutingShape,
    val configuredAppCount: Int,
    val ownPackageExcluded: Boolean,
    val ipv6Intent: Boolean,
    val mtuBand: String,
    val metered: Boolean?,
    val appliedTunnelReceiptGeneration: Long? = null,
)

/** Identifier-free evidence about the Android VPN route created by RIPDPI. */
@Serializable
data class VpnRouteEvidence(
    val observerRole: String = "vpn_owner_service",
    val observerSource: String = "service_network_callback",
    val lifecycle: VpnRouteLifecycleReceipt? = null,
    val callbackState: VpnRouteCallbackState = VpnRouteCallbackState.Unavailable,
    val callbackRevision: Long? = null,
    val ownerVerification: VpnRouteOwnerVerification = VpnRouteOwnerVerification.Unavailable,
    val evidenceAgeBand: String = "unknown",
    val observedDefaultRouteFamilies: List<String> = emptyList(),
    val routeConsistency: VpnRouteConsistency = VpnRouteConsistency.Unavailable,
    val vpnPresent: Boolean? = null,
    val hasInternet: Boolean? = null,
    val validated: Boolean? = null,
    val captivePortal: Boolean? = null,
    val forwardingOutcome: String = "unavailable",
    val forwardingLifecycleGeneration: Long? = null,
    val forwardingTerminal: Boolean? = null,
)

interface VpnRouteEvidenceProvider {
    val changes: StateFlow<Long>

    fun capture(): VpnRouteEvidence

    fun convergenceRefreshDelayMillis(): Long? = null
}
