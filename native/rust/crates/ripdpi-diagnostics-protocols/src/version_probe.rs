//! Protocol-version-mismatch probe classifier.
//!
//! Generic classifier that maps observed handshake-failure bytes to
//! a typed [`ProbeOutcome`] so the diagnostics runner can surface a
//! user-actionable reason ("server speaks an older protocol version")
//! distinct from generic failures (auth, network, blocked).
//!
//! Per-protocol byte-level classifiers live in their respective crates:
//! - `ripdpi-tuic::classify_failure_payload` (v4 wire byte at offset 0)
//! - `ripdpi-shadowtls::classify_failure_payload` (TLS record header at offset 0)
//!
//! This module aggregates those signals into one typed surface so the
//! diagnostics runner doesn't pull every protocol crate in directly.
//!
//! Tracks the cross-crate probe acceptance criterion in
//! `docs/tasks/issues/introduce-protocol-version-enum-and-version-probe-diagnostic.md`.

/// Wire protocol the probe is targeting. Drives which byte-level
/// heuristic applies.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProbeProtocol {
    /// TUIC v5 client. v4-server replies start with a non-`0x05`
    /// byte at offset 0 of the AUTHENTICATE-reject frame.
    TuicV5,
    /// ShadowTLS v3 client. v2-server replies skip the 4-byte HMAC
    /// prefix and send the TLS handshake record directly
    /// (`0x16 0x03 ...` at offset 0).
    ShadowTlsV3,
    /// VLESS+REALITY v1 client. Future variants will land here as
    /// REALITY adds new wire forms; today there is no v0/v2 to map.
    VlessRealityV1,
}

/// Classification result for an attempted handshake observation.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ProbeOutcome {
    /// Handshake completed successfully; the peer speaks the offered
    /// protocol version.
    Reachable,
    /// Peer signaled a different protocol version on the wire. The
    /// `offered` field is the version the client tried; `server_signaled`
    /// is a coarse description of what came back (e.g. `"0x04"` for
    /// a v4-shaped reply).
    VersionMismatch { offered: ProbeProtocol, server_signaled: String },
    /// The handshake reached the auth phase and was rejected for an
    /// auth reason (wrong UUID, wrong password). Distinct from
    /// version mismatch.
    AuthFailure,
    /// No reply within the timeout, or the connection was reset
    /// before any reply bytes were observed. Most likely network
    /// censorship or a hard firewall drop.
    BlockedOrDropped,
    /// Classification not determinable from the observed bytes.
    /// Pass through to the generic failure classifier.
    Unknown,
}

impl ProbeOutcome {
    /// Stable identifier for telemetry / catalog dispatch. Matches
    /// the `as_str` convention used by
    /// `ripdpi-failure-classifier::FailureClass`.
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Reachable => "reachable",
            Self::VersionMismatch { .. } => "version_mismatch",
            Self::AuthFailure => "auth_failure",
            Self::BlockedOrDropped => "blocked_or_dropped",
            Self::Unknown => "unknown",
        }
    }
}

/// Classify an observed handshake reply payload as a probe outcome.
///
/// `observed` is whatever bytes the probe saw on the failure path.
/// An empty slice signals "no reply observed" which maps to
/// [`ProbeOutcome::BlockedOrDropped`].
pub fn classify_probe_observation(protocol: ProbeProtocol, observed: &[u8]) -> ProbeOutcome {
    if observed.is_empty() {
        return ProbeOutcome::BlockedOrDropped;
    }
    match protocol {
        ProbeProtocol::TuicV5 => classify_tuic_v5(observed),
        ProbeProtocol::ShadowTlsV3 => classify_shadowtls_v3(observed),
        ProbeProtocol::VlessRealityV1 => ProbeOutcome::Unknown,
    }
}

fn classify_tuic_v5(observed: &[u8]) -> ProbeOutcome {
    // v4-server reject frame starts with a non-`0x05` byte. v5
    // replies with `0x05` lead into either a successful handshake or
    // an auth failure. Without deeper parsing we surface those as
    // Reachable (subsequent layers handle auth).
    match observed.first() {
        Some(&0x05) => ProbeOutcome::Reachable,
        Some(&byte) => {
            ProbeOutcome::VersionMismatch { offered: ProbeProtocol::TuicV5, server_signaled: format!("0x{byte:02x}") }
        }
        None => ProbeOutcome::BlockedOrDropped,
    }
}

