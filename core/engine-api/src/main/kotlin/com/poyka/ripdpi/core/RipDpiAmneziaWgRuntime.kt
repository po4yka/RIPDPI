package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import kotlinx.serialization.Serializable

/**
 * Stable runtime contract for the AmneziaWG (obfuscated WireGuard) native
 * session, implemented by `RipDpiAmneziaWg` in `:core:engine`. New
 * `:core:service` / AmneziaWG features depend on this interface rather than on
 * the JNI implementation module, mirroring [RipDpiWarpRuntime].
 */
interface RipDpiAmneziaWgRuntime {
    suspend fun start(config: ResolvedRipDpiAmneziaWgConfig): Int

    suspend fun awaitReady(timeoutMillis: Long = defaultAmneziaWgReadyTimeoutMs)

    suspend fun stop()

    suspend fun pollTelemetry(): NativeRuntimeSnapshot
}

internal const val defaultAmneziaWgReadyTimeoutMs = 5_000L

/**
 * AmneziaWG obfuscation parameters. Field names and types mirror the Rust
 * `AmneziaWgProfileConfig::amnezia` object (serde camelCase) deserialized by
 * `ripdpi-amneziawg-android`. `jc`/`jmin`/`jmax` size the junk-packet padding,
 * `s1`..`s4` the per-message-type junk-size knobs (`s1`/`s2` the
 * handshake-init/response prefixes, `s3`/`s4` the AWG-2.x cookie/transport
 * padding), `h1`..`h4` the magic header constants (64-bit, hence [Long]), and
 * `i1`..`i5` the optional packet templates (empty string = unused).
 */
@Serializable
data class RipDpiAmneziaWgObfuscationConfig(
    val jc: Int = 0,
    val jmin: Int = 0,
    val jmax: Int = 0,
    val s1: Int = 0,
    val s2: Int = 0,
    val s3: Int = 0,
    val s4: Int = 0,
    val h1: Long = 0L,
    val h2: Long = 0L,
    val h3: Long = 0L,
    val h4: Long = 0L,
    val i1: String = "",
    val i2: String = "",
    val i3: String = "",
    val i4: String = "",
    val i5: String = "",
)

/**
 * Fully-resolved AmneziaWG session configuration. Field names and types mirror
 * the Rust `AmneziaWgProfileConfig` (serde camelCase) the native bridge
 * deserializes from the create-config JSON; serializing this with [RipDpiJson]
 * must produce exactly those keys (the cross-language contract is guarded by
 * `RipDpiAmneziaWgConfigSerializationTest`).
 */
@Serializable
data class ResolvedRipDpiAmneziaWgConfig(
    val enabled: Boolean,
    val profileId: String,
    val privateKey: String,
    val peerPublicKey: String,
    val presharedKey: String = "",
    val endpointHost: String,
    val endpointIpv4: String = "",
    val endpointIpv6: String = "",
    val endpointPort: Int,
    val interfaceAddressV4: String,
    val interfaceAddressV6: String = "",
    val mtu: Int = DefaultAmneziaWgTunnelMtu,
    val persistentKeepalive: Int = 0,
    val amnezia: RipDpiAmneziaWgObfuscationConfig = RipDpiAmneziaWgObfuscationConfig(),
    val localSocksHost: String,
    val localSocksPort: Int,
)

internal const val DefaultAmneziaWgTunnelMtu = 1330
