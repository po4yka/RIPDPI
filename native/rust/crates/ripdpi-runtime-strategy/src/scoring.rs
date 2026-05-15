//! Bayesian posterior arm scoring for the privacy-preserving strategy learner.
//!
//! Score formula:
//! ```text
//! posterior = alpha / (alpha + beta)
//! score = posterior
//!       - 0.10 * normalized_ttfb
//!       - 0.08 * normalized_bytes_overhead
//!       - 0.15 * repeated_attempt_penalty
//!       - 0.20 * rarity_penalty
//! ```
//!
//! All penalty terms are in [0, 1]. The final score may be negative.

use crate::profiles::{AccessType, ArmStats, HostProfile, NetProfile};

// ── Penalty weights ──────────────────────────────────────────────────────────

const WEIGHT_TTFB: f64 = 0.10;
const WEIGHT_BYTES: f64 = 0.08;
const WEIGHT_REPEATED: f64 = 0.15;
const WEIGHT_RARITY: f64 = 0.20;

// ── Network-profile-aware baselines ─────────────────────────────────────────

/// TTFB baseline (ms) for WiFi / wired / VPN / unknown access types.
const TTFB_BASELINE_WIFI_MS: f64 = 300.0;
/// TTFB baseline (ms) for all cellular access types.
const TTFB_BASELINE_CELLULAR_MS: f64 = 600.0;

/// Bytes-overhead baseline for WiFi / wired / VPN / unknown.
const BYTES_BASELINE_WIFI: f64 = 1500.0;
/// Bytes-overhead baseline for cellular.
const BYTES_BASELINE_CELLULAR: f64 = 800.0;

/// After this many consecutive failures the repeated-attempt penalty
/// saturates at 1.0.
const REPEATED_FAILURE_SATURATION: f64 = 10.0;

/// If an arm has not succeeded within this epoch-offset window (ms) it is
/// considered "rare" and receives the full rarity penalty.
/// Default: 5 minutes in milliseconds.
const RARITY_WINDOW_MS: u64 = 5 * 60 * 1_000;

// ── Context ──────────────────────────────────────────────────────────────────

/// Per-scoring-run context passed to [`score_arm`].
///
/// `now_ms` is the evolver's monotonic millisecond offset (same epoch as
/// [`ArmStats::last_success_at`]).
///
/// `run_seed` is a caller-supplied u64 seed used for tie-break jitter so
/// that callers in production can pass a per-run random seed while tests
/// can pass a fixed value for deterministic ordering.
#[derive(Debug, Clone, Copy)]
pub struct ScoringContext {
    /// Current evolver-monotonic millisecond offset.
    pub now_ms: u64,
    /// Per-run seed for tie-break jitter.
    pub run_seed: u64,
}

// ── Public API ───────────────────────────────────────────────────────────────

/// Compute the Bayesian posterior score for a single arm.
///
/// Returns a `f64` in approximately `(-0.53, 1.0]`.  Higher is better.
/// Two arms with numerically identical base scores receive a tiny
/// deterministic jitter derived from `arm_id` XOR `ctx.run_seed` so that
/// sort order is stable across identical arms without favouring any fixed
/// arm.
pub fn score_arm(stats: &ArmStats, net: &NetProfile, _host: &HostProfile, ctx: &ScoringContext) -> f64 {
    let posterior = compute_posterior(stats);
    let ttfb_penalty = WEIGHT_TTFB * normalized_ttfb(stats, net);
    let bytes_penalty = WEIGHT_BYTES * normalized_bytes_overhead(stats, net);
    let repeated_penalty = WEIGHT_REPEATED * repeated_attempt_penalty(stats);
    let rare_penalty = WEIGHT_RARITY * rarity_penalty(stats, ctx);

    let base = posterior - ttfb_penalty - bytes_penalty - repeated_penalty - rare_penalty;
    base + tie_break_jitter(&stats.arm_id, ctx.run_seed)
}

// ── Component helpers ────────────────────────────────────────────────────────