fn classify_shadowtls_v3(observed: &[u8]) -> ProbeOutcome {
    // v3-server replies prefix every record with a 4-byte HMAC tag,
    // so a TLS record header (0x16 0x03 ...) at offset 0 signals a
    // v2 server. Anything else passes through as Reachable (the
    // server is v3; subsequent layers handle auth / decryption).
    if observed.len() >= 2 && observed[0] == 0x16 && observed[1] == 0x03 {
        ProbeOutcome::VersionMismatch {
            offered: ProbeProtocol::ShadowTlsV3,
            server_signaled: "tls-handshake-no-hmac-prefix".to_string(),
        }
    } else {
        ProbeOutcome::Reachable
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_observation_classifies_as_blocked_or_dropped() {
        for protocol in [ProbeProtocol::TuicV5, ProbeProtocol::ShadowTlsV3, ProbeProtocol::VlessRealityV1] {
            assert_eq!(
                classify_probe_observation(protocol, &[]),
                ProbeOutcome::BlockedOrDropped,
                "protocol {protocol:?} on empty observation must report BlockedOrDropped",
            );
        }
    }

    #[test]
    fn tuic_v5_byte_classifies_as_reachable() {
        let observed = [0x05u8, 0x00, 0x01, 0x02];
        assert_eq!(classify_probe_observation(ProbeProtocol::TuicV5, &observed), ProbeOutcome::Reachable);
    }

    #[test]
    fn tuic_v4_byte_classifies_as_version_mismatch_with_server_signaled() {
        let observed = [0x04u8, 0xff, 0xfe];
        let outcome = classify_probe_observation(ProbeProtocol::TuicV5, &observed);
        match outcome {
            ProbeOutcome::VersionMismatch { offered, server_signaled } => {
                assert_eq!(offered, ProbeProtocol::TuicV5);
                assert_eq!(server_signaled, "0x04");
            }
            other => panic!("expected VersionMismatch, got {other:?}"),
        }
    }

    #[test]
    fn shadowtls_v2_tls_record_classifies_as_version_mismatch() {
        let observed = [0x16u8, 0x03, 0x03, 0x00, 0x10];
        let outcome = classify_probe_observation(ProbeProtocol::ShadowTlsV3, &observed);
        match outcome {
            ProbeOutcome::VersionMismatch { offered, server_signaled } => {
                assert_eq!(offered, ProbeProtocol::ShadowTlsV3);
                assert_eq!(server_signaled, "tls-handshake-no-hmac-prefix");
            }
            other => panic!("expected VersionMismatch, got {other:?}"),
        }
    }

    #[test]
    fn shadowtls_v3_hmac_prefix_classifies_as_reachable() {
        // Non-TLS first bytes (a 4-byte HMAC tag) signal a v3 server.
        let observed = [0xabu8, 0xcd, 0xef, 0x01, 0x16, 0x03];
        assert_eq!(classify_probe_observation(ProbeProtocol::ShadowTlsV3, &observed), ProbeOutcome::Reachable);
    }

    #[test]
    fn vless_reality_v1_falls_through_to_unknown_until_variant_lands() {
        // Today there's no v0/v2 REALITY to map; classifier must
        // pass through.
        let observed = [0x01u8, 0x00, 0x00];
        assert_eq!(classify_probe_observation(ProbeProtocol::VlessRealityV1, &observed), ProbeOutcome::Unknown);
    }

    #[test]
    fn probe_outcome_as_str_covers_all_variants() {
        let cases = [
            (ProbeOutcome::Reachable, "reachable"),
            (
                ProbeOutcome::VersionMismatch { offered: ProbeProtocol::TuicV5, server_signaled: "0x04".to_string() },
                "version_mismatch",
            ),
            (ProbeOutcome::AuthFailure, "auth_failure"),
            (ProbeOutcome::BlockedOrDropped, "blocked_or_dropped"),
            (ProbeOutcome::Unknown, "unknown"),
        ];
        for (outcome, expected) in cases {
            assert_eq!(outcome.as_str(), expected, "{outcome:?} -> {expected:?}");
        }
    }
}
