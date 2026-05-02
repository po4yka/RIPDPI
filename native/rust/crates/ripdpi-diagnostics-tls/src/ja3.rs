//! JA3 TLS fingerprint computation for HTTPS strategy probes.
//!
//! JA3 captures the TLS ClientHello parameters and produces a stable MD5 hash
//! that identifies the TLS client implementation. This is used to verify that
//! different desync strategies produce distinct TLS handshakes and to detect
//! DPI fingerprint-based blocking.
//!
//! Reference: <https://github.com/salesforce/ja3>

mod client_hello_parser;
mod fingerprint;
mod grease;
mod recording_stream;

pub use fingerprint::compute_ja3;
pub use recording_stream::RecordingStream;

#[cfg(test)]
mod tests;
