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

mod prior_store;
mod selection;
mod shared_priors;
#[cfg(test)]
mod tests;
mod thompson_sampling;
mod types;

// Re-exported for the Thompson scorer surface; UCB1 remains production default.
#[allow(unused_imports)]
pub use thompson_sampling::{sample_beta, BetaParams, ThompsonSampling};

// Re-exported so callers (including the JNI bridge) can verify and apply
// signed shared-priors bundles without reaching into sub-modules.
pub use shared_priors::{
    apply_global_shared_priors, apply_global_shared_priors_with_embedded_key, apply_priors,
    apply_priors_with_embedded_key, canonical_combo_hash, global_shared_priors_len, is_production_key_set,
    latest_shared_priors, AppliedPriors, ApplyError, ManifestError, SharedPriorsError, SharedPriorsManifest,
    SHARED_PRIORS_PUB_KEY,
};

use std::collections::HashMap;
use std::time::Instant;

use ripdpi_desync::AdaptivePlannerHints;
use ripdpi_failure_classifier::FailureClass;

// Re-export the public API types so external callers continue to find them at
// `crate::strategy_evolver::{StrategyCombo, ComboStats, LearningContext, …}`.
pub use ripdpi_config::EnvironmentKind;
pub use types::{
    CapabilityContext, ComboStats, LearningAlpnClass, LearningContext, LearningHostingFamily, LearningReachabilitySet,
    LearningTargetBucket, LearningTransportKind, ResolverHealthClass, StrategyCombo,
};

use selection::{combo_matches_bucket, default_family_for_bucket, evict_context_if_needed, pilot_combo_for_bucket};
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
    /// Hard cap on the number of attempts a single combo can accumulate
    /// before it is "frozen" — skipped during random exploration. The
    /// niche-winner / family-best paths still keep using a frozen combo so
    /// proven winners can be exploited indefinitely. `u32::MAX` (the
    /// default) disables the cap.
    pub max_arm_attempts: u32,
    /// When true, fitness scoring layers the rarity (`attempts < RARITY_FLOOR`)
    /// and retry-cost (`attempts > RETRY_SATURATION`) penalties on top of
    /// the standard score. Defaults to `false` so existing callers see
    /// unchanged behaviour.
    pub penalties_enabled: bool,
    /// Shared Beta posteriors keyed by canonical combo hash. Populated by
    /// [`Self::apply_shared_priors`] from a verified GitHub-hosted bundle.
    /// The UCB1 selection path does not consume these today; they are exposed
    /// for Thompson-style scoring and diagnostics. The "field data wins" merge rule lives at the
    /// consumption site rather than at apply time so the bundle stays a
    /// pure prior store.
    shared_priors: HashMap<u64, BetaParams>,
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
            max_arm_attempts: u32::MAX,
            penalties_enabled: false,
            shared_priors: HashMap::new(),
            #[cfg(test)]
            test_clock_override_ms: None,
        }
    }

    /// Builder-style override for offline-learner hardening knobs. Both knobs
    /// default to OFF in [`Self::new`] so existing call sites are unaffected;
    /// opt in here.
    ///
    /// `max_arm_attempts` is the hard cap before a combo is skipped during
    /// random exploration (use `u32::MAX` to keep the cap disabled).
    /// `penalties_enabled` toggles the rarity / retry-cost terms on top of
    /// the standard fitness function.
    pub fn with_learning_hardening(mut self, max_arm_attempts: u32, penalties_enabled: bool) -> Self {
        self.max_arm_attempts = max_arm_attempts;
        self.penalties_enabled = penalties_enabled;
        self
    }

    /// Number of attempts left for `combo` before the attempt-budget cap
    /// kicks in. Returns `0` when the combo has already been frozen out of
    /// random exploration. When the cap is disabled (`u32::MAX`), returns
    /// `u32::MAX - attempts` so callers can treat the value uniformly.
    pub fn attempts_budget_remaining(&self, combo: &StrategyCombo) -> u32 {
        let used = self.combos.get(combo).map_or(0, |stats| stats.attempts);
        self.max_arm_attempts.saturating_sub(used)
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
        // Take the upper 32 bits of the 64-bit state; this is the standard
        // PCG/Lehmer pattern. Shifting by 33 (the previous value) only kept
        // 31 bits, which made `lcg_f64` produce values in [0, 0.5) — a
        // distribution-skewing bug for epsilon-greedy and bucket selection.
        (self.rng_state >> 32) as u32
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
    /// pool entry has stats still in cooldown or has exceeded the
    /// per-arm attempt budget.
    fn pick_non_cooled_random_for_bucket(&mut self, bucket: LearningTargetBucket, now_ms: u64) -> StrategyCombo {
        let max_attempts = self.max_arm_attempts;
        let available: Vec<usize> = (0..COMBO_POOL.len())
            .filter(|idx| combo_matches_bucket(&combo_from_pool(*idx), bucket))
            .filter(|idx| {
                let combo = combo_from_pool(*idx);
                let stats = self.combos.get(&combo);
                let cooled = stats.is_some_and(|s| s.is_cooled(now_ms));
                // Frozen combos (attempt budget exhausted) are skipped
                // during random exploration; niche-winner and
                // family-best paths still keep using them.
                let frozen = stats.is_some_and(|s| s.attempts >= max_attempts);
                !cooled && !frozen
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
        let penalties = self.penalties_enabled;
        // Find the combo with the lowest decayed fitness, excluding `keep`.
        let worst = self
            .combos
            .iter()
            .filter(|(k, _)| *k != keep)
            .min_by(|a, b| {
                combo_fitness_at_with_penalties(a.0, a.1, now_ms, half_life, penalties)
                    .partial_cmp(&combo_fitness_at_with_penalties(b.0, b.1, now_ms, half_life, penalties))
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