/// `alpha / (alpha + beta)` — Beta posterior mean.
#[inline]
pub fn compute_posterior(stats: &ArmStats) -> f64 {
    let denom = stats.alpha + stats.beta;
    if denom <= 0.0 {
        return 0.5; // degenerate prior: fall back to 50 %
    }
    stats.alpha / denom
}

/// TTFB penalty term, normalized against the network-profile baseline.
/// Result is clamped to [0, 1].
#[inline]
pub fn normalized_ttfb(stats: &ArmStats, net: &NetProfile) -> f64 {
    let baseline = ttfb_baseline(net);
    (stats.p50_ttfb_ms as f64 / baseline).min(1.0)
}

/// Bytes-overhead penalty term, normalized against the network-profile baseline.
/// Result is clamped to [0, 1].
#[inline]
pub fn normalized_bytes_overhead(stats: &ArmStats, net: &NetProfile) -> f64 {
    let baseline = bytes_baseline(net);
    (stats.bytes_overhead as f64 / baseline).min(1.0)
}

/// Penalty that scales monotonically with `repeated_failures`, saturating
/// at 1.0 after [`REPEATED_FAILURE_SATURATION`] consecutive failures.
#[inline]
pub fn repeated_attempt_penalty(stats: &ArmStats) -> f64 {
    (stats.repeated_failures as f64 / REPEATED_FAILURE_SATURATION).min(1.0)
}

/// Full rarity penalty (1.0) when the arm has never succeeded or its last
/// success was outside [`RARITY_WINDOW_MS`]; 0.0 otherwise.
#[inline]
pub fn rarity_penalty(stats: &ArmStats, ctx: &ScoringContext) -> f64 {
    match stats.last_success_at {
        None => 1.0,
        Some(t) => {
            if ctx.now_ms.saturating_sub(t) > RARITY_WINDOW_MS {
                1.0
            } else {
                0.0
            }
        }
    }
}

// ── Private helpers ──────────────────────────────────────────────────────────

fn ttfb_baseline(net: &NetProfile) -> f64 {
    match net.access_type {
        AccessType::CellularNr | AccessType::CellularLte | AccessType::CellularOther => TTFB_BASELINE_CELLULAR_MS,
        _ => TTFB_BASELINE_WIFI_MS,
    }
}

fn bytes_baseline(net: &NetProfile) -> f64 {
    match net.access_type {
        AccessType::CellularNr | AccessType::CellularLte | AccessType::CellularOther => BYTES_BASELINE_CELLULAR,
        _ => BYTES_BASELINE_WIFI,
    }
}

