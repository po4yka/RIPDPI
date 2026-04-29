//! Thompson sampling (Beta-Bernoulli bandit) for arm selection.
//!
//! This module provides [`ThompsonSampling`], a plug-in alternative to the
//! UCB1 selection used by [`super::StrategyEvolver`]. The caller maintains
//! Beta(α, β) posteriors per arm and selects the arm with the highest
//! sampled value.
//!
//! # Usage
//!
//! ```ignore
//! let mut ts: ThompsonSampling<MyArmKey> = ThompsonSampling::new();
//! ts.record_outcome(&key, true);   // success
//! ts.record_outcome(&key, false);  // failure
//! let best = ts.select_arm(&mut rng_state);
//! ```
//!
//! # Beta sampling
//!
//! Beta samples are drawn using **Johnk's method** — no external crate
//! required. The method accepts a pair (U, V) ∈ (0,1)² when
//! `U^(1/α) + V^(1/β) ≤ 1` and returns `U^(1/α) / (U^(1/α) + V^(1/β))`.
//! For the special cases α = 1 or β = 1 the distribution simplifies to
//! `U^(1/β)` or `1 - U^(1/α)` respectively, making the sampler exact.
//!
//! Convergence is O(1) expected iterations for α, β ≥ 1 (the prior starts
//! at (1, 1), so this always holds). At high arm counts (≥ 200) the Cheng
//! algorithm would be faster, but the evolver caps arms at 64 so Johnk is
//! appropriate.
//!
// Standalone scorer; the evolver still uses UCB1 by default. Production
// integration is selected outside this module; suppress dead_code until then.
#![allow(dead_code)]

use std::collections::HashMap;
use std::hash::Hash;

// ---------------------------------------------------------------------------
// BetaParams
// ---------------------------------------------------------------------------

/// Beta distribution parameters for a single arm.
///
/// The posterior starts at `Beta(1, 1)` (uniform prior) and is updated by
/// calling [`ThompsonSampling::record_outcome`].
#[derive(Debug, Clone, Copy)]
pub struct BetaParams {
    /// α = prior + success count. Starts at 1 (uniform prior).
    pub alpha: f64,
    /// β = prior + failure count. Starts at 1 (uniform prior).
    pub beta: f64,
}

impl Default for BetaParams {
    fn default() -> Self {
        Self { alpha: 1.0, beta: 1.0 }
    }
}

impl BetaParams {
    /// Return the posterior mean, useful for diagnostics.
    pub fn mean(&self) -> f64 {
        self.alpha / (self.alpha + self.beta)
    }
}

// ---------------------------------------------------------------------------
// Beta sampling (Johnk's method, no external deps)
// ---------------------------------------------------------------------------

/// Sample one draw from Beta(alpha, beta) using a caller-supplied LCG state.
///
/// `rng` is a `u64` Lehmer LCG state (same constants as `StrategyEvolver`).
/// The function mutates `rng` in place and returns a value in (0.0, 1.0).
pub fn sample_beta(rng: &mut u64, alpha: f64, beta: f64) -> f64 {
    // Fast path: Beta(1, β) = 1 - U^(1/β); Beta(α, 1) = U^(1/α).
    if (alpha - 1.0).abs() < f64::EPSILON {
        // Beta(1, β): PDF = β*(1-x)^(β-1), sample via inversion: X = 1 - U^(1/β).
        let u = lcg_f64(rng);
        return 1.0 - u.powf(1.0 / beta);
    }
    if (beta - 1.0).abs() < f64::EPSILON {
        // Beta(α, 1): PDF = α*x^(α-1), sample via inversion: X = U^(1/α).
        let u = lcg_f64(rng);
        return u.powf(1.0 / alpha);
    }

    // General Johnk method: O(1) expected iterations for α, β ≥ 1.
    let inv_a = 1.0 / alpha;
    let inv_b = 1.0 / beta;
    loop {
        let u = lcg_f64(rng);
        let v = lcg_f64(rng);
        let x = u.powf(inv_a);
        let y = v.powf(inv_b);
        let s = x + y;
        if s <= 1.0 && s > 0.0 {
            return x / s;
        }
    }
}

