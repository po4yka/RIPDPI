package com.poyka.ripdpi.e2e

import android.util.Log
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.DefaultVpnTunnelSessionProvider
import com.poyka.ripdpi.services.VpnAppRoutingPlan
import com.poyka.ripdpi.services.VpnProfileInterface
import com.poyka.ripdpi.services.VpnTunnelBuilderHost
import com.poyka.ripdpi.services.VpnTunnelNetworkParameters
import com.poyka.ripdpi.services.VpnTunnelSession
import com.poyka.ripdpi.services.VpnTunnelSessionProvider

/** Observes actual provider entries, including failed calls; never substitutes a Builder or session. */
internal class ObservedVpnTunnelSessionProvider : VpnTunnelSessionProvider {
    private val delegate = DefaultVpnTunnelSessionProvider()
    private val observationLock = Any()
    private var callSequence = 0L
    private var previous: CallInputs? = null

    override suspend fun establish(
        host: VpnTunnelBuilderHost,
        dns: String,
        ipv6: Boolean,
        appRoutingPlan: VpnAppRoutingPlan,
        interfaceSettings: AppSettings,
        httpProxyPort: Int?,
        networkParameters: VpnTunnelNetworkParameters,
        profileInterface: VpnProfileInterface?,
    ): VpnTunnelSession {
        synchronized(observationLock) {
            callSequence++
            if (callSequence <= MaxEvents) {
                val current =
                    CallInputs(
                        dns,
                        ipv6,
                        appRoutingPlan,
                        interfaceSettings,
                        httpProxyPort,
                        networkParameters,
                        profileInterface,
                    )
                Log.i(LogTag, current.equalityMetadata(callSequence, previous))
                previous = current
            } else {
                previous = null
            }
        }
        // No catch, retry, host wrapping or argument rewriting: preserve the real result/exception.
        return delegate.establish(
            host,
            dns,
            ipv6,
            appRoutingPlan,
            interfaceSettings,
            httpProxyPort,
            networkParameters,
            profileInterface,
        )
    }

    // Raw inputs remain private and transient; this class deliberately has no generated toString.
    private class CallInputs(
        val dns: String,
        val ipv6: Boolean,
        val appRouting: VpnAppRoutingPlan,
        val settings: AppSettings,
        val httpProxy: Int?,
        val network: VpnTunnelNetworkParameters,
        val profileInterface: VpnProfileInterface?,
    ) {
        fun equalityMetadata(
            sequence: Long,
            prior: CallInputs?,
        ): String =
            buildString {
                append("callSequence=").append(sequence)
                append(" priorActualCallPresent=").append(prior != null)
                append(" dnsEqual=").append(prior?.let { dns == it.dns })
                append(" ipv6Equal=").append(prior?.let { ipv6 == it.ipv6 })
                append(" appRoutingEqual=").append(prior?.let { appRouting == it.appRouting })
                append(" httpProxyEqual=").append(prior?.let { httpProxy == it.httpProxy })
                append(" settingsDhtEqual=").append(
                    prior?.let {
                        settings.dhtMitigationMode ==
                            it.settings.dhtMitigationMode
                    },
                )
                append(" settingsFullTunnelEqual=").append(
                    prior?.let {
                        settings.fullTunnelMode ==
                            it.settings.fullTunnelMode
                    },
                )
                append(" networkMtuEqual=").append(prior?.let { network.tunnelMtu == it.network.tunnelMtu })
                append(" meteredEqual=").append(prior?.let { network.metered == it.network.metered })
                append(" encapsulationBudgetEqual=")
                    .append(
                        prior?.let {
                            network.appliedEncapsulationBudgetBytes ==
                                it.network.appliedEncapsulationBudgetBytes
                        },
                    )
                append(" profileInterfaceEqual=").append(prior?.let { profileInterface == it.profileInterface })
                // Full settings also contain native-only inputs: equality is not a Builder criterion.
                append(" fullSettingsEqual=").append(prior?.let { settings == it.settings })
                append(" fullSettingsBuilderCriterion=false")
            }
    }

    private companion object {
        const val MaxEvents = 8
        const val LogTag = "XrayBuilderInputs"
    }
}
