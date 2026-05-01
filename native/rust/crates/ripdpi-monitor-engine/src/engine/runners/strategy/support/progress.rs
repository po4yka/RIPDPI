use crate::candidates::StrategyCandidateSpec;
use crate::execution::not_applicable_candidate_execution;
use crate::types::{StrategyProbeLiveProgress, StrategyProbeProgressLane};

use super::super::super::super::runtime::{ExecutionPlan, ExecutionRuntime, RunnerArtifacts};

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
    runtime.record_step(
        plan,
        phase,
        format!("Marked {} as not applicable{}", spec.label, log_suffix),
        Some(spec.label.to_string()),
        Some(execution.summary.outcome.clone()),
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
            execution.results.clone(),
            "strategy_probe",
            "debug",
            format!("Skipped execution for {}{}", spec.label, log_suffix),
        ),
    );
    runtime.strategy.tcp_candidates.push(execution.summary);
}

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
