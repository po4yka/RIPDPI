use ripdpi_desync::AdaptivePlannerHints;

use crate::strategy_evolver::{StrategyCombo, StrategyEvolver};

impl StrategyEvolver {
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
        super::experiment::drop_expired_experiment(self, now_ms);

        if let Some(ref combo) = self.current_experiment {
            let hints = combo.to_hints();
            tracing::debug!(
                combo = ?combo,
                hints = ?hints,
                "strategy evolution reused pending combo, overriding per-flow adaptive tuning",
            );
            return Some(hints);
        }

        Some(start_next_experiment(self, now_ms))
    }
}

pub(in crate::strategy_evolver) fn start_next_experiment(
    evolver: &mut StrategyEvolver,
    now_ms: u64,
) -> AdaptivePlannerHints {
    let combo = evolver.select_next_combo();
    let hints = combo.to_hints();
    tracing::debug!(
        combo = ?combo,
        hints = ?hints,
        context = ?evolver.current_learning_context,
        "strategy evolution selected combo, overriding per-flow adaptive tuning",
    );
    evolver.current_experiment_context = Some(evolver.current_learning_context.clone());
    evolver.current_experiment_family = Some(combo.family());
    evolver.current_experiment_started_ms = Some(now_ms);
    evolver.current_experiment = Some(combo);
    hints
}
