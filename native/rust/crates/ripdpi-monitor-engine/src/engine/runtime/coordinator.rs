use std::collections::{BTreeMap, HashSet};
use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::types::ScanKind;

use super::artifacts::CollectedStep;
use super::plan::ExecutionPlan;
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

        if is_connectivity {
            let parallel_runners: Vec<&ExecutionStageId> = plan
                .stage_order
                .iter()
                .filter(|stage| {
                    PARALLEL_GROUP.contains(stage) && self.runners.get(stage).is_some_and(|r| r.total_steps(plan) > 0)
                })
                .collect();

            if parallel_runners.len() > 1 {
                if runtime.is_cancelled() || runtime.is_past_deadline() {
                    return RunnerOutcome::Cancelled;
                }

                let mut thread_results: Vec<Option<Vec<CollectedStep>>> =
                    (0..parallel_runners.len()).map(|_| None).collect();

                std::thread::scope(|s| {
                    let mut handles = Vec::with_capacity(parallel_runners.len());
                    for stage in &parallel_runners {
                        let runner = self.runners.get(stage).expect("runner present");
                        let cancel = runtime.cancel_token();
                        handles.push(s.spawn(move || runner.run_collecting(plan, cancel, tls_verifier)));
                    }
                    for (i, handle) in handles.into_iter().enumerate() {
                        thread_results[i] = handle.join().expect("parallel runner thread panicked");
                    }
                });

                for (stage, collected_opt) in parallel_runners.iter().zip(thread_results.into_iter()) {
                    parallel_done.insert(*stage);
                    let Some(steps) = collected_opt else {
                        return RunnerOutcome::Cancelled;
                    };
                    for step in steps {
                        runtime.record_step(
                            plan,
                            step.phase,
                            step.message,
                            step.latest_probe_target,
                            step.latest_probe_outcome,
                            None,
                            step.artifacts,
                        );
                    }
                }
            }
        }

        for stage in &plan.stage_order {
            if parallel_done.contains(stage) {
                continue;
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
