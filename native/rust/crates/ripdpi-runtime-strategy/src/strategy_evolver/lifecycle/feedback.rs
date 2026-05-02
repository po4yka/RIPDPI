use ripdpi_failure_classifier::FailureClass;

use super::experiment::discard_pending_experiment;
use crate::strategy_evolver::types::{ComboStats, CooldownTransition, FITNESS_LATENCY_CAP_MS};
use crate::strategy_evolver::StrategyEvolver;

impl StrategyEvolver {
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
            discard_pending_experiment(self);
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
}
