mod baseline;
mod batch_execution;
mod batch_pipeline;
mod candidate_ordering;
mod capability_gating;
mod pilot_execution;
mod pilot_qualification;
mod quic_pivot;
mod result_recording;
mod runner;
mod worker_join;

use std::sync::Arc;
use std::thread;
use std::time::Duration;

use rustls::client::danger::ServerCertVerifier;

use crate::candidates::candidate_pause_ms;

use super::super::super::runtime::{
    ExecutionPlan, ExecutionRuntime, ExecutionStageId, ExecutionStageRunner, RunnerOutcome,
};
use super::support::FamilyFailureTracker;

use self::baseline::run_baseline_candidate;
use self::batch_execution::{ROUND2_PARALLELISM, select_next_candidate_batch};
use self::batch_pipeline::{BatchFilterContext, merge_batch_results, select_executable_candidates};
use self::candidate_ordering::ordered_pending_tcp_candidates;
use self::capability_gating::probe_tcp_capabilities;
use self::pilot_qualification::qualify_pilot_candidates;
use self::quic_pivot::skip_for_confirmed_quic;
pub(in crate::engine::runners) use self::runner::StrategyTcpRunner;
use self::worker_join::execute_candidate_batch;

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
        let deadline = runtime.stage_deadline().or_else(|| runtime.scan_deadline());
        ripdpi_diagnostics_contracts::util::with_scan_io_deadline(deadline, || {
            self.run_with_deadline(plan, runtime, tls_verifier)
        })
    }
}

impl StrategyTcpRunner {
    fn run_with_deadline(
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
        if execution_should_stop(runtime) {
            return RunnerOutcome::Cancelled;
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

        if tcp_specs.len() > 1
            && !sleep_within_active_deadline(Duration::from_millis(candidate_pause_ms(
                strategy_plan.probe_seed,
                baseline_run.spec,
                runtime.strategy.baseline_failure.is_some(),
            )))
        {
            return RunnerOutcome::Cancelled;
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
            if execution_should_stop(runtime) {
                tracing::warn!(
                    executed = executed_count,
                    planned = planned_count,
                    "strategy probe: TCP suite terminated early"
                );
                return RunnerOutcome::Cancelled;
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
            let filter_context = BatchFilterContext {
                plan,
                phase: self.phase(),
                short_circuit_hostfake: strategy_plan.suite.short_circuit_hostfake,
                hostfake_family_succeeded,
                capabilities,
                tcp_candidate_total,
                domain_target_count: domain_targets.len(),
            };
            let to_execute = select_executable_candidates(runtime, batch, filter_context);

            if to_execute.is_empty() {
                continue;
            }

            // Execute candidates in parallel using thread::scope.
            let exec_results = execute_candidate_batch(self, plan, runtime, to_execute, &domain_targets, tls_verifier);

            // Merge results back into the runtime sequentially.
            let merge_outcome = merge_batch_results(
                runtime,
                plan,
                self.phase(),
                exec_results,
                &mut tcp_failure_tracker,
                &mut executed_count,
                capabilities,
                tcp_candidate_total,
            );
            if merge_outcome.hostfake_family_succeeded {
                hostfake_family_succeeded = true;
            }
            if merge_outcome.any_cancelled {
                return RunnerOutcome::Cancelled;
            }
            if execution_should_stop(runtime) {
                tracing::warn!(
                    executed = executed_count,
                    planned = planned_count,
                    "strategy probe: TCP suite deadline-terminated"
                );
                return RunnerOutcome::Cancelled;
            }
            if !pending_tcp_specs.is_empty()
                && !sleep_within_active_deadline(Duration::from_millis(candidate_pause_ms(
                    strategy_plan.probe_seed,
                    // Use the first spec's seed for pause calculation.
                    tcp_specs.first().expect("tcp candidate"),
                    false,
                )))
            {
                // Brief pause between batches to avoid overwhelming the network.
                return RunnerOutcome::Cancelled;
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

pub(super) fn execution_should_stop(runtime: &ExecutionRuntime) -> bool {
    runtime.is_cancelled() || runtime.is_past_deadline() || runtime.is_past_stage_deadline()
}

pub(super) fn sleep_within_active_deadline(delay: Duration) -> bool {
    let Ok(permitted) = ripdpi_diagnostics_contracts::util::bounded_scan_io_timeout(delay) else {
        return false;
    };
    thread::sleep(permitted);
    permitted == delay
}

#[cfg(test)]
mod deadline_pacing_tests {
    use super::*;
    use std::time::Instant;

    #[test]
    fn sleeps_full_delay_without_ambient_deadline() {
        assert!(sleep_within_active_deadline(Duration::from_millis(1)));
    }

    #[test]
    fn truncates_inter_candidate_pause_at_active_deadline() {
        let deadline = Instant::now() + Duration::from_millis(5);
        ripdpi_diagnostics_contracts::util::with_scan_io_deadline(Some(deadline), || {
            let started = Instant::now();
            assert!(!sleep_within_active_deadline(Duration::from_secs(30)));
            assert!(started.elapsed() < Duration::from_secs(5));
            // Once the deadline has elapsed the pause must report exhaustion.
            std::thread::sleep(Duration::from_millis(10));
            assert!(!sleep_within_active_deadline(Duration::from_secs(30)));
        });
    }
}
