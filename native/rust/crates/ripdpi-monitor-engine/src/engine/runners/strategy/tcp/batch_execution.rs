use std::{sync::Arc, thread};

use rustls::client::danger::ServerCertVerifier;

use crate::candidates::StrategyCandidateSpec;
use crate::classification::next_candidate_index;
use crate::execution::{CandidateExecution, StrategyLaneExecutor, failed_candidate_execution};
use crate::types::DomainTarget;

use super::super::super::super::runtime::{ExecutionPlan, ExecutionRuntime};
use super::super::support::FamilyFailureTracker;
use super::StrategyTcpRunner;

pub(super) const ROUND2_PARALLELISM: usize = 2;

pub(super) fn select_next_candidate_batch<'a>(
    pending_tcp_specs: &mut Vec<StrategyCandidateSpec>,
    tracker: &FamilyFailureTracker<'a>,
    recorded_candidate_count: usize,
    parallelism: usize,
) -> Vec<(usize, StrategyCandidateSpec)> {
    let mut batch = Vec::with_capacity(parallelism);
    while batch.len() < parallelism && !pending_tcp_specs.is_empty() {
        let spec = pending_tcp_specs.remove(next_candidate_index(pending_tcp_specs, tracker.blocked_family()));
        batch.push((recorded_candidate_count + batch.len() + 1, spec));
    }
    batch
}

pub(super) fn execute_candidate_batch(
    runner: &StrategyTcpRunner,
    plan: &ExecutionPlan,
    runtime: &ExecutionRuntime,
    to_execute: Vec<(usize, StrategyCandidateSpec)>,
    domain_targets: &[DomainTarget],
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> Vec<(usize, StrategyCandidateSpec, CandidateExecution)> {
    let (strategy_plan, cancel_token) = (plan.strategy.as_ref().expect("strategy plan"), runtime.cancel_token());
    let deadline = ripdpi_diagnostics_contracts::util::active_scan_io_deadline();
    thread::scope(|s| {
        let handles: Vec<_> = to_execute
            .iter()
            .map(|(candidate_index, spec)| {
                let candidate_index = *candidate_index;
                let spec = spec.clone();
                s.spawn(move || {
                    ripdpi_diagnostics_contracts::util::with_scan_io_deadline(deadline, || {
                        let execution = runner.lane_executor.execute_tcp_candidate(
                            &spec,
                            domain_targets,
                            strategy_plan.runtime_context.as_ref(),
                            strategy_plan.probe_seed,
                            tls_verifier,
                            plan.request.diagnostic_tls_keylog_path.as_deref(),
                            cancel_token,
                        );
                        (candidate_index, spec, execution)
                    })
                })
            })
            .collect();
        // A panicking candidate worker must not discard its siblings' completed
        // executions or abort the remaining stages; degrade it to a failed
        // execution like the per-probe containment in domain_probe does.
        super::worker_join::join_with_panic_fallback(handles, |index| {
            let (candidate_index, spec) = &to_execute[index];
            (
                *candidate_index,
                spec.clone(),
                failed_candidate_execution(
                    spec,
                    domain_targets.len() * 2,
                    3,
                    "candidate probe worker panicked".to_string(),
                ),
            )
        })
    })
}
