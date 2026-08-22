use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::thread;

use rustls::client::danger::ServerCertVerifier;

use crate::candidates::StrategyCandidateSpec;
use crate::execution::{CandidateExecution, StrategyLaneExecutor, failed_candidate_execution};

use super::super::super::super::runtime::{ExecutionPlan, ExecutionRuntime};
use super::StrategyTcpRunner;

pub(super) fn execute_pilot_batch(
    runner: &StrategyTcpRunner,
    plan: &ExecutionPlan,
    runtime: &ExecutionRuntime,
    batch: &[StrategyCandidateSpec],
    qualifier_targets: &[crate::types::DomainTarget],
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> Vec<(StrategyCandidateSpec, Option<CandidateExecution>)> {
    let strategy_plan = plan.strategy.as_ref().expect("strategy plan");
    let cancel_token = runtime.cancel_token();
    let deadline = ripdpi_diagnostics_contracts::util::active_scan_io_deadline();
    thread::scope(|scope| {
        let handles: Vec<_> = batch
            .iter()
            .map(|spec| {
                let spec_clone = spec.clone();
                scope.spawn(move || {
                    ripdpi_diagnostics_contracts::util::with_scan_io_deadline(deadline, || {
                        if cancel_token.load(Ordering::Acquire) {
                            return (spec_clone, None);
                        }
                        let execution = runner.lane_executor.execute_tcp_candidate(
                            &spec_clone,
                            qualifier_targets,
                            strategy_plan.runtime_context.as_ref(),
                            strategy_plan.probe_seed,
                            tls_verifier,
                            plan.request.diagnostic_tls_keylog_path.as_deref(),
                            cancel_token,
                        );
                        (spec_clone, Some(execution))
                    })
                })
            })
            .collect();
        // A panicking qualifier worker must not discard its siblings' results or
        // unwind through the runner; degrade it to a failed execution so the
        // candidate is eliminated by the normal Round 1 rules.
        super::worker_join::join_with_panic_fallback(handles, |index| {
            let spec = &batch[index];
            (
                spec.clone(),
                Some(failed_candidate_execution(
                    spec,
                    qualifier_targets.len() * 2,
                    3,
                    "candidate probe worker panicked".to_string(),
                )),
            )
        })
    })
}
