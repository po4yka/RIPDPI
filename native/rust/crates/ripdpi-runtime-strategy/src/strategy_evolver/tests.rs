use std::hash::{Hash, Hasher};

use ripdpi_config::{EntropyMode, OffsetBase, QuicFakeProfile};
use ripdpi_desync::{
    AdaptiveOobBytePlacement, AdaptiveTimingJitterProfile, AdaptiveTlsRandRecProfile, AdaptiveUdpBurstProfile,
};
use ripdpi_failure_classifier::FailureClass;

use super::selection::{combo_matches_bucket, pilot_combo_for_bucket};
use super::*;

#[test]
fn evolver_disabled_returns_none() {
    let mut e = StrategyEvolver::new(false, 0.1);
    assert!(e.suggest_hints().is_none());
}

#[test]
fn evolver_epsilon_is_sanitized_into_unit_range() {
    // M3 regression: NaN compared false against every threshold and silently
    // disabled exploration; out-of-range values were passed through as-is.
    assert_eq!(StrategyEvolver::new(true, f64::NAN).epsilon(), 0.0);
    assert_eq!(StrategyEvolver::new(true, -0.5).epsilon(), 0.0);
    assert_eq!(StrategyEvolver::new(true, 2.0).epsilon(), 1.0);
    assert_eq!(StrategyEvolver::new(true, 0.3).epsilon(), 0.3);
}

#[test]
fn discriminant_sentinel_is_reserved_for_unknown_variants() {
    // M2 regression: the wildcard arms mapped future non_exhaustive variants
    // onto Disabled's discriminant, colliding in both the Hash impl and the
    // canonical shared-priors key. The sentinel must stay unused by every
    // known variant.
    use crate::strategy_evolver::types::{UNKNOWN_VARIANT_DISC, entropy_mode_disc, quic_fake_disc};
    for disc in [
        quic_fake_disc(QuicFakeProfile::Disabled),
        quic_fake_disc(QuicFakeProfile::CompatDefault),
        quic_fake_disc(QuicFakeProfile::RealisticInitial),
    ] {
        assert_ne!(disc, UNKNOWN_VARIANT_DISC);
    }
    for disc in [
        entropy_mode_disc(EntropyMode::Disabled),
        entropy_mode_disc(EntropyMode::Popcount),
        entropy_mode_disc(EntropyMode::Shannon),
        entropy_mode_disc(EntropyMode::Combined),
    ] {
        assert_ne!(disc, UNKNOWN_VARIANT_DISC);
    }
    assert_eq!(quic_fake_disc(QuicFakeProfile::Disabled), 0);
    assert_eq!(entropy_mode_disc(EntropyMode::Disabled), 0);
}

#[test]
fn evolver_selects_best_combo_after_training() {
    let mut e = StrategyEvolver::new(true, 0.0); // pure exploitation

    // Combo A: AutoHost offset, no TTL
    let combo_a = StrategyCombo { split_offset_base: Some(OffsetBase::AutoHost), ..StrategyCombo::default_combo() };
    // Combo B: MidSld offset, no TTL
    let combo_b = StrategyCombo { split_offset_base: Some(OffsetBase::MidSld), ..StrategyCombo::default_combo() };

    // Manually insert combo A: 2 successes, 1 fail (3 attempts)
    e.combos
        .insert(combo_a.clone(), ComboStats { attempts: 3, successes: 2, total_latency_ms: 200, ..ComboStats::new() });

    // Manually insert combo B: 3 successes (3 attempts)
    e.combos
        .insert(combo_b.clone(), ComboStats { attempts: 3, successes: 3, total_latency_ms: 300, ..ComboStats::new() });

    let (best, _) = e.best_combo().expect("should have a best combo");
    assert_eq!(*best, combo_b, "combo B with 100% success rate should be best");
}

