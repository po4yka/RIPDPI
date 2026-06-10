//! Post-quantum hybrid KEM (X25519MLKEM768) configuration and telemetry.
//!
//! Chrome/Edge already offer the hybrid `X25519MLKEM768` group in their
//! ClientHello key-share via the static profile `curves` template (see
//! `chrome.rs` / `edge.rs`). This module adds:
//!
//! - a small runtime override API ([`normalize_kem_groups`] /
//!   [`apply_kem_groups`]) so a transport config can supply an ordered group
//!   list (`["X25519MLKEM768", "X25519", "P256"]`) that replaces the profile's
//!   curve list before the connector is built; and
//! - a privacy-safe negotiation telemetry counter
//!   ([`note_pq_kem_negotiation`] / [`pq_kem_negotiated_count`]) that records
//!   how many handshakes actually agreed on the hybrid PQ group.
//!
//! ## Transport coverage (out-of-layer note)
//!
//! The runtime `kem_groups` override is wired into the two transports that own
//! their TLS connector and expose a per-connection config: VLESS+REALITY
//! (`ripdpi-vless`) and xHTTP (`ripdpi-xhttp`). Both already advertise
//! `X25519MLKEM768` first via their static profile `curves` template; the
//! override only matters when a deployment wants a different group order.
//!
//! MASQUE (`ripdpi-masque`) is intentionally **out of layer** for the runtime
//! override and is not wired here, mirroring how ShadowTLS was treated for the
//! sibling uTLS-rotation task. MASQUE's primary transport is QUIC/HTTP-3 over
//! rustls with cert-pinning (no BoringSSL curve list to override), and its H2
//! fallback path pins a caller-supplied root certificate rather than carrying a
//! per-connection `kem_groups` field. It still inherits the hybrid PQ group
//! through the static profile template, so its ClientHello fingerprint already
//! matches a modern browser; a dedicated MASQUE override would be a separate,
//! larger change to `MasqueConfig` and is out of scope for this task.

use std::sync::atomic::{AtomicU64, Ordering};

use boring::ssl::SslConnectorBuilder;

use crate::Error;

/// IANA TLS supported-group id for the hybrid `X25519MLKEM768` KEM.
pub const X25519MLKEM768_GROUP_ID: u16 = 0x11ec;
/// IANA TLS supported-group id for `X25519`.
pub const X25519_GROUP_ID: u16 = 0x001d;
/// IANA TLS supported-group id for `secp256r1` (NIST P-256).
pub const SECP256R1_GROUP_ID: u16 = 0x0017;

/// Telemetry counter `tls.pq_kem_negotiated`: the cumulative number of
/// completed handshakes that negotiated the hybrid post-quantum
/// `X25519MLKEM768` group. Exposed for the telemetry layer to poll.
static PQ_KEM_NEGOTIATED: AtomicU64 = AtomicU64::new(0);

/// Returns the cumulative count of handshakes that negotiated the hybrid PQ
/// group — the `tls.pq_kem_negotiated` telemetry counter.
#[must_use]
pub fn pq_kem_negotiated_count() -> u64 {
    // Ordering: Relaxed — standalone monotonic telemetry tally with no
    // happens-before contract to any other state (pull-model poll).
    PQ_KEM_NEGOTIATED.load(Ordering::Relaxed)
}

/// Records a completed-handshake negotiation outcome for PQ-KEM telemetry.
///
/// `group_id` is the negotiated supported-group id, as returned by
/// `SslRef::curve()` after a successful handshake. The counter increments — and
/// a single static-string `tracing::debug!` event fires — **iff** the
/// negotiated group is the hybrid `X25519MLKEM768`. Any other group, or `None`
/// (no group reported), is a no-op.
///
/// The log line carries only the static event name; it never includes the
/// authority, SNI, peer IP, or any other destination identifier
/// (`network-fingerprint-privacy` rule).
pub fn note_pq_kem_negotiation(group_id: Option<u16>) {
    if group_id == Some(X25519MLKEM768_GROUP_ID) {
        // Ordering: Relaxed — standalone monotonic telemetry tally, no
        // happens-before contract with other state.
        PQ_KEM_NEGOTIATED.fetch_add(1, Ordering::Relaxed);
        tracing::debug!("tls.pq_kem_negotiated");
    }
}

