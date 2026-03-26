//! Geneva-style automated exploration of DPI evasion parameter combinations.
//!
//! Sits on top of the existing adaptive framework and explores *combinations*
//! across the 5 adaptive dimensions plus fake-TTL using epsilon-greedy + UCB1
//! selection.

use std::collections::HashMap;
use std::hash::{Hash, Hasher};
use std::time::{SystemTime, UNIX_EPOCH};

use ripdpi_config::{OffsetBase, QuicFakeProfile};
use ripdpi_desync::{AdaptivePlannerHints, AdaptiveTlsRandRecProfile, AdaptiveUdpBurstProfile};
use ripdpi_failure_classifier::FailureClass;

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

fn now_millis() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}

/// Assign a stable discriminant for hashing. The exact values are arbitrary
/// but must be consistent for the lifetime of the process.
fn offset_base_disc(o: &OffsetBase) -> u8 {
    match o {
        OffsetBase::Abs => 0,
        OffsetBase::PayloadEnd => 1,
        OffsetBase::PayloadMid => 2,
        OffsetBase::PayloadRand => 3,
        OffsetBase::Host => 4,
        OffsetBase::EndHost => 5,
        OffsetBase::HostMid => 6,
        OffsetBase::HostRand => 7,
        OffsetBase::Sld => 8,
        OffsetBase::MidSld => 9,
        OffsetBase::EndSld => 10,
        OffsetBase::Method => 11,
        OffsetBase::ExtLen => 12,
        OffsetBase::SniExt => 13,
        OffsetBase::AutoBalanced => 14,
        OffsetBase::AutoHost => 15,
        OffsetBase::AutoMidSld => 16,
        OffsetBase::AutoEndHost => 17,
        OffsetBase::AutoMethod => 18,
        OffsetBase::AutoSniExt => 19,
        OffsetBase::AutoExtLen => 20,
    }
}

fn quic_fake_disc(q: &QuicFakeProfile) -> u8 {
    match q {
        QuicFakeProfile::Disabled => 0,
        QuicFakeProfile::CompatDefault => 1,
        QuicFakeProfile::RealisticInitial => 2,
    }
}

fn tls_randrec_disc(t: &AdaptiveTlsRandRecProfile) -> u8 {
    match t {
        AdaptiveTlsRandRecProfile::Balanced => 0,
        AdaptiveTlsRandRecProfile::Tight => 1,
        AdaptiveTlsRandRecProfile::Wide => 2,
    }
}

fn udp_burst_disc(u: &AdaptiveUdpBurstProfile) -> u8 {
    match u {
        AdaptiveUdpBurstProfile::Balanced => 0,
        AdaptiveUdpBurstProfile::Conservative => 1,
        AdaptiveUdpBurstProfile::Aggressive => 2,
    }
}

fn hash_option_disc<H: Hasher>(h: &mut H, tag: u8, disc: Option<u8>) {
    h.write_u8(tag);
    match disc {
        None => h.write_u8(0xFF),
        Some(d) => {
            h.write_u8(0);
            h.write_u8(d);
        }
    }
}

// ---------------------------------------------------------------------------
// StrategyCombo
// ---------------------------------------------------------------------------

/// Snapshot of all adaptive dimensions that together form a single evasion
/// strategy. `None` means "defer to the default planner for that dimension".
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StrategyCombo {
    pub split_offset_base: Option<OffsetBase>,
    pub tls_record_offset_base: Option<OffsetBase>,
    pub tlsrandrec_profile: Option<AdaptiveTlsRandRecProfile>,
    pub udp_burst_profile: Option<AdaptiveUdpBurstProfile>,
    pub quic_fake_profile: Option<QuicFakeProfile>,
    pub fake_ttl: Option<u8>,
}

impl Hash for StrategyCombo {
    fn hash<H: Hasher>(&self, state: &mut H) {
        hash_option_disc(state, 0, self.split_offset_base.as_ref().map(offset_base_disc));
        hash_option_disc(state, 1, self.tls_record_offset_base.as_ref().map(offset_base_disc));
        hash_option_disc(state, 2, self.tlsrandrec_profile.as_ref().map(tls_randrec_disc));
        hash_option_disc(state, 3, self.udp_burst_profile.as_ref().map(udp_burst_disc));
        hash_option_disc(state, 4, self.quic_fake_profile.as_ref().map(quic_fake_disc));
        hash_option_disc(state, 5, self.fake_ttl);
    }
}

