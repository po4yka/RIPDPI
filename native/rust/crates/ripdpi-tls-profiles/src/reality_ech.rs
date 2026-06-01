//! REALITY ECH-GREASE parity decision (ADR 0001).
//!
//! REALITY authenticates with a visible cover `serverName` plus a sealed
//! SessionID; it does **not** use real ECH (ADR 0001 § Decision). The only ECH
//! behavior REALITY may exhibit is *GREASE*, and only as outer-ClientHello
//! fingerprint parity: if the mimicked browser profile would carry an
//! `encrypted_client_hello` outer extension for the cover class, REALITY may
//! GREASE so it does not stand out; otherwise it emits no ECH extension at all.
//!
//! Per ADR 0001 the default — for any profile/cover *without* explicit ECH
//! parity evidence — is [`RealityEchParity::Off`]. GREASE is returned only when
//! BOTH hold: the selected profile is itself ECH-capable (the mimicked browser
//! carries the outer extension) AND there is explicit evidence the cover
//! population carries ECH. That keeps GREASE from standing out in either
//! direction (ADR: "a cover-domain mimic can be wrong in both directions").
//!
//! This module is the `ripdpi-tls-profiles` decision helper named in the
//! ADR 0001 Implementation Sketch; `ripdpi-vless::reality` calls it after
//! `connector.configure()` and before `into_ssl(&config.server_name)`, applying
//! BoringSSL GREASE (`set_enable_ech_grease(true)`) only on
//! [`RealityEchParity::Grease`]. The real-ECH facade (`configure_ech`,
//! `resolve_outbound_ech`, `prepare_ech_retry`) is deliberately NOT used on the
//! REALITY path.

use crate::profile::ProfileConfig;

/// Whether REALITY should emit an ECH GREASE extension for a given
/// profile/cover combination. REALITY never emits *real* ECH (ADR 0001).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RealityEchParity {
    /// Emit no ECH extension — the ADR 0001 default.
    Off,
    /// Emit a BoringSSL ECH GREASE extension for outer-ClientHello parity.
    Grease,
}

/// Per-cover-domain evidence about whether the imitated cover population
/// carries an ECH outer extension.
///
/// This is the third input to the ADR 0001 decision tuple. RIPDPI does not yet
/// ship a per-cover evidence table (that is future profile-catalog data work,
/// per ADR 0001 § Consequences), so the REALITY connect path supplies
/// [`CoverEchEvidence::Unknown`] today — which resolves to
/// [`RealityEchParity::Off`], preserving the current no-ECH baseline.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CoverEchEvidence {
    /// No parity evidence for this cover — the ADR default (resolves to `Off`).
    Unknown,
    /// The cover population is known to carry an ECH outer extension.
    CoverUsesEch,
    /// The cover population is known NOT to carry an ECH outer extension.
    CoverOmitsEch,
}

/// Decide the REALITY ECH parity for `(profile, cover_server_name, evidence)`
/// per ADR 0001.
///
/// Returns [`RealityEchParity::Grease`] only when the selected profile is
/// ECH-capable (so the mimicked browser carries an ECH outer extension) AND the
/// cover evidence says the cover population carries ECH. Every other case —
/// a non-ECH profile, or `Unknown` / `CoverOmitsEch` evidence — returns
/// [`RealityEchParity::Off`], the ADR default.
///
/// `cover_server_name` is part of the ADR 0001 decision tuple and is reserved
/// for a future per-domain evidence lookup; the v1 decision is driven by the
/// explicit `evidence` argument. Unused function parameters are not linted in
/// Rust, so the name is kept for signature fidelity with the ADR.
#[must_use]
pub fn reality_ech_parity(
    profile: &ProfileConfig,
    cover_server_name: &str,
    evidence: CoverEchEvidence,
) -> RealityEchParity {
    // `cover_server_name` reserved for future per-cover evidence lookup (ADR 0001).
    let _ = cover_server_name;
    match (profile.ech_capable, evidence) {
        (true, CoverEchEvidence::CoverUsesEch) => RealityEchParity::Grease,
        _ => RealityEchParity::Off,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::profile::{AVAILABLE_PROFILES, lookup_profile};

    #[test]
    fn ech_capable_profile_greases_only_with_cover_ech_evidence() {
        let firefox_ech = lookup_profile("firefox_ech_stable");
        assert!(firefox_ech.ech_capable, "precondition: firefox_ech_stable is ECH-capable");

        assert_eq!(
            reality_ech_parity(firefox_ech, "cover.example", CoverEchEvidence::CoverUsesEch),
            RealityEchParity::Grease,
        );
        assert_eq!(reality_ech_parity(firefox_ech, "cover.example", CoverEchEvidence::Unknown), RealityEchParity::Off,);
        assert_eq!(
            reality_ech_parity(firefox_ech, "cover.example", CoverEchEvidence::CoverOmitsEch),
            RealityEchParity::Off,
        );
    }

    #[test]
    fn non_ech_profile_never_greases() {
        for name in ["chrome_stable", "chrome_desktop_stable", "firefox_stable", "safari_stable", "edge_stable"] {
            let profile = lookup_profile(name);
            assert!(!profile.ech_capable, "{name} precondition: not ECH-capable");
            for evidence in [CoverEchEvidence::Unknown, CoverEchEvidence::CoverUsesEch, CoverEchEvidence::CoverOmitsEch]
            {
                assert_eq!(
                    reality_ech_parity(profile, "cover.example", evidence),
                    RealityEchParity::Off,
                    "{name} with {evidence:?} must not GREASE",
                );
            }
        }
    }

    #[test]
    fn default_evidence_preserves_no_ech_baseline_for_all_profiles() {
        // The REALITY connect path supplies `Unknown` today; every catalog
        // profile must resolve to `Off` so the documented no-ECH baseline
        // (protocol-ground-truth-matrix row 5b) is preserved.
        for name in AVAILABLE_PROFILES {
            let profile = lookup_profile(name);
            assert_eq!(
                reality_ech_parity(profile, "cover.example", CoverEchEvidence::Unknown),
                RealityEchParity::Off,
                "{name} must default to Off",
            );
        }
    }
}
