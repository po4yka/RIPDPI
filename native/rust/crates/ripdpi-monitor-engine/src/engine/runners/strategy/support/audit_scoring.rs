use crate::types::StrategyProbeCandidateSummary;

use super::audit_counts::round_percent;

pub(in crate::engine::runners::strategy) fn all_candidates_tied(candidates: &[StrategyProbeCandidateSummary]) -> bool {
    let eligible: Vec<_> = candidates.iter().filter(|c| !c.skipped && c.outcome != "not_applicable").collect();
    if eligible.len() < 2 {
        return false;
    }
    let first = &eligible[0];
    eligible
        .iter()
        .all(|c| c.weighted_success_score == first.weighted_success_score && c.quality_score == first.quality_score)
}

pub(in crate::engine::runners::strategy) fn candidate_score_percent(
    candidate: &StrategyProbeCandidateSummary,
) -> usize {
    round_percent(candidate.weighted_success_score, candidate.total_weight)
}

pub(in crate::engine::runners::strategy) fn winner_margin_percent(
    candidates: &[StrategyProbeCandidateSummary],
    winner_candidate_id: &str,
) -> usize {
    let executable_scores = candidates
        .iter()
        .filter(|candidate| !candidate.skipped && candidate.outcome != "not_applicable")
        .map(|candidate| (candidate.id.as_str(), candidate_score_percent(candidate)))
        .collect::<Vec<_>>();
    let Some((_, winner_score)) =
        executable_scores.iter().find(|(candidate_id, _)| *candidate_id == winner_candidate_id)
    else {
        return 0;
    };
    let runner_up_score = executable_scores
        .iter()
        .filter(|(candidate_id, _)| *candidate_id != winner_candidate_id)
        .map(|(_, score)| *score)
        .max()
        .unwrap_or(0);
    winner_score.saturating_sub(runner_up_score)
}
