package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.decodeRipDpiProxyUiPreferences
import com.poyka.ripdpi.core.deriveStrategyLaneFamilies
import com.poyka.ripdpi.core.stripRipDpiRuntimeContext
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.RememberedNetworkPolicyJson
import com.poyka.ripdpi.data.StrategyLaneFamilies
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import com.poyka.ripdpi.data.strategyFamily
import com.poyka.ripdpi.data.toVpnDnsPolicyJson
import com.poyka.ripdpi.proto.AppSettings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class RememberedConnectionPolicyMatcher
    @Inject
    constructor(
        private val rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
    ) {
        suspend fun findValidatedMatch(
            networkScopeKey: String,
            mode: Mode,
        ): RememberedNetworkPolicyEntity? =
            rememberedNetworkPolicyStore.findValidatedMatch(
                fingerprintHash = networkScopeKey,
                mode = mode,
            )

        fun deriveLaneFamilies(
            proxyPreferences: RipDpiProxyPreferences,
            activeDns: ActiveDnsSettings,
        ): StrategyLaneFamilies? =
            (proxyPreferences as? RipDpiProxyUIPreferences)?.deriveStrategyLaneFamilies(activeDns = activeDns)

        fun deriveLaneFamilies(
            proxyConfigJson: String,
            activeDns: ActiveDnsSettings,
        ): StrategyLaneFamilies? =
            decodeRipDpiProxyUiPreferences(proxyConfigJson)?.deriveStrategyLaneFamilies(activeDns = activeDns)

        fun baselinePolicy(
            settings: AppSettings,
            mode: Mode,
            fingerprintSnapshot: NetworkFingerprint?,
            networkScopeKey: String?,
            baselinePreferences: RipDpiProxyPreferences,
            baselineVpnDnsSelection: VpnDnsSelection,
            baselineLaneFamilies: StrategyLaneFamilies?,
        ): RememberedNetworkPolicyJson? {
            if (settings.enableCmdSettings || fingerprintSnapshot == null || networkScopeKey == null) return null
            return RememberedNetworkPolicyJson(
                fingerprintHash = networkScopeKey,
                mode = mode.preferenceValue,
                summary = fingerprintSnapshot.summary(),
                proxyConfigJson = stripRipDpiRuntimeContext(baselinePreferences.toNativeConfigJson()),
                vpnDnsPolicy =
                    if (mode == Mode.VPN) {
                        baselineVpnDnsSelection.activeDns.toVpnDnsPolicyJson()
                    } else {
                        null
                    },
                winningTcpStrategyFamily = baselineLaneFamilies?.tcpStrategyFamily,
                winningQuicStrategyFamily = baselineLaneFamilies?.quicStrategyFamily,
                winningDnsStrategyFamily = baselineLaneFamilies?.dnsStrategyFamily,
            )
        }

        fun appliedPolicy(
            rememberedPolicy: RememberedNetworkPolicyJson?,
            rememberedLaneFamilies: StrategyLaneFamilies?,
            effectiveDns: ActiveDnsSettings,
        ): RememberedNetworkPolicyJson? =
            rememberedPolicy?.copy(
                winningTcpStrategyFamily =
                    rememberedPolicy.winningTcpStrategyFamily ?: rememberedLaneFamilies?.tcpStrategyFamily,
                winningQuicStrategyFamily =
                    rememberedPolicy.winningQuicStrategyFamily ?: rememberedLaneFamilies?.quicStrategyFamily,
                winningDnsStrategyFamily =
                    rememberedPolicy.winningDnsStrategyFamily ?: effectiveDns.strategyFamily(),
            )
    }
