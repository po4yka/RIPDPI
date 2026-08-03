use std::{sync::Arc, thread};

use rustls::client::danger::ServerCertVerifier;

use crate::candidates::StrategyCandidateSpec;
use crate::classification::next_candidate_index;
use crate::execution::{CandidateExecution, StrategyLaneExecutor};
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
        let idx = next_candidate_index(pending_tcp_specs, tracker.blocked_family());
        let spec = pending_tcp_specs.remove(idx);
        let candidate_index = recorded_candidate_count + batch.len() + 1;
        batch.push((candidate_index, spec));
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
    let strategy_plan = plan.strategy.as_ref().expect("strategy plan");
    let cancel_token = runtime.cancel_token();
    let deadline = ripdpi_diagnostics_contracts::util::active_scan_io_deadline();
    thread::scope(|s| {
        let handles: Vec<_> = to_execute
            .into_iter()
            .map(|(candidate_index, spec)| {
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
        handles.into_iter().map(|h| h.join().expect("tcp candidate thread panicked")).collect()
    })
}