#[test]
fn evolver_explores_with_epsilon_one() {
    let mut e = StrategyEvolver::new(true, 1.0); // pure exploration
    let context = LearningContext {
        target_bucket: LearningTargetBucket::Tls,
        transport: LearningTransportKind::Tcp,
        ..LearningContext::default()
    };
    e.set_learning_context(context.clone());

    // Seed some combos so select_next_combo hits the exploration path.
    e.combos.insert(StrategyCombo::default_combo(), ComboStats::new());
    e.contexts.insert(
        context,
        ContextBanditState {
            piloted_buckets: [LearningTargetBucket::Tls].into_iter().collect(),
            ..ContextBanditState::default()
        },
    );

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
fn strategy_execution_failure_does_not_penalize_learning_state() {
    let mut e = StrategyEvolver::new(true, 1.0);
    e.combos.insert(StrategyCombo::default_combo(), ComboStats::new());
    e.set_learning_context(LearningContext {
        network_identity: Some("wifi-a".to_string()),
        target_bucket: LearningTargetBucket::Tls,
        transport: LearningTransportKind::Tcp,
        ..LearningContext::default()
    });
    e.suggest_hints();

    let experiment = e.current_experiment.clone().expect("should have an experiment");
    e.record_failure(FailureClass::StrategyExecutionFailure);

    assert!(!e.combos.contains_key(&experiment), "emitter failures must not create or update combo stats");
    assert!(
        !e.contexts.contains_key(e.current_learning_context()),
        "emitter failures must not create contextual state"
    );
    assert!(e.current_experiment.is_none(), "pending experiment should still be cleared");
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
fn combo_pool_includes_timing_jitter_and_oob_placement_axes() {
    let combos: Vec<_> = (0..COMBO_POOL.len()).map(combo_from_pool).collect();

    assert!(
        combos.iter().any(|combo| combo.timing_jitter_profile == Some(AdaptiveTimingJitterProfile::Balanced)),
        "bounded pool should explore timing jitter"
    );
    assert!(
        combos.iter().any(|combo| combo.oob_byte_placement == Some(AdaptiveOobBytePlacement::PostSni)),
        "bounded pool should explore OOB byte placement"
    );
}

#[test]
fn timing_and_oob_combos_match_tls_bucket() {
    let timing = StrategyCombo {
        timing_jitter_profile: Some(AdaptiveTimingJitterProfile::Aggressive),
        ..StrategyCombo::default_combo()
    };
    let oob = StrategyCombo {
        oob_byte_placement: Some(AdaptiveOobBytePlacement::MidPayload),
        ..StrategyCombo::default_combo()
    };

    assert_eq!(timing.family(), StrategyFamily::TimingJitter);
    assert_eq!(oob.family(), StrategyFamily::OobPlacement);
    assert!(combo_matches_bucket(&timing, LearningTargetBucket::Tls));
    assert!(combo_matches_bucket(&oob, LearningTargetBucket::Tls));
}

// Offline-learner hardening.

#[test]
fn with_learning_hardening_overrides_defaults() {
    let e = StrategyEvolver::new(true, 0.0).with_learning_hardening(50, true);
    assert_eq!(e.max_arm_attempts, 50);
    assert!(e.penalties_enabled);
}

#[test]
fn defaults_disable_attempt_budget_and_penalties() {
    // The new fields must default to OFF so existing call sites observe
    // unchanged behaviour.
    let e = StrategyEvolver::new(true, 0.0);
    assert_eq!(e.max_arm_attempts, u32::MAX);
    assert!(!e.penalties_enabled);
}

#[test]
fn attempts_budget_remaining_reflects_recorded_attempts() {
    let mut e = StrategyEvolver::new(true, 0.0).with_learning_hardening(5, false);
    let combo = combo_from_pool(1);
    let mut stats = ComboStats::new();
    stats.attempts = 3;
    e.combos.insert(combo.clone(), stats);
    assert_eq!(e.attempts_budget_remaining(&combo), 2);

    // Disabled cap (u32::MAX) returns a very large number rather than
    // wrapping or panicking.
    let mut g = StrategyEvolver::new(true, 0.0);
    g.combos.insert(combo.clone(), ComboStats { attempts: 7, ..ComboStats::new() });
    assert_eq!(g.attempts_budget_remaining(&combo), u32::MAX - 7);
}

#[test]
fn random_exploration_skips_frozen_combos() {
    // Build an evolver with a tight attempt budget, freeze every pool
    // entry that matches the Generic bucket, and confirm the random
    // exploration path falls back to the pilot combo (the only escape
    // hatch) instead of returning a frozen combo.
    let mut e = StrategyEvolver::new(true, 0.0).with_learning_hardening(2, false);
    for idx in 0..COMBO_POOL.len() {
        let combo = combo_from_pool(idx);
        if combo_matches_bucket(&combo, LearningTargetBucket::Generic) {
            e.combos.insert(combo, ComboStats { attempts: 5, ..ComboStats::new() });
        }
    }
    let now_ms = e.monotonic_now_ms();
    let picked = e.pick_non_cooled_random_for_bucket(LearningTargetBucket::Generic, now_ms);
    // pilot_combo_for_bucket returns the default (all-None) combo.
    assert_eq!(picked, StrategyCombo::default_combo());
}

#[test]
fn random_exploration_returns_unfrozen_combo_when_some_remain() {
    // With only one frozen combo and the rest fresh, the random path
    // must pick from the unfrozen survivors. Repeat enough draws to make
    // accidental collisions astronomically unlikely.
    let mut e = StrategyEvolver::new(true, 0.0).with_learning_hardening(2, false);
    let frozen = combo_from_pool(1); // AutoHost split, matches Generic.
    e.combos.insert(frozen.clone(), ComboStats { attempts: 5, ..ComboStats::new() });
    let now_ms = e.monotonic_now_ms();
    for _ in 0..100 {
        let picked = e.pick_non_cooled_random_for_bucket(LearningTargetBucket::Generic, now_ms);
        assert_ne!(picked, frozen, "frozen combo must never be returned by random exploration");
    }
}

#[test]
fn rarity_penalty_drops_to_zero_at_floor() {
    // Use the helper to assert the curve directly. The internal helper
    // is `pub(super)` so we re-import via the module path.
    use crate::strategy_evolver::types::{RARITY_FLOOR, RARITY_PENALTY, rarity_penalty};
    assert_eq!(rarity_penalty(0), RARITY_PENALTY * f64::from(RARITY_FLOOR));
    assert_eq!(rarity_penalty(RARITY_FLOOR - 1), RARITY_PENALTY);
    assert_eq!(rarity_penalty(RARITY_FLOOR), 0.0);
    assert_eq!(rarity_penalty(RARITY_FLOOR + 100), 0.0);
}

#[test]
fn retry_cost_is_zero_below_saturation_and_log_damped_above() {
    use crate::strategy_evolver::types::{RETRY_COST_FACTOR, RETRY_SATURATION, retry_cost};
    assert_eq!(retry_cost(0), 0.0);
    assert_eq!(retry_cost(RETRY_SATURATION), 0.0);
    let one_over = retry_cost(RETRY_SATURATION + 1);
    let ten_over = retry_cost(RETRY_SATURATION + 10);
    assert!(one_over > 0.0);
    assert!(ten_over > one_over, "retry_cost must monotonically grow above the saturation threshold");
    // Bounded by the log term, so growth slows.
    let hundred_over = retry_cost(RETRY_SATURATION + 100);
    assert!((hundred_over / one_over) < 100.0, "retry_cost growth must be log-damped, not linear");
    // Use the constant to confirm the multiplier wires through.
    assert!(one_over > 0.0 && one_over <= RETRY_COST_FACTOR * 5.0);
}

#[test]
fn fitness_with_penalties_demotes_rare_arm_below_proven_arm() {
    // Two arms with the *same* attempt-windowed success rate but one is
    // rarely tried. With penalties enabled the rare arm must score
    // strictly lower so eviction prefers it.
    use crate::strategy_evolver::types::combo_fitness_at_with_penalties;
    let combo = combo_from_pool(1);

    let rare = ComboStats { attempts: 1, successes: 1, total_latency_ms: 100, ..ComboStats::new() };
    let proven = ComboStats { attempts: 30, successes: 30, total_latency_ms: 3_000, ..ComboStats::new() };
    let now_ms = 0u64;
    let half_life = 0u64;

    // With penalties OFF the standard fitness already prefers the
    // proven arm but only slightly; with penalties ON the gap widens
    // because the rarity penalty levies a fixed cost on the rare arm.
    let proven_off = combo_fitness_at_with_penalties(&combo, &proven, now_ms, half_life, false);
    let rare_off = combo_fitness_at_with_penalties(&combo, &rare, now_ms, half_life, false);
    let proven_on = combo_fitness_at_with_penalties(&combo, &proven, now_ms, half_life, true);
    let rare_on = combo_fitness_at_with_penalties(&combo, &rare, now_ms, half_life, true);
    let gap_off = proven_off - rare_off;
    let gap_on = proven_on - rare_on;
    assert!(gap_on > gap_off, "rarity penalty must widen the proven-vs-rare gap (off={gap_off}, on={gap_on})");
}

#[test]
fn fitness_with_penalties_demotes_oversaturated_arm() {
    // Two arms with identical per-attempt stats; one has tried 5×
    // saturation many times. Penalties must drop its score.
    use crate::strategy_evolver::types::{RETRY_SATURATION, combo_fitness_at_with_penalties};
    let combo = combo_from_pool(1);
    let n_fresh = 5_u32;
    let n_saturated = RETRY_SATURATION * 5;

    let fresh = ComboStats {
        attempts: n_fresh,
        successes: n_fresh,
        total_latency_ms: 100 * u64::from(n_fresh),
        ..ComboStats::new()
    };
    let saturated = ComboStats {
        attempts: n_saturated,
        successes: n_saturated,
        total_latency_ms: 100 * u64::from(n_saturated),
        ..ComboStats::new()
    };

    let fresh_on = combo_fitness_at_with_penalties(&combo, &fresh, 0, 0, true);
    let saturated_on = combo_fitness_at_with_penalties(&combo, &saturated, 0, 0, true);
    assert!(
        fresh_on > saturated_on,
        "saturated arm must score below fresh arm with penalties on (fresh={fresh_on}, saturated={saturated_on})"
    );
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
    let a = ComboStats { attempts: 10, successes: 8, total_latency_ms: 800, ..ComboStats::new() };
    let b = ComboStats { attempts: 10, successes: 3, total_latency_ms: 300, ..ComboStats::new() };
    assert!(a.fitness() > b.fitness(), "a.fitness={} should be > b.fitness={}", a.fitness(), b.fitness());
}

#[test]
fn fitness_penalizes_high_latency() {
    let fast = ComboStats { attempts: 10, successes: 10, total_latency_ms: 500, ..ComboStats::new() };
    let slow = ComboStats { attempts: 10, successes: 10, total_latency_ms: 50_000, ..ComboStats::new() };
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

#[test]
fn lcg_f64_spans_full_unit_interval() {
    // Regression guard: the previous `>> 33` shift kept only 31
    // bits of state and divided by 2^32, which capped `lcg_f64` at 0.5.
    // After the fix the values should span the full `[0, 1)` range.
    let mut e = StrategyEvolver::new(true, 0.0);
    e.rng_state = 0xDEAD_BEEF_1234_5678_u64;

    let n = 5_000;
    let mut max_seen = f64::NEG_INFINITY;
    let mut min_seen = f64::INFINITY;
    let mut above_half = 0;
    let mut sum = 0.0_f64;
    for _ in 0..n {
        let v = e.lcg_f64();
        assert!((0.0..1.0).contains(&v), "lcg_f64 out of [0,1) range: {v}");
        if v > max_seen {
            max_seen = v;
        }
        if v < min_seen {
            min_seen = v;
        }
        if v >= 0.5 {
            above_half += 1;
        }
        sum += v;
    }
    // With a uniform [0,1) distribution we expect ~50% of samples >= 0.5
    // and a mean near 0.5. The earlier `>> 33` bug pinned all samples
    // below 0.5, so any non-trivial fraction above 0.5 is sufficient
    // to lock in the fix.
    assert!(max_seen >= 0.9, "lcg_f64 never reached the upper half: max={max_seen}");
    assert!(above_half > n / 4, "fewer than 25% of samples above 0.5: {above_half}/{n}");
    let mean = sum / n as f64;
    assert!((mean - 0.5).abs() < 0.05, "lcg_f64 mean {mean:.4} too far from expected 0.5 — distribution is skewed");
    // Sanity: minimum should comfortably reach the lower half too.
    assert!(min_seen < 0.1, "lcg_f64 never reached small values: min={min_seen}");
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
fn peek_hints_returns_none_when_disabled() {
    let e = StrategyEvolver::new(false, 0.0);
    assert!(e.peek_hints().is_none());
}

#[test]
fn peek_hints_returns_pending_experiment_without_mutation() {
    let mut e = StrategyEvolver::new(true, 0.0);
    e.combos.insert(StrategyCombo::default_combo(), ComboStats::new());
    let suggested = e.suggest_hints().expect("suggested hints");

    assert_eq!(e.peek_hints(), Some(suggested));
    assert!(e.current_experiment.is_some(), "peek should not clear the pending experiment");
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
    e.evict_if_needed(&keep, 0);
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

    e.combos
        .insert(good.clone(), ComboStats { attempts: 10, successes: 9, total_latency_ms: 100, ..ComboStats::new() });
    e.combos
        .insert(bad.clone(), ComboStats { attempts: 10, successes: 1, total_latency_ms: 5000, ..ComboStats::new() });

    e.evict_if_needed(&new_combo, 0);
    assert!(e.combos.contains_key(&good), "good combo should survive");
    assert!(!e.combos.contains_key(&bad), "bad combo should be evicted");
}

#[test]
fn fitness_with_zero_successes_penalizes_latency() {
    let stats = ComboStats { attempts: 5, successes: 0, total_latency_ms: 0, ..ComboStats::new() };
    // 0% success rate, avg_latency defaults to FITNESS_LATENCY_CAP_MS
    // fitness = 0.0 * 1000 - 5000.0 * 0.02 = -100.0
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
    let context = LearningContext { target_bucket: LearningTargetBucket::Generic, ..LearningContext::default() };
    e.set_learning_context(context.clone());

    let tried = StrategyCombo { fake_ttl: Some(6), ..StrategyCombo::default_combo() };
    let untried = StrategyCombo { fake_ttl: Some(8), ..StrategyCombo::default_combo() };
    let tried_stats = ComboStats { attempts: 10, successes: 5, total_latency_ms: 500, ..ComboStats::new() };
    let untried_stats = ComboStats::new();

    e.combos.insert(tried.clone(), tried_stats.clone());
    e.combos.insert(untried.clone(), untried_stats.clone()); // 0 attempts => UCB1 = MAX
    e.contexts.insert(
        context,
        ContextBanditState {
            combos: [(tried.clone(), tried_stats), (untried.clone(), untried_stats)].into_iter().collect(),
            families: [(StrategyFamily::FakeTtl, FamilyStats { attempts: 10, total_reward: 100.0 })]
                .into_iter()
                .collect(),
            piloted_buckets: [LearningTargetBucket::Generic].into_iter().collect(),
            ..ContextBanditState::default()
        },
    );

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
        entropy_mode: Some(EntropyMode::Shannon),
        timing_jitter_profile: Some(AdaptiveTimingJitterProfile::Balanced),
        oob_byte_placement: Some(AdaptiveOobBytePlacement::PostSni),
    };
    let hints = combo.to_hints();
    assert_eq!(hints.split_offset_base, Some(OffsetBase::AutoHost));
    assert_eq!(hints.tls_record_offset_base, Some(OffsetBase::EndHost));
    assert_eq!(hints.tlsrandrec_profile, Some(AdaptiveTlsRandRecProfile::Tight));
    assert_eq!(hints.udp_burst_profile, Some(AdaptiveUdpBurstProfile::Conservative));
    assert_eq!(hints.quic_fake_profile, Some(QuicFakeProfile::RealisticInitial));
    assert_eq!(hints.entropy_mode, Some(EntropyMode::Shannon));
    assert_eq!(hints.timing_jitter_profile, Some(AdaptiveTimingJitterProfile::Balanced));
    assert_eq!(hints.oob_byte_placement, Some(AdaptiveOobBytePlacement::PostSni));
}

#[test]
fn to_hints_maps_ech_ext_offsets() {
    let combo = StrategyCombo {
        split_offset_base: Some(OffsetBase::EchExt),
        tls_record_offset_base: Some(OffsetBase::EchExt),
        ..StrategyCombo::default_combo()
    };

    let hints = combo.to_hints();

    assert_eq!(hints.split_offset_base, Some(OffsetBase::EchExt));
    assert_eq!(hints.tls_record_offset_base, Some(OffsetBase::EchExt));
}

#[test]
fn ech_ext_offset_base_hash_is_distinct_from_sni_ext() {
    use std::collections::hash_map::DefaultHasher;

    let hash_of = |combo: &StrategyCombo| -> u64 {
        let mut hasher = DefaultHasher::new();
        combo.hash(&mut hasher);
        hasher.finish()
    };
    let ech_combo = StrategyCombo {
        split_offset_base: Some(OffsetBase::EchExt),
        tls_record_offset_base: Some(OffsetBase::EchExt),
        ..StrategyCombo::default_combo()
    };
    let sni_combo = StrategyCombo {
        split_offset_base: Some(OffsetBase::SniExt),
        tls_record_offset_base: Some(OffsetBase::SniExt),
        ..StrategyCombo::default_combo()
    };

    assert_ne!(hash_of(&ech_combo), hash_of(&sni_combo));
}

#[test]
fn entropy_mode_produces_distinct_hashes() {
    use std::collections::hash_map::DefaultHasher;

    let combo_none = StrategyCombo::default_combo();
    let combo_shannon = StrategyCombo { entropy_mode: Some(EntropyMode::Shannon), ..StrategyCombo::default_combo() };
    let combo_popcount = StrategyCombo { entropy_mode: Some(EntropyMode::Popcount), ..StrategyCombo::default_combo() };
    let combo_combined = StrategyCombo { entropy_mode: Some(EntropyMode::Combined), ..StrategyCombo::default_combo() };

    let hash_of = |combo: &StrategyCombo| -> u64 {
        let mut h = DefaultHasher::new();
        combo.hash(&mut h);
        h.finish()
    };

    let h_none = hash_of(&combo_none);
    let h_shannon = hash_of(&combo_shannon);
    let h_popcount = hash_of(&combo_popcount);
    let h_combined = hash_of(&combo_combined);

    // All four should have distinct hashes
    let hashes = [h_none, h_shannon, h_popcount, h_combined];
    for i in 0..hashes.len() {
        for j in (i + 1)..hashes.len() {
            assert_ne!(hashes[i], hashes[j], "hash collision between variant {i} and {j}");
        }
    }
}

#[test]
fn combo_pool_contains_shannon_variants() {
    let combos: Vec<StrategyCombo> = (0..COMBO_POOL.len()).map(combo_from_pool).collect();
    let shannon_count = combos.iter().filter(|c| matches!(c.entropy_mode, Some(EntropyMode::Shannon))).count();
    let combined_count = combos.iter().filter(|c| matches!(c.entropy_mode, Some(EntropyMode::Combined))).count();
    assert!(shannon_count >= 1, "pool should have at least one Shannon combo");
    assert!(combined_count >= 1, "pool should have at least one Combined combo");
}

#[test]
fn combo_from_pool_includes_entropy_mode() {
    // Find a Shannon variant in the pool
    let combos: Vec<StrategyCombo> = (0..COMBO_POOL.len()).map(combo_from_pool).collect();
    let shannon_idx = combos
        .iter()
        .position(|c| matches!(c.entropy_mode, Some(EntropyMode::Shannon)))
        .expect("pool should contain a Shannon variant");
    let combo = combo_from_pool(shannon_idx);
    assert_eq!(combo.entropy_mode, Some(EntropyMode::Shannon));
}

#[test]
fn combo_pool_covers_all_dimensions() {
    let combos: Vec<StrategyCombo> = (0..COMBO_POOL.len()).map(combo_from_pool).collect();
    assert!(
        combos.iter().any(|c| c.tls_record_offset_base.is_some()),
        "pool should have at least one entry with tls_record_offset_base"
    );
    assert!(
        combos.iter().any(|c| c.tlsrandrec_profile.is_some()),
        "pool should have at least one entry with tlsrandrec_profile"
    );
    assert!(
        combos.iter().any(|c| c.udp_burst_profile.is_some()),
        "pool should have at least one entry with udp_burst_profile"
    );
    assert!(
        combos.iter().any(|c| c.quic_fake_profile.is_some()),
        "pool should have at least one entry with quic_fake_profile"
    );
}

#[test]
fn default_combo_has_no_entropy_mode() {
    let combo = StrategyCombo::default_combo();
    assert_eq!(combo.entropy_mode, None);
}

#[test]
fn to_hints_maps_none_entropy_mode() {
    let combo = StrategyCombo::default_combo();
    let hints = combo.to_hints();
    assert_eq!(hints.entropy_mode, None);
}

#[test]
fn evolver_can_track_entropy_combos() {
    let mut e = StrategyEvolver::new(true, 0.0); // pure exploitation

    let combo_no_entropy = StrategyCombo::default_combo();
    let combo_with_entropy =
        StrategyCombo { entropy_mode: Some(EntropyMode::Shannon), ..StrategyCombo::default_combo() };

    // combo without entropy: 50% success rate
    e.combos.insert(
        combo_no_entropy.clone(),
        ComboStats { attempts: 10, successes: 5, total_latency_ms: 500, ..ComboStats::new() },
    );
    // combo with Shannon entropy: 90% success rate
    e.combos.insert(
        combo_with_entropy.clone(),
        ComboStats { attempts: 10, successes: 9, total_latency_ms: 900, ..ComboStats::new() },
    );

    let (best, _) = e.best_combo().expect("should have a best combo");
    assert_eq!(best.entropy_mode, Some(EntropyMode::Shannon), "Shannon combo should be best");
}

// ---- Slice 2.6: capability-skipped runs must not update bandit arms ----

#[test]
fn capability_skipped_does_not_update_bandit_arms() {
    // Session A: 1 successful run only.
    let mut session_a = StrategyEvolver::new(true, 0.0);
    session_a.rng_state = 99;
    let combo = StrategyCombo::default_combo();
    session_a.combos.insert(combo.clone(), ComboStats::new());

    session_a.current_experiment = Some(combo.clone());
    session_a.record_success(50);

    let stats_a = session_a.combos.get(&combo).expect("stats_a must exist");

    // Session B: 3 capability-skipped runs, then the same 1 successful run.
    let mut session_b = StrategyEvolver::new(true, 0.0);
    session_b.rng_state = 99;
    session_b.combos.insert(combo.clone(), ComboStats::new());

    for _ in 0..3 {
        session_b.current_experiment = Some(combo.clone());
        session_b.record_failure(FailureClass::CapabilitySkipped);
    }

    session_b.current_experiment = Some(combo.clone());
    session_b.record_success(50);

    let stats_b = session_b.combos.get(&combo).expect("stats_b must exist");

    assert_eq!(
        stats_a.attempts, stats_b.attempts,
        "capability-skipped runs must not increment attempt count \
             (session_a={}, session_b={})",
        stats_a.attempts, stats_b.attempts,
    );
    assert_eq!(stats_a.successes, stats_b.successes, "capability-skipped runs must not alter success count",);
    assert!(
        (stats_a.fitness() - stats_b.fitness()).abs() < f64::EPSILON,
        "capability-skipped runs must not change fitness: \
             session_a={:.4}, session_b={:.4}",
        stats_a.fitness(),
        stats_b.fitness(),
    );
}

#[test]
fn learning_context_change_clears_pending_experiment() {
    let mut evolver = StrategyEvolver::new(true, 0.0);
    evolver.combos.insert(StrategyCombo::default_combo(), ComboStats::new());
    let _ = evolver.suggest_hints().expect("pending hints");
    assert!(evolver.current_experiment.is_some());

    evolver.set_learning_context(LearningContext {
        network_identity: Some("wifi:office".to_string()),
        target_bucket: LearningTargetBucket::Quic,
        transport: LearningTransportKind::UdpQuic,
        alpn_class: LearningAlpnClass::H3,
        ech_capable: false,
        resolver_health: ResolverHealthClass::Healthy,
        rooted: false,
        capability_context: CapabilityContext::Full,
        ..LearningContext::default()
    });

    assert!(evolver.current_experiment.is_none());
}

#[test]
fn contextual_bandit_keeps_niche_winner_per_bucket() {
    let mut evolver = StrategyEvolver::new(true, 0.0);
    let tls_combo = StrategyCombo { split_offset_base: Some(OffsetBase::AutoHost), ..StrategyCombo::default_combo() };
    let quic_combo =
        StrategyCombo { quic_fake_profile: Some(QuicFakeProfile::CompatDefault), ..StrategyCombo::default_combo() };

    evolver.set_learning_context(LearningContext {
        target_bucket: LearningTargetBucket::Tls,
        transport: LearningTransportKind::Tcp,
        alpn_class: LearningAlpnClass::H2Http11,
        ..LearningContext::default()
    });
    evolver.current_experiment = Some(tls_combo.clone());
    evolver.current_experiment_context = Some(evolver.current_learning_context.clone());
    evolver.current_experiment_family = Some(tls_combo.family());
    evolver.record_success(50);

    evolver.set_learning_context(LearningContext {
        target_bucket: LearningTargetBucket::Quic,
        transport: LearningTransportKind::UdpQuic,
        alpn_class: LearningAlpnClass::H3,
        ..LearningContext::default()
    });
    evolver.current_experiment = Some(quic_combo.clone());
    evolver.current_experiment_context = Some(evolver.current_learning_context.clone());
    evolver.current_experiment_family = Some(quic_combo.family());
    evolver.record_success(50);

    let tls_state = evolver
        .contexts
        .get(&LearningContext {
            target_bucket: LearningTargetBucket::Tls,
            transport: LearningTransportKind::Tcp,
            alpn_class: LearningAlpnClass::H2Http11,
            ..LearningContext::default()
        })
        .expect("tls context");
    assert_eq!(tls_state.niche_winners.get(&LearningTargetBucket::Tls), Some(&tls_combo));

    let quic_state = evolver
        .contexts
        .get(&LearningContext {
            target_bucket: LearningTargetBucket::Quic,
            transport: LearningTransportKind::UdpQuic,
            alpn_class: LearningAlpnClass::H3,
            ..LearningContext::default()
        })
        .expect("quic context");
    assert_eq!(quic_state.niche_winners.get(&LearningTargetBucket::Quic), Some(&quic_combo));
}

#[test]
fn ech_bucket_pilot_prefers_ech_offset_combo() {
    let mut evolver = StrategyEvolver::new(true, 0.0);
    evolver.combos.insert(StrategyCombo::default_combo(), ComboStats::new());
    evolver.set_learning_context(LearningContext {
        target_bucket: LearningTargetBucket::Ech,
        transport: LearningTransportKind::Tcp,
        alpn_class: LearningAlpnClass::H2Http11,
        ech_capable: true,
        ..LearningContext::default()
    });

    let combo = evolver.select_next_combo();
    assert_eq!(combo.split_offset_base, Some(OffsetBase::EchExt));
}

// ── Asymmetric ArmStats decay ──

#[test]
fn apply_decay_zero_elapsed_is_idempotent() {
    // elapsed = 0 must leave the struct bit-for-bit identical.
    let mut stats = ComboStats { attempts: 10, successes: 5, total_latency_ms: 500, ..ComboStats::new() };
    let before_attempts = stats.attempts;
    let before_successes = stats.successes;
    let before_latency = stats.total_latency_ms;
    stats.apply_decay(0);
    assert_eq!(stats.attempts, before_attempts, "attempts must be unchanged at elapsed=0");
    assert_eq!(stats.successes, before_successes, "successes must be unchanged at elapsed=0");
    assert_eq!(stats.total_latency_ms, before_latency, "latency must be unchanged at elapsed=0");
}

#[test]
fn apply_decay_50_50_history_at_loss_half_life_shows_asymmetry() {
    // With 50 wins and 50 losses, after elapsed = LOSS_HALF_LIFE_MS:
    //   - Losses halve  → ~25 losses
    //   - Wins decay by win multiplier (half-life 2×) → wins > 25 (> half of original)
    // So the win component is strictly greater than the loss component after decay.
    use crate::strategy_evolver::types::{LOSS_HALF_LIFE_MS, WIN_HALF_LIFE_MS};
    let _ = WIN_HALF_LIFE_MS; // confirm constant is exported

    let mut stats = ComboStats { attempts: 100, successes: 50, total_latency_ms: 5_000, ..ComboStats::new() };
    stats.apply_decay(LOSS_HALF_LIFE_MS);

    // Losses after one LOSS_HALF_LIFE: ~50 * 0.5 = 25
    let decayed_losses = stats.attempts - stats.successes;
    // Wins after one LOSS_HALF_LIFE (= half of WIN_HALF_LIFE): ~50 * 0.707 ≈ 35
    // Asymmetry: wins > losses
    assert!(
        stats.successes > decayed_losses,
        "after one loss half-life, win count ({}) must exceed loss count ({}) — asymmetry broken",
        stats.successes,
        decayed_losses
    );
    // Wins must be strictly greater than half the original (50/2 = 25).
    assert!(
        stats.successes > 25,
        "win component must be > half of original after one LOSS_HALF_LIFE (got {})",
        stats.successes
    );
}

#[test]
fn apply_decay_repeated_losses_decrease_score_without_zeroing() {
    // Simulate N successive losses by calling record_attempt + apply_decay in a loop.
    // The arm must retain a positive fitness score throughout (losses should not
    // zero out the win-side completely because wins decay much slower).
    use crate::strategy_evolver::types::LOSS_HALF_LIFE_MS;

    let mut stats = ComboStats { attempts: 20, successes: 20, total_latency_ms: 2_000, ..ComboStats::new() };

    for tick in 1..=10u64 {
        // Record a failure.
        stats.record_attempt(false, 5000, None, tick * 1_000, 0, 0);
        // Apply one LOSS_HALF_LIFE worth of decay each round.
        stats.apply_decay(LOSS_HALF_LIFE_MS);
    }

    // After 10 rounds of losses + decay, the score must still be positive.
    assert!(
        stats.successes > 0,
        "successes must remain positive after repeated losses + decay (got {})",
        stats.successes
    );
}

#[test]
fn apply_decay_no_op_on_zero_attempts() {
    use crate::strategy_evolver::types::LOSS_HALF_LIFE_MS;
    let mut stats = ComboStats::new(); // attempts = 0
    stats.apply_decay(LOSS_HALF_LIFE_MS);
    assert_eq!(stats.attempts, 0);
    assert_eq!(stats.successes, 0);
}

#[test]
fn apply_decay_shrinks_flip_and_detectability_counters_with_attempts() {
    // H1 regression: outcome_flips and detectability_events are numerators of
    // per-attempt penalty ratios in `fitness_at`. If they stay constant while
    // `attempts` decays, the stability/detectability penalties grow without
    // bound after a long idle period. Decay must shrink them together with
    // the attempt history so their ratios stay in [0, 1].
    use crate::strategy_evolver::types::LOSS_HALF_LIFE_MS;
    let mut stats = ComboStats::new();
    for i in 0..10u64 {
        let success = i % 2 == 0;
        stats.record_attempt(success, 100, Some(FailureClass::TlsAlert), i * 1_000, 0, 0);
    }
    assert_eq!(stats.attempts, 10);
    assert_eq!(stats.outcome_flips, 9); // first attempt never flips
    assert_eq!(stats.detectability_events, 5);

    stats.apply_decay(LOSS_HALF_LIFE_MS * 4);

    assert!(stats.attempts > 0, "decay must not zero out the history entirely");
    let stability_ratio = stats.outcome_flips as f64 / stats.attempts as f64;
    let detectability_ratio = stats.detectability_events as f64 / stats.attempts as f64;
    assert!(
        stability_ratio <= 1.0 + f64::EPSILON,
        "stability ratio {stability_ratio} exceeds 1.0 after decay (flips={}, attempts={})",
        stats.outcome_flips,
        stats.attempts
    );
    assert!(
        detectability_ratio <= 1.0 + f64::EPSILON,
        "detectability ratio {detectability_ratio} exceeds 1.0 after decay (events={}, attempts={})",
        stats.detectability_events,
        stats.attempts
    );
}

#[test]
fn fitness_after_long_idle_stays_within_documented_penalty_envelope() {
    // H1 regression: after a long idle decay the fitness of a formerly
    // flip-heavy combo must stay inside the documented weight envelope
    // (~ -280 worst case) instead of collapsing without bound.
    use crate::strategy_evolver::types::LOSS_HALF_LIFE_MS;
    let mut stats = ComboStats::new();
    for i in 0..40u64 {
        let success = i % 2 == 0;
        stats.record_attempt(success, 100, Some(FailureClass::ConnectionFreeze), i * 1_000, 0, 0);
    }
    let idle_ms = LOSS_HALF_LIFE_MS * 20;
    stats.apply_decay(idle_ms);
    let fitness = stats.fitness_at(stats.last_attempt_ms + idle_ms, 0);
    assert!(fitness > -300.0, "fitness {fitness} collapsed below the documented penalty envelope after idle decay");
}

// ── Time-driven evolver semantics: TTL, decay, cooldown ──

#[test]
fn experiment_ttl_drops_stale_pending_experiment_without_recording() {
    let mut evolver = StrategyEvolver::new(true, 0.0);
    evolver.experiment_ttl_ms = 30_000;
    evolver.set_test_clock_ms(0);
    let _hints = evolver.suggest_hints().expect("first roll");
    let pending_before = evolver.current_experiment.clone();
    assert!(pending_before.is_some());

    evolver.set_test_clock_ms(60_000);
    let _hints_after = evolver.suggest_hints().expect("post-TTL roll");
    // The stats map must remain empty: TTL expiry never records an attempt.
    assert!(evolver.combos.is_empty(), "TTL expiry must not record stats, got {} combo(s)", evolver.combos.len());
    // A new experiment was rolled.
    assert!(evolver.current_experiment.is_some());
    assert_eq!(evolver.current_experiment_started_ms, Some(60_000));
}

#[test]
fn experiment_ttl_zero_disables_drop() {
    let mut evolver = StrategyEvolver::new(true, 0.0);
    evolver.experiment_ttl_ms = 0;
    evolver.set_test_clock_ms(0);
    let _ = evolver.suggest_hints().expect("first roll");
    let pending = evolver.current_experiment.clone();

    evolver.set_test_clock_ms(10_000_000);
    let _ = evolver.suggest_hints().expect("still rolling");
    assert_eq!(evolver.current_experiment, pending, "TTL=0 must not drop the pending experiment");
}

#[test]
fn idle_decay_demotes_stale_winners() {
    // Two combos with the same record. The one observed long ago should
    // score lower under decay than the freshly observed one.
    let combo = StrategyCombo { split_offset_base: Some(OffsetBase::AutoHost), ..StrategyCombo::default_combo() };
    let stats_fresh = ComboStats {
        attempts: 4,
        successes: 4,
        total_latency_ms: 200,
        total_latency_square_ms: 10_000,
        last_attempt_ms: 1_000_000,
        ..ComboStats::new()
    };
    let stats_stale = ComboStats { last_attempt_ms: 0, ..stats_fresh.clone() };

    let now = 1_000_000;
    let half_life = 60_000; // 1 minute
    let fresh_score = combo_fitness_at(&combo, &stats_fresh, now, half_life);
    let stale_score = combo_fitness_at(&combo, &stats_stale, now, half_life);

    assert!(
        fresh_score > stale_score,
        "decayed stale fitness should be lower: fresh={fresh_score}, stale={stale_score}"
    );

    // half_life=0 disables decay -- the two scores should match.
    let no_decay_fresh = combo_fitness_at(&combo, &stats_fresh, now, 0);
    let no_decay_stale = combo_fitness_at(&combo, &stats_stale, now, 0);
    assert!(
        (no_decay_fresh - no_decay_stale).abs() < f64::EPSILON,
        "half_life=0 must disable decay: fresh={no_decay_fresh}, stale={no_decay_stale}"
    );
}

#[test]
fn cooldown_trips_after_consecutive_failures_and_clears_on_success() {
    let mut stats = ComboStats::new();
    // Two failures: not yet cooled.
    let t = stats.record_attempt(false, 5000, Some(FailureClass::TcpReset), 1000, 3, 5_000);
    assert!(matches!(t, CooldownTransition::Unchanged));
    let t = stats.record_attempt(false, 5000, Some(FailureClass::TcpReset), 2000, 3, 5_000);
    assert!(matches!(t, CooldownTransition::Unchanged));
    assert!(stats.cooldown_until_ms.is_none());

    // Third failure trips the cooldown.
    let t = stats.record_attempt(false, 5000, Some(FailureClass::TcpReset), 3000, 3, 5_000);
    assert!(matches!(t, CooldownTransition::Tripped { until_ms: 8_000 }));
    assert_eq!(stats.cooldown_until_ms, Some(8_000));
    assert_eq!(stats.consecutive_failure_count, 3);
    assert!(stats.is_cooled(7_999));
    assert!(!stats.is_cooled(8_000));

    // Success clears the cooldown and resets the counter.
    let t = stats.record_attempt(true, 100, None, 4000, 3, 5_000);
    assert!(matches!(t, CooldownTransition::Cleared));
    assert!(stats.cooldown_until_ms.is_none());
    assert_eq!(stats.consecutive_failure_count, 0);
}

#[test]
fn cooldown_zero_failures_disables_gate() {
    let mut stats = ComboStats::new();
    for tick in 0..20 {
        let t = stats.record_attempt(
            false,
            5000,
            Some(FailureClass::TcpReset),
            1000 * tick,
            0, // disabled
            5_000,
        );
        assert!(matches!(t, CooldownTransition::Unchanged));
    }
    assert!(stats.cooldown_until_ms.is_none());
}

#[test]
fn cooled_combo_is_skipped_by_best_context_combo_for_family() {
    let mut state = ContextBanditState::default();
    let cooled = StrategyCombo { split_offset_base: Some(OffsetBase::AutoHost), ..StrategyCombo::default_combo() };
    let warm = StrategyCombo { split_offset_base: Some(OffsetBase::MidSld), ..StrategyCombo::default_combo() };
    state.combos.insert(
        cooled.clone(),
        ComboStats {
            attempts: 5,
            successes: 5,
            total_latency_ms: 250,
            cooldown_until_ms: Some(10_000),
            last_attempt_ms: 9_000,
            ..ComboStats::new()
        },
    );
    state.combos.insert(
        warm.clone(),
        ComboStats { attempts: 5, successes: 4, total_latency_ms: 250, last_attempt_ms: 9_000, ..ComboStats::new() },
    );

    let pick = StrategyEvolver::best_context_combo_for_family(&state, StrategyFamily::SplitOffset, 9_500, 3_600_000);
    assert_eq!(pick, Some(warm), "cooled combo must be filtered out at now < cooldown_until");
}

#[test]
fn select_falls_back_to_pilot_when_every_bucket_combo_cools() {
    let mut evolver = StrategyEvolver::new(true, 0.0);
    evolver.experiment_ttl_ms = 0;
    evolver.set_test_clock_ms(1_000);
    let bucket = LearningTargetBucket::Tls;
    let context = LearningContext { target_bucket: bucket, ..LearningContext::default() };
    evolver.set_learning_context(context.clone());

    // Cool every TLS-bucket pool entry by inserting cooled stats.
    for idx in 0..COMBO_POOL.len() {
        let combo = combo_from_pool(idx);
        if !combo_matches_bucket(&combo, bucket) {
            continue;
        }
        evolver.combos.insert(
            combo,
            ComboStats {
                attempts: 5,
                successes: 0,
                cooldown_until_ms: Some(10_000),
                last_attempt_ms: 900,
                ..ComboStats::new()
            },
        );
    }
    // Mark the bucket as piloted so the selector reaches the cooldown
    // filter rather than short-circuiting on the pilot path.
    evolver.contexts.entry(context).or_default().piloted_buckets.insert(bucket);

    let chosen = evolver.pick_non_cooled_random_for_bucket(bucket, evolver.monotonic_now_ms());
    assert_eq!(chosen, pilot_combo_for_bucket(bucket));
}

#[test]
fn with_time_knobs_overrides_defaults() {
    let evolver = StrategyEvolver::new(true, 0.0).with_time_knobs(60_000, 600_000, 5, 120_000);
    assert_eq!(evolver.experiment_ttl_ms, 60_000);
    assert_eq!(evolver.decay_half_life_ms, 600_000);
    assert_eq!(evolver.cooldown_after_failures, 5);
    assert_eq!(evolver.cooldown_ms, 120_000);
}

#[test]
fn monotonic_clock_survives_systemtime_jumps() {
    // The evolver epoch is `Instant::now()`, which is monotonic. A
    // simulated `SystemTime` jump (test clock override) does not
    // affect the production path -- here we exercise the override
    // itself to confirm the helper returns the override when set.
    let mut evolver = StrategyEvolver::new(true, 0.0);
    evolver.set_test_clock_ms(123);
    assert_eq!(evolver.monotonic_now_ms(), 123);
    evolver.set_test_clock_ms(100); // simulate negative jump
    assert_eq!(evolver.monotonic_now_ms(), 100);
}

// -----------------------------------------------------------------
// Shared-priors integration
// -----------------------------------------------------------------

#[test]
fn apply_shared_priors_loads_records_under_test_key() {
    use shared_priors::manifest::test_support::{generate_test_key, sign_manifest_bytes};

    let priors =
        b"{\"combo_hash\": 1, \"alpha\": 12.0, \"beta\": 4.0}\n{\"combo_hash\": 2, \"alpha\": 3.0, \"beta\": 1.0}\n";
    let key = generate_test_key();
    let manifest = sign_manifest_bytes(&key, priors, 1, "https://example/p.ndjson");

    let mut evolver = StrategyEvolver::new(true, 0.0);
    let count = evolver
        .apply_shared_priors(manifest.as_bytes(), priors, &key.public_bytes)
        .expect("apply must succeed under test key");
    assert_eq!(count, 2);
    assert_eq!(evolver.shared_priors_len(), 2);
}

#[test]
fn apply_shared_priors_fail_secure_keeps_existing_store() {
    use shared_priors::manifest::test_support::{generate_test_key, sign_manifest_bytes};

    let priors = b"{\"combo_hash\": 1, \"alpha\": 12.0, \"beta\": 4.0}\n";
    let key = generate_test_key();
    let manifest = sign_manifest_bytes(&key, priors, 1, "https://example/p.ndjson");
    let mut evolver = StrategyEvolver::new(true, 0.0);
    evolver.apply_shared_priors(manifest.as_bytes(), priors, &key.public_bytes).expect("first apply must succeed");
    assert_eq!(evolver.shared_priors_len(), 1);

    // Second call: a tampered payload. Verification must fail and the
    // previously-loaded prior must remain in place.
    let tampered = b"{\"combo_hash\": 1, \"alpha\": 99.0, \"beta\": 4.0}\n";
    let err = evolver
        .apply_shared_priors(manifest.as_bytes(), tampered, &key.public_bytes)
        .expect_err("tampered apply must fail");
    assert!(matches!(err, ApplyError::Manifest(ManifestError::HashMismatch)));
    assert_eq!(evolver.shared_priors_len(), 1, "fail-secure: existing priors must survive a failed refresh");
}

#[test]
fn shared_prior_for_returns_loaded_record_by_canonical_hash() {
    use shared_priors::manifest::test_support::{generate_test_key, sign_manifest_bytes};

    // Pre-compute the canonical hash of a specific combo so the bundle
    // can reference it. The combo here is `Some(AutoHost) split offset
    // + fake_ttl=8`, which yields a stable canonical hash by design.
    let combo = StrategyCombo {
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
    let hash = canonical_combo_hash(&combo);
    let priors_json = format!("{{\"combo_hash\": {hash}, \"alpha\": 7.0, \"beta\": 3.0}}\n");
    let key = generate_test_key();
    let manifest = sign_manifest_bytes(&key, priors_json.as_bytes(), 1, "https://example/p.ndjson");

    let mut evolver = StrategyEvolver::new(true, 0.0);
    evolver
        .apply_shared_priors(manifest.as_bytes(), priors_json.as_bytes(), &key.public_bytes)
        .expect("apply must succeed");

    let prior = evolver.shared_prior_for(&combo).expect("prior should be loaded for this combo");
    assert!((prior.alpha - 7.0).abs() < f64::EPSILON);
    assert!((prior.beta - 3.0).abs() < f64::EPSILON);

    // A different combo must not match.
    let other_combo = StrategyCombo::default_combo();
    assert!(evolver.shared_prior_for(&other_combo).is_none());
}

// -----------------------------------------------------------------
// EnvironmentKind context segregation
// -----------------------------------------------------------------

#[test]
fn learning_context_default_environment_is_unknown() {
    // Default must stay `Unknown` so existing call sites that use the
    // `..LearningContext::default()` spread idiom keep their existing
    // behavior without an explicit `environment:` field.
    let ctx = LearningContext::default();
    assert_eq!(ctx.environment, EnvironmentKind::Unknown);
}

#[test]
fn field_and_emulator_contexts_hash_differently() {
    // Two contexts identical in every dimension except `environment`
    // must not collide in the bandit's per-context HashMap. This is
    // the type-system guarantee that emulator runs cannot pollute
    // field priors.
    use std::collections::HashMap;
    let field = LearningContext { environment: EnvironmentKind::Field, ..LearningContext::default() };
    let emulator = LearningContext { environment: EnvironmentKind::Emulator, ..LearningContext::default() };
    assert_ne!(field, emulator);

    let mut map: HashMap<LearningContext, u32> = HashMap::new();
    map.insert(field.clone(), 1);
    map.insert(emulator.clone(), 2);
    assert_eq!(map.get(&field).copied(), Some(1));
    assert_eq!(map.get(&emulator).copied(), Some(2));
}

#[test]
fn evolver_per_context_state_segregates_by_environment() {
    // Concrete check: recording outcomes against a `Field` context
    // does not affect the `Emulator` context's state for the same
    // network identity / target bucket / etc.
    let mut e = StrategyEvolver::new(true, 0.0);
    let combo = StrategyCombo::default_combo();

    let field_ctx = LearningContext { environment: EnvironmentKind::Field, ..LearningContext::default() };
    e.set_learning_context(field_ctx.clone());
    e.contexts.entry(field_ctx.clone()).or_default().combos.insert(combo.clone(), ComboStats::new());

    let emulator_ctx = LearningContext { environment: EnvironmentKind::Emulator, ..LearningContext::default() };
    e.set_learning_context(emulator_ctx.clone());
    e.contexts.entry(emulator_ctx.clone()).or_default().combos.insert(combo.clone(), ComboStats::new());

    assert_eq!(e.contexts.len(), 2, "field and emulator must occupy distinct context slots");
    assert!(e.contexts.contains_key(&field_ctx));
    assert!(e.contexts.contains_key(&emulator_ctx));
}

#[test]
fn embedded_key_apply_short_circuits_until_release_key_lands() {
    use shared_priors::manifest::test_support::{generate_test_key, sign_manifest_bytes};

    let priors = b"{\"combo_hash\": 1, \"alpha\": 12.0, \"beta\": 4.0}\n";
    let key = generate_test_key();
    let manifest = sign_manifest_bytes(&key, priors, 1, "https://example/p.ndjson");

    let mut evolver = StrategyEvolver::new(true, 0.0);
    let err = evolver
        .apply_shared_priors_with_embedded_key(manifest.as_bytes(), priors)
        .expect_err("placeholder embedded key must reject");
    assert!(matches!(err, ApplyError::Manifest(ManifestError::NoProductionKey)));
    assert_eq!(evolver.shared_priors_len(), 0, "no priors should be stored after a rejected apply");
}
