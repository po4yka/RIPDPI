#![forbid(unsafe_code)]

mod client;
mod config;
mod frames;
mod handshake;
mod hmac;
#[cfg(any(test, feature = "test-server"))]
mod loopback;
mod stream;
#[cfg(test)]
mod tests;
#[cfg(test)]
mod upstream_oracle_tests;

pub use client::ShadowTlsClient;
pub use config::Config;
#[cfg(feature = "test-server")]
pub use loopback::{ShadowTlsLoopback, ShadowTlsV2RejectLoopback};
pub use stream::ShadowTlsStream;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ShadowTlsFailureKind {
    VersionMismatch,
    CoverHelloRetry,
    Other,
}

pub fn classify_failure_payload(payload: &[u8]) -> ShadowTlsFailureKind {
    if payload.len() >= 2 && payload[0] == 0x16 && payload[1] == 0x03 {
        ShadowTlsFailureKind::VersionMismatch
    } else {
        ShadowTlsFailureKind::Other
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ShadowTlsHandshakeError {
    kind: ShadowTlsFailureKind,
}

impl ShadowTlsHandshakeError {
    #[must_use]
    pub const fn version_mismatch() -> Self {
        Self { kind: ShadowTlsFailureKind::VersionMismatch }
    }

    #[must_use]
    pub const fn cover_hello_retry() -> Self {
        Self { kind: ShadowTlsFailureKind::CoverHelloRetry }
    }

    #[must_use]
    pub const fn kind(&self) -> ShadowTlsFailureKind {
        self.kind
    }
}

impl std::fmt::Display for ShadowTlsHandshakeError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self.kind {
            ShadowTlsFailureKind::VersionMismatch => f.write_str(
                "ShadowTLS server speaks v2 framing (this client is v3-only); \
                 upgrade the server to ShadowTLS v3 — see docs/architecture/shadowtls-version-policy.md",
            ),
            ShadowTlsFailureKind::CoverHelloRetry => f.write_str(
                "ShadowTLS cover server sent a TLS HelloRetryRequest, which this client does not support; \
                 choose a cover server that accepts an X25519 key share",
            ),
            ShadowTlsFailureKind::Other => f.write_str("ShadowTLS handshake failed"),
        }
    }
}

impl std::error::Error for ShadowTlsHandshakeError {}

#[cfg(test)]
mod classify_failure_tests {
    use super::*;

    #[test]
    fn classify_failure_payload_detects_v2_tls_handshake_header_at_offset_zero() {
        assert_eq!(classify_failure_payload(&[0x16, 0x03, 0x03, 0x00, 0x10]), ShadowTlsFailureKind::VersionMismatch,);
        assert_eq!(classify_failure_payload(&[0x16, 0x03]), ShadowTlsFailureKind::VersionMismatch);
    }

    #[test]
    fn classify_failure_payload_passes_through_v3_hmac_prefix() {
        assert_eq!(classify_failure_payload(&[0xab, 0xcd, 0xef, 0x01, 0x16, 0x03]), ShadowTlsFailureKind::Other);
    }

    #[test]
    fn classify_failure_payload_returns_other_for_short_or_empty() {
        assert_eq!(classify_failure_payload(&[]), ShadowTlsFailureKind::Other);
        assert_eq!(classify_failure_payload(&[0x16]), ShadowTlsFailureKind::Other);
    }

    #[test]
    fn classify_failure_payload_returns_other_for_non_tls_first_bytes() {
        assert_eq!(classify_failure_payload(&[0x14, 0x03, 0x03]), ShadowTlsFailureKind::Other);
        assert_eq!(classify_failure_payload(&[0x17, 0x03, 0x03]), ShadowTlsFailureKind::Other);
        assert_eq!(classify_failure_payload(&[0x00, 0x00, 0x00]), ShadowTlsFailureKind::Other);
    }
}
