//! Session-level strategy evolution for DPI evasion parameter combinations.
//!
//! This module implements a UCB1 multi-armed bandit that explores *combinations*
//! across the 5 adaptive dimensions plus fake-TTL using epsilon-greedy + UCB1
//! selection. It operates at the **session** level: a single [`StrategyEvolver`]
//! instance picks one [`StrategyCombo`] at a time and holds it until feedback
//! (success/failure) arrives.
//!
//! # Time-driven semantics (2026-04-25)
//!
//! The bandit is connection-event driven, but it consumes wall-clock time on
//! three read-side checks so it stays responsive to network drift without
//! adding a background timer thread (see
//! `docs/architecture/spike-evolver-timer-ttl-decay.md`):
//!
//! 1. **Active-experiment TTL** -- if a pending experiment has not seen a
//!    success/failure within [`StrategyEvolver::experiment_ttl_ms`], the
//!    next [`StrategyEvolver::suggest_hints`] call drops it silently and
//!    re-rolls. Default 30 s. Closes the silent-stall gap where a stuck
//!    flow could pin one combo for the entire session.
//! 2. **Idle-decay on combo stats** -- [`combo_fitness_at`] applies an
//!    `exp(-Δt / half_life)` weight to the success-rate term so stale
//!    winners fade and the bandit can re-explore. Default half-life 1 h.
//! 3. **Cooldown after consecutive failures** -- after
//!    [`StrategyEvolver::cooldown_after_failures`] non-skip failures in a
//!    row, the combo's stats record a `cooldown_until_ms` and selection
//!    skips it for [`StrategyEvolver::cooldown_ms`] (default 5 min). The
//!    next success clears the cooldown. If every bucket-matching combo is
//!    cooling at once, [`StrategyEvolver::select_next_combo`] falls back
//!    to [`pilot_combo_for_bucket`] so the evolver always returns a hint.
//!
//! The evolver uses a monotonic clock (`Instant` deltas relative to a
//! per-evolver epoch) so TTL, decay, and cooldown survive `SystemTime`
//! jumps and NTP corrections.
//!
//! # Interaction with per-flow adaptive tuning
//!
//! The crate also contains a per-flow adaptive tuning system in
//! [`crate::adaptive_tuning::AdaptivePlannerResolver`]. Both systems produce
//! [`AdaptivePlannerHints`], but they serve different roles:
//!
//! | System | Scope | Granularity |
//! |--------|-------|-------------|
//! | **Strategy Evolver** (this module) | Session-wide | One combo for all flows |
//! | **Adaptive Tuning** (`adaptive_tuning`) | Per-flow | Per (host, group, flow-kind) |
//!
//! **Priority chain for hint resolution:**
//!
//! 1. Evolver hints (when `strategy_evolution` is enabled) -- override everything
//! 2. Per-flow adaptive hints (from `AdaptivePlannerResolver`) -- used when the
//!    evolver is disabled or returns `None`
//! 3. Group defaults (from the `DesyncGroup` configuration)
//!
//! When the evolver is enabled (`--strategy-evolution`), its hints take
//! precedence and per-flow dimension cycling in `adaptive_tuning` is effectively
//! bypassed for the dimensions the evolver sets.
//!
//! # When to enable the evolver
//!
//! - Enable when exploring a new network where the best parameter combination is
//!   unknown. The evolver will converge on a high-fitness combo over time.
//! - Disable (the default) for stable networks where per-flow adaptive tuning
//!   already performs well, or when you want fine-grained per-host adaptation.

mod types;

use std::collections::HashMap;
use std::time::Instant;

use ripdpi_config::OffsetBase;
use ripdpi_config::QuicFakeProfile;
use ripdpi_desync::AdaptivePlannerHints;
use ripdpi_failure_classifier::FailureClass;

// Re-export the public API types so external callers continue to find them at
// `crate::strategy_evolver::{StrategyCombo, ComboStats, LearningContext, …}`.
pub use types::{
    CapabilityContext, ComboStats, LearningAlpnClass, LearningContext, LearningHostingFamily, LearningReachabilitySet,
    LearningTargetBucket, LearningTransportKind, ResolverHealthClass, StrategyCombo,
};

