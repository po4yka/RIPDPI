package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.effectiveTcpChainSteps
import com.poyka.ripdpi.data.effectiveUdpChainSteps
import com.poyka.ripdpi.data.setGroupActivationFilterCompat
import com.poyka.ripdpi.data.setStrategyChains
import com.poyka.ripdpi.proto.AppSettings

fun RipDpiProxyUIPreferences.applyStrategyToSettings(
    settings: AppSettings,
    applyTcp: Boolean,
    applyQuic: Boolean,
): AppSettings {
    require(applyTcp || applyQuic)
    require(!applyTcp || (chains.tcpRotation == null && chains.payloadDisable.isEmpty()))
    return settings
        .toBuilder()
        .apply {
            if (applyTcp) {
                setDesyncHttp(protocols.desyncHttp)
                setDesyncHttps(protocols.desyncHttps)
                setTcpFastOpen(listen.tcpFastOpen)
                setCustomTtl(listen.customTtl)
                setDefaultTtl(if (listen.customTtl) listen.defaultTtl else 0)
                setGroupActivationFilterCompat(chains.groupActivationFilter)
                setDesyncAnyProtocol(chains.anyProtocol)
                applyFakePacketPreferences(this@applyStrategyToSettings, includeUdp = false)
                applyParserAndAdaptivePreferences(this@applyStrategyToSettings)
            }
            if (applyQuic) {
                setDesyncUdp(protocols.desyncUdp)
                applyQuicPreferences(this@applyStrategyToSettings)
                setUdpFakeProfile(fakePackets.udpFakeProfile)
                setQuicBindLowPort(fakePackets.quicBindLowPort)
                setQuicMigrateAfterHandshake(fakePackets.quicMigrateAfterHandshake)
            }
            setStrategyChains(
                tcpSteps = if (applyTcp) chains.tcpSteps else settings.effectiveTcpChainSteps(),
                udpSteps = if (applyQuic) chains.udpSteps else settings.effectiveUdpChainSteps(),
            )
        }.build()
}