impl StrategyCombo {
    fn default_combo() -> Self {
        Self {
            split_offset_base: None,
            tls_record_offset_base: None,
            tlsrandrec_profile: None,
            udp_burst_profile: None,
            quic_fake_profile: None,
            fake_ttl: None,
        }
    }

    fn to_hints(&self) -> AdaptivePlannerHints {
        AdaptivePlannerHints {
            split_offset_base: self.split_offset_base,
            tls_record_offset_base: self.tls_record_offset_base,
            tlsrandrec_profile: self.tlsrandrec_profile,
            udp_burst_profile: self.udp_burst_profile,
            quic_fake_profile: self.quic_fake_profile,
        }
    }
}

// ---------------------------------------------------------------------------
// ComboStats
// ---------------------------------------------------------------------------

/// Per-combo performance statistics.
#[derive(Debug, Clone)]
pub struct ComboStats {
    pub attempts: u32,
    pub successes: u32,
    pub total_latency_ms: u64,
    pub last_attempt_ms: u64,
    pub last_failure_class: Option<FailureClass>,
}

impl ComboStats {
    fn new() -> Self {
        Self { attempts: 0, successes: 0, total_latency_ms: 0, last_attempt_ms: 0, last_failure_class: None }
    }

    /// Fitness score: higher is better.
    /// `success_rate * 1000.0 - avg_latency.min(5000.0) * 0.001`
    pub fn fitness(&self) -> f64 {
        if self.attempts == 0 {
            return 0.0;
        }
        let success_rate = self.successes as f64 / self.attempts as f64;
        let avg_latency = if self.successes > 0 {
            self.total_latency_ms as f64 / self.successes as f64
        } else {
            5000.0
        };
        success_rate * 1000.0 - avg_latency.min(5000.0) * 0.001
    }
}

// ---------------------------------------------------------------------------
// Combo pool (fixed set of common combinations)
// ---------------------------------------------------------------------------

/// Pre-defined pool of combos to explore. Each entry is a `(split_offset, fake_ttl)` pair;
/// all other dimensions are left at None (planner defaults).
const COMBO_POOL: &[(Option<OffsetBase>, Option<u8>)] = &[
    // Default (all None)
    (None, None),
    // AutoHost offset variants
    (Some(OffsetBase::AutoHost), None),
    (Some(OffsetBase::AutoHost), Some(6)),
    (Some(OffsetBase::AutoHost), Some(8)),
    (Some(OffsetBase::AutoHost), Some(10)),
    // MidSld offset variants
    (Some(OffsetBase::MidSld), None),
    (Some(OffsetBase::MidSld), Some(6)),
    (Some(OffsetBase::MidSld), Some(8)),
    (Some(OffsetBase::MidSld), Some(10)),
    // EndHost offset variants
    (Some(OffsetBase::EndHost), None),
    (Some(OffsetBase::EndHost), Some(6)),
    (Some(OffsetBase::EndHost), Some(8)),
    (Some(OffsetBase::EndHost), Some(10)),
];

fn combo_from_pool(index: usize) -> StrategyCombo {
    let (offset, ttl) = COMBO_POOL[index % COMBO_POOL.len()];
    StrategyCombo {
        split_offset_base: offset,
        tls_record_offset_base: None,
        tlsrandrec_profile: None,
        udp_burst_profile: None,
        quic_fake_profile: None,
        fake_ttl: ttl,
    }
}

// ---------------------------------------------------------------------------
// StrategyEvolver
// ---------------------------------------------------------------------------

pub struct StrategyEvolver {
    combos: HashMap<StrategyCombo, ComboStats>,
    current_experiment: Option<StrategyCombo>,
    explore_epsilon: f64,
    pub max_combos: usize,
    enabled: bool,
    rng_state: u64,
}

impl StrategyEvolver {
    pub fn new(enabled: bool, epsilon: f64) -> Self {
        Self {
            combos: HashMap::new(),
            current_experiment: None,
            explore_epsilon: epsilon,
            max_combos: 64,
            enabled,
            rng_state: now_millis().wrapping_add(1),
        }
    }

    /// Returns adaptive hints if the evolver wants to override the default planner.
    /// Called before each outbound send.
    pub fn suggest_hints(&mut self) -> Option<AdaptivePlannerHints> {
        if !self.enabled {
            return None;
        }

        // If we already have an outstanding experiment, return its hints.
        if let Some(ref combo) = self.current_experiment {
            return Some(combo.to_hints());
        }

        let combo = self.select_next_combo();
        let hints = combo.to_hints();
        self.current_experiment = Some(combo);
        Some(hints)
    }

