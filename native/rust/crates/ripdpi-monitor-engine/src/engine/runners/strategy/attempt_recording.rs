use crate::execution::CandidateExecution;
use crate::types::StrategyProbeAttemptRound;

use super::super::super::runtime::ExecutionRuntime;

pub(super) fn record_candidate_attempts(
    runtime: &mut ExecutionRuntime,
    execution: &mut CandidateExecution,
    round: StrategyProbeAttemptRound,
) {
    for attempt in &mut execution.attempts {
        attempt.round = round;
    }
    runtime.strategy.attempts.append(&mut execution.attempts);
}

pub(super) fn record_baseline_candidate_attempts(runtime: &mut ExecutionRuntime, execution: &mut CandidateExecution) {
    record_candidate_attempts(runtime, execution, StrategyProbeAttemptRound::Baseline);
}

pub(super) fn record_qualifier_candidate_attempts(runtime: &mut ExecutionRuntime, execution: &mut CandidateExecution) {
    record_candidate_attempts(runtime, execution, StrategyProbeAttemptRound::Qualifier);
}

pub(super) fn record_full_matrix_candidate_attempts(
    runtime: &mut ExecutionRuntime,
    execution: &mut CandidateExecution,
) {
    record_candidate_attempts(runtime, execution, StrategyProbeAttemptRound::FullMatrix);
}