/// Maps a single KEM-group token to its BoringSSL curve name.
///
/// Accepts (case-insensitively) the hybrid PQ group and the classical EC
/// groups, including the common P-curve aliases.
fn kem_group_curve_name(token: &str) -> Option<&'static str> {
    match token.trim().to_ascii_uppercase().as_str() {
        "X25519MLKEM768" => Some("X25519MLKEM768"),
        "X25519" => Some("X25519"),
        "P256" | "P-256" | "SECP256R1" => Some("P-256"),
        "P384" | "P-384" | "SECP384R1" => Some("P-384"),
        "P521" | "P-521" | "SECP521R1" => Some("P-521"),
        _ => None,
    }
}

/// Normalizes an ordered list of KEM-group tokens into a BoringSSL
/// `set_curves_list` string (group names joined with `:`).
///
/// Tokens are matched case-insensitively and the common P-curve aliases
/// (`P256` / `P-256` / `SECP256R1`, etc.) are accepted. Order is preserved so
/// the caller controls preference. For example
/// `["X25519MLKEM768", "X25519", "P256"]` normalizes to
/// `"X25519MLKEM768:X25519:P-256"`.
///
/// # Errors
/// Returns [`Error::Invariant`] if `groups` is empty or contains an unknown
/// token.
pub fn normalize_kem_groups(groups: &[String]) -> Result<String, Error> {
    if groups.is_empty() {
        return Err(Error::Invariant { profile: "kem_groups", reason: "kem group list must not be empty" });
    }
    let mut names = Vec::with_capacity(groups.len());
    for token in groups {
        let name = kem_group_curve_name(token)
            .ok_or(Error::Invariant { profile: "kem_groups", reason: "unknown KEM group token" })?;
        names.push(name);
    }
    Ok(names.join(":"))
}

/// Overrides the connector's curve list with the given ordered KEM-group list.
///
/// Call this on a builder produced by `configure_builder` and BEFORE `.build()`
/// to replace the profile's static `curves` template with a runtime-supplied
/// group order.
///
/// # Errors
/// Returns [`Error::Invariant`] if `groups` is empty or contains an unknown
/// token, or [`Error::Ssl`] if BoringSSL rejects the resulting curve list.
pub fn apply_kem_groups(builder: &mut SslConnectorBuilder, groups: &[String]) -> Result<(), Error> {
    let list = normalize_kem_groups(groups)?;
    builder.set_curves_list(&list)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalize_valid_ordered_list() {
        let groups = vec!["X25519MLKEM768".to_owned(), "X25519".to_owned(), "P256".to_owned()];
        assert_eq!(normalize_kem_groups(&groups).expect("valid"), "X25519MLKEM768:X25519:P-256");
    }

    #[test]
    fn normalize_is_case_insensitive() {
        let groups = vec!["x25519mlkem768".to_owned(), "x25519".to_owned()];
        assert_eq!(normalize_kem_groups(&groups).expect("valid"), "X25519MLKEM768:X25519");
    }

    #[test]
    fn normalize_accepts_p256_aliases() {
        for alias in ["P256", "P-256", "secp256r1", "SECP256R1"] {
            let groups = vec![alias.to_owned()];
            assert_eq!(normalize_kem_groups(&groups).expect("valid alias"), "P-256", "alias {alias}");
        }
    }

    #[test]
    fn normalize_rejects_empty_list() {
        let err = normalize_kem_groups(&[]).expect_err("empty must error");
        assert!(matches!(err, Error::Invariant { profile: "kem_groups", .. }));
    }

    #[test]
    fn normalize_rejects_unknown_token() {
        let groups = vec!["X25519".to_owned(), "kyber-classic".to_owned()];
        let err = normalize_kem_groups(&groups).expect_err("unknown must error");
        assert!(matches!(err, Error::Invariant { profile: "kem_groups", .. }));
    }

    #[test]
    fn note_negotiation_increments_only_for_hybrid_group() {
        let before = pq_kem_negotiated_count();
        note_pq_kem_negotiation(Some(X25519MLKEM768_GROUP_ID));
        assert_eq!(pq_kem_negotiated_count(), before + 1, "hybrid group must increment");

        note_pq_kem_negotiation(Some(X25519_GROUP_ID));
        note_pq_kem_negotiation(None);
        assert_eq!(pq_kem_negotiated_count(), before + 1, "non-hybrid and None must not increment");
    }
}
