package com.poyka.ripdpi.services

import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.annotation.RequiresApi
import com.poyka.ripdpi.data.AuthoritativeVpnUnderlayObservationProvider
import com.poyka.ripdpi.data.NetworkPathAssociationServiceBinder
import com.poyka.ripdpi.data.NetworkPathObservation
import com.poyka.ripdpi.data.boundedNetworkPathCount
import com.poyka.ripdpi.data.networkPathBandwidthBand
import com.poyka.ripdpi.data.networkPathCountWasTruncated
import com.poyka.ripdpi.data.networkPathMtuBand
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

internal data class DirectDnsUnderlaySnapshot(
    val network: Network,
    val generation: Long,
    val dnsServers: Set<InetAddress>,
)

private data class DirectDnsCapabilitySnapshot(
    val hasInternet: Boolean,
    val validated: Boolean,
    val notVpn: Boolean,
    val captivePortal: Boolean,
    val transport: String,
    val metered: Boolean,
    val roaming: Boolean,
    val suspended: Boolean,
    val congested: Boolean?,
    val restricted: Boolean,
    val upstreamBandwidthBand: String,
    val downstreamBandwidthBand: String,
) {
    val eligible: Boolean
        get() = isDirectDnsUnderlayEligible(hasInternet, validated, notVpn, captivePortal)
}

private data class DirectDnsLinkSnapshot(
    val addressFamilies: List<String>,
    val defaultRouteFamilies: List<String>,
    val dnsServerFamilies: List<String>,
    val addressCount: Int,
    val routeCount: Int,
    val dnsServerCount: Int,
    val countsTruncated: Boolean,
    val nat64Present: Boolean,
    val privateDnsCategory: String,
    val mtuBand: String,
)

private data class DirectDnsEligibilitySignal(
    val epoch: Long?,
    val eligible: Boolean,
    val generation: Long,
)

@Singleton
internal class DirectDnsUnderlayAuthority
    @Inject
    constructor() : AuthoritativeVpnUnderlayObservationProvider {
        private var epochCounter = 0L
        private var activeEpoch: Long? = null
        private var generation = 0L
        private var network: Network? = null
        private var capabilities: DirectDnsCapabilitySnapshot? = null
        private var dnsServers: Set<InetAddress>? = null
        private var link: DirectDnsLinkSnapshot? = null
        private val eligibility = MutableStateFlow(DirectDnsEligibilitySignal(null, false, generation))
        private val mutableChanges = MutableStateFlow(0L)

        override val changes: StateFlow<Long> = mutableChanges.asStateFlow()

        @Synchronized
        fun beginCallbackEpoch(): Long {
            epochCounter += 1
            activeEpoch = epochCounter
            clearSnapshot()
            publishEligibility(epochCounter)
            return epochCounter
        }

        @Synchronized
        fun onAvailable(
            epoch: Long,
            candidate: Network,
        ) {
            if (activeEpoch != epoch || network == candidate) return
            network = candidate
            capabilities = null
            dnsServers = null
            link = null
            advanceGeneration()
            publishEligibility(epoch)
        }

        @Synchronized
        fun onCapabilitiesChanged(
            epoch: Long,
            candidate: Network,
            value: NetworkCapabilities,
        ) {
            if (activeEpoch != epoch || network != candidate) return
            val next = value.toDirectDnsSnapshot()
            if (capabilities == next) return
            capabilities = next
            advanceGeneration()
            publishEligibility(epoch)
        }

        @Synchronized
        fun onLinkPropertiesChanged(
            epoch: Long,
            candidate: Network,
            value: LinkProperties,
        ) {
            if (activeEpoch != epoch || network != candidate) return
            val next = value.dnsServers.toSet()
            val nextLink = value.toDirectDnsLinkSnapshot()
            if (dnsServers == next && link == nextLink) return
            dnsServers = next
            link = nextLink
            advanceGeneration()
            publishEligibility(epoch)
        }

        @Synchronized
        fun onLost(
            epoch: Long,
            candidate: Network,
        ) {
            if (activeEpoch != epoch || network != candidate) return
            clearSnapshot()
            publishEligibility(epoch)
        }

        @Synchronized
        fun endCallbackEpoch(epoch: Long) {
            if (activeEpoch != epoch) return
            activeEpoch = null
            clearSnapshot()
            publishEligibility(epoch)
        }

        suspend fun awaitEligible(epoch: Long) {
            val signal = eligibility.first { it.epoch != epoch || it.eligible }
            check(signal.epoch == epoch && signal.eligible) { "VPN underlay callback epoch expired" }
        }

        @Synchronized
        fun snapshot(epoch: Long): DirectDnsUnderlaySnapshot? {
            val currentNetwork = network
            val currentDns = dnsServers
            val eligible = activeEpoch == epoch && capabilities?.eligible == true
            return currentNetwork?.takeIf { eligible }?.let { selected ->
                currentDns?.takeIf(Set<InetAddress>::isNotEmpty)?.let { dns ->
                    DirectDnsUnderlaySnapshot(selected, generation, dns)
                }
            }
        }

        @Synchronized
        fun observation(epoch: Long): Pair<Long, DirectDnsUnderlaySnapshot?>? =
            if (activeEpoch == epoch) generation to snapshot(epoch) else null

        @Synchronized
        override fun capture(): NetworkPathObservation {
            val currentNetwork = network.takeIf { activeEpoch != null }
            val capabilitySnapshot = capabilities
            val linkSnapshot = link
            return if (currentNetwork == null || capabilitySnapshot?.notVpn != true || linkSnapshot == null) {
                NetworkPathObservation()
            } else {
                NetworkPathObservation(
                    association = NetworkPathAssociationServiceBinder,
                    generation = generation,
                    transport = capabilitySnapshot.transport,
                    hasInternet = capabilitySnapshot.hasInternet,
                    validated = capabilitySnapshot.validated,
                    captivePortal = capabilitySnapshot.captivePortal,
                    metered = capabilitySnapshot.metered,
                    roaming = capabilitySnapshot.roaming,
                    suspended = capabilitySnapshot.suspended,
                    congested = capabilitySnapshot.congested,
                    restricted = capabilitySnapshot.restricted,
                    addressFamilies = linkSnapshot.addressFamilies,
                    defaultRouteFamilies = linkSnapshot.defaultRouteFamilies,
                    dnsServerFamilies = linkSnapshot.dnsServerFamilies,
                    addressCount = linkSnapshot.addressCount,
                    routeCount = linkSnapshot.routeCount,
                    dnsServerCount = linkSnapshot.dnsServerCount,
                    countsTruncated = linkSnapshot.countsTruncated,
                    nat64Present = linkSnapshot.nat64Present,
                    privateDnsCategory = linkSnapshot.privateDnsCategory,
                    mtuBand = linkSnapshot.mtuBand,
                    upstreamBandwidthBand = capabilitySnapshot.upstreamBandwidthBand,
                    downstreamBandwidthBand = capabilitySnapshot.downstreamBandwidthBand,
                )
            }
        }

        @Synchronized
        fun generationFor(
            candidate: Network,
            capabilities: NetworkCapabilities?,
            linkProperties: LinkProperties?,
        ): Long? {
            val current = activeEpoch?.let(::snapshot)
            val capturedLink = linkProperties?.toDirectDnsLinkSnapshot()
            val sameNetwork = current?.network == candidate
            val sameCapabilities = this.capabilities == capabilities?.toDirectDnsSnapshot()
            val sameDnsServers = current?.dnsServers == linkProperties?.dnsServers?.toSet()
            val sameLink = link == capturedLink
            val exactMatch = listOf(sameNetwork, sameCapabilities, sameDnsServers, sameLink).all { it }
            return current?.generation?.takeIf { exactMatch }
        }

        private fun clearSnapshot() {
            val alreadyClear = listOf(network, capabilities, dnsServers, link).all { it == null }
            if (alreadyClear) return
            network = null
            capabilities = null
            dnsServers = null
            link = null
            advanceGeneration()
        }

        private fun advanceGeneration() {
            generation += 1
            mutableChanges.value = generation
        }

        private fun publishEligibility(epoch: Long) {
            eligibility.value =
                DirectDnsEligibilitySignal(
                    epoch = activeEpoch,
                    eligible = activeEpoch == epoch && snapshot(epoch) != null,
                    generation = generation,
                )
        }
    }

