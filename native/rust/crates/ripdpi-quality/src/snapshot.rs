//! Wire types for the connection-quality telemetry contract.
//!
//! `QualitySample` is the producer-side input; `ConnectionQualitySnapshot` is
//! the consumer-side aggregated output that crosses the JNI boundary as an
//! additive field on `NativeRuntimeSnapshot` (see `telemetry/types.rs`).
//!
//! Both types are strictly aggregate-only. None of the fields carry host,
//! IP, port, BSSID, SSID, IMEI, IMSI, or any other identifier listed in
//! `.claude/rules/network-fingerprint-privacy.md` § "Forbidden inputs".

use serde::Serialize;

/// Single observation recorded by a `QualityWindow` producer.
///
/// `rtt_ms` is the SOCKS5 connect-to-CONNECT-ACK round trip time in
/// milliseconds (clamped to `60_000` by the histogram). `succeeded` is
/// `false` for any error arm of the TCP-session connect path; failed
/// samples increment the loss counter but are NOT recorded into the
/// latency histogram (their RTT is undefined).
#[derive(Debug, Clone, Copy)]
pub struct QualitySample {
    pub rtt_ms: u64,
    pub succeeded: bool,
}

/// Frozen identifier for the runtime that produced a quality sample stream.
///
/// The string form is the JSON value seen by Kotlin / the Play Store Data
/// Safety surface; per `TELEMETRY_CONTRACT.md` stable-identifiers rules, the
/// variants here are a closed enum and cannot be renamed without a contract
/// bump.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum TransportKind {
    TcpProxy,
    TcpTunnel,
    UdpRelay,
}

/// Aggregated snapshot of the rolling quality window.
///
/// Emitted as an additive field on `NativeRuntimeSnapshot`. All fields are
/// camelCase on the wire to match the existing telemetry conventions at
/// `telemetry/types.rs:7`. None of the fields leak per-host information —
/// the producer aggregates before the histogram boundary, see
/// `docs/architecture/G008_SUBSYSTEMS_DESIGN.md` § P5 "Privacy".
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ConnectionQualitySnapshot {
    pub loss_pct: f32,
    pub rtt_p50_ms: u64,
    pub rtt_p95_ms: u64,
    pub jitter_ms: u64,
    pub sample_count: u64,
    pub window_start_at_ms: u64,
    pub transport_kind: TransportKind,
}
