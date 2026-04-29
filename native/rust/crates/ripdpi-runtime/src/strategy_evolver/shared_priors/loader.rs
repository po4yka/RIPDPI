//! Verify-and-apply entry point for shared-priors bundles.
//!
//! `apply_priors` is fail-secure: if any step rejects (signature, hash,
//! version, parser), it returns `Err` and the caller's evolver state is
//! left untouched.

use std::collections::HashMap;

use crate::strategy_evolver::thompson_sampling::BetaParams;
use crate::strategy_evolver::types::{
    entropy_mode_disc, offset_base_disc, quic_fake_disc, tls_randrec_disc, udp_burst_disc, StrategyCombo,
};

use super::manifest::{verify_manifest, ManifestError, SharedPriorsManifest, SHARED_PRIORS_PUB_KEY};
use super::parser::{parse, SharedPriorsError};

#[derive(Debug)]
pub enum ApplyError {
    Manifest(ManifestError),
    Parse(SharedPriorsError),
    InvalidUtf8,
}

impl std::fmt::Display for ApplyError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Manifest(err) => write!(f, "manifest verification failed: {err}"),
            Self::Parse(err) => write!(f, "priors payload parse failed: {err}"),
            Self::InvalidUtf8 => write!(f, "priors payload was not valid utf-8"),
        }
    }
}

impl std::error::Error for ApplyError {}

#[derive(Debug)]
pub struct AppliedPriors {
    pub manifest: SharedPriorsManifest,
    pub priors: HashMap<u64, BetaParams>,
    pub skipped: Vec<(usize, String)>,
}

/// Verify the manifest and parse the priors payload.
///
/// On success returns the parsed manifest plus a `combo_hash -> BetaParams`
/// map. The caller — typically `StrategyEvolver::apply_shared_priors` —
/// decides how to merge the priors against any existing local field data.
/// On failure the error is returned without mutation.
pub fn apply_priors(
    manifest_bytes: &[u8],
    priors_bytes: &[u8],
    public_key: &[u8; 32],
) -> Result<AppliedPriors, ApplyError> {
    let manifest = verify_manifest(manifest_bytes, priors_bytes, public_key).map_err(ApplyError::Manifest)?;
    let priors_str = std::str::from_utf8(priors_bytes).map_err(|_| ApplyError::InvalidUtf8)?;
    let loaded = parse(priors_str).map_err(ApplyError::Parse)?;
    Ok(AppliedPriors { manifest, priors: loaded.priors, skipped: loaded.skipped })
}

/// Production entry point: verify with the embedded release public key.
pub fn apply_priors_with_embedded_key(manifest_bytes: &[u8], priors_bytes: &[u8]) -> Result<AppliedPriors, ApplyError> {
    apply_priors(manifest_bytes, priors_bytes, &SHARED_PRIORS_PUB_KEY)
}

/// Stable canonical hash of a [`StrategyCombo`] used as the key in shared
/// priors bundles. Independent of [`std::hash::Hasher`] (whose algorithm
/// is version-dependent) — this hash is FNV-1a 64-bit over a fixed 14-byte
/// wire form, so an external signing tool can compute the same value.
///
/// Wire form (14 bytes total, two per dimension in slot order):
/// 0..2  split_offset_base
/// 2..4  tls_record_offset_base
/// 4..6  tlsrandrec_profile
/// 6..8  udp_burst_profile
/// 8..10 quic_fake_profile
/// 10..12 fake_ttl
/// 12..14 entropy_mode
///
/// Each pair is `(slot_index, discriminant)` where `discriminant` is
/// `0xFF` for `None` or the dimension's stable discriminant value for
/// `Some`. The discriminants are assigned in `types.rs` and must remain
/// stable across releases.
pub fn canonical_combo_hash(combo: &StrategyCombo) -> u64 {
    let mut bytes = [0u8; 14];
    write_dim(&mut bytes, 0, combo.split_offset_base.map(offset_base_disc));
    write_dim(&mut bytes, 1, combo.tls_record_offset_base.map(offset_base_disc));
    write_dim(&mut bytes, 2, combo.tlsrandrec_profile.map(tls_randrec_disc));
    write_dim(&mut bytes, 3, combo.udp_burst_profile.map(udp_burst_disc));
    write_dim(&mut bytes, 4, combo.quic_fake_profile.map(quic_fake_disc));
    write_dim(&mut bytes, 5, combo.fake_ttl);
    write_dim(&mut bytes, 6, combo.entropy_mode.map(entropy_mode_disc));
    fnv1a_64(&bytes)
}

fn write_dim(out: &mut [u8; 14], slot: usize, disc: Option<u8>) {
    out[slot * 2] = slot as u8;
    out[slot * 2 + 1] = disc.unwrap_or(0xFF);
}

fn fnv1a_64(bytes: &[u8]) -> u64 {
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    for &b in bytes {
        hash ^= u64::from(b);
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
    }
    hash
}

#[cfg(test)]
mod tests {
    use ripdpi_config::OffsetBase;

    use super::super::manifest::test_support::{generate_test_key, sign_manifest_bytes};
    use super::*;

