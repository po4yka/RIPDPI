#![forbid(unsafe_code)]

mod client;
mod config;
mod frames;
mod handshake;
mod hmac;
mod stream;
#[cfg(test)]
mod tests;

pub use client::ShadowTlsClient;
pub use config::Config;
pub use stream::ShadowTlsStream;

/// Coarse classification of a failed ShadowTLS handshake payload.
///
/// Downstream maps `VersionMismatch` to
/// `ripdpi-failure-classifier::FailureClass::ShadowTlsVersionMismatch`
/// so the user-visible diagnostic recommends upgrading the server
/// from v2 to v3. Local enum + function avoids a cross-crate
/// dependency on the failure classifier.
///
/// See `docs/architecture/shadowtls-version-policy.md`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ShadowTlsFailureKind {
    /// Server sent a TLS record header (`0x16 0x03 ...`) at offset 0
    /// instead of v3's 4-byte HMAC tag prefix — strong signal the
    /// server speaks v2 framing.
    VersionMismatch,
    /// Payload doesn't match a known version-mismatch signature;
    /// pass through to the generic protocol-failure classifier
    /// (auth, framing corruption, network error, etc.).
    Other,
}

/// Classify a ShadowTLS handshake-failure payload as version mismatch
/// or generic failure. The payload is whatever bytes were observed
/// on the wire when the connection failed.
///
/// v3 servers prefix every server response with a 4-byte HMAC-SHA1
/// truncated tag; v2 servers do not — they send the TLS record
/// directly. A payload that starts with a TLS handshake record
/// header (`0x16 0x03 ..`) at offset 0 therefore signals a v2 server.
///
/// Empty payloads and payloads shorter than 2 bytes classify as
/// `Other` (cannot decide).
pub fn classify_failure_payload(payload: &[u8]) -> ShadowTlsFailureKind {
    // TLS record header: ContentType=0x16 (Handshake), Version major=0x03.
    // v3 wraps this in a 4-byte HMAC, so this header at offset 0 means
    // the server skipped the v3 prefix.
    if payload.len() >= 2 && payload[0] == 0x16 && payload[1] == 0x03 {
        ShadowTlsFailureKind::VersionMismatch
    } else {
        ShadowTlsFailureKind::Other
    }
}

#[cfg(test)]
mod classify_failure_tests {
    use super::*;

    #[test]
    fn classify_failure_payload_detects_v2_tls_handshake_header_at_offset_zero() {
        // v2 server sends the TLS handshake record directly; first
        // bytes are 0x16 (Handshake content type) + 0x03 (TLS major).
        assert_eq!(
            classify_failure_payload(&[0x16, 0x03, 0x03, 0x00, 0x10]),
            ShadowTlsFailureKind::VersionMismatch,
        );
        // 0x16 + 0x03 followed by any payload still classifies as v2.
        assert_eq!(classify_failure_payload(&[0x16, 0x03]), ShadowTlsFailureKind::VersionMismatch);
    }

    #[test]
    fn classify_failure_payload_passes_through_v3_hmac_prefix() {
        // v3 server prefixes responses with a 4-byte HMAC-SHA1
        // truncated tag (arbitrary bytes, never the TLS record marker
        // at offset 0). These pass through as Other.
        assert_eq!(classify_failure_payload(&[0xab, 0xcd, 0xef, 0x01, 0x16, 0x03]), ShadowTlsFailureKind::Other);
    }

    #[test]
    fn classify_failure_payload_returns_other_for_short_or_empty() {
        assert_eq!(classify_failure_payload(&[]), ShadowTlsFailureKind::Other);
        assert_eq!(classify_failure_payload(&[0x16]), ShadowTlsFailureKind::Other);
    }

    #[test]
    fn classify_failure_payload_returns_other_for_non_tls_first_bytes() {
        // First byte not 0x16 -> not a TLS handshake record.
        assert_eq!(classify_failure_payload(&[0x14, 0x03, 0x03]), ShadowTlsFailureKind::Other); // ChangeCipherSpec
        assert_eq!(classify_failure_payload(&[0x17, 0x03, 0x03]), ShadowTlsFailureKind::Other); // ApplicationData
        assert_eq!(classify_failure_payload(&[0x00, 0x00, 0x00]), ShadowTlsFailureKind::Other);
    }
}
