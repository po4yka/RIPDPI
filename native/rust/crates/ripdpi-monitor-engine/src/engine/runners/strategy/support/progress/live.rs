use crate::types::{StrategyProbeLiveProgress, StrategyProbeProgressLane};

pub(in crate::engine::runners::strategy) fn strategy_probe_live_progress_with_targets(
    lane: StrategyProbeProgressLane,
    candidate_index: usize,
    candidate_total: usize,
    candidate_id: &str,
    candidate_label: &str,
    succeeded_targets: usize,
    total_targets: usize,
) -> StrategyProbeLiveProgress {
    StrategyProbeLiveProgress {
        lane,
        candidate_index,
        candidate_total,
        candidate_id: candidate_id.to_string(),
        candidate_label: candidate_label.to_string(),
        succeeded_targets,
        total_targets,
    }
}
