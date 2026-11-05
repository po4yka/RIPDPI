package com.poyka.ripdpi.core

import com.poyka.ripdpi.proto.AppSettings

fun RipDpiProxyUIPreferences.preservingUnmeasuredStrategyLanes(
    settings: AppSettings,
    measuredTcp: Boolean,
    measuredQuic: Boolean,
): RipDpiProxyUIPreferences {
    val current = RipDpiProxyUIPreferences.fromSettings(settings)
    val tcp = if (measuredTcp) this else current
    val udp = if (measuredQuic) this else current
    return RipDpiProxyUIPreferences(
        listen =
            listen.copy(
                tcpFastOpen = tcp.listen.tcpFastOpen,
                customTtl = tcp.listen.customTtl,
                defaultTtl = tcp.listen.defaultTtl,
            ),
        protocols =
            protocols.copy(
                desyncHttp = tcp.protocols.desyncHttp,
                desyncHttps = tcp.protocols.desyncHttps,
                desyncUdp = udp.protocols.desyncUdp,
            ),
        chains = tcp.chains.copy(udpSteps = udp.chains.udpSteps),
        fakePackets =
            tcp.fakePackets.copy(
                udpFakeProfile = udp.fakePackets.udpFakeProfile,
                quicBindLowPort = udp.fakePackets.quicBindLowPort,
                quicMigrateAfterHandshake = udp.fakePackets.quicMigrateAfterHandshake,
            ),
        parserEvasions = tcp.parserEvasions,
        adaptiveFallback = tcp.adaptiveFallback,
        quic = udp.quic,
        hosts = hosts,
        relay = relay,
        warp = warp,
        hostAutolearn = hostAutolearn,
        wsTunnel = wsTunnel,
        nativeLogLevel = nativeLogLevel,
        runtimeContext = runtimeContext,
        logContext = logContext,
        rootMode = rootMode,
        rootHelperSocketPath = rootHelperSocketPath,
        geoipDbPath = geoipDbPath,
        geositeDbPath = geositeDbPath,
        environmentKind = environmentKind,
        destinationRouting = destinationRouting,
        awg = awg,
    )
}
