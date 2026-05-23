package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import kotlinx.serialization.Serializable

/**
 * Stable runtime contract for the WARP (WireGuard) native session, implemented
 * by `RipDpiWarp` in `:core:engine`. New `:core:service` / WARP features depend
 * on this interface rather than on the JNI implementation module.
 */
interface RipDpiWarpRuntime {
    suspend fun start(config: ResolvedRipDpiWarpConfig): Int

    suspend fun awaitReady(timeoutMillis: Long = defaultWarpReadyTimeoutMs)

    suspend fun stop()

    suspend fun pollTelemetry(): NativeRuntimeSnapshot
}

internal const val defaultWarpReadyTimeoutMs = 5_000L

@Serializable
data class ResolvedRipDpiWarpEndpoint(
    val host: String,
    val ipv4: String? = null,
    val ipv6: String? = null,
    val port: Int,
    val source: String = "provisioning",
)

@Serializable
data class ResolvedRipDpiWarpConfig(
    val enabled: Boolean,
    val profileId: String,
    val accountKind: String,
    val deviceId: String,
    val accessToken: String,
    val clientId: String? = null,
    val privateKey: String,
    val publicKey: String,
    val peerPublicKey: String,
    val interfaceAddressV4: String? = null,
    val interfaceAddressV6: String? = null,
    val endpoint: ResolvedRipDpiWarpEndpoint,
    val routeMode: String,
    val routeHosts: String,
    val builtInRulesEnabled: Boolean,
    val endpointSelectionMode: String,
    val manualEndpoint: RipDpiWarpManualEndpointConfig,
    val scannerEnabled: Boolean,
    val scannerParallelism: Int,
    val scannerMaxRttMs: Int,
    val amnezia: RipDpiWarpAmneziaConfig,
    val localSocksHost: String,
    val localSocksPort: Int,
    val mtu: Int = DefaultWarpTunnelMtu,
)

@Serializable
data class WarpEndpointProbeNativeRequest(
    val endpoint: ResolvedRipDpiWarpEndpoint,
    val privateKey: String,
    val peerPublicKey: String,
    val clientId: String? = null,
    val amnezia: RipDpiWarpAmneziaConfig = RipDpiWarpAmneziaConfig(),
    val timeoutMs: Long,
)

@Serializable
data class WarpEndpointProbeNativeResult(
    val host: String,
    val ipv4: String? = null,
    val ipv6: String? = null,
    val port: Int,
    val rttMs: Long,
)

internal const val DefaultWarpTunnelMtu = 1330
