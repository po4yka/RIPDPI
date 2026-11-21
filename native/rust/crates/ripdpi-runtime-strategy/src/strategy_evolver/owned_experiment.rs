use ripdpi_desync::AdaptivePlannerHints;

use super::types::StrategyFamily;
use super::{LearningContext, StrategyCombo, StrategyEvolver};

/// A selected combo with the context and clock of its own outbound attempt.
/// This value is consumed once by feedback, or dropped without a reward.
#[derive(Debug)]
pub struct StrategyExperiment {
    pub(super) combo: StrategyCombo,
    pub(super) context: LearningContext,
    pub(super) family: StrategyFamily,
    started_ms: u64,
    // The TTL bounds the wait for an answer. A relay can report its outcome much later.
    answered_ms: Option<u64>,
}

impl StrategyExperiment {
    /// Planner hints selected for this attempt.
    pub fn hints(&self) -> AdaptivePlannerHints {
        self.combo.to_hints()
    }

    /// The learning context which selected this attempt.
    pub fn context(&self) -> &LearningContext {
        &self.context
    }
}

impl StrategyEvolver {
    /// Select an independent experiment without retaining a session-wide slot.
    pub fn start_experiment(&mut self, context: LearningContext) -> Option<StrategyExperiment> {
        self.set_learning_context(context);
        // Legacy serial callers retain their own API; owned attempts never reuse its slot.
        self.current_experiment = None;
        self.current_experiment_context = None;
        self.current_experiment_family = None;
        self.current_experiment_started_ms = None;
        self.suggest_hints()?;
        self.take_experiment()
    }

    pub(super) fn take_experiment(&mut self) -> Option<StrategyExperiment> {
        let combo = self.current_experiment.take()?;
        Some(StrategyExperiment {
            context: self.current_experiment_context.take().unwrap_or_else(|| self.current_learning_context.clone()),
            family: self.current_experiment_family.take().unwrap_or_else(|| combo.family()),
            started_ms: self.current_experiment_started_ms.take().unwrap_or_else(|| self.monotonic_now_ms()),
            answered_ms: None,
            combo,
        })
    }

    pub(super) fn expired_experiment_age(&self, started_ms: u64, now_ms: u64) -> Option<u64> {
        let elapsed_ms = now_ms.saturating_sub(started_ms);
        (self.experiment_ttl_ms != 0 && elapsed_ms >= self.experiment_ttl_ms).then_some(elapsed_ms)
    }

    /// Expired observations are neutral, including feedback after the TTL boundary.
    pub fn experiment_expired(&self, experiment: &StrategyExperiment) -> bool {
        let end_ms = experiment.answered_ms.unwrap_or_else(|| self.monotonic_now_ms());
        self.expired_experiment_age(experiment.started_ms, end_ms).is_some()
    }

    /// Stop the TTL at the first accepted answer. A later answer cannot revive an expired attempt.
    pub fn note_experiment_answered(&self, experiment: &mut StrategyExperiment) {
        if experiment.answered_ms.is_none() {
            experiment.answered_ms = Some(self.monotonic_now_ms());
        }
    }
}
