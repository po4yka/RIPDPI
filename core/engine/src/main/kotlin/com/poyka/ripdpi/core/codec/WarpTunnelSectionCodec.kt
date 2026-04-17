package com.poyka.ripdpi.core.codec

import com.poyka.ripdpi.core.RipDpiWarpAmneziaConfig
import com.poyka.ripdpi.core.RipDpiWarpConfig
import com.poyka.ripdpi.core.RipDpiWarpManualEndpointConfig
import com.poyka.ripdpi.core.RipDpiWsTunnelConfig
import kotlinx.serialization.Serializable

@Serializable
internal data class NativeWarpManualEndpointConfig(
    val host: String = "",
    val ipv4: String = "",
    val ipv6: String = "",
    val port: Int = 2408,
)

@Serializable
internal data class NativeWarpAmneziaConfig(
    val enabled: Boolean = false,
    val jc: Int = 0,
    val jmin: Int = 0,
    val jmax: Int = 0,
    val h1: Long = 0L,
    val h2: Long = 0L,
    val h3: Long = 0L,
    val h4: Long = 0L,
    val s1: Int = 0,
    val s2: Int = 0,
    val s3: Int = 0,
    val s4: Int = 0,
)

@Serializable
internal data class NativeWarpConfig(
    val enabled: Boolean = false,
    val routeMode: String = "off",
    val routeHosts: String = "",
    val builtInRulesEnabled: Boolean = true,
    val endpointSelectionMode: String = "automatic",
    val manualEndpoint: NativeWarpManualEndpointConfig = NativeWarpManualEndpointConfig(),
    val scannerEnabled: Boolean = true,
    val scannerParallelism: Int = 10,
    val scannerMaxRttMs: Int = 1500,
    val amneziaPreset: String = "off",
    val amnezia: NativeWarpAmneziaConfig = NativeWarpAmneziaConfig(),
    val localSocksHost: String = "127.0.0.1",
    val localSocksPort: Int = 11888,
)

@Serializable
internal data class NativeWsTunnelConfig(
    val enabled: Boolean = false,
    val mode: String? = null,
)

internal object WarpSectionCodec {
    fun toModel(value: NativeWarpConfig): RipDpiWarpConfig =
        RipDpiWarpConfig(
            enabled = value.enabled,
            routeMode = value.routeMode,
            routeHosts = value.routeHosts,
            builtInRulesEnabled = value.builtInRulesEnabled,
            endpointSelectionMode = value.endpointSelectionMode,
            manualEndpoint =
                RipDpiWarpManualEndpointConfig(
                    host = value.manualEndpoint.host,
                    ipv4 = value.manualEndpoint.ipv4,
                    ipv6 = value.manualEndpoint.ipv6,
                    port = value.manualEndpoint.port,
                ),
            scannerEnabled = value.scannerEnabled,
            scannerParallelism = value.scannerParallelism,
            scannerMaxRttMs = value.scannerMaxRttMs,
            amneziaPreset = value.amneziaPreset,
            amnezia =
                RipDpiWarpAmneziaConfig(
                    enabled = value.amnezia.enabled,
                    jc = value.amnezia.jc,
                    jmin = value.amnezia.jmin,
                    jmax = value.amnezia.jmax,
                    h1 = value.amnezia.h1,
                    h2 = value.amnezia.h2,
                    h3 = value.amnezia.h3,
                    h4 = value.amnezia.h4,
                    s1 = value.amnezia.s1,
                    s2 = value.amnezia.s2,
                    s3 = value.amnezia.s3,
                    s4 = value.amnezia.s4,
                ),
            localSocksHost = value.localSocksHost,
            localSocksPort = value.localSocksPort,
        )

    fun toNative(value: RipDpiWarpConfig): NativeWarpConfig =
        NativeWarpConfig(
            enabled = value.enabled,
            routeMode = value.routeMode,
            routeHosts = value.routeHosts,
            builtInRulesEnabled = value.builtInRulesEnabled,
            endpointSelectionMode = value.endpointSelectionMode,
            manualEndpoint =
                NativeWarpManualEndpointConfig(
                    host = value.manualEndpoint.host,
                    ipv4 = value.manualEndpoint.ipv4,
                    ipv6 = value.manualEndpoint.ipv6,
                    port = value.manualEndpoint.port,
                ),
            scannerEnabled = value.scannerEnabled,
            scannerParallelism = value.scannerParallelism,
            scannerMaxRttMs = value.scannerMaxRttMs,
            amneziaPreset = value.amneziaPreset,
            amnezia =
                NativeWarpAmneziaConfig(
                    enabled = value.amnezia.enabled,
                    jc = value.amnezia.jc,
                    jmin = value.amnezia.jmin,
                    jmax = value.amnezia.jmax,
                    h1 = value.amnezia.h1,
                    h2 = value.amnezia.h2,
                    h3 = value.amnezia.h3,
                    h4 = value.amnezia.h4,
                    s1 = value.amnezia.s1,
                    s2 = value.amnezia.s2,
                    s3 = value.amnezia.s3,
                    s4 = value.amnezia.s4,
                ),
            localSocksHost = value.localSocksHost,
            localSocksPort = value.localSocksPort,
        )
}

internal object WsTunnelSectionCodec {
    fun toModel(value: NativeWsTunnelConfig): RipDpiWsTunnelConfig =
        RipDpiWsTunnelConfig(
            enabled = value.enabled,
            mode = value.mode,
        )

    fun toNative(value: RipDpiWsTunnelConfig): NativeWsTunnelConfig =
        NativeWsTunnelConfig(
            enabled = value.enabled,
            mode = value.mode,
        )
}
