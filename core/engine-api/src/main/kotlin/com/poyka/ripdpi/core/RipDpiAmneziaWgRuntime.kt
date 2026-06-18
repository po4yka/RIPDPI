package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import kotlinx.serialization.SerialName
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
 * The transport an AmneziaWG tunnel egresses its WireGuard datagrams over.
 * Mirrors the Rust `AmneziaWgCarrierKind` (serde `snake_case`): [Udp] is the
 * default plain-UDP path; [Ws] frames each WireGuard datagram as a binary
 * WebSocket message over one protected TLS/TCP stream (the WG-over-WebSocket
 * carrier in `ripdpi-wireguard-ws`). The wire tokens are pinned by
 * [SerialName] so a rename on either side is caught by the contract test.
 */
@Serializable
enum class RipDpiAmneziaWgCarrierKind {
    @SerialName("udp")
    Udp,

    @SerialName("ws")
    Ws,
}

/**
 * Fully-resolved AmneziaWG session configuration. Field names and types mirror
 * the Rust `AmneziaWgProfileConfig` (serde camelCase) the native bridge
 * deserializes from the create-config JSON; serializing this with [RipDpiJson]
 * must produce exactly those keys (the cross-language contract is guarded by
 * `RipDpiAmneziaWgConfigSerializationTest`).
 *
 * [carrier] / [carrierWsUrl] are additive and defaulted ([carrier] =
 * [RipDpiAmneziaWgCarrierKind.Udp]) so a profile that omits them — and a native
 * core built before the carrier seam — deserializes unchanged; the native
 * schema does not bump. The endpoint host/port carried in [endpointHost] and
 * (when WS) [carrierWsUrl] are user-pasted config; they MUST NOT flow into
 * telemetry or logs in plain form (network-fingerprint-privacy).
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
    val carrier: RipDpiAmneziaWgCarrierKind = RipDpiAmneziaWgCarrierKind.Udp,
    val carrierWsUrl: String = "",
    val localSocksHost: String,
    val localSocksPort: Int,
)

internal const val DefaultAmneziaWgTunnelMtu = 1330
