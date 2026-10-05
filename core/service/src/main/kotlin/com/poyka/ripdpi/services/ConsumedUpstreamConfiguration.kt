package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedRipDpiAmneziaWgConfig
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.ResolvedRipDpiWarpConfig
import com.poyka.ripdpi.serialization.RipDpiEncodeDefaultsJson

/** Immutable service-private material captured from the exact configuration passed to start. */
internal class ConsumedUpstreamConfiguration private constructor(
    private val canonical: String,
    val selection: com.poyka.ripdpi.data.RuntimeConfigurationSelection,
) {
    fun identityMaterial(): String = canonical

    override fun toString(): String = "ConsumedUpstreamConfiguration([REDACTED])"

    companion object {
        fun relay(config: ResolvedRipDpiRelayConfig) =
            ConsumedUpstreamConfiguration(
                RipDpiEncodeDefaultsJson.encodeToString(ResolvedRipDpiRelayConfig.serializer(), config),
                com.poyka.ripdpi.data.RuntimeConfigurationSelection(
                    "native",
                    relayKind = config.kind,
                    transport =
                        config.vlessTransport.takeIf {
                            config.kind ==
                                "vless" ||
                                config.kind == "vless_reality"
                        },
                    profileId = config.profileId,
                ),
            )

        fun warp(config: ResolvedRipDpiWarpConfig) =
            ConsumedUpstreamConfiguration(
                RipDpiEncodeDefaultsJson.encodeToString(
                    ResolvedRipDpiWarpConfig.serializer(),
                    config.copy(
                        deviceId = "",
                        accessToken = "",
                        publicKey = "",
                        interfaceAddressV6 = null,
                        accountKind = "",
                        endpointSelectionMode = "",
                        manualEndpoint =
                            com.poyka.ripdpi.core
                                .RipDpiWarpManualEndpointConfig(),
                        scannerEnabled = false,
                        scannerParallelism = 0,
                        scannerMaxRttMs = 0,
                    ),
                ),
                com.poyka.ripdpi.data.RuntimeConfigurationSelection(
                    "native",
                    transport = "wireguard",
                    relayKind = "warp",
                    profileId = config.profileId,
                ),
            )

        fun awg(config: ResolvedRipDpiAmneziaWgConfig) =
            ConsumedUpstreamConfiguration(
                RipDpiEncodeDefaultsJson.encodeToString(ResolvedRipDpiAmneziaWgConfig.serializer(), config),
                com.poyka.ripdpi.data.RuntimeConfigurationSelection(
                    "native",
                    relayKind = "amneziawg",
                    transport = config.carrier.name.lowercase(),
                    profileId = config.profileId,
                ),
            )
    }
}