/// Advance the LCG and return a float in (0.0, 1.0).
///
/// Uses the same Lehmer constants as `StrategyEvolver::lcg_f64` but maps
/// to the full [0, 1) range using a 53-bit mantissa extraction (IEEE 754
/// double has 52 explicit mantissa bits; shifting right by 11 gives 53
/// significant bits, then dividing by 2^53 yields a uniform value in
/// [0, 1)).
fn lcg_f64(state: &mut u64) -> f64 {
    *state = state.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1_442_695_040_888_963_407);
    // Extract 53 high-quality bits (bits 63..11) and map to [0, 1).
    let raw = (*state >> 11) as f64 * (1.0 / (1u64 << 53) as f64);
    // Clamp away exact 0 to keep sample_beta denominators non-zero.
    if raw == 0.0 {
        f64::MIN_POSITIVE
    } else {
        raw
    }
}

// ---------------------------------------------------------------------------
// ThompsonSampling
// ---------------------------------------------------------------------------

/// Beta-Bernoulli Thompson sampling bandit.
///
/// Arms are identified by any `Clone + Eq + Hash` key. Each arm maintains a
/// Beta posterior; [`select_arm`] draws one sample per arm and returns the
/// arm with the highest draw.
///
/// This type is **not** integrated into [`super::StrategyEvolver`]'s
/// selection path yet; UCB1 remains the default.
pub struct ThompsonSampling<K> {
    arms: HashMap<K, BetaParams>,
}

impl<K: Clone + Eq + Hash> ThompsonSampling<K> {
    /// Create a new empty sampler.
    pub fn new() -> Self {
        Self { arms: HashMap::new() }
    }

    /// Record a Bernoulli outcome for `arm`. Inserts the arm with a
    /// `Beta(1,1)` prior if it has not been seen before.
    pub fn record_outcome(&mut self, arm: &K, success: bool) {
        let params = self.arms.entry(arm.clone()).or_default();
        if success {
            params.alpha += 1.0;
        } else {
            params.beta += 1.0;
        }
    }

    /// Sample one draw per arm and return the arm with the highest draw.
    ///
    /// Returns `None` if no arms have been registered. Mutates `rng_state`
    /// in place so the caller retains the updated LCG seed.
    pub fn select_arm(&self, rng_state: &mut u64) -> Option<K> {
        self.arms
            .iter()
            .map(|(arm, params)| {
                let draw = sample_beta(rng_state, params.alpha, params.beta);
                (arm, draw)
            })
            .max_by(|a, b| a.1.partial_cmp(&b.1).unwrap_or(std::cmp::Ordering::Equal))
            .map(|(arm, _)| arm.clone())
    }

    /// Number of registered arms.
    pub fn arm_count(&self) -> usize {
        self.arms.len()
    }

    /// Return the current Beta posterior for an arm, if known.
    pub fn params(&self, arm: &K) -> Option<&BetaParams> {
        self.arms.get(arm)
    }
}