    /// Record successful connection with observed latency.
    pub fn record_success(&mut self, latency_ms: u64) {
        let combo = match self.current_experiment.take() {
            Some(c) => c,
            None => return,
        };
        self.evict_if_needed(&combo);
        let stats = self.combos.entry(combo).or_insert_with(ComboStats::new);
        stats.attempts += 1;
        stats.successes += 1;
        stats.total_latency_ms += latency_ms;
        stats.last_attempt_ms = now_millis();
        stats.last_failure_class = None;
    }

    /// Record failed connection with failure class.
    pub fn record_failure(&mut self, class: FailureClass) {
        let combo = match self.current_experiment.take() {
            Some(c) => c,
            None => return,
        };
        self.evict_if_needed(&combo);
        let stats = self.combos.entry(combo).or_insert_with(ComboStats::new);
        stats.attempts += 1;
        stats.last_attempt_ms = now_millis();
        stats.last_failure_class = Some(class);
    }

    /// Returns the best-performing combo found so far.
    pub fn best_combo(&self) -> Option<(&StrategyCombo, &ComboStats)> {
        self.combos.iter().max_by(|a, b| a.1.fitness().partial_cmp(&b.1.fitness()).unwrap_or(std::cmp::Ordering::Equal))
    }

    /// Number of unique combos tested.
    pub fn combos_tested(&self) -> usize {
        self.combos.len()
    }

    /// Best fitness score.
    pub fn best_fitness(&self) -> f64 {
        self.best_combo().map(|(_, s)| s.fitness()).unwrap_or(0.0)
    }

    // -- internal -----------------------------------------------------------

