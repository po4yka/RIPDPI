mod baseline;
mod batch_execution;
mod candidate_ordering;
mod capability_gating;
mod pilot_qualification;
mod quic_pivot;
mod result_recording;
mod runner;

use std::sync::Arc;
use std::thread;
use std::time::Duration;

use rustls::client::danger::ServerCertVerifier;

use crate::candidates::candidate_pause_ms;
use crate::types::{StrategyProbeAttemptRound, StrategyProbeProgressLane};

use super::super::super::runtime::{
    ExecutionPlan, ExecutionRuntime, ExecutionStageId, ExecutionStageRunner, RunnerOutcome,
};
use super::support::FamilyFailureTracker;

use self::baseline::run_baseline_candidate;
use self::batch_execution::{ROUND2_PARALLELISM, execute_candidate_batch, select_next_candidate_batch};
use self::candidate_ordering::ordered_pending_tcp_candidates;
use self::capability_gating::{candidate_not_applicable, probe_tcp_capabilities};
use self::pilot_qualification::qualify_pilot_candidates;
use self::quic_pivot::skip_for_confirmed_quic;
use self::result_recording::{
    record_executed_candidate, record_hostfake_short_circuit, record_not_applicable_candidate,
};

pub(in crate::engine::runners) use self::runner::StrategyTcpRunner;

