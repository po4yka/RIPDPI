use std::cmp::Reverse;

use crate::types::StrategyProbeCandidateSummary;

pub fn winning_candidate_index(candidates: &[StrategyProbeCandidateSummary]) -> Option<usize> {
    candidates
        .iter()
        .enumerate()
        .filter(|(_, candidate)| !candidate.skipped && candidate.outcome != "not_applicable")
        .max_by_key(|(index, candidate)| {
            (
                candidate.weighted_success_score,
                candidate.quality_score,
                Reverse(candidate.average_latency_ms.unwrap_or(u64::MAX)),
                Reverse(*index),
            )
        })
        .map(|(index, _)| index)
}
