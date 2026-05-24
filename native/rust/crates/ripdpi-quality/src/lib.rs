//! Bounded rolling-window quality telemetry primitive.
//!
//! `QualityWindow` aggregates per-session connect-RTT samples (success +
//! failure) into two `hdrhistogram::Histogram<u64>` instances — a 60-second
//! "instant" window for the DegradationStrip's current values and a 15-minute
//! "series" window for the throughput / latency graphs. It is the producer
//! side of the connection-quality contract defined in
//! `docs/architecture/G008_SUBSYSTEMS_DESIGN.md` § P5.
//!
//! The crate is `#![forbid(unsafe_code)]` and intentionally adapter-agnostic:
//! the four runtimes (`ripdpi-tunnel-android`, `ripdpi-android-telemetry-adapter`,
//! `ripdpi-relay-android`, `ripdpi-warp-android`) each own their own
//! `QualityWindow` and wire it via the observer pattern documented at
//! `ripdpi-tunnel-core::Stats::set_dns_latency_observer`.
//!
//! Jitter is computed per RFC 3550 (`J = J + (|D(i-1,i)| - J) / 16`), the same
//! formula used by Wireshark's RTP statistics tooling.

#![forbid(unsafe_code)]

mod snapshot;
mod window;

#[cfg(test)]
mod tests;

pub use snapshot::{ConnectionQualitySnapshot, QualitySample, TransportKind};
pub use window::QualityWindow;
