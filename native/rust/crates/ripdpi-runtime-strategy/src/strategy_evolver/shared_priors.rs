//! Strategy-facing shared-priors helpers.

pub use ripdpi_shared_priors::{
    ACTIVE_BROAD_BETA_PSEUDO_FAILURES, AppliedPriors, ApplyError, ManifestError, ProtocolThreatPriors,
    SHARED_PRIORS_PUB_KEY, SharedPriorsError, SharedPriorsManifest, apply_global_shared_priors,
    apply_global_shared_priors_with_embedded_key, apply_priors, apply_priors_with_embedded_key,
    global_protocol_threat_priors_len, global_shared_priors_len, is_production_key_set, latest_protocol_threat_priors,
    latest_shared_priors,
};

use crate::strategy_evolver::types::{
    StrategyCombo, entropy_mode_disc, offset_base_disc, oob_placement_disc, quic_fake_disc, timing_jitter_disc,
    tls_randrec_disc, udp_burst_disc,
};

/// Stable canonical hash of a [`StrategyCombo`] used as the key in shared
/// priors bundles. Independent of [`std::hash::Hasher`] (whose algorithm
/// is version-dependent). The first 14 bytes preserve the original 7-dimension
/// wire form; timing and OOB dimensions are appended only when selected so
/// hashes for legacy combos stay stable.
pub fn canonical_combo_hash(combo: &StrategyCombo) -> u64 {
    let mut bytes = Vec::with_capacity(18);
    write_dim(&mut bytes, 0, combo.split_offset_base.map(offset_base_disc));
    write_dim(&mut bytes, 1, combo.tls_record_offset_base.map(offset_base_disc));
    write_dim(&mut bytes, 2, combo.tlsrandrec_profile.map(tls_randrec_disc));
    write_dim(&mut bytes, 3, combo.udp_burst_profile.map(udp_burst_disc));
    write_dim(&mut bytes, 4, combo.quic_fake_profile.map(quic_fake_disc));
    // fake_ttl is a raw TTL, not an enum discriminant: 0xFF is reserved as
    // the "absent dimension" marker, so Some(255) cannot be written as-is.
    // Encode it with an explicit escape tail instead of clamping (clamping
    // would collide with 254); every other value hashes exactly as before.
    write_dim(&mut bytes, 5, combo.fake_ttl.filter(|ttl| *ttl != u8::MAX));
    if combo.fake_ttl == Some(u8::MAX) {
        bytes.extend_from_slice(&[5, 0x00]);
    }
    write_dim(&mut bytes, 6, combo.entropy_mode.map(entropy_mode_disc));
    if combo.timing_jitter_profile.is_some() || combo.oob_byte_placement.is_some() {
        write_dim(&mut bytes, 7, combo.timing_jitter_profile.map(timing_jitter_disc));
        write_dim(&mut bytes, 8, combo.oob_byte_placement.map(oob_placement_disc));
    }
    fnv1a_64(&bytes)
}

fn write_dim(out: &mut Vec<u8>, slot: usize, disc: Option<u8>) {
    out.push(slot as u8);
    out.push(disc.unwrap_or(0xFF));
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
pub(crate) mod manifest {
    pub(crate) use ripdpi_shared_priors::manifest::test_support;
}

#[cfg(test)]
mod tests {
    use ripdpi_config::OffsetBase;
    use ripdpi_desync::{AdaptiveOobBytePlacement, AdaptiveTimingJitterProfile};

    use super::*;
    use crate::strategy_evolver::StrategyCombo;

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
            timing_jitter_profile: None,
            oob_byte_placement: None,
        };
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
            timing_jitter_profile: None,
            oob_byte_placement: None,
        };
        let combo_b = StrategyCombo {
            split_offset_base: Some(OffsetBase::MidSld),
            tls_record_offset_base: None,
            tlsrandrec_profile: None,
            udp_burst_profile: None,
            quic_fake_profile: None,
            fake_ttl: Some(8),
            entropy_mode: None,
            timing_jitter_profile: None,
            oob_byte_placement: None,
        };
        assert_ne!(canonical_combo_hash(&combo_a), canonical_combo_hash(&combo_b));
    }

    #[test]
    fn canonical_combo_hash_separates_timing_and_oob_dimensions() {
        let timing = StrategyCombo {
            timing_jitter_profile: Some(AdaptiveTimingJitterProfile::Balanced),
            ..StrategyCombo::default_combo()
        };
        let oob = StrategyCombo {
            oob_byte_placement: Some(AdaptiveOobBytePlacement::PostSni),
            ..StrategyCombo::default_combo()
        };

        assert_ne!(canonical_combo_hash(&timing), canonical_combo_hash(&StrategyCombo::default_combo()));
        assert_ne!(canonical_combo_hash(&oob), canonical_combo_hash(&StrategyCombo::default_combo()));
        assert_ne!(canonical_combo_hash(&timing), canonical_combo_hash(&oob));
    }

    #[test]
    fn canonical_combo_hash_separates_absent_fake_ttl_from_max_ttl() {
        // L6 regression: fake_ttl is a raw byte, so Some(255) used to encode
        // as the 0xFF "absent dimension" marker and collide with None.
        let absent = StrategyCombo { fake_ttl: None, ..StrategyCombo::default_combo() };
        let max_ttl = StrategyCombo { fake_ttl: Some(255), ..StrategyCombo::default_combo() };
        let ttl_254 = StrategyCombo { fake_ttl: Some(254), ..StrategyCombo::default_combo() };
        assert_ne!(canonical_combo_hash(&absent), canonical_combo_hash(&max_ttl));
        assert_ne!(canonical_combo_hash(&absent), canonical_combo_hash(&ttl_254));
        assert_ne!(canonical_combo_hash(&max_ttl), canonical_combo_hash(&ttl_254));
    }
}