    fn lcg_next(&mut self) -> u32 {
        self.rng_state = self.rng_state.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1_442_695_040_888_963_407);
        (self.rng_state >> 33) as u32
    }

    /// Returns a float in [0.0, 1.0).
    fn lcg_f64(&mut self) -> f64 {
        self.lcg_next() as f64 / (u32::MAX as f64 + 1.0)
    }

    fn generate_random_combo(&mut self) -> StrategyCombo {
        let idx = self.lcg_next() as usize;
        combo_from_pool(idx)
    }

    fn select_next_combo(&mut self) -> StrategyCombo {
        // If no combos recorded yet, return the default combo.
        if self.combos.is_empty() {
            return StrategyCombo::default_combo();
        }

        // Epsilon-greedy: explore with probability epsilon.
        if self.lcg_f64() < self.explore_epsilon {
            return self.generate_random_combo();
        }

        // Exploit: pick the combo with highest UCB1 score.
        let total_attempts: u32 = self.combos.values().map(|s| s.attempts).sum();
        let ln_total = (total_attempts as f64).ln();

        self.combos
            .iter()
            .map(|(combo, stats)| {
                let ucb = if stats.attempts == 0 {
                    f64::MAX
                } else {
                    stats.fitness() + 1.41 * (ln_total / stats.attempts as f64).sqrt()
                };
                (combo.clone(), ucb)
            })
            .max_by(|a, b| a.1.partial_cmp(&b.1).unwrap_or(std::cmp::Ordering::Equal))
            .map(|(c, _)| c)
            .unwrap_or_else(StrategyCombo::default_combo)
    }

    fn evict_if_needed(&mut self, keep: &StrategyCombo) {
        if self.combos.len() < self.max_combos {
            return;
        }
        // Find the combo with the lowest fitness, excluding `keep`.
        let worst = self
            .combos
            .iter()
            .filter(|(k, _)| *k != keep)
            .min_by(|a, b| a.1.fitness().partial_cmp(&b.1.fitness()).unwrap_or(std::cmp::Ordering::Equal))
            .map(|(k, _)| k.clone());
        if let Some(w) = worst {
            self.combos.remove(&w);
        }
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn evolver_disabled_returns_none() {
        let mut e = StrategyEvolver::new(false, 0.1);
        assert!(e.suggest_hints().is_none());
    }

    #[test]
    fn evolver_selects_best_combo_after_training() {
        let mut e = StrategyEvolver::new(true, 0.0); // pure exploitation

        // Combo A: AutoHost offset, no TTL
        let combo_a = StrategyCombo {
            split_offset_base: Some(OffsetBase::AutoHost),
            ..StrategyCombo::default_combo()
        };
        // Combo B: MidSld offset, no TTL
        let combo_b = StrategyCombo {
            split_offset_base: Some(OffsetBase::MidSld),
            ..StrategyCombo::default_combo()
        };

        // Manually insert combo A: 2 successes, 1 fail (3 attempts)
        e.combos.insert(combo_a.clone(), ComboStats {
            attempts: 3,
            successes: 2,
            total_latency_ms: 200,
            last_attempt_ms: 0,
            last_failure_class: None,
        });

        // Manually insert combo B: 3 successes (3 attempts)
        e.combos.insert(combo_b.clone(), ComboStats {
            attempts: 3,
            successes: 3,
            total_latency_ms: 300,
            last_attempt_ms: 0,
            last_failure_class: None,
        });

        let (best, _) = e.best_combo().expect("should have a best combo");
        assert_eq!(*best, combo_b, "combo B with 100% success rate should be best");
    }

    #[test]
    fn evolver_explores_with_epsilon_one() {
        let mut e = StrategyEvolver::new(true, 1.0); // pure exploration

        // Seed some combos so select_next_combo hits the exploration path.
        e.combos.insert(StrategyCombo::default_combo(), ComboStats::new());

        // Collect several suggestions -- with epsilon=1.0 they are randomly generated
        // so we expect at least 2 distinct combos over many draws.
        let mut seen = std::collections::HashSet::new();
        for _ in 0..50 {
            e.suggest_hints();
            if let Some(ref combo) = e.current_experiment {
                seen.insert(combo.clone());
            }
            // Clear experiment to allow next suggestion.
            e.current_experiment = None;
        }
        assert!(seen.len() >= 2, "pure exploration should produce multiple distinct combos, got {}", seen.len());
    }

    #[test]
    fn evolver_records_failure_class() {
        let mut e = StrategyEvolver::new(true, 1.0);
        // Seed a combo so suggest_hints can proceed.
        e.combos.insert(StrategyCombo::default_combo(), ComboStats::new());
        e.suggest_hints();

        let experiment = e.current_experiment.clone().expect("should have an experiment");
        e.record_failure(FailureClass::TcpReset);

        let stats = e.combos.get(&experiment).expect("stats should exist");
        assert_eq!(stats.last_failure_class, Some(FailureClass::TcpReset));
        assert_eq!(stats.attempts, 1);
        assert_eq!(stats.successes, 0);
    }

    #[test]
    fn evolver_respects_max_combos_limit() {
        let mut e = StrategyEvolver::new(true, 1.0);
        e.max_combos = 4;

        // Insert 5 distinct combos via direct manipulation.
        for i in 0..5 {
            let combo = combo_from_pool(i);
            e.current_experiment = Some(combo);
            e.record_success(100);
        }

        assert!(e.combos.len() <= 4, "should respect max_combos limit, got {}", e.combos.len());
    }

    #[test]
    fn evolver_clears_experiment_after_feedback() {
        let mut e = StrategyEvolver::new(true, 1.0);
        // Seed a combo.
        e.combos.insert(StrategyCombo::default_combo(), ComboStats::new());
        e.suggest_hints(); // sets current_experiment
        assert!(e.current_experiment.is_some());

        e.record_success(100);
        assert!(e.current_experiment.is_none(), "experiment should be cleared after feedback");

        // Next suggest_hints should pick a new combo.
        e.suggest_hints();
        assert!(e.current_experiment.is_some());
    }

    #[test]
    fn fitness_prefers_higher_success_rate() {
        let a = ComboStats {
            attempts: 10,
            successes: 8,
            total_latency_ms: 800,
            last_attempt_ms: 0,
            last_failure_class: None,
        };
        let b = ComboStats {
            attempts: 10,
            successes: 3,
            total_latency_ms: 300,
            last_attempt_ms: 0,
            last_failure_class: None,
        };
        assert!(a.fitness() > b.fitness(), "a.fitness={} should be > b.fitness={}", a.fitness(), b.fitness());
    }

    #[test]
    fn fitness_penalizes_high_latency() {
        let fast = ComboStats {
            attempts: 10,
            successes: 10,
            total_latency_ms: 500,
            last_attempt_ms: 0,
            last_failure_class: None,
        };
        let slow = ComboStats {
            attempts: 10,
            successes: 10,
            total_latency_ms: 50_000,
            last_attempt_ms: 0,
            last_failure_class: None,
        };
        assert!(fast.fitness() > slow.fitness());
    }

    #[test]
    fn zero_attempts_gives_zero_fitness() {
        let stats = ComboStats::new();
        assert!((stats.fitness() - 0.0).abs() < f64::EPSILON);
    }

    #[test]
    fn combo_hash_is_consistent() {
        use std::collections::hash_map::DefaultHasher;

        let combo = StrategyCombo {
            split_offset_base: Some(OffsetBase::AutoHost),
            fake_ttl: Some(8),
            ..StrategyCombo::default_combo()
        };

        let hash1 = {
            let mut h = DefaultHasher::new();
            combo.hash(&mut h);
            h.finish()
        };
        let hash2 = {
            let mut h = DefaultHasher::new();
            combo.hash(&mut h);
            h.finish()
        };
        assert_eq!(hash1, hash2);
    }

    #[test]
    fn lcg_produces_deterministic_sequence() {
        let mut e1 = StrategyEvolver::new(true, 0.5);
        let mut e2 = StrategyEvolver::new(true, 0.5);
        // Force same seed for determinism.
        e1.rng_state = 42;
        e2.rng_state = 42;

        let seq1: Vec<u32> = (0..10).map(|_| e1.lcg_next()).collect();
        let seq2: Vec<u32> = (0..10).map(|_| e2.lcg_next()).collect();
        assert_eq!(seq1, seq2);
    }

    // ---- Edge case tests ----

    #[test]
    fn record_success_without_experiment_is_noop() {
        let mut e = StrategyEvolver::new(true, 0.0);
        // No current_experiment set, record_success should silently do nothing.
        e.record_success(100);
        assert_eq!(e.combos_tested(), 0);
    }

    #[test]
    fn record_failure_without_experiment_is_noop() {
        let mut e = StrategyEvolver::new(true, 0.0);
        e.record_failure(FailureClass::TcpReset);
        assert_eq!(e.combos_tested(), 0);
    }

    #[test]
    fn double_record_success_only_counts_first() {
        let mut e = StrategyEvolver::new(true, 1.0);
        e.combos.insert(StrategyCombo::default_combo(), ComboStats::new());
        e.suggest_hints();
        assert!(e.current_experiment.is_some());

        e.record_success(50);
        // Second record should be a no-op (current_experiment already taken)
        e.record_success(50);

        let total_attempts: u32 = e.combos.values().map(|s| s.attempts).sum();
        assert_eq!(total_attempts, 1, "only one attempt should be recorded");
    }

    #[test]
    fn suggest_hints_returns_same_combo_while_experiment_pending() {
        let mut e = StrategyEvolver::new(true, 0.0);
        e.combos.insert(StrategyCombo::default_combo(), ComboStats::new());

        let first = e.suggest_hints().expect("first suggestion");
        let second = e.suggest_hints().expect("second suggestion");
        // Should return same hints since experiment is still outstanding
        assert_eq!(first, second);
    }

    #[test]
    fn best_combo_empty_returns_none() {
        let e = StrategyEvolver::new(true, 0.0);
        assert!(e.best_combo().is_none());
    }

    #[test]
    fn best_fitness_empty_returns_zero() {
        let e = StrategyEvolver::new(true, 0.0);
        assert!((e.best_fitness() - 0.0).abs() < f64::EPSILON);
    }

    #[test]
    fn combos_tested_counts_unique_combos() {
        let mut e = StrategyEvolver::new(true, 0.0);
        let combo_a = StrategyCombo { split_offset_base: Some(OffsetBase::AutoHost), ..StrategyCombo::default_combo() };
        let combo_b = StrategyCombo { split_offset_base: Some(OffsetBase::MidSld), ..StrategyCombo::default_combo() };
        e.combos.insert(combo_a, ComboStats::new());
        e.combos.insert(combo_b, ComboStats::new());
        assert_eq!(e.combos_tested(), 2);
    }

    #[test]
    fn evict_preserves_keep_combo() {
        let mut e = StrategyEvolver::new(true, 0.0);
        e.max_combos = 2;

        let keep = StrategyCombo { fake_ttl: Some(6), ..StrategyCombo::default_combo() };
        let other = StrategyCombo { fake_ttl: Some(8), ..StrategyCombo::default_combo() };

        // Both have same (zero) fitness
        e.combos.insert(keep.clone(), ComboStats::new());
        e.combos.insert(other.clone(), ComboStats::new());

        // Evict should remove `other` (or any combo != keep), not `keep`
        e.evict_if_needed(&keep);
        assert!(e.combos.contains_key(&keep), "keep combo should survive eviction");
        assert_eq!(e.combos.len(), 1);
    }

    #[test]
    fn evict_removes_lowest_fitness() {
        let mut e = StrategyEvolver::new(true, 0.0);
        e.max_combos = 2;

        let good = StrategyCombo { fake_ttl: Some(6), ..StrategyCombo::default_combo() };
        let bad = StrategyCombo { fake_ttl: Some(8), ..StrategyCombo::default_combo() };
        let new_combo = StrategyCombo { fake_ttl: Some(10), ..StrategyCombo::default_combo() };

        e.combos.insert(good.clone(), ComboStats { attempts: 10, successes: 9, total_latency_ms: 100, last_attempt_ms: 0, last_failure_class: None });
        e.combos.insert(bad.clone(), ComboStats { attempts: 10, successes: 1, total_latency_ms: 5000, last_attempt_ms: 0, last_failure_class: None });

        e.evict_if_needed(&new_combo);
        assert!(e.combos.contains_key(&good), "good combo should survive");
        assert!(!e.combos.contains_key(&bad), "bad combo should be evicted");
    }

    #[test]
    fn fitness_with_zero_successes_penalizes_latency() {
        let stats = ComboStats {
            attempts: 5,
            successes: 0,
            total_latency_ms: 0,
            last_attempt_ms: 0,
            last_failure_class: None,
        };
        // 0% success rate, avg_latency defaults to 5000.0
        // fitness = 0.0 * 1000 - 5000.0 * 0.001 = -5.0
        assert!(stats.fitness() < 0.0, "0% success should give negative fitness, got {}", stats.fitness());
    }

    #[test]
    fn combo_pool_is_non_empty_and_wraps() {
        assert!(!COMBO_POOL.is_empty());
        let c1 = combo_from_pool(0);
        let c2 = combo_from_pool(COMBO_POOL.len());
        assert_eq!(c1, c2, "pool should wrap around");
    }

    #[test]
    fn combo_pool_entries_are_unique() {
        let combos: Vec<StrategyCombo> = (0..COMBO_POOL.len()).map(combo_from_pool).collect();
        let unique: std::collections::HashSet<_> = combos.iter().collect();
        assert_eq!(unique.len(), COMBO_POOL.len(), "all pool entries should be unique");
    }

    #[test]
    fn select_next_combo_returns_default_when_no_history() {
        let mut e = StrategyEvolver::new(true, 0.0);
        assert!(e.combos.is_empty());
        let combo = e.select_next_combo();
        assert_eq!(combo, StrategyCombo::default_combo());
    }

    #[test]
    fn ucb1_prefers_untried_combos() {
        let mut e = StrategyEvolver::new(true, 0.0); // pure exploitation
        e.rng_state = 42;

        let tried = StrategyCombo { fake_ttl: Some(6), ..StrategyCombo::default_combo() };
        let untried = StrategyCombo { fake_ttl: Some(8), ..StrategyCombo::default_combo() };

        e.combos.insert(tried.clone(), ComboStats {
            attempts: 10, successes: 5, total_latency_ms: 500,
            last_attempt_ms: 0, last_failure_class: None,
        });
        e.combos.insert(untried.clone(), ComboStats::new()); // 0 attempts => UCB1 = MAX

        let selected = e.select_next_combo();
        assert_eq!(selected, untried, "UCB1 should prefer untried combo (score = f64::MAX)");
    }

    #[test]
    fn to_hints_maps_all_fields() {
        let combo = StrategyCombo {
            split_offset_base: Some(OffsetBase::AutoHost),
            tls_record_offset_base: Some(OffsetBase::EndHost),
            tlsrandrec_profile: Some(AdaptiveTlsRandRecProfile::Tight),
            udp_burst_profile: Some(AdaptiveUdpBurstProfile::Conservative),
            quic_fake_profile: Some(QuicFakeProfile::RealisticInitial),
            fake_ttl: Some(8),
        };
        let hints = combo.to_hints();
        assert_eq!(hints.split_offset_base, Some(OffsetBase::AutoHost));
        assert_eq!(hints.tls_record_offset_base, Some(OffsetBase::EndHost));
        assert_eq!(hints.tlsrandrec_profile, Some(AdaptiveTlsRandRecProfile::Tight));
        assert_eq!(hints.udp_burst_profile, Some(AdaptiveUdpBurstProfile::Conservative));
        assert_eq!(hints.quic_fake_profile, Some(QuicFakeProfile::RealisticInitial));
    }
}