    const SAMPLE_PRIORS: &[u8] =
        b"{\"combo_hash\": 1, \"alpha\": 12.0, \"beta\": 4.0}\n{\"combo_hash\": 2, \"alpha\": 3.5, \"beta\": 1.5}\n";

    #[test]
    fn apply_priors_roundtrip_returns_parsed_records() {
        let key = generate_test_key();
        let manifest = sign_manifest_bytes(&key, SAMPLE_PRIORS, 1_745_798_400, "https://example/priors.ndjson");
        let applied = apply_priors(manifest.as_bytes(), SAMPLE_PRIORS, &key.public_bytes)
            .expect("apply_priors should succeed for a signed bundle");
        assert_eq!(applied.priors.len(), 2);
        assert!(applied.skipped.is_empty());
        assert_eq!(applied.manifest.issued_at_unix, 1_745_798_400);
    }

    #[test]
    fn apply_priors_fail_secure_on_hash_mismatch() {
        let key = generate_test_key();
        let manifest = sign_manifest_bytes(&key, SAMPLE_PRIORS, 1_745_798_400, "https://example/priors.ndjson");
        let tampered = b"{\"combo_hash\": 1, \"alpha\": 99.0, \"beta\": 4.0}\n";
        let err = apply_priors(manifest.as_bytes(), tampered, &key.public_bytes)
            .expect_err("hash mismatch must fail before parsing the payload");
        assert!(matches!(err, ApplyError::Manifest(ManifestError::HashMismatch)), "got {err:?}");
    }

    #[test]
    fn apply_priors_fail_secure_on_bad_signature() {
        let signing_key = generate_test_key();
        let other_key = generate_test_key();
        let manifest = sign_manifest_bytes(&signing_key, SAMPLE_PRIORS, 1_745_798_400, "https://example/priors.ndjson");
        let err = apply_priors(manifest.as_bytes(), SAMPLE_PRIORS, &other_key.public_bytes)
            .expect_err("verifying under a different key must fail");
        assert!(matches!(err, ApplyError::Manifest(ManifestError::BadSignature)), "got {err:?}");
    }

    #[test]
    fn apply_priors_with_embedded_key_short_circuits_until_real_key_lands() {
        let key = generate_test_key();
        let manifest = sign_manifest_bytes(&key, SAMPLE_PRIORS, 1_745_798_400, "https://example/priors.ndjson");
        let err = apply_priors_with_embedded_key(manifest.as_bytes(), SAMPLE_PRIORS)
            .expect_err("embedded placeholder key must reject");
        assert!(matches!(err, ApplyError::Manifest(ManifestError::NoProductionKey)), "got {err:?}");
    }

    #[test]
    fn canonical_combo_hash_is_stable_for_default_combo() {
        let combo = StrategyCombo {
            split_offset_base: None,
            tls_record_offset_base: None,
            tlsrandrec_profile: None,
            udp_burst_profile: None,
            quic_fake_profile: None,
            fake_ttl: None,
            entropy_mode: None,
        };
        // Must remain constant across releases. If this assertion changes,
        // every shared-priors bundle in circulation needs to be re-signed.
        let expected = fnv1a_64(&[0, 0xFF, 1, 0xFF, 2, 0xFF, 3, 0xFF, 4, 0xFF, 5, 0xFF, 6, 0xFF]);
        assert_eq!(canonical_combo_hash(&combo), expected);
    }

    #[test]
    fn canonical_combo_hash_differs_for_different_combos() {
        let combo_a = StrategyCombo {
            split_offset_base: Some(OffsetBase::AutoHost),
            tls_record_offset_base: None,
            tlsrandrec_profile: None,
            udp_burst_profile: None,
            quic_fake_profile: None,
            fake_ttl: Some(8),
            entropy_mode: None,
        };
        let combo_b = StrategyCombo {
            split_offset_base: Some(OffsetBase::MidSld),
            tls_record_offset_base: None,
            tlsrandrec_profile: None,
            udp_burst_profile: None,
            quic_fake_profile: None,
            fake_ttl: Some(8),
            entropy_mode: None,
        };
        assert_ne!(canonical_combo_hash(&combo_a), canonical_combo_hash(&combo_b));
    }

    #[test]
    fn canonical_combo_hash_distinguishes_some_zero_from_none() {
        // Some(0) for fake_ttl must hash differently than None for fake_ttl.
        // FNV-1a sees 0x00 vs 0xFF in slot 11.
        let combo_some = StrategyCombo {
            split_offset_base: None,
            tls_record_offset_base: None,
            tlsrandrec_profile: None,
            udp_burst_profile: None,
            quic_fake_profile: None,
            fake_ttl: Some(0),
            entropy_mode: None,
        };
        let combo_none = StrategyCombo {
            split_offset_base: None,
            tls_record_offset_base: None,
            tlsrandrec_profile: None,
            udp_burst_profile: None,
            quic_fake_profile: None,
            fake_ttl: None,
            entropy_mode: None,
        };
        assert_ne!(canonical_combo_hash(&combo_some), canonical_combo_hash(&combo_none));
    }
}