use types::*;

/// Default time-driven evolver knobs. See module-level docs.
pub(crate) const DEFAULT_EXPERIMENT_TTL_MS: u64 = 30_000;
pub(crate) const DEFAULT_DECAY_HALF_LIFE_MS: u64 = 3_600_000;
pub(crate) const DEFAULT_COOLDOWN_AFTER_FAILURES: u32 = 3;
pub(crate) const DEFAULT_COOLDOWN_MS: u64 = 300_000;

// ---------------------------------------------------------------------------
// StrategyEvolver
// ---------------------------------------------------------------------------

pub struct StrategyEvolver {
    combos: HashMap<StrategyCombo, ComboStats>,
    contexts: HashMap<LearningContext, ContextBanditState>,
    current_experiment: Option<StrategyCombo>,
    current_experiment_context: Option<LearningContext>,
    current_experiment_family: Option<StrategyFamily>,
    current_experiment_started_ms: Option<u64>,
    current_learning_context: LearningContext,
    explore_epsilon: f64,
    pub max_combos: usize,
    enabled: bool,
    rng_state: u64,
    /// Monotonic clock anchor. All internal timestamps are millisecond
    /// deltas from this instant.
    epoch: Instant,
    /// Wall-clock budget for a single experiment slot. After elapsing,
    /// the next [`Self::suggest_hints`] drops the experiment without
    /// updating stats and re-rolls.
    pub experiment_ttl_ms: u64,
    /// Half-life for the recency-weighted decay applied to combo
    /// fitness. `0` disables decay.
    pub decay_half_life_ms: u64,
    /// Number of consecutive failures that trips a per-combo cooldown.
    /// `0` disables cooldown.
    pub cooldown_after_failures: u32,
    /// Length of the cooldown window in milliseconds.
    pub cooldown_ms: u64,
    /// Test-only override for the monotonic clock; production code leaves
    /// this `None` and the evolver uses [`Instant`] deltas.
    #[cfg(test)]
    test_clock_override_ms: Option<u64>,
}

impl StrategyEvolver {
    pub fn new(enabled: bool, epsilon: f64) -> Self {
        Self {
            combos: HashMap::new(),
            contexts: HashMap::new(),
            current_experiment: None,
            current_experiment_context: None,
            current_experiment_family: None,
            current_experiment_started_ms: None,
            current_learning_context: LearningContext::default(),
            explore_epsilon: epsilon,
            max_combos: 64,
            enabled,
            rng_state: now_millis()
                .wrapping_add(1)
                .wrapping_mul(6_364_136_223_846_793_005)
                .wrapping_add(std::process::id() as u64),
            epoch: Instant::now(),
            experiment_ttl_ms: DEFAULT_EXPERIMENT_TTL_MS,
            decay_half_life_ms: DEFAULT_DECAY_HALF_LIFE_MS,
            cooldown_after_failures: DEFAULT_COOLDOWN_AFTER_FAILURES,
            cooldown_ms: DEFAULT_COOLDOWN_MS,
            #[cfg(test)]
            test_clock_override_ms: None,
        }
    }

    /// Monotonic ms-since-epoch tick used by all evolver-internal
    /// timestamps. Independent of `SystemTime`, so TTL/decay/cooldown
    /// survive NTP corrections.
    fn monotonic_now_ms(&self) -> u64 {
        #[cfg(test)]
        if let Some(override_ms) = self.test_clock_override_ms {
            return override_ms;
        }
        self.epoch.elapsed().as_millis() as u64
    }

    /// Test-only: pin the evolver's monotonic clock so TTL / decay /
    /// cooldown can be exercised deterministically.
    #[cfg(test)]
    fn set_test_clock_ms(&mut self, ms: u64) {
        self.test_clock_override_ms = Some(ms);
    }

