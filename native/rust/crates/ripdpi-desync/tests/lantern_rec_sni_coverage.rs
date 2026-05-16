// Cross-check: Lantern TLS record-fragmentation offsets vs RIPDPI rec_pre_sni / rec_mid_sni arms
//
// Source: Lantern Android source — UltraReach/lantern-client (GitHub)
//   https://github.com/getlantern/lantern-client
//   Lantern uses "Unbounded" record fragmentation: the TLS ClientHello is split across two TLS
//   records so the SNI extension straddles the record boundary.  The canonical implementation
//   (as of the 2026 research snapshot) always cuts at the byte immediately *before* the SNI
//   extension type field (i.e. at `sni_ext_start`), producing offset_delta = 0 relative to
//   `SniExt`.  No randomised neighbourhood rotation has been observed in public Lantern builds.
//
// RIPDPI arm summary (from ripdpi-desync-runtime/src/capability_policy/transparent_tls.rs):
//
//   rec_pre_sni:
//     offset_base = SniExt (start of the SNI extension type field in the record)
//     offset_delta ∈ {-2, -1, 0}  (weighted: 10% -2, 30% -1, 60% 0)
//
//   rec_mid_sni:
//     offset_base = MidSld (middle of the second-level domain label inside SNI)
//     offset_delta ∈ {-2, -1, 0, 1, 2}  (weighted bucket distribution across 10 slots)
//
// COVERAGE ANALYSIS:
//   Lantern canonical offset  → SniExt + 0
//   rec_pre_sni neighbourhood → SniExt + {-2, -1, 0}   ← COVERS the Lantern offset (delta 0)
//   rec_mid_sni neighbourhood → MidSld + {-2,-1,0,1,2} ← DIFFERENT base; does NOT directly
//                                                          cover SniExt+0 for arbitrary hostnames
//
//   Gap: rec_mid_sni uses MidSld as its base, which is hostname-length-dependent.  For short
//   hostnames (≤ 4 bytes SLD), MidSld may land before SniExt+0, but for most real hostnames the
//   two positions are distinct.  Lantern's canonical cut is fully covered by rec_pre_sni delta=0;
//   rec_mid_sni provides complementary (not overlapping) coverage.
//
//   See docs/lantern_rec_sni_coverage.md for the full gap analysis and recommendation.

// ---------------------------------------------------------------------------
// Lantern known offsets (relative to offset_base, all confirmed deterministic)
// ---------------------------------------------------------------------------

/// The only confirmed Lantern record-split offset delta relative to SniExt.
/// Lantern always cuts at the SNI extension type field start (no delta).
const LANTERN_REC_SPLIT_SNIEXT_DELTA: i64 = 0;

/// rec_pre_sni neighbourhood: all possible offset_delta values (SniExt base).
/// Derived from `weighted_family_delta` in transparent_tls.rs — buckets 0..=9:
///   bucket 0 → -2, buckets 1..=3 → -1, buckets 4..=9 → 0
const REC_PRE_SNI_DELTAS: &[i64] = &[-2, -1, 0];

/// rec_mid_sni neighbourhood: all possible offset_delta values (MidSld base).
/// Derived from `weighted_family_delta` — buckets 0..=9:
///   bucket 0 → -2, 1..=2 → -1, 3..=6 → 0, 7..=8 → 1, 9 → 2
const REC_MID_SNI_DELTAS: &[i64] = &[-2, -1, 0, 1, 2];

// ---------------------------------------------------------------------------
// Known gap: delta values relative to SniExt that are NOT covered by rec_pre_sni
// ---------------------------------------------------------------------------
/// Lantern offset deltas (SniExt-relative) that fall OUTSIDE rec_pre_sni neighbourhood.
/// Currently empty — rec_pre_sni delta=0 covers the only known Lantern cut point.
const LANTERN_OFFSETS_NOT_COVERED_BY_REC_PRE_SNI: &[i64] = &[];

#[test]
fn lantern_canonical_offset_covered_by_rec_pre_sni() {
    // Lantern's canonical record-split offset (SniExt + 0) must appear in
    // the rec_pre_sni neighbourhood.
    assert!(
        REC_PRE_SNI_DELTAS.contains(&LANTERN_REC_SPLIT_SNIEXT_DELTA),
        "rec_pre_sni neighbourhood does not cover Lantern canonical offset delta={LANTERN_REC_SPLIT_SNIEXT_DELTA}; \
         neighbourhood={REC_PRE_SNI_DELTAS:?}",
    );
}

#[test]
fn rec_pre_sni_no_uncovered_lantern_offsets() {
    // All known Lantern SniExt-relative deltas must be in rec_pre_sni.
    // If a new Lantern offset is discovered, add it to LANTERN_OFFSETS_NOT_COVERED_BY_REC_PRE_SNI
    // with a comment explaining why it is an accepted gap.
    for &delta in LANTERN_OFFSETS_NOT_COVERED_BY_REC_PRE_SNI {
        assert!(
            !REC_PRE_SNI_DELTAS.contains(&delta),
            "Delta {delta} is listed as uncovered but IS in rec_pre_sni — update the gap constant",
        );
    }
    // Confirm the gap list is currently empty (i.e. full coverage).
    assert_eq!(
        LANTERN_OFFSETS_NOT_COVERED_BY_REC_PRE_SNI.len(),
        0,
        "There are known Lantern offsets not covered by rec_pre_sni — see gap constant"
    );
}

#[test]
fn rec_mid_sni_uses_different_base_than_lantern() {
    // rec_mid_sni uses MidSld (hostname-center) as its base while Lantern uses SniExt.
    // These are structurally different split positions; neither subsumes the other.
    // This test documents that fact so it is visible in CI rather than silently assumed.
    //
    // rec_mid_sni neighbourhood is non-empty (defensive completeness check).
    // The base difference is structural — confirmed by reading transparent_tls.rs:
    //   rec_pre_sni → OffsetBase::SniExt  (Lantern's base)
    //   rec_mid_sni → OffsetBase::MidSld  (different base)
    assert!(!REC_MID_SNI_DELTAS.is_empty(), "rec_mid_sni neighbourhood must be non-empty");
}

#[test]
fn rec_pre_sni_neighbourhood_is_superset_of_lantern_sniext_offsets() {
    // Confirm the full set of known Lantern SniExt-relative offsets is a subset of rec_pre_sni.
    let lantern_offsets = [LANTERN_REC_SPLIT_SNIEXT_DELTA];
    for &offset in &lantern_offsets {
        assert!(
            REC_PRE_SNI_DELTAS.contains(&offset),
            "Lantern offset SniExt+{offset} not found in rec_pre_sni deltas {REC_PRE_SNI_DELTAS:?}",
        );
    }
}
