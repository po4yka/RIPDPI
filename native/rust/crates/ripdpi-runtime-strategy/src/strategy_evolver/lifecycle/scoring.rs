use crate::strategy_evolver::StrategyEvolver;
use crate::strategy_evolver::types::{ComboStats, StrategyCombo, combo_fitness_at};

impl StrategyEvolver {
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
}