    /// Builder-style override for the four time-driven knobs. Used by the
    /// runtime listener to thread `RuntimeAdaptiveSettings::evolution_*`
    /// into the evolver without touching [`Self::new`]'s signature.
    pub fn with_time_knobs(
        mut self,
        experiment_ttl_ms: u64,
        decay_half_life_ms: u64,
        cooldown_after_failures: u32,
        cooldown_ms: u64,
    ) -> Self {
        self.experiment_ttl_ms = experiment_ttl_ms;
        self.decay_half_life_ms = decay_half_life_ms;
        self.cooldown_after_failures = cooldown_after_failures;
        self.cooldown_ms = cooldown_ms;
        self
    }

    pub fn is_enabled(&self) -> bool {
        self.enabled
    }

    pub fn epsilon(&self) -> f64 {
        self.explore_epsilon
    }

    pub fn set_learning_context(&mut self, context: LearningContext) {
        if self.current_learning_context == context {
            return;
        }
        self.current_learning_context = context;
        self.current_experiment = None;
        self.current_experiment_context = None;
        self.current_experiment_family = None;
        self.current_experiment_started_ms = None;
    }

    pub fn current_learning_context(&self) -> &LearningContext {
        &self.current_learning_context
    }

    /// Returns the currently pending experiment hints without mutating state.
    pub fn peek_hints(&self) -> Option<AdaptivePlannerHints> {
        if !self.enabled {
            return None;
        }
        self.current_experiment.as_ref().map(StrategyCombo::to_hints)
    }

    /// Returns adaptive hints if the evolver wants to override the default planner.
    ///
    /// When `Some` is returned, the caller should use these hints **instead of**
    /// per-flow adaptive hints from [`crate::adaptive_tuning::AdaptivePlannerResolver`].
    /// Called before each outbound send.
    pub fn suggest_hints(&mut self) -> Option<AdaptivePlannerHints> {
        if !self.enabled {
            return None;
        }

        let now_ms = self.monotonic_now_ms();

        // Drop a pending experiment that has exceeded its TTL. The flow that
        // started it never reported success or failure, so updating stats
        // would record a phantom outcome.
        if self.experiment_ttl_ms > 0 {
            if let Some(started_ms) = self.current_experiment_started_ms {
                let elapsed_ms = now_ms.saturating_sub(started_ms);
                if elapsed_ms >= self.experiment_ttl_ms {
                    let dropped = self.current_experiment.take();
                    self.current_experiment_context = None;
                    self.current_experiment_family = None;
                    self.current_experiment_started_ms = None;
                    tracing::debug!(
                        combo = ?dropped,
                        elapsed_ms,
                        ttl_ms = self.experiment_ttl_ms,
                        "strategy evolution dropped experiment due to TTL expiry",
                    );
                }
            }
        }

        // If we already have an outstanding experiment, return its hints.
        if let Some(ref combo) = self.current_experiment {
            let hints = combo.to_hints();
            tracing::debug!(
                combo = ?combo,
                hints = ?hints,
                "strategy evolution reused pending combo, overriding per-flow adaptive tuning",
            );
            return Some(hints);
        }

        let combo = self.select_next_combo();
        let hints = combo.to_hints();
        tracing::debug!(
            combo = ?combo,
            hints = ?hints,
            context = ?self.current_learning_context,
            "strategy evolution selected combo, overriding per-flow adaptive tuning",
        );
        self.current_experiment_context = Some(self.current_learning_context.clone());
        self.current_experiment_family = Some(combo.family());
        self.current_experiment_started_ms = Some(now_ms);
        self.current_experiment = Some(combo);
        Some(hints)
    }

