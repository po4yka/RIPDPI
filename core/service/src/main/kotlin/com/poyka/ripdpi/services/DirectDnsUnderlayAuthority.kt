package com.poyka.ripdpi.services

import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
) {
    val eligible: Boolean
        get() = isDirectDnsUnderlayEligible(hasInternet, validated, notVpn, captivePortal)
}

@Singleton
internal class DirectDnsUnderlayAuthority
    @Inject
    constructor() {
        private var epochCounter = 0L
        private var activeEpoch: Long? = null
        private var generation = 0L
        private var network: Network? = null
        private var capabilities: DirectDnsCapabilitySnapshot? = null
        private var dnsServers: Set<InetAddress>? = null
        private val eligibleEpoch = MutableStateFlow<Long?>(null)

        @Synchronized
        fun beginCallbackEpoch(): Long {
            epochCounter += 1
            activeEpoch = epochCounter
            clearSnapshot()
            eligibleEpoch.value = null
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
            generation += 1
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
            generation += 1
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
            if (dnsServers == next) return
            dnsServers = next
            generation += 1
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
            eligibleEpoch.value = null
        }

        suspend fun awaitEligible(epoch: Long) {
            eligibleEpoch.first { it == epoch }
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
        fun generationFor(
            candidate: Network,
            capabilities: NetworkCapabilities?,
            linkProperties: LinkProperties?,
        ): Long? {
            val current = activeEpoch?.let(::snapshot)
            val exactMatch =
                current?.network == candidate &&
                    this.capabilities == capabilities?.toDirectDnsSnapshot() &&
                    current.dnsServers == linkProperties?.dnsServers?.toSet()
            return current?.generation?.takeIf { exactMatch }
        }

        private fun clearSnapshot() {
            if (network == null && capabilities == null && dnsServers == null) return
            network = null
            capabilities = null
            dnsServers = null
            generation += 1
        }

        private fun publishEligibility(epoch: Long) {
            eligibleEpoch.value = epoch.takeIf { snapshot(epoch) != null }
        }
    }

private fun NetworkCapabilities.toDirectDnsSnapshot(): DirectDnsCapabilitySnapshot =
    DirectDnsCapabilitySnapshot(
        hasInternet = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        validated = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        notVpn = hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
        captivePortal = hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
    )
