package com.poyka.ripdpi.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirror of `ripdpi_quality::TransportKind`.
 *
 * Serialises as snake_case (`"tcp_proxy"` / `"tcp_tunnel"` / `"udp_relay"`)
 * matching the Rust producer's `#[serde(rename_all = "snake_case")]` directive
 * in `native/rust/crates/ripdpi-quality/src/snapshot.rs`.
 *
 * Per `TELEMETRY_CONTRACT.md` stable-identifiers rules this is a closed enum;
 * adding a variant requires a contract bump on both sides simultaneously.
 */
@Serializable
enum class TransportKind {
    @SerialName("tcp_proxy")
    TcpProxy,

    @SerialName("tcp_tunnel")
    TcpTunnel,

    @SerialName("udp_relay")
    UdpRelay,
}

/**
 * Mirror of `ripdpi_quality::ConnectionQualitySnapshot` produced by the
 * native data plane.
 *
 * Carries aggregate per-transport quality metrics for UI consumption
 * (degradation strip plus throughput and latency graphs). All fields are
 * aggregate-only — per `.claude/rules/network-fingerprint-privacy.md`
 * § "Forbidden inputs" none of them carry host, IP, port, BSSID, SSID, IMEI,
 * IMSI, or any other identifier.
 *
 * Wire shape: camelCase JSON. All fields are defaulted so the decoder
 * tolerates a payload that omits the field — the Rust side wraps the field
 * in `Option<...>` and skips serialisation when empty.
 */
@Serializable
data class ConnectionQualitySnapshot(
    val lossPct: Float = 0f,
    val rttP50Ms: Long = 0,
    val rttP95Ms: Long = 0,
    val jitterMs: Long = 0,
    val sampleCount: Long = 0,
    val windowStartAtMs: Long = 0,
    val transportKind: TransportKind = TransportKind.TcpTunnel,
)
