use std::{sync::Arc, thread};

use rustls::client::danger::ServerCertVerifier;

use crate::candidates::StrategyCandidateSpec;
use crate::execution::{CandidateExecution, StrategyLaneExecutor, failed_candidate_execution};
use crate::types::DomainTarget;

use super::super::super::super::runtime::{ExecutionPlan, ExecutionRuntime};
use super::StrategyTcpRunner;

/// Joins scoped worker handles, converting an individual worker panic into a
/// caller-provided fallback value instead of propagating through `expect`.
///
/// Sibling workers that already completed keep their results: joining continues
/// past a panicked handle, mirroring the containment contract of
/// `execution::lanes::tcp::domain_probe`.
pub(super) fn join_with_panic_fallback<T, F>(handles: Vec<thread::ScopedJoinHandle<'_, T>>, mut on_panic: F) -> Vec<T>
where
    F: FnMut(usize) -> T,
{
    handles
        .into_iter()
        .enumerate()
        .map(|(index, handle)| match handle.join() {
            Ok(value) => value,
            Err(_) => on_panic(index),
        })
        .collect()
}

/// Executes one batch of TCP candidates in parallel scoped threads under the
/// active scan I/O deadline, degrading a panicking worker to a failed
/// execution instead of discarding its siblings' results.
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
        join_with_panic_fallback(handles, |index| {
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn panicking_worker_degrades_to_fallback_and_keeps_sibling_results() {
        thread::scope(|scope| {
            let handles =
                vec![scope.spawn(|| 1u32), scope.spawn(|| panic!("candidate worker exploded")), scope.spawn(|| 3u32)];
            let results = join_with_panic_fallback(handles, |index| 100 + index as u32);
            assert_eq!(results, vec![1, 101, 3]);
        });
    }

    #[test]
    fn completed_workers_are_returned_in_spawn_order() {
        thread::scope(|scope| {
            let handles: Vec<_> = (0..4u32).map(|value| scope.spawn(move || value * 2)).collect();
            let results = join_with_panic_fallback(handles, |_| panic!("fallback must not run"));
            assert_eq!(results, vec![0, 2, 4, 6]);
        });
    }
}
