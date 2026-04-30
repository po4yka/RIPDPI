use std::sync::Arc;
use std::thread;
use std::time::Duration;

use rustls::client::danger::ServerCertVerifier;

use crate::candidates::{
    build_quic_candidates_for_suite, candidate_pause_ms, probe_fake_ttl_capability, probe_ip_fragmentation_capabilities,
};
use crate::classification::{filter_quic_candidates_for_failure, interleave_candidate_families, next_candidate_index};
use crate::execution::{
    execute_quic_candidate, skipped_candidate_summary, winning_candidate_index, CandidateRuntimeLauncher,
};
use crate::types::StrategyProbeProgressLane;
use crate::util::stable_probe_hash;

use super::super::super::runtime::{
    ExecutionPlan, ExecutionRuntime, ExecutionStageId, ExecutionStageRunner, RunnerArtifacts, RunnerOutcome,
};
use super::strategy_support::{
    annotate_emitter_execution, capability_available, capability_suffix, missing_capability_rationale,
    strategy_probe_live_progress_with_targets, FamilyFailureTracker,
};

pub(in crate::engine::runners) struct StrategyQuicRunner {
    candidate_runtime_launcher: Arc<dyn CandidateRuntimeLauncher>,
}

impl StrategyQuicRunner {
    pub(in crate::engine::runners) fn new(candidate_runtime_launcher: Arc<dyn CandidateRuntimeLauncher>) -> Self {
        Self { candidate_runtime_launcher }
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
        plan.strategy.as_ref().map_or(0, |strategy| strategy.suite.quic_candidates.len())
    }

    fn run(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome {
        let Some(strategy_plan) = plan.strategy.as_ref() else {
            return RunnerOutcome::Completed;
        };
        let tcp_winner_id = winning_candidate_index(&runtime.strategy.tcp_candidates)
            .map(|i| runtime.strategy.tcp_candidates[i].id.as_str());
        let tcp_winner_spec = tcp_winner_id
            .and_then(|id| strategy_plan.suite.tcp_candidates.iter().find(|s| s.id == id))
            .or_else(|| strategy_plan.suite.tcp_candidates.first());
        let Some(tcp_winner_spec) = tcp_winner_spec else {
            return RunnerOutcome::Completed;
        };
        let quic_specs = filter_quic_candidates_for_failure(
            build_quic_candidates_for_suite(&strategy_plan.suite_id, &tcp_winner_spec.config)
                .unwrap_or_else(|_| strategy_plan.suite.quic_candidates.clone()),
            runtime.strategy.baseline_failure.as_ref().map(|value| value.class),
        );
        // Use encrypted-DNS-resolved targets when DNS tampering was detected.
        // Clone to avoid holding an immutable borrow on `runtime` across mutable calls.
        let quic_targets =
            runtime.strategy.dns_override_quic_targets.clone().unwrap_or_else(|| plan.request.quic_targets.clone());
        let quic_candidate_total = quic_specs.len();
        if quic_candidate_total == 0 {
            return RunnerOutcome::Completed;
        }
        let mut pending_quic_specs =
            interleave_candidate_families(quic_specs.clone(), stable_probe_hash(strategy_plan.probe_seed, "quic"));
        if let Some(max) = strategy_plan.max_candidates {
            if pending_quic_specs.len() > max {
                pending_quic_specs.truncate(max);
            }
        }
        let fake_ttl_available = probe_fake_ttl_capability();
        let ipfrag_caps = probe_ip_fragmentation_capabilities();
        let mut quic_family_succeeded = false;
        let mut quic_failure_tracker = FamilyFailureTracker::new(strategy_plan.suite.family_failure_threshold);
        while !pending_quic_specs.is_empty() {
            let candidate_index = runtime.strategy.quic_candidates.len() + 1;
            let spec = pending_quic_specs
                .remove(next_candidate_index(&pending_quic_specs, quic_failure_tracker.blocked_family()));
            if runtime.is_cancelled() || runtime.is_past_deadline() {
                tracing::warn!("strategy probe: QUIC suite terminated early");
                break;
            }
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
            if strategy_plan.suite.short_circuit_quic_burst && spec.family == "quic_burst" && quic_family_succeeded {
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

            let execution = execute_quic_candidate(
                self.candidate_runtime_launcher.as_ref(),
                &spec,
                &quic_targets,
                strategy_plan.runtime_context.as_ref(),
                strategy_plan.probe_seed,
                runtime.cancel_token(),
            );
            if execution.cancelled {
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
            // "QUIC disabled" is a baseline candidate: failure is the expected
            // outcome (site unreachable without QUIC), so log at info, not warn.
            let log_level = if failed && spec.id != "quic_disabled" { "warn" } else { "info" };
            runtime.record_step(
                plan,
                self.phase(),
                format!("Tested {}", spec.label),
                Some(spec.label.to_string()),
                Some(summary.outcome.clone()),
                Some(strategy_probe_live_progress_with_targets(
                    StrategyProbeProgressLane::Quic,
                    candidate_index,
                    quic_candidate_total,
                    spec.id,
                    spec.label,
                    summary.succeeded_targets,
                    summary.total_targets,
                )),
                RunnerArtifacts::from_results(
                    execution.results.clone(),
                    "strategy_probe",
                    log_level,
                    format!("Testing QUIC candidate {}", spec.label),
                ),
            );
            runtime.strategy.quic_candidates.push(summary);
            if runtime.is_past_deadline() {
                tracing::warn!("strategy probe: QUIC suite deadline-terminated");
                break;
            }
            quic_failure_tracker.record(spec.family, failed);
            if !pending_quic_specs.is_empty() {
                thread::sleep(Duration::from_millis(candidate_pause_ms(strategy_plan.probe_seed, &spec, failed)));
            }
        }
        RunnerOutcome::Completed
    }
}
