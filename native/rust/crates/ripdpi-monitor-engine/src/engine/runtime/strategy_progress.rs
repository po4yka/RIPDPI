use super::plan::ExecutionPlan;
use super::state::ExecutionRuntime;
use crate::types::{StrategyProbeLiveProgress, StrategyProbeProgressLane};

impl ExecutionRuntime {
    #[allow(clippy::too_many_arguments)]
    pub(in crate::engine) fn publish_strategy_probe_candidate_started(
        &self,
        plan: &ExecutionPlan,
        phase: &str,
        lane: StrategyProbeProgressLane,
        candidate_index: usize,
        candidate_total: usize,
        candidate_id: &str,
        candidate_label: &str,
        message: String,
    ) {
        let progress = StrategyProbeLiveProgress {
            lane,
            candidate_index,
            candidate_total,
            candidate_id: candidate_id.to_string(),
            candidate_label: candidate_label.to_string(),
            succeeded_targets: 0,
            total_targets: 0,
        };
        self.publish_progress(
            plan,
            phase,
            self.completed_steps,
            message,
            Some(candidate_label.to_string()),
            None,
            Some(progress),
        );
    }
}
