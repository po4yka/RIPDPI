use crate::candidates::StrategyCandidateSpec;
use crate::execution::not_applicable_candidate_execution;
use crate::types::StrategyProbeProgressLane;

use super::super::super::super::runtime::{ExecutionPlan, ExecutionRuntime, RunnerArtifacts};

mod live;

pub(in crate::engine::runners::strategy) use self::live::strategy_probe_live_progress_with_targets;

pub(in crate::engine::runners::strategy) fn record_not_applicable_tcp_candidate(
    runtime: &mut ExecutionRuntime,
    plan: &ExecutionPlan,
    phase: &str,
    spec: &StrategyCandidateSpec,
    candidate_index: usize,
    candidate_total: usize,
    reason: &str,
    log_suffix: &str,
) {
    let execution = not_applicable_candidate_execution(spec, plan.request.domain_targets.len() * 2, 3, reason);
    let outcome = execution.summary.outcome.clone();
    runtime.strategy.tcp_candidates.push(execution.summary);
    runtime.record_step(
        plan,
        phase,
        format!("Marked {} as not applicable{}", spec.label, log_suffix),
        Some(spec.label.to_string()),
        Some(outcome),
        Some(strategy_probe_live_progress_with_targets(
            StrategyProbeProgressLane::Tcp,
            candidate_index,
            candidate_total,
            spec.id,
            spec.label,
            0,
            0,
        )),
        RunnerArtifacts::from_results(
            execution.results,
            "strategy_probe",
            "debug",
            format!("Skipped execution for {}{}", spec.label, log_suffix),
        ),
    );
}