private fun NetworkCapabilities.toDirectDnsSnapshot(): DirectDnsCapabilitySnapshot =
    DirectDnsCapabilitySnapshot(
        hasInternet = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        validated = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        notVpn = hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
        captivePortal = hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
        transport = pathTransport(),
        metered = !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        roaming = !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING),
        suspended = !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED),
        congested =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED)
            } else {
                null
            },
        restricted = !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED),
        upstreamBandwidthBand = networkPathBandwidthBand(linkUpstreamBandwidthKbps),
        downstreamBandwidthBand = networkPathBandwidthBand(linkDownstreamBandwidthKbps),
    )

private fun NetworkCapabilities.pathTransport(): String =
    when {
        hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> "other"
    }

private fun LinkProperties.toDirectDnsLinkSnapshot(): DirectDnsLinkSnapshot {
    val addressSize = linkAddresses.size
    val routeSize = routes.size
    val dnsSize = dnsServers.size
    return DirectDnsLinkSnapshot(
        addressFamilies = linkAddresses.map { it.address }.pathFamilies(),
        defaultRouteFamilies = routes.filter { it.isDefaultRoute }.map { it.destination.address }.pathFamilies(),
        dnsServerFamilies = dnsServers.pathFamilies(),
        addressCount = boundedNetworkPathCount(addressSize),
        routeCount = boundedNetworkPathCount(routeSize),
        dnsServerCount = boundedNetworkPathCount(dnsSize),
        countsTruncated =
            networkPathCountWasTruncated(addressSize) ||
                networkPathCountWasTruncated(routeSize) ||
                networkPathCountWasTruncated(dnsSize),
        nat64Present = nat64PresentCompat(),
        privateDnsCategory = privateDnsCategory(),
        mtuBand = networkPathMtuBand(mtuCompat()),
    )
}

private fun LinkProperties.nat64PresentCompat(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && nat64PresentOnR()

@RequiresApi(Build.VERSION_CODES.R)
private fun LinkProperties.nat64PresentOnR(): Boolean = nat64Prefix != null

private fun LinkProperties.mtuCompat(): Int? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) mtuOnQ() else null

@RequiresApi(Build.VERSION_CODES.Q)
private fun LinkProperties.mtuOnQ(): Int = mtu

private fun List<InetAddress>.pathFamilies(): List<String> =
    mapNotNull { address ->
        when (address) {
            is Inet4Address -> "ipv4"
            is Inet6Address -> "ipv6"
            else -> null
        }
    }.distinct().sorted()

private fun LinkProperties.privateDnsCategory(): String =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        "unknown"
    } else {
        when {
            !isPrivateDnsActive -> "inactive"
            privateDnsServerName.isNullOrBlank() -> "opportunistic"
            else -> "strict"
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AuthoritativeVpnUnderlayObservationModule {
    @Binds
    @Singleton
    abstract fun bindAuthoritativeVpnUnderlayObservationProvider(
        authority: DirectDnsUnderlayAuthority,
    ): AuthoritativeVpnUnderlayObservationProvider
}
