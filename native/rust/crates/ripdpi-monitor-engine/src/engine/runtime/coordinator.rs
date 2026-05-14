use std::collections::{BTreeMap, HashSet};
use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::types::ScanKind;

use super::plan::ExecutionPlan;
use super::recording::{record_steps, CollectedStageOutcome};
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
        // For CONNECTIVITY scans, DNS + TCP + QUIC are independent I/O-bound
        // stages that can run concurrently. Each stage collects its own steps,
        // then the coordinator merges them back into runtime in stage order.
        const PARALLEL_GROUP: &[ExecutionStageId] =
            &[ExecutionStageId::Dns, ExecutionStageId::Tcp, ExecutionStageId::Quic];

        let is_connectivity = matches!(plan.request.kind, ScanKind::Connectivity);
        let mut parallel_done = HashSet::new();

        for stage in &plan.stage_order {
            if parallel_done.contains(stage) {
                continue;
            }
            if is_connectivity && PARALLEL_GROUP.contains(stage) {
                let parallel_runners: Vec<&ExecutionStageId> = plan
                    .stage_order
                    .iter()
                    .filter(|candidate| {
                        PARALLEL_GROUP.contains(candidate)
                            && self.runners.get(candidate).is_some_and(|r| r.total_steps(plan) > 0)
                    })
                    .collect();

                if parallel_runners.len() > 1 {
                    if runtime.is_cancelled() || runtime.is_past_deadline() {
                        return RunnerOutcome::Cancelled;
                    }

                    let thread_results = std::thread::scope(|s| {
                        let mut handles = Vec::with_capacity(parallel_runners.len());
                        for parallel_stage in &parallel_runners {
                            let runner = self.runners.get(parallel_stage).expect("runner present");
                            let cancel = runtime.cancel_token();
                            handles.push(s.spawn(move || runner.run_collecting(plan, cancel, tls_verifier)));
                        }
                        handles
                            .into_iter()
                            .map(|handle| handle.join().expect("parallel runner thread panicked"))
                            .collect::<Vec<_>>()
                    });

                    let mut cancelled = false;
                    for (parallel_stage, collected) in parallel_runners.iter().zip(thread_results.into_iter()) {
                        parallel_done.insert(*parallel_stage);
                        let steps = match collected {
                            CollectedStageOutcome::Completed(steps) => steps,
                            CollectedStageOutcome::Cancelled(steps) => {
                                cancelled = true;
                                steps
                            }
                        };
                        record_steps(plan, runtime, steps);
                    }
                    if cancelled {
                        return RunnerOutcome::Cancelled;
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
            }
        }
        RunnerOutcome::Completed
    }
}
