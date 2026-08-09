use crate::candidates::StrategyCandidateSpec;
use crate::types::{ProbeResult, StrategyProbeAttempt, StrategyProbeCandidateSummary};

use super::score_state::CandidateScore;
use super::summary::build_candidate_execution;

#[derive(Debug)]
pub struct CandidateExecution {
    pub summary: StrategyProbeCandidateSummary,
    pub results: Vec<ProbeResult>,
    pub attempts: Vec<StrategyProbeAttempt>,
    pub cancelled: bool,
}

impl CandidateExecution {
    pub fn fully_succeeded_family(&self, family: &str) -> bool {
        self.summary.family == family && self.summary.succeeded_targets == self.summary.total_targets
    }
}

pub(in crate::execution) fn cancelled_candidate_execution(
    spec: &StrategyCandidateSpec,
    score: CandidateScore,
    quality_floor: usize,
) -> CandidateExecution {
    let mut execution = build_candidate_execution(spec, score, quality_floor);
    execution.cancelled = true;
    execution
}
