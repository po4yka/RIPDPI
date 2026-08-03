use std::collections::{BTreeMap, HashSet};
use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use super::panic_recovery::{self, JoinedStageOutcome};
use super::parallel;
use super::plan::ExecutionPlan;
use super::recording::{CollectedStageOutcome, record_steps};
use super::stage::{ExecutionStageId, ExecutionStageRunner, RunnerOutcome};
use super::state::ExecutionRuntime;

pub(in crate::engine) struct ExecutionCoordinator {
    runners: BTreeMap<ExecutionStageId, Box<dyn ExecutionStageRunner + Send + Sync>>,
}

impl ExecutionCoordinator {
    pub(in crate::engine) fn new(runners: Vec<Box<dyn ExecutionStageRunner + Send + Sync>>) -> Self {
        let runners = runners.into_iter().map(|runner| (runner.id(), runner)).collect();
        Self { runners }
    }

    pub(in crate::engine) fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        plan.stage_order
            .iter()
            .filter_map(|stage| self.runners.get(stage))
            .map(|runner| runner.total_steps(plan))
            .sum::<usize>()
            .max(1)
    }

    pub(in crate::engine) fn run(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome {
        let mut parallel_done = HashSet::new();

        for stage in &plan.stage_order {
            if parallel_done.contains(stage) {
                continue;
            }
            if parallel::is_connectivity_parallel_plan_stage(plan, stage) {
                let parallel_runners = parallel::runnable_connectivity_parallel_stages(plan, &self.runners);

                if parallel_runners.len() > 1 {
                    if runtime.is_cancelled() || runtime.is_past_deadline() {
                        return RunnerOutcome::Cancelled;
                    }
                    let parallel_target_count = parallel::total_steps(&parallel_runners, &self.runners, plan);
                    runtime.publish_progress(
                        plan,
                        "parallel_connectivity",
                        runtime.completed_steps,
                        format!("Running DNS, TCP, and QUIC probes ({parallel_target_count} targets)"),
                        None,
                        None,
                        None,
                    );

                    let thread_results = std::thread::scope(|s| {
                        let mut handles = Vec::with_capacity(parallel_runners.len());
                        for parallel_stage in &parallel_runners {
                            let runner = self.runners.get(parallel_stage).expect("runner present");
                            let cancel = runtime.cancel_token();
                            let deadline = runtime.scan_deadline();
                            handles.push(s.spawn(move || {
                                ripdpi_diagnostics_contracts::util::with_scan_io_deadline(deadline, || {
                                    runner.run_collecting(plan, cancel, tls_verifier)
                                })
                            }));
                        }
                        // Record panics as support-visible probe failures before
                        // returning a terminal runner outcome. `join()` returns
                        // `Err` only when the thread panicked.
                        handles.into_iter().map(|h| panic_recovery::classify(h.join())).collect::<Vec<_>>()
                    });

                    let mut cancelled = false;
                    let mut panic_message = None;
                    for (parallel_stage, joined) in parallel_runners.iter().zip(thread_results) {
                        parallel_done.insert(*parallel_stage);
                        let steps = match joined {
                            JoinedStageOutcome::Collected(CollectedStageOutcome::Completed(steps)) => steps,
                            JoinedStageOutcome::Collected(CollectedStageOutcome::Cancelled(steps)) => {
                                cancelled = true;
                                steps
                            }
                            JoinedStageOutcome::Panicked(message) => {
                                panic_message.get_or_insert_with(|| message.clone());
                                panic_recovery::handle_panicked_runner(parallel_stage, &message)
                            }
                        };
                        record_steps(plan, runtime, steps);
                    }
                    if cancelled {
                        return RunnerOutcome::Cancelled;
                    }
                    if let Some(message) = panic_message {
                        return RunnerOutcome::Failed(format!("parallel diagnostics runner panicked: {message}"));
                    }
                    continue;
                }
            }
            let Some(runner) = self.runners.get(stage) else {
                continue;
            };
            if runtime.is_cancelled() || runtime.is_past_deadline() {
                return RunnerOutcome::Cancelled;
            }
            if runner.total_steps(plan) == 0 {
                continue;
            }
            match runner.run(plan, runtime, tls_verifier) {
                RunnerOutcome::Completed => {}
                RunnerOutcome::Cancelled => return RunnerOutcome::Cancelled,
                RunnerOutcome::Finished => return RunnerOutcome::Finished,
                RunnerOutcome::Failed(message) => return RunnerOutcome::Failed(message),
            }
        }
        RunnerOutcome::Completed
    }
}