impl<K: Clone + Eq + Hash> Default for ThompsonSampling<K> {
    fn default() -> Self {
        Self::new()
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    // --- BetaParams ---

    #[test]
    fn beta_params_default_is_uniform() {
        let p = BetaParams::default();
        assert_eq!(p.alpha, 1.0);
        assert_eq!(p.beta, 1.0);
        // Uniform prior: mean = 0.5.
        assert!((p.mean() - 0.5).abs() < f64::EPSILON);
    }

    // --- sample_beta ---

    #[test]
    fn sample_beta_stays_in_unit_interval() {
        let mut rng = 0xDEAD_BEEF_1234_5678_u64;
        for _ in 0..1_000 {
            let s = sample_beta(&mut rng, 2.0, 5.0);
            assert!(s > 0.0 && s < 1.0, "sample out of range: {s}");
        }
    }

    #[test]
    fn sample_beta_fast_path_beta1() {
        // Beta(α, 1): PDF = α·x^(α-1), sampled via X = U^(1/α). Mean = α/(α+1).
        let mut rng = 42_u64;
        let n = 5_000;
        let alpha = 3.0;
        let mean: f64 = (0..n).map(|_| sample_beta(&mut rng, alpha, 1.0)).sum::<f64>() / n as f64;
        let expected = alpha / (alpha + 1.0); // 0.75
        assert!((mean - expected).abs() < 0.02, "Beta({alpha},1) mean {mean:.3} ≠ expected {expected:.3}");
    }

    #[test]
    fn sample_beta_fast_path_alpha1() {
        // Beta(1, β): PDF = β·(1-x)^(β-1), sampled via X = 1 - U^(1/β). Mean = 1/(1+β).
        let mut rng = 99_u64;
        let n = 5_000;
        let beta = 4.0;
        let mean: f64 = (0..n).map(|_| sample_beta(&mut rng, 1.0, beta)).sum::<f64>() / n as f64;
        let expected = 1.0 / (1.0 + beta); // 0.2
        assert!((mean - expected).abs() < 0.02, "Beta(1,{beta}) mean {mean:.3} ≠ expected {expected:.3}");
    }

    #[test]
    fn sample_beta_general_mean_close_to_analytical() {
        let mut rng = 7_u64;
        let n = 10_000;
        let (a, b) = (3.0, 2.0);
        let mean: f64 = (0..n).map(|_| sample_beta(&mut rng, a, b)).sum::<f64>() / n as f64;
        let expected = a / (a + b); // 0.6
        assert!((mean - expected).abs() < 0.02, "mean {mean:.4} ≠ {expected:.4}");
    }

    // --- ThompsonSampling ---

    #[test]
    fn select_arm_returns_none_when_empty() {
        let ts: ThompsonSampling<u32> = ThompsonSampling::new();
        let mut rng = 1_u64;
        assert!(ts.select_arm(&mut rng).is_none());
    }

    #[test]
    fn select_arm_returns_single_arm() {
        let mut ts: ThompsonSampling<&str> = ThompsonSampling::new();
        ts.record_outcome(&"only", true);
        let mut rng = 42_u64;
        assert_eq!(ts.select_arm(&mut rng), Some("only"));
    }

    #[test]
    fn record_outcome_inserts_arm_with_prior() {
        let mut ts: ThompsonSampling<u32> = ThompsonSampling::new();
        ts.record_outcome(&1, true);
        let p = ts.params(&1).expect("arm 1 should exist");
        assert_eq!(p.alpha, 2.0); // 1 prior + 1 success
        assert_eq!(p.beta, 1.0); // prior only
    }

    #[test]
    fn record_outcome_failure_increments_beta() {
        let mut ts: ThompsonSampling<u32> = ThompsonSampling::new();
        ts.record_outcome(&1, false);
        let p = ts.params(&1).expect("arm 1 should exist");
        assert_eq!(p.alpha, 1.0);
        assert_eq!(p.beta, 2.0);
    }

    #[test]
    fn convergence_prefers_good_arm_after_training() {
        // After 60 successes on arm A and 60 failures on arm B, Thompson
        // sampling should prefer arm A in the overwhelming majority of draws.
        let mut ts: ThompsonSampling<u32> = ThompsonSampling::new();
        for _ in 0..60 {
            ts.record_outcome(&1, true); // arm 1: good
            ts.record_outcome(&2, false); // arm 2: bad
        }
        let mut rng = 0xABCD_1234_u64;
        let trials = 200;
        let arm1_picks: usize = (0..trials).filter(|_| ts.select_arm(&mut rng) == Some(1)).count();
        // Arm 1 has posterior Beta(61,1) ≈ mean 0.984; arm 2 has Beta(1,61) ≈ mean 0.016.
        // Expect arm 1 to win > 90% of draws.
        assert!(arm1_picks > 170, "arm 1 should dominate after training (got {arm1_picks}/{trials})");
    }

    #[test]
    fn arm_count_reflects_unique_arms() {
        let mut ts: ThompsonSampling<u32> = ThompsonSampling::new();
        ts.record_outcome(&1, true);
        ts.record_outcome(&1, false);
        ts.record_outcome(&2, true);
        assert_eq!(ts.arm_count(), 2);
    }

    #[test]
    fn rng_state_is_mutated_by_select_arm() {
        let mut ts: ThompsonSampling<u32> = ThompsonSampling::new();
        ts.record_outcome(&1, true);
        let mut rng = 123_456_u64;
        let before = rng;
        ts.select_arm(&mut rng);
        assert_ne!(rng, before, "select_arm must advance the RNG state");
    }
}