    /// Record successful connection with observed latency.
    pub fn record_success(&mut self, latency_ms: u64) {
        let Some(combo) = self.current_experiment.take() else {
            return;
        };
        let context = self.current_experiment_context.take().unwrap_or_else(|| self.current_learning_context.clone());
        let family = self.current_experiment_family.take().unwrap_or_else(|| combo.family());
        self.current_experiment_started_ms = None;
        tracing::debug!(combo = ?combo, latency_ms, "strategy evolution recorded success");
        let now_ms = self.monotonic_now_ms();
        self.evict_if_needed(&combo, now_ms);
        let stats = self.combos.entry(combo.clone()).or_insert_with(ComboStats::new);
        let transition =
            stats.record_attempt(true, latency_ms, None, now_ms, self.cooldown_after_failures, self.cooldown_ms);
        let last_attempt_ms = stats.last_attempt_ms;
        if matches!(transition, CooldownTransition::Cleared) {
            tracing::debug!(combo = ?combo, "strategy evolution cooldown cleared by success");
        }
        self.record_contextual_feedback(&context, family, &combo, true, latency_ms, None, last_attempt_ms);
        tracing::debug!(
            combos_tested = self.combos_tested(),
            best_fitness = format_args!("{:.1}", self.best_fitness()),
            "strategy evolution progress",
        );
    }

    /// Record failed connection with failure class.
    ///
    /// `FailureClass::CapabilitySkipped` and
    /// `FailureClass::StrategyExecutionFailure` are no-ops: the tactic was
    /// never emitted successfully, so they must not affect arm counts or
    /// reward estimates.
    pub fn record_failure(&mut self, class: FailureClass) {
        if matches!(class, FailureClass::CapabilitySkipped | FailureClass::StrategyExecutionFailure) {
            // Discard the pending experiment without touching bandit state.
            // The run was skipped before any packet was sent, so it carries
            // no signal about the strategy's quality.
            self.current_experiment = None;
            self.current_experiment_context = None;
            self.current_experiment_family = None;
            self.current_experiment_started_ms = None;
            return;
        }
        let Some(combo) = self.current_experiment.take() else {
            return;
        };
        let context = self.current_experiment_context.take().unwrap_or_else(|| self.current_learning_context.clone());
        let family = self.current_experiment_family.take().unwrap_or_else(|| combo.family());
        self.current_experiment_started_ms = None;
        tracing::debug!(combo = ?combo, class = class.as_str(), "strategy evolution recorded failure");
        let now_ms = self.monotonic_now_ms();
        self.evict_if_needed(&combo, now_ms);
        let stats = self.combos.entry(combo.clone()).or_insert_with(ComboStats::new);
        let transition = stats.record_attempt(
            false,
            FITNESS_LATENCY_CAP_MS as u64,
            Some(class),
            now_ms,
            self.cooldown_after_failures,
            self.cooldown_ms,
        );
        let last_attempt_ms = stats.last_attempt_ms;
        if let CooldownTransition::Tripped { until_ms } = transition {
            tracing::debug!(
                combo = ?combo,
                cooldown_until_ms = until_ms,
                cooldown_ms = self.cooldown_ms,
                consecutive_failures = self.cooldown_after_failures,
                "strategy evolution combo entered cooldown",
            );
        }
        self.record_contextual_feedback(
            &context,
            family,
            &combo,
            false,
            FITNESS_LATENCY_CAP_MS as u64,
            Some(class),
            last_attempt_ms,
        );
    }

    /// Returns the best-performing combo found so far. Decay is applied so
    /// stale winners do not pin the result indefinitely.
    pub fn best_combo(&self) -> Option<(&StrategyCombo, &ComboStats)> {
        let now_ms = self.monotonic_now_ms();
        let half_life = self.decay_half_life_ms;
        self.combos.iter().max_by(|a, b| {
            combo_fitness_at(a.0, a.1, now_ms, half_life)
                .partial_cmp(&combo_fitness_at(b.0, b.1, now_ms, half_life))
                .unwrap_or(std::cmp::Ordering::Equal)
        })
    }

    /// Number of unique combos tested.
    pub fn combos_tested(&self) -> usize {
        self.combos.len()
    }

