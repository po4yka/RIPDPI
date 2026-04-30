use std::collections::HashMap;
use std::time::Instant;

use ripdpi_desync::AdaptivePlannerHints;
use ripdpi_failure_classifier::FailureClass;

use super::types::{
    combo_fitness_at, now_millis, ComboStats, CooldownTransition, LearningContext, StrategyCombo,
    FITNESS_LATENCY_CAP_MS,
};
use super::{
    StrategyEvolver, DEFAULT_COOLDOWN_AFTER_FAILURES, DEFAULT_COOLDOWN_MS, DEFAULT_DECAY_HALF_LIFE_MS,
    DEFAULT_EXPERIMENT_TTL_MS,
};

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
    pub(super) fn monotonic_now_ms(&self) -> u64 {
        #[cfg(test)]
        if let Some(override_ms) = self.test_clock_override_ms {
            return override_ms;
        }
        self.epoch.elapsed().as_millis() as u64
    }

    /// Test-only: pin the evolver's monotonic clock so TTL / decay /
    /// cooldown can be exercised deterministically.
    #[cfg(test)]
    pub(super) fn set_test_clock_ms(&mut self, ms: u64) {
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

    pub(super) fn lcg_next(&mut self) -> u32 {
        self.rng_state = self.rng_state.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1_442_695_040_888_963_407);
        // Take the upper 32 bits of the 64-bit state; this is the standard
        // PCG/Lehmer pattern. Shifting by 33 (the previous value) only kept
        // 31 bits, which made `lcg_f64` produce values in [0, 0.5) — a
        // distribution-skewing bug for epsilon-greedy and bucket selection.
        (self.rng_state >> 32) as u32
    }

    /// Returns a float in [0.0, 1.0).
    pub(super) fn lcg_f64(&mut self) -> f64 {
        self.lcg_next() as f64 / (u32::MAX as f64 + 1.0)
    }
}
