package com.poyka.ripdpi.services

import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import com.poyka.ripdpi.core.defaultTun2SocksTunnelMtu
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

data class VpnTunnelNetworkParameters(
    val tunnelMtu: Int = defaultTun2SocksTunnelMtu,
    val metered: Boolean = false,
    val appliedEncapsulationBudgetBytes: Int? = null,
)

data class VpnTunnelAppliedNetworkReceipt(
    val generation: Long,
    val appliedTunnelMtu: Int,
    val appliedEncapsulationBudgetBytes: Int?,
    val metered: Boolean?,
    val effectiveEgress: String,
)

@Singleton
class VpnTunnelAppliedNetworkReceiptStore
    @Inject
    constructor() {
        private val nextGeneration = AtomicLong()
        private val receipt = AtomicReference<VpnTunnelAppliedNetworkReceipt?>()

        fun publish(
            parameters: VpnTunnelNetworkParameters,
            apiLevel: Int,
        ): VpnTunnelAppliedNetworkReceipt =
            VpnTunnelAppliedNetworkReceipt(
                generation = nextGeneration.incrementAndGet(),
                appliedTunnelMtu = parameters.tunnelMtu,
                appliedEncapsulationBudgetBytes = parameters.appliedEncapsulationBudgetBytes,
                metered = parameters.metered.takeIf { apiLevel >= Build.VERSION_CODES.Q },
                effectiveEgress = AppliedTun2SocksEgress,
            ).also(
                receipt::set,
            )

        fun invalidate() {
            receipt.set(null)
        }

        fun snapshot(): VpnTunnelAppliedNetworkReceipt? = receipt.get()
    }

internal object VpnTunnelNetworkPolicy {
    private const val TunnelMtuFloor = 1280
    private const val TunnelMtuCeiling = defaultTun2SocksTunnelMtu
    internal const val TunnelEncapsulationBudgetBytes = 80

    fun parameters(
        linkProperties: LinkProperties?,
        capabilities: NetworkCapabilities?,
    ): VpnTunnelNetworkParameters {
        val linkMtu = observedLinkMtu(linkProperties)
        val tunnelMtu = linkMtu?.let(::clampTunnelMtu) ?: defaultTun2SocksTunnelMtu
        return VpnTunnelNetworkParameters(
            tunnelMtu = tunnelMtu,
            metered = isMetered(capabilities),
            appliedEncapsulationBudgetBytes = linkMtu?.minus(tunnelMtu)?.takeIf { it >= 0 },
        )
    }

    fun tunnelMtu(linkProperties: LinkProperties?): Int =
        observedLinkMtu(linkProperties)?.let(::clampTunnelMtu) ?: defaultTun2SocksTunnelMtu

    fun isMetered(capabilities: NetworkCapabilities?): Boolean =
        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true

    private fun observedLinkMtu(linkProperties: LinkProperties?): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            linkProperties?.mtu?.takeIf { it > 0 }
        } else {
            null
        }

    private fun clampTunnelMtu(linkMtu: Int): Int =
        (linkMtu - TunnelEncapsulationBudgetBytes).coerceIn(TunnelMtuFloor, TunnelMtuCeiling)
}

private const val AppliedTun2SocksEgress = "tun2socks"