    /// Best fitness score.
    pub fn best_fitness(&self) -> f64 {
        let now_ms = self.monotonic_now_ms();
        let half_life = self.decay_half_life_ms;
        self.best_combo().map_or(0.0, |(combo, stats)| combo_fitness_at(combo, stats, now_ms, half_life))
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

    fn select_next_combo(&mut self) -> StrategyCombo {
        let context = self.current_learning_context.clone();
        let bucket = context.target_bucket;
        let bucket_piloted = self.contexts.get(&context).is_some_and(|state| state.piloted_buckets.contains(&bucket));
        let now_ms = self.monotonic_now_ms();
        let half_life = self.decay_half_life_ms;

        if self.combos.is_empty() {
            return pilot_combo_for_bucket(bucket);
        }
        if !bucket_piloted {
            return pilot_combo_for_bucket(bucket);
        }
        if self.lcg_f64() < self.explore_epsilon {
            return self.pick_non_cooled_random_for_bucket(bucket, now_ms);
        }
        let Some(state) = self.contexts.get(&context) else {
            return self.pick_non_cooled_random_for_bucket(bucket, now_ms);
        };
        if let Some(niche) = state.niche_winners.get(&bucket) {
            if !state.combos.get(niche).is_some_and(|stats| stats.is_cooled(now_ms)) {
                return niche.clone();
            }
        }
        let Some(family) = Self::select_next_family(state, bucket) else {
            return self.pick_non_cooled_random_for_bucket(bucket, now_ms);
        };
        Self::best_context_combo_for_family(state, family, now_ms, half_life)
            .unwrap_or_else(|| self.pick_non_cooled_random_for_bucket(bucket, now_ms))
    }

    /// Random-from-pool fallback that prefers combos not currently cooling.
    /// Falls back to [`pilot_combo_for_bucket`] when every bucket-matching
    /// pool entry has stats still in cooldown.
    fn pick_non_cooled_random_for_bucket(&mut self, bucket: LearningTargetBucket, now_ms: u64) -> StrategyCombo {
        let available: Vec<usize> = (0..COMBO_POOL.len())
            .filter(|idx| combo_matches_bucket(&combo_from_pool(*idx), bucket))
            .filter(|idx| {
                let combo = combo_from_pool(*idx);
                !self.combos.get(&combo).is_some_and(|stats| stats.is_cooled(now_ms))
            })
            .collect();
        if available.is_empty() {
            return pilot_combo_for_bucket(bucket);
        }
        let idx = available[self.lcg_next() as usize % available.len()];
        combo_from_pool(idx)
    }

    fn evict_if_needed(&mut self, keep: &StrategyCombo, now_ms: u64) {
        if self.combos.len() < self.max_combos {
            return;
        }
        let half_life = self.decay_half_life_ms;
        // Find the combo with the lowest decayed fitness, excluding `keep`.
        let worst = self
            .combos
            .iter()
            .filter(|(k, _)| *k != keep)
            .min_by(|a, b| {
                combo_fitness_at(a.0, a.1, now_ms, half_life)
                    .partial_cmp(&combo_fitness_at(b.0, b.1, now_ms, half_life))
                    .unwrap_or(std::cmp::Ordering::Equal)
            })
            .map(|(k, _)| k.clone());
        if let Some(w) = worst {
            self.combos.remove(&w);
        }
    }

    fn select_next_family(state: &ContextBanditState, bucket: LearningTargetBucket) -> Option<StrategyFamily> {
        if state.families.is_empty() {
            return Some(default_family_for_bucket(bucket));
        }
        let total_attempts: u32 = state.families.values().map(|stats| stats.attempts.max(1)).sum();
        let ln_total = (total_attempts as f64).ln().max(1.0);
        state
            .families
            .iter()
            .map(|(family, stats)| {
                let score = if stats.attempts == 0 {
                    f64::MAX
                } else {
                    (stats.total_reward / stats.attempts as f64) + 1.41 * (ln_total / stats.attempts as f64).sqrt()
                };
                (*family, score)
            })
            .max_by(|a, b| a.1.partial_cmp(&b.1).unwrap_or(std::cmp::Ordering::Equal))
            .map(|(family, _)| family)
    }

    fn best_context_combo_for_family(
        state: &ContextBanditState,
        family: StrategyFamily,
        now_ms: u64,
        half_life_ms: u64,
    ) -> Option<StrategyCombo> {
        let total_attempts: u32 = state.combos.values().map(|stats| stats.attempts.max(1)).sum();
        let ln_total = (total_attempts as f64).ln().max(1.0);
        state
            .combos
            .iter()
            .filter(|(combo, _)| combo.family() == family || family == StrategyFamily::Mixed)
            // Skip combos that are still cooling.
            .filter(|(_, stats)| !stats.is_cooled(now_ms))
            .map(|(combo, stats)| {
                let score = if stats.attempts == 0 {
                    f64::MAX
                } else {
                    combo_fitness_at(combo, stats, now_ms, half_life_ms)
                        + 1.41 * (ln_total / stats.attempts as f64).sqrt()
                };
                (combo.clone(), score)
            })
            .max_by(|a, b| a.1.partial_cmp(&b.1).unwrap_or(std::cmp::Ordering::Equal))
            .map(|(combo, _)| combo)
    }

    fn record_contextual_feedback(
        &mut self,
        context: &LearningContext,
        family: StrategyFamily,
        combo: &StrategyCombo,
        success: bool,
        latency_ms: u64,
        failure_class: Option<FailureClass>,
        last_attempt_ms: u64,
    ) {
        let cooldown_after_failures = self.cooldown_after_failures;
        let cooldown_ms = self.cooldown_ms;
        let half_life = self.decay_half_life_ms;
        let state = self.contexts.entry(context.clone()).or_default();
        state.piloted_buckets.insert(context.target_bucket);
        evict_context_if_needed(state, combo, self.max_combos, last_attempt_ms, half_life);
        let stats = state.combos.entry(combo.clone()).or_insert_with(ComboStats::new);
        let _ = stats.record_attempt(
            success,
            latency_ms,
            failure_class,
            last_attempt_ms,
            cooldown_after_failures,
            cooldown_ms,
        );
        let updated_fitness = combo_fitness_at(combo, stats, last_attempt_ms, half_life);
        let family_stats = state.families.entry(family).or_default();
        family_stats.attempts += 1;
        family_stats.total_reward += updated_fitness;
        let _ = stats;

        let niche_entry = state.niche_winners.entry(context.target_bucket).or_insert_with(|| combo.clone());
        let niche_fitness = state
            .combos
            .get(niche_entry)
            .map_or(f64::MIN, |stats| combo_fitness_at(niche_entry, stats, last_attempt_ms, half_life));
        if updated_fitness >= niche_fitness {
            *niche_entry = combo.clone();
        }
    }
}

fn combo_matches_bucket(combo: &StrategyCombo, bucket: LearningTargetBucket) -> bool {
    match bucket {
        LearningTargetBucket::Generic | LearningTargetBucket::Control => combo.family() == StrategyFamily::Baseline,
        LearningTargetBucket::Tls => {
            combo.split_offset_base.is_some()
                || combo.tls_record_offset_base.is_some()
                || combo.tlsrandrec_profile.is_some()
        }
        LearningTargetBucket::Ech => {
            combo.split_offset_base == Some(OffsetBase::EchExt)
                || combo.tls_record_offset_base == Some(OffsetBase::EchExt)
        }
        LearningTargetBucket::Quic => combo.quic_fake_profile.is_some() || combo.udp_burst_profile.is_some(),
    }
}

fn default_family_for_bucket(bucket: LearningTargetBucket) -> StrategyFamily {
    match bucket {
        LearningTargetBucket::Generic | LearningTargetBucket::Control => StrategyFamily::Baseline,
        LearningTargetBucket::Tls => StrategyFamily::SplitOffset,
        LearningTargetBucket::Ech => StrategyFamily::TlsRecordOffset,
        LearningTargetBucket::Quic => StrategyFamily::QuicFake,
    }
}

fn pilot_combo_for_bucket(bucket: LearningTargetBucket) -> StrategyCombo {
    match bucket {
        LearningTargetBucket::Generic | LearningTargetBucket::Control => StrategyCombo::default_combo(),
        LearningTargetBucket::Tls => {
            StrategyCombo { split_offset_base: Some(OffsetBase::AutoHost), ..StrategyCombo::default_combo() }
        }
        LearningTargetBucket::Ech => {
            StrategyCombo { split_offset_base: Some(OffsetBase::EchExt), ..StrategyCombo::default_combo() }
        }
        LearningTargetBucket::Quic => {
            StrategyCombo { quic_fake_profile: Some(QuicFakeProfile::CompatDefault), ..StrategyCombo::default_combo() }
        }
    }
}

fn evict_context_if_needed(
    state: &mut ContextBanditState,
    keep: &StrategyCombo,
    max_combos: usize,
    now_ms: u64,
    half_life_ms: u64,
) {
    if state.combos.len() < max_combos {
        return;
    }
    let worst = state
        .combos
        .iter()
        .filter(|(combo, _)| *combo != keep)
        .min_by(|a, b| {
            combo_fitness_at(a.0, a.1, now_ms, half_life_ms)
                .partial_cmp(&combo_fitness_at(b.0, b.1, now_ms, half_life_ms))
                .unwrap_or(std::cmp::Ordering::Equal)
        })
        .map(|(combo, _)| combo.clone());
    if let Some(worst_combo) = worst {
        state.combos.remove(&worst_combo);
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use std::hash::{Hash, Hasher};

    use ripdpi_config::EntropyMode;
    use ripdpi_desync::{AdaptiveTlsRandRecProfile, AdaptiveUdpBurstProfile};

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
        let combo_a = StrategyCombo { split_offset_base: Some(OffsetBase::AutoHost), ..StrategyCombo::default_combo() };
        // Combo B: MidSld offset, no TTL
        let combo_b = StrategyCombo { split_offset_base: Some(OffsetBase::MidSld), ..StrategyCombo::default_combo() };

        // Manually insert combo A: 2 successes, 1 fail (3 attempts)
        e.combos.insert(
            combo_a.clone(),
            ComboStats { attempts: 3, successes: 2, total_latency_ms: 200, ..ComboStats::new() },
        );

        // Manually insert combo B: 3 successes (3 attempts)
        e.combos.insert(
            combo_b.clone(),
            ComboStats { attempts: 3, successes: 3, total_latency_ms: 300, ..ComboStats::new() },
        );

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

        e.combos.insert(
            good.clone(),
            ComboStats { attempts: 10, successes: 9, total_latency_ms: 100, ..ComboStats::new() },
        );
        e.combos.insert(
            bad.clone(),
            ComboStats { attempts: 10, successes: 1, total_latency_ms: 5000, ..ComboStats::new() },
        );

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
        };
        let hints = combo.to_hints();
        assert_eq!(hints.split_offset_base, Some(OffsetBase::AutoHost));
        assert_eq!(hints.tls_record_offset_base, Some(OffsetBase::EndHost));
        assert_eq!(hints.tlsrandrec_profile, Some(AdaptiveTlsRandRecProfile::Tight));
        assert_eq!(hints.udp_burst_profile, Some(AdaptiveUdpBurstProfile::Conservative));
        assert_eq!(hints.quic_fake_profile, Some(QuicFakeProfile::RealisticInitial));
        assert_eq!(hints.entropy_mode, Some(EntropyMode::Shannon));
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
        let combo_shannon =
            StrategyCombo { entropy_mode: Some(EntropyMode::Shannon), ..StrategyCombo::default_combo() };
        let combo_popcount =
            StrategyCombo { entropy_mode: Some(EntropyMode::Popcount), ..StrategyCombo::default_combo() };
        let combo_combined =
            StrategyCombo { entropy_mode: Some(EntropyMode::Combined), ..StrategyCombo::default_combo() };

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
        let tls_combo =
            StrategyCombo { split_offset_base: Some(OffsetBase::AutoHost), ..StrategyCombo::default_combo() };
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
            ComboStats {
                attempts: 5,
                successes: 4,
                total_latency_ms: 250,
                last_attempt_ms: 9_000,
                ..ComboStats::new()
            },
        );

        let pick =
            StrategyEvolver::best_context_combo_for_family(&state, StrategyFamily::SplitOffset, 9_500, 3_600_000);
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
}
