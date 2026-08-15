use std::collections::{BTreeMap, HashSet};
use std::sync::Arc;
use std::time::Instant;

use rustls::client::danger::ServerCertVerifier;

use super::parallel;
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

                    let outcome =
                        parallel::run_connectivity_group(plan, runtime, &self.runners, &parallel_runners, tls_verifier);
                    parallel_done.extend(parallel_runners);
                    if !matches!(outcome, RunnerOutcome::Completed) {
                        return outcome;
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
            let completed_before_stage = runtime.completed_steps;
            let planned_stage_steps = runner.total_steps(plan);
            runtime.begin_stage(stage.as_str(), planned_stage_steps);
            runtime.set_stage_deadline(stage_budget_deadline(plan, &self.runners, stage, runtime));
            let outcome = runner.run(plan, runtime, tls_verifier);
            let stage_exhausted = runtime.is_past_stage_deadline() && !runtime.is_past_deadline();
            runtime.set_stage_deadline(None);
            match outcome {
                RunnerOutcome::Completed => {}
                RunnerOutcome::Cancelled if stage_exhausted => {
                    runtime.record_stage_budget_skips(
                        plan,
                        runner.phase(),
                        stage.as_str(),
                        planned_stage_steps.saturating_sub(runtime.completed_steps - completed_before_stage),
                    );
                    runtime.finish_stage();
                    continue;
                }
                RunnerOutcome::Cancelled => {
                    runtime.finish_stage();
                    return RunnerOutcome::Cancelled;
                }
                RunnerOutcome::Finished => {
                    runtime.finish_stage();
                    return RunnerOutcome::Finished;
                }
                RunnerOutcome::Failed(message) => {
                    runtime.finish_stage();
                    return RunnerOutcome::Failed(message);
                }
            }
            runtime.finish_stage();
        }
        RunnerOutcome::Completed
    }
}

fn stage_budget_deadline(
    plan: &ExecutionPlan,
    runners: &BTreeMap<ExecutionStageId, Box<dyn ExecutionStageRunner + Send + Sync>>,
    current_stage: &ExecutionStageId,
    runtime: &ExecutionRuntime,
) -> Option<Instant> {
    let global_deadline = runtime.scan_deadline()?;
    let remaining_stages = plan
        .stage_order
        .iter()
        .skip_while(|stage| *stage != current_stage)
        .filter(|stage| runners.get(*stage).is_some_and(|runner| runner.total_steps(plan) > 0))
        .count();
    let remaining = global_deadline.checked_duration_since(Instant::now())?;
    let stage_count = u32::try_from(remaining_stages.max(1)).unwrap_or(u32::MAX);
    Some(Instant::now() + remaining / stage_count)
}
