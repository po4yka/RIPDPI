use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::candidates::StrategyCandidateSpec;
use crate::classification::{classified_failure_probe_result, classify_strategy_probe_baseline_observations};
use crate::execution::{CandidateExecution, StrategyLaneExecutor};
use crate::observations::observations_for_results;
use crate::types::{DomainTarget, StrategyProbeAttemptRound, StrategyProbeProgressLane};

use super::super::super::super::runtime::{ExecutionPlan, ExecutionRuntime, ExecutionStageRunner, RunnerArtifacts};
use super::super::support::strategy_probe_live_progress_with_targets;
use super::StrategyTcpRunner;

pub(super) struct BaselineRun<'a> {
    pub(super) spec: &'a StrategyCandidateSpec,
    pub(super) execution: CandidateExecution,
    pub(super) hostfake_family_succeeded: bool,
}

pub(super) fn run_baseline_candidate<'a>(
    runner: &StrategyTcpRunner,
    plan: &ExecutionPlan,
    runtime: &mut ExecutionRuntime,
    domain_targets: &[DomainTarget],
    tcp_specs: &'a [StrategyCandidateSpec],
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> Option<BaselineRun<'a>> {
    let strategy_plan = plan.strategy.as_ref().expect("strategy plan");
    let baseline_spec = tcp_specs.first().expect("tcp candidate");
    runtime.publish_strategy_probe_candidate_started(
        plan,
        runner.phase(),
        StrategyProbeProgressLane::Tcp,
        1,
        tcp_specs.len(),
        baseline_spec.id,
        baseline_spec.label,
        format!("Testing TCP candidate {}", baseline_spec.label),
    );
    let mut baseline_execution = runner.lane_executor.execute_tcp_candidate(
        baseline_spec,
        domain_targets,
        strategy_plan.runtime_context.as_ref(),
        strategy_plan.probe_seed,
        tls_verifier,
        plan.request.diagnostic_tls_keylog_path.as_deref(),
        runtime.cancel_token(),
    );
    for attempt in &mut baseline_execution.attempts {
        attempt.round = StrategyProbeAttemptRound::Baseline;
    }
    runtime.strategy.attempts.append(&mut baseline_execution.attempts);
    if baseline_execution.cancelled {
        return None;
    }

    let baseline_observations = observations_for_results(&baseline_execution.results);
    runtime.strategy.baseline_failure = classify_strategy_probe_baseline_observations(&baseline_observations);
    let mut baseline_results = baseline_execution.results.clone();
    if let Some(failure) = &runtime.strategy.baseline_failure {
        baseline_results.push(classified_failure_probe_result(baseline_spec.label, failure));
        // Publish the DPI failure class so the UI can show it as a badge.
        runtime.publish_progress(
            plan,
            runner.phase(),
            runtime.completed_steps,
            format!("DPI: {}", failure.class.as_str()),
            Some("baseline_failure_class".to_string()),
            Some(failure.class.as_str().to_string()),
            None,
        );
    }
    runtime.record_step(
        plan,
        runner.phase(),
        format!("Tested {}", baseline_spec.label),
        Some(baseline_spec.label.to_string()),
        Some(baseline_execution.summary.outcome.clone()),
        Some(strategy_probe_live_progress_with_targets(
            StrategyProbeProgressLane::Tcp,
            1,
            tcp_specs.len(),
            baseline_spec.id,
            baseline_spec.label,
            baseline_execution.summary.succeeded_targets,
            baseline_execution.summary.total_targets,
        )),
        RunnerArtifacts::from_results(
            baseline_results,
            "strategy_probe",
            if runtime.strategy.baseline_failure.is_some() { "warn" } else { "info" },
            format!("Testing TCP candidate {}", baseline_spec.label),
        ),
    );

    let hostfake_family_succeeded = baseline_execution.summary.family == "hostfake"
        && baseline_execution.summary.succeeded_targets == baseline_execution.summary.total_targets;
    Some(BaselineRun { spec: baseline_spec, execution: baseline_execution, hostfake_family_succeeded })
}
