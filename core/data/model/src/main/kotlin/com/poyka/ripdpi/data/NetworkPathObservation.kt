package com.poyka.ripdpi.data

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

const val NetworkPathAssociationServiceBinder = "service_binder"
const val NetworkPathAssociationActiveDefault = "active_default"
const val NetworkPathAssociationUnknown = "unknown"

/**
 * Identifier-free Android network-path evidence safe for diagnostics persistence and export.
 *
 * Network handles, interface names, addresses, resolver values, SSIDs, and carrier identity
 * must be reduced to the categorical, family, count, or band fields below before crossing
 * this boundary.
 */
@Serializable
data class NetworkPathObservation(
    val association: String = NetworkPathAssociationUnknown,
    val generation: Long? = null,
    val transport: String = "unknown",
    val hasInternet: Boolean? = null,
    val validated: Boolean? = null,
    val captivePortal: Boolean? = null,
    val metered: Boolean? = null,
    val roaming: Boolean? = null,
    val suspended: Boolean? = null,
    val congested: Boolean? = null,
    val restricted: Boolean? = null,
    val addressFamilies: List<String> = emptyList(),
    val defaultRouteFamilies: List<String> = emptyList(),
    val dnsServerFamilies: List<String> = emptyList(),
    val addressCount: Int? = null,
    val routeCount: Int? = null,
    val dnsServerCount: Int? = null,
    val countsTruncated: Boolean = false,
    val nat64Present: Boolean? = null,
    val privateDnsCategory: String = "unknown",
    val mtuBand: String = "unknown",
    val upstreamBandwidthBand: String = "unknown",
    val downstreamBandwidthBand: String = "unknown",
)

/** Authoritative view of the physical network selected by RIPDPI's VPN binder. */
interface AuthoritativeVpnUnderlayObservationProvider {
    /** Monotonic signal used to refresh consumers after binder state changes. */
    val changes: StateFlow<Long>

    fun capture(): NetworkPathObservation
}

const val MaxNetworkPathAggregateCount = 32

private const val Ipv6MinimumMtu = 1_280
private const val ReducedMtuUpperBound = 1_492
private const val StandardMtuUpperBound = 1_500
private const val LowBandwidthUpperBoundKbps = 1_000
private const val MediumBandwidthUpperBoundKbps = 10_000
private const val HighBandwidthUpperBoundKbps = 100_000

fun boundedNetworkPathCount(size: Int): Int = size.coerceIn(0, MaxNetworkPathAggregateCount)

fun networkPathCountWasTruncated(size: Int): Boolean = size > MaxNetworkPathAggregateCount

fun networkPathMtuBand(mtu: Int?): String =
    when {
        mtu == null || mtu <= 0 -> "unknown"
        mtu < Ipv6MinimumMtu -> "sub_ipv6_minimum"
        mtu < ReducedMtuUpperBound -> "reduced"
        mtu <= StandardMtuUpperBound -> "standard"
        else -> "jumbo"
    }

fun networkPathBandwidthBand(kbps: Int?): String =
    when {
        kbps == null || kbps <= 0 -> "unknown"
        kbps < LowBandwidthUpperBoundKbps -> "low"
        kbps < MediumBandwidthUpperBoundKbps -> "medium"
        kbps < HighBandwidthUpperBoundKbps -> "high"
        else -> "very_high"
    }