impl ExecutionStageRunner for StrategyTcpRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::StrategyTcpCandidates
    }

    fn phase(&self) -> &'static str {
        "tcp"
    }

    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        plan.strategy.as_ref().map_or(0, |strategy| strategy.suite.tcp_candidates.len())
    }

    fn run(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome {
        let Some(strategy_plan) = plan.strategy.as_ref() else {
            return RunnerOutcome::Completed;
        };
        let tcp_specs = &strategy_plan.suite.tcp_candidates;
        if tcp_specs.is_empty() {
            return RunnerOutcome::Completed;
        }
        if skip_for_confirmed_quic(plan, runtime, tcp_specs) {
            return RunnerOutcome::Completed;
        }
        // Use encrypted-DNS-resolved targets when DNS tampering was detected.
        // Clone to avoid holding an immutable borrow on `runtime` across mutable calls.
        let domain_targets =
            runtime.strategy.dns_override_domain_targets.clone().unwrap_or_else(|| plan.request.domain_targets.clone());
        let tcp_candidate_total = tcp_specs.len();
        let baseline_run = run_baseline_candidate(self, plan, runtime, &domain_targets, tcp_specs, tls_verifier);
        let Some(baseline_run) = baseline_run else {
            return RunnerOutcome::Cancelled;
        };
        let mut hostfake_family_succeeded = baseline_run.hostfake_family_succeeded;
        let baseline_results = baseline_run.execution.results;
        runtime.strategy.tcp_candidates.push(baseline_run.execution.summary);

        if tcp_specs.len() > 1 {
            thread::sleep(Duration::from_millis(candidate_pause_ms(
                strategy_plan.probe_seed,
                baseline_run.spec,
                runtime.strategy.baseline_failure.is_some(),
            )));
        }

        let capabilities = probe_tcp_capabilities(&baseline_results, runtime.strategy.baseline_failure.as_ref());
        let mut pending_tcp_specs = ordered_pending_tcp_candidates(
            tcp_specs,
            &baseline_results,
            runtime.strategy.baseline_failure.as_ref(),
            strategy_plan.probe_seed,
            strategy_plan.max_candidates,
            capabilities,
        );
        pending_tcp_specs = qualify_pilot_candidates(
            self,
            plan,
            runtime,
            pending_tcp_specs,
            &domain_targets,
            capabilities,
            tls_verifier,
        );
        let mut tcp_failure_tracker = FamilyFailureTracker::new(strategy_plan.suite.family_failure_threshold);
        let planned_count = tcp_specs.len();
        let mut executed_count = 1usize; // baseline already executed

        // Round 2: test up to 2 candidates concurrently to reduce wall-clock time.
        while !pending_tcp_specs.is_empty() {
            if runtime.is_cancelled() || runtime.is_past_deadline() {
                tracing::warn!(
                    executed = executed_count,
                    planned = planned_count,
                    "strategy probe: TCP suite terminated early"
                );
                break;
            }

            // Pick up to ROUND2_PARALLELISM candidates, skipping blocked families.
            let batch = select_next_candidate_batch(
                &mut pending_tcp_specs,
                &tcp_failure_tracker,
                runtime.strategy.tcp_candidates.len(),
                ROUND2_PARALLELISM,
            );

            // Pre-filter: handle skip/not-applicable candidates synchronously,
            // collect candidates that need actual execution for parallel testing.
            let mut to_execute = Vec::new();
            for (candidate_index, spec) in batch {
                tracing::debug!(candidate = spec.id, label = spec.label, "strategy probe: testing TCP candidate");
                runtime.publish_strategy_probe_candidate_started(
                    plan,
                    self.phase(),
                    StrategyProbeProgressLane::Tcp,
                    candidate_index,
                    tcp_candidate_total,
                    spec.id,
                    spec.label,
                    format!("Testing TCP candidate {}", spec.label),
                );
                if strategy_plan.suite.short_circuit_hostfake && spec.family == "hostfake" && hostfake_family_succeeded
                {
                    record_hostfake_short_circuit(
                        runtime,
                        plan,
                        self.phase(),
                        &spec,
                        candidate_index,
                        tcp_candidate_total,
                        domain_targets.len(),
                    );
                    continue;
                }
                if let Some(not_applicable) = candidate_not_applicable(&spec, capabilities) {
                    record_not_applicable_candidate(
                        runtime,
                        plan,
                        self.phase(),
                        &spec,
                        candidate_index,
                        tcp_candidate_total,
                        not_applicable,
                    );
                    continue;
                }
                to_execute.push((candidate_index, spec));
            }

            if to_execute.is_empty() {
                continue;
            }

            // Execute candidates in parallel using thread::scope.
            let exec_results = execute_candidate_batch(self, plan, runtime, to_execute, &domain_targets, tls_verifier);

            // Merge results back into the runtime sequentially.
            let mut any_cancelled = false;
            for (candidate_index, spec, mut execution) in exec_results {
                if execution.cancelled {
                    for attempt in &mut execution.attempts {
                        attempt.round = StrategyProbeAttemptRound::FullMatrix;
                    }
                    runtime.strategy.attempts.append(&mut execution.attempts);
                    any_cancelled = true;
                    continue;
                }
                let record = record_executed_candidate(
                    runtime,
                    plan,
                    self.phase(),
                    &spec,
                    candidate_index,
                    tcp_candidate_total,
                    execution,
                    capabilities,
                );
                if record.hostfake_family_succeeded {
                    hostfake_family_succeeded = true;
                }
                executed_count += 1;
                tcp_failure_tracker.record(spec.family, record.failed);
                if tcp_failure_tracker.blocked_family().is_some() {
                    tracing::debug!(
                        candidate = spec.id,
                        family = spec.family,
                        "strategy probe: candidate skipped, family blocked"
                    );
                }
            }
            if any_cancelled {
                return RunnerOutcome::Cancelled;
            }
            // Break out with partial results if the scan deadline has passed.
            if runtime.is_past_deadline() {
                tracing::warn!(
                    executed = executed_count,
                    planned = planned_count,
                    "strategy probe: TCP suite deadline-terminated"
                );
                break;
            }
            if !pending_tcp_specs.is_empty() {
                // Brief pause between batches to avoid overwhelming the network.
                thread::sleep(Duration::from_millis(candidate_pause_ms(
                    strategy_plan.probe_seed,
                    // Use the first spec's seed for pause calculation.
                    tcp_specs.first().expect("tcp candidate"),
                    false,
                )));
            }
        }
        let skipped_count = planned_count.saturating_sub(executed_count);
        tracing::info!(
            executed = executed_count,
            planned = planned_count,
            skipped = skipped_count,
            "strategy probe: TCP suite completed"
        );
        RunnerOutcome::Completed
    }
}
