use std::sync::Arc;
use std::time::Duration;

use rustls::client::danger::ServerCertVerifier;

use crate::candidates::{candidate_pause_ms, probe_fake_ttl_capability, probe_ip_fragmentation_capabilities};
use crate::classification::next_candidate_index;
use crate::execution::{
    CandidateRuntimeLauncher, CandidateRuntimeSupervisor, DefaultStrategyLaneExecutor, StrategyLaneExecutor,
    skipped_candidate_summary, wait_candidate_pause,
};
use crate::types::StrategyProbeProgressLane;

use super::super::super::runtime::{
    ExecutionPlan, ExecutionRuntime, ExecutionStageId, ExecutionStageRunner, RunnerArtifacts, RunnerOutcome,
};
use super::support::{
    FamilyFailureTracker, annotate_emitter_execution, capability_available, capability_suffix,
    missing_capability_rationale, strategy_probe_live_progress_with_targets,
};
use super::tcp::execution_should_stop;

mod selection;

pub(in crate::engine::runners) struct StrategyQuicRunner {
    lane_executor: DefaultStrategyLaneExecutor,
}

impl StrategyQuicRunner {
    pub(in crate::engine::runners) fn new(
        candidate_runtime_launcher: Arc<dyn CandidateRuntimeLauncher>,
        supervisor: Arc<CandidateRuntimeSupervisor>,
    ) -> Self {
        Self { lane_executor: DefaultStrategyLaneExecutor::new(candidate_runtime_launcher, supervisor) }
    }
}

impl ExecutionStageRunner for StrategyQuicRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::StrategyQuicCandidates
    }

    fn phase(&self) -> &'static str {
        "quic"
    }

    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        plan.strategy_candidate_steps().1
    }

    fn run(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome {
        let deadline = [runtime.stage_deadline(), runtime.scan_deadline()].into_iter().flatten().min();
        ripdpi_diagnostics_contracts::util::with_scan_io_deadline(deadline, || {
            let Some(strategy_plan) = plan.strategy.as_ref() else {
                return RunnerOutcome::Completed;
            };
            let fake_ttl_available = probe_fake_ttl_capability();
            let ipfrag_caps = probe_ip_fragmentation_capabilities();
            let Some(prepared) = selection::prepare_quic_candidates(plan, runtime, fake_ttl_available, ipfrag_caps)
            else {
                return RunnerOutcome::Completed;
            };
            let selection::PreparedQuicCandidates {
                mut pending_quic_specs,
                quic_targets,
                quic_candidate_total,
                candidate_ids_omitted_by_cap,
            } = prepared;
            runtime.record_candidate_cap_omissions(candidate_ids_omitted_by_cap);
            if quic_candidate_total == 0 {
                return RunnerOutcome::Completed;
            }
            let mut quic_family_succeeded = false;
            let mut quic_failure_tracker = FamilyFailureTracker::new(strategy_plan.suite.family_failure_threshold);
            while !pending_quic_specs.is_empty() {
                if execution_should_stop(runtime) {
                    return RunnerOutcome::Cancelled;
                }
                let candidate_index = runtime.strategy.quic_candidates.len() + 1;
                let spec = pending_quic_specs
                    .remove(next_candidate_index(&pending_quic_specs, quic_failure_tracker.blocked_family()));
                runtime.publish_strategy_probe_candidate_started(
                    plan,
                    self.phase(),
                    StrategyProbeProgressLane::Quic,
                    candidate_index,
                    quic_candidate_total,
                    spec.id,
                    spec.label,
                    format!("Testing QUIC candidate {}", spec.label),
                );
                if strategy_plan.suite.short_circuit_quic_burst && spec.family == "quic_burst" && quic_family_succeeded
                {
                    let summary = skipped_candidate_summary(
                        &spec,
                        quic_targets.len(),
                        2,
                        "Earlier QUIC burst candidate already achieved full success",
                    );
                    runtime.strategy.quic_candidates.push(summary.clone());
                    runtime.record_skipped_strategy_probe_candidate(
                        plan,
                        self.phase(),
                        StrategyProbeProgressLane::Quic,
                        candidate_index,
                        quic_candidate_total,
                        &summary.id,
                        &summary.label,
                        Some(summary.outcome.clone()),
                        format!("Skipped {}", summary.label),
                    );
                    continue;
                }
                if let Some(capability) = spec
                    .requires_capabilities
                    .iter()
                    .copied()
                    .find(|&capability| !capability_available(capability, fake_ttl_available, ipfrag_caps))
                {
                    let rationale = format!("{}{}", missing_capability_rationale(&spec), capability_suffix(capability));
                    let summary = skipped_candidate_summary(&spec, quic_targets.len(), 2, &rationale);
                    runtime.strategy.quic_candidates.push(summary.clone());
                    runtime.record_skipped_strategy_probe_candidate(
                        plan,
                        self.phase(),
                        StrategyProbeProgressLane::Quic,
                        candidate_index,
                        quic_candidate_total,
                        &summary.id,
                        &summary.label,
                        Some(summary.outcome.clone()),
                        format!("Skipped {}", summary.label),
                    );
                    continue;
                }

                let execution = self.lane_executor.execute_quic_candidate(
                    &spec,
                    &quic_targets,
                    strategy_plan.runtime_context.as_ref(),
                    strategy_plan.probe_seed,
                    runtime.cancel_token(),
                );
                if execution.cancelled {
                    runtime.observations.extend(crate::observations::observations_for_results(&execution.results));
                    runtime.results.extend(execution.results);
                    return RunnerOutcome::Cancelled;
                }
                let mut summary = execution.summary;
                annotate_emitter_execution(&mut summary, &spec, fake_ttl_available, ipfrag_caps);
                if summary.family == "quic_burst"
                    && summary.succeeded_targets == summary.total_targets
                    && summary.total_targets > 0
                {
                    quic_family_succeeded = true;
                }
                let failed = summary.outcome == "failed";
                let log_level = if failed && spec.id != "quic_disabled" { "warn" } else { "info" };
                let outcome = summary.outcome.clone();
                let progress = strategy_probe_live_progress_with_targets(
                    StrategyProbeProgressLane::Quic,
                    candidate_index,
                    quic_candidate_total,
                    spec.id,
                    spec.label,
                    summary.succeeded_targets,
                    summary.total_targets,
                );
                runtime.strategy.quic_candidates.push(summary);
                runtime.record_step(
                    plan,
                    self.phase(),
                    format!("Tested {}", spec.label),
                    Some(spec.label.to_string()),
                    Some(outcome),
                    Some(progress),
                    RunnerArtifacts::from_results(
                        execution.results,
                        "strategy_probe",
                        log_level,
                        format!("Testing QUIC candidate {}", spec.label),
                    ),
                );
                quic_failure_tracker.record(spec.family, failed);
                if !pending_quic_specs.is_empty()
                    && !wait_candidate_pause(
                        Duration::from_millis(candidate_pause_ms(strategy_plan.probe_seed, &spec, failed)),
                        runtime.cancel_token(),
                    )
                {
                    return RunnerOutcome::Cancelled;
                }
            }
            RunnerOutcome::Completed
        })
    }
}
