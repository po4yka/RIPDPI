use ripdpi_failure_classifier::FailureClass;

use super::StrategyEvolver;
use super::selection::evict_context_if_needed;
use super::types::{
    ComboStats, LearningContext, StrategyCombo, StrategyFamily, combo_fitness_at, combo_fitness_at_with_penalties,
};

impl StrategyEvolver {
    pub(super) fn evict_if_needed(&mut self, keep: &StrategyCombo, now_ms: u64) {
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

    pub(super) fn record_contextual_feedback(
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
        family_stats.attempts = family_stats.attempts.saturating_add(1);
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