/// Deterministic sub-ULP jitter so that two arms with the same base score
/// do not always map to the same sort position.
///
/// The jitter is derived by hashing `arm_id` bytes with `run_seed` via a
/// simple FNV-1a mix, then scaling to `[0, 1e-9)`.  At this magnitude it
/// cannot change which arm wins a non-tie; it only breaks exact ties.
fn tie_break_jitter(arm_id: &str, run_seed: u64) -> f64 {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325_u64.wrapping_add(run_seed);
    for b in arm_id.bytes() {
        h ^= u64::from(b);
        h = h.wrapping_mul(0x0100_0000_01b3);
    }
    // Map to [0, 1e-9)
    (h as f64 / u64::MAX as f64) * 1e-9
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use crate::profiles::{AccessType, AppFamily, ArmStats, DnsClass, HostProfile, IpFamily, NetProfile};

    // ── Fixtures ─────────────────────────────────────────────────────────────

    fn wifi_net() -> NetProfile {
        NetProfile {
            asn: 0,
            access_type: AccessType::Wifi,
            ip_family: IpFamily::V4,
            dns_class: DnsClass::SystemDefault,
            udp443_ok: true,
            tcp443_ok: true,
            observed_fail_phase: None,
        }
    }

    fn cellular_net() -> NetProfile {
        NetProfile { access_type: AccessType::CellularLte, ..wifi_net() }
    }

    fn sample_host() -> HostProfile {
        HostProfile {
            etld_plus_1: "example.com".to_owned(),
            h3_advertised: false,
            https_rr_present: false,
            ech_capable: false,
            app_family: AppFamily::Browser,
        }
    }

    fn base_stats() -> ArmStats {
        ArmStats {
            arm_id: "split".to_owned(),
            alpha: 3.0,
            beta: 1.0,
            p50_ttfb_ms: 0,
            bytes_overhead: 0,
            repeated_failures: 0,
            last_success_at: Some(0),
        }
    }

    fn ctx_at(now_ms: u64) -> ScoringContext {
        ScoringContext { now_ms, run_seed: 42 }
    }

    // ── posterior = alpha / (alpha + beta) ───────────────────────────────────

    #[test]
    fn posterior_equals_alpha_over_alpha_plus_beta() {
        let stats = ArmStats { alpha: 3.0, beta: 1.0, ..base_stats() };
        let got = compute_posterior(&stats);
        let want = 3.0 / 4.0;
        assert!((got - want).abs() < 1e-12, "posterior {got} != expected {want}");
    }

    #[test]
    fn posterior_with_equal_alpha_beta_is_half() {
        let stats = ArmStats { alpha: 5.0, beta: 5.0, ..base_stats() };
        assert!((compute_posterior(&stats) - 0.5).abs() < 1e-12);
    }

    #[test]
    fn posterior_degenerate_zero_denom_returns_half() {
        let stats = ArmStats { alpha: 0.0, beta: 0.0, ..base_stats() };
        assert!((compute_posterior(&stats) - 0.5).abs() < 1e-12);
    }

    // ── TTFB penalty term in isolation ───────────────────────────────────────

    #[test]
    fn ttfb_penalty_term_moves_score_by_expected_magnitude() {
        // alpha=1, beta=0 => posterior=1.0
        // All other penalties zeroed out: bytes_overhead=0, repeated=0,
        // last_success_at recent enough for no rarity penalty.
        let high_ttfb = ArmStats {
            arm_id: "ttfb-arm".to_owned(),
            alpha: 1.0,
            beta: 0.0,
            p50_ttfb_ms: 300, // = wifi baseline => normalized=1.0 => penalty=0.10
            bytes_overhead: 0,
            repeated_failures: 0,
            last_success_at: Some(0),
        };
        let zero_ttfb = ArmStats { p50_ttfb_ms: 0, ..high_ttfb.clone() };

        let ctx = ctx_at(0);
        let net = wifi_net();
        let host = sample_host();

        let score_high = score_arm(&high_ttfb, &net, &host, &ctx);
        let score_zero = score_arm(&zero_ttfb, &net, &host, &ctx);

        // Difference should be ~ WEIGHT_TTFB (0.10), within jitter tolerance.
        let diff = score_zero - score_high;
        assert!((diff - WEIGHT_TTFB).abs() < 1e-6, "expected ttfb delta {WEIGHT_TTFB}, got {diff}");
    }

    // ── Bytes-overhead penalty term in isolation ─────────────────────────────

    #[test]
    fn bytes_overhead_penalty_term_moves_score_by_expected_magnitude() {
        let high_bytes = ArmStats {
            arm_id: "bytes-arm".to_owned(),
            alpha: 1.0,
            beta: 0.0,
            p50_ttfb_ms: 0,
            bytes_overhead: 1500, // = wifi baseline => normalized=1.0 => penalty=0.08
            repeated_failures: 0,
            last_success_at: Some(0),
        };
        let zero_bytes = ArmStats { bytes_overhead: 0, ..high_bytes.clone() };

        let ctx = ctx_at(0);
        let net = wifi_net();
        let host = sample_host();

        let diff = score_arm(&zero_bytes, &net, &host, &ctx) - score_arm(&high_bytes, &net, &host, &ctx);
        assert!((diff - WEIGHT_BYTES).abs() < 1e-6, "expected bytes delta {WEIGHT_BYTES}, got {diff}");
    }

    // ── Cellular vs WiFi baseline normalization ───────────────────────────────

    #[test]
    fn ttfb_normalization_differs_cellular_vs_wifi() {
        // 300 ms TTFB: on WiFi baseline (300) it normalizes to 1.0;
        // on cellular baseline (600) it normalizes to 0.5.
        let stats = ArmStats {
            arm_id: "ttfb-norm-arm".to_owned(),
            alpha: 1.0,
            beta: 0.0,
            p50_ttfb_ms: 300,
            bytes_overhead: 0,
            repeated_failures: 0,
            last_success_at: Some(0),
        };
        let norm_wifi = normalized_ttfb(&stats, &wifi_net());
        let norm_cell = normalized_ttfb(&stats, &cellular_net());

        assert!((norm_wifi - 1.0).abs() < 1e-9, "wifi normalized={norm_wifi}");
        assert!((norm_cell - 0.5).abs() < 1e-9, "cellular normalized={norm_cell}");
        assert!(norm_cell < norm_wifi, "cellular penalty should be smaller for same TTFB");
    }

    #[test]
    fn bytes_normalization_differs_cellular_vs_wifi() {
        // 800 bytes: on cellular baseline (800) => 1.0; on wifi baseline (1500) => 0.533.
        let stats = ArmStats {
            arm_id: "bytes-norm-arm".to_owned(),
            alpha: 1.0,
            beta: 0.0,
            p50_ttfb_ms: 0,
            bytes_overhead: 800,
            repeated_failures: 0,
            last_success_at: Some(0),
        };
        let norm_wifi = normalized_bytes_overhead(&stats, &wifi_net());
        let norm_cell = normalized_bytes_overhead(&stats, &cellular_net());

        assert!((norm_cell - 1.0).abs() < 1e-9, "cellular normalized={norm_cell}");
        assert!(norm_wifi < norm_cell, "wifi penalty should be smaller for same bytes");
    }

    // ── Repeated-attempt penalty ──────────────────────────────────────────────

    #[test]
    fn repeated_attempt_penalty_scales_monotonically() {
        let mut prev = 0.0_f64;
        for failures in [0u32, 1, 3, 5, 10, 20] {
            let stats = ArmStats { repeated_failures: failures, ..base_stats() };
            let p = repeated_attempt_penalty(&stats);
            assert!(p >= prev, "penalty not monotone at failures={failures}");
            assert!(p <= 1.0, "penalty exceeds 1.0 at failures={failures}");
            prev = p;
        }
    }

    #[test]
    fn repeated_attempt_penalty_zero_when_no_failures() {
        let stats = ArmStats { repeated_failures: 0, ..base_stats() };
        assert_eq!(repeated_attempt_penalty(&stats), 0.0);
    }

    #[test]
    fn repeated_attempt_penalty_saturates_at_one() {
        let stats = ArmStats { repeated_failures: 100, ..base_stats() };
        assert_eq!(repeated_attempt_penalty(&stats), 1.0);
    }

    #[test]
    fn repeated_penalty_term_moves_score_by_expected_magnitude() {
        let full_repeat = ArmStats {
            arm_id: "repeat-arm".to_owned(),
            alpha: 1.0,
            beta: 0.0,
            p50_ttfb_ms: 0,
            bytes_overhead: 0,
            repeated_failures: 100, // saturated => penalty = WEIGHT_REPEATED
            last_success_at: Some(0),
        };
        let no_repeat = ArmStats { repeated_failures: 0, ..full_repeat.clone() };
        let ctx = ctx_at(0);
        let net = wifi_net();
        let host = sample_host();

        let diff = score_arm(&no_repeat, &net, &host, &ctx) - score_arm(&full_repeat, &net, &host, &ctx);
        assert!((diff - WEIGHT_REPEATED).abs() < 1e-6, "expected repeated delta {WEIGHT_REPEATED}, got {diff}");
    }

    // ── Rarity penalty ────────────────────────────────────────────────────────

    #[test]
    fn rarity_penalty_one_when_never_succeeded() {
        let stats = ArmStats { last_success_at: None, ..base_stats() };
        let ctx = ctx_at(1_000_000);
        assert_eq!(rarity_penalty(&stats, &ctx), 1.0);
    }

    #[test]
    fn rarity_penalty_zero_when_recently_succeeded() {
        let ctx = ctx_at(1_000);
        let stats = ArmStats { last_success_at: Some(900), ..base_stats() }; // 100 ms ago
        assert_eq!(rarity_penalty(&stats, &ctx), 0.0);
    }

    #[test]
    fn rarity_penalty_one_when_success_was_too_long_ago() {
        let ctx = ctx_at(RARITY_WINDOW_MS + 1);
        let stats = ArmStats { last_success_at: Some(0), ..base_stats() };
        assert_eq!(rarity_penalty(&stats, &ctx), 1.0);
    }

    #[test]
    fn rarity_penalty_term_moves_score_by_expected_magnitude() {
        let never_seen = ArmStats {
            arm_id: "rarity-arm".to_owned(),
            alpha: 1.0,
            beta: 0.0,
            p50_ttfb_ms: 0,
            bytes_overhead: 0,
            repeated_failures: 0,
            last_success_at: None, // => rarity = 1.0
        };
        let recent = ArmStats { last_success_at: Some(0), ..never_seen.clone() };
        let ctx = ctx_at(0); // now=0, recent.last_success_at=0 => within window
        let net = wifi_net();
        let host = sample_host();

        let diff = score_arm(&recent, &net, &host, &ctx) - score_arm(&never_seen, &net, &host, &ctx);
        assert!((diff - WEIGHT_RARITY).abs() < 1e-6, "expected rarity delta {WEIGHT_RARITY}, got {diff}");
    }

    // ── Tie-break: consistent ordering with bounded jitter ───────────────────

    #[test]
    fn tie_break_is_deterministic_for_same_arm_and_seed() {
        let stats = base_stats();
        let ctx = ScoringContext { now_ms: 0, run_seed: 99 };
        let net = wifi_net();
        let host = sample_host();

        let s1 = score_arm(&stats, &net, &host, &ctx);
        let s2 = score_arm(&stats, &net, &host, &ctx);
        assert_eq!(s1, s2, "same inputs must produce identical score");
    }

    #[test]
    fn tie_break_jitter_is_sub_ulp_relative_to_score() {
        // Jitter must be < 1e-9; a typical score is in [0, 1].
        // Verify the jitter alone is never >= 1e-9.
        let j = tie_break_jitter("any-arm-id", 12345);
        assert!(j < 1e-9, "jitter {j} exceeds bound");
        assert!(j >= 0.0, "jitter must be non-negative");
    }

    #[test]
    fn different_seeds_produce_different_jitter_for_same_arm() {
        let j1 = tie_break_jitter("arm", 1);
        let j2 = tie_break_jitter("arm", 2);
        assert_ne!(j1, j2, "different seeds should yield different jitter");
    }

    #[test]
    fn identical_base_scores_ordered_consistently() {
        // Two arms with alpha=beta produce posterior=0.5, same penalties.
        let arm_a = ArmStats { arm_id: "arm-a".to_owned(), alpha: 2.0, beta: 2.0, ..base_stats() };
        let arm_b = ArmStats { arm_id: "arm-b".to_owned(), alpha: 2.0, beta: 2.0, ..base_stats() };
        let ctx = ScoringContext { now_ms: 0, run_seed: 7 };
        let net = wifi_net();
        let host = sample_host();

        let sa = score_arm(&arm_a, &net, &host, &ctx);
        let sb = score_arm(&arm_b, &net, &host, &ctx);

        // Must differ (tie broken) and difference is tiny.
        assert_ne!(sa, sb, "tie-break must distinguish identical arms");
        assert!((sa - sb).abs() < 1e-9, "tie-break diff {:.2e} exceeds bound", (sa - sb).abs());

        // Must be stable across calls.
        assert_eq!(score_arm(&arm_a, &net, &host, &ctx), sa);
        assert_eq!(score_arm(&arm_b, &net, &host, &ctx), sb);
    }
}
