package com.poyka.ripdpi.core.codec

import com.poyka.ripdpi.core.RipDpiWarpAmneziaConfig
import com.poyka.ripdpi.core.RipDpiWarpConfig
import com.poyka.ripdpi.core.RipDpiWarpManualEndpointConfig
import com.poyka.ripdpi.core.RipDpiWsTunnelConfig
import com.poyka.ripdpi.data.SecretString
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
    // AmneziaWG 2.0 I1..I5 special-junk frames as hex strings. Empty = unset.
    val i1: String = "",
    val i2: String = "",
    val i3: String = "",
    val i4: String = "",
    val i5: String = "",
    // Base64/hex 32-byte WireGuard preshared key ([Peer] PresharedKey); empty =
    // none. WARP itself uses no PSK; a generic AmneziaWG peer may.
    val presharedKey: String = "",
    // [Peer] PersistentKeepalive in seconds; 0 disables keepalive. Defaults to
    // WARP's historical 25s pin.
    val persistentKeepalive: Int = 25,
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
    val fakeSni: String? = null,
    val allowInsecureSni: Boolean = false,
    val cloudflareWorkerUrl: String? = null,
    val cloudflareWorkerCredentialRef: String? = null,
    val cloudflareWorkerBearer: SecretString? = null,
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
                    i1 = value.amnezia.i1,
                    i2 = value.amnezia.i2,
                    i3 = value.amnezia.i3,
                    i4 = value.amnezia.i4,
                    i5 = value.amnezia.i5,
                    presharedKey = value.amnezia.presharedKey,
                    persistentKeepalive = value.amnezia.persistentKeepalive,
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
                    i1 = value.amnezia.i1,
                    i2 = value.amnezia.i2,
                    i3 = value.amnezia.i3,
                    i4 = value.amnezia.i4,
                    i5 = value.amnezia.i5,
                    presharedKey = value.amnezia.presharedKey,
                    persistentKeepalive = value.amnezia.persistentKeepalive,
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
            fakeSni = value.fakeSni,
            allowInsecureSni = value.allowInsecureSni,
            cloudflareWorkerUrl = value.cloudflareWorkerUrl,
            cloudflareWorkerCredentialRef = value.cloudflareWorkerCredentialRef,
            cloudflareWorkerBearer = value.cloudflareWorkerBearer,
        )

    fun toNative(value: RipDpiWsTunnelConfig): NativeWsTunnelConfig =
        NativeWsTunnelConfig(
            enabled = value.enabled,
            mode = value.mode,
            fakeSni = value.fakeSni,
            allowInsecureSni = value.allowInsecureSni,
            cloudflareWorkerUrl = value.cloudflareWorkerUrl,
            cloudflareWorkerCredentialRef = value.cloudflareWorkerCredentialRef,
            cloudflareWorkerBearer = value.cloudflareWorkerBearer,
        )
}
