use std::collections::{BTreeMap, HashSet};
use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use super::deadline::stage_budget_deadline;
use super::parallel;
use super::plan::ExecutionPlan;
use super::stage::{ExecutionStageId, ExecutionStageRunner, RunnerOutcome};
use super::state::ExecutionRuntime;

mod constructor;
mod steps;

pub(in crate::engine) struct ExecutionCoordinator {
    runners: BTreeMap<ExecutionStageId, Box<dyn ExecutionStageRunner + Send + Sync>>,
}

impl ExecutionCoordinator {
    pub(in crate::engine) fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        steps::total_steps(plan, &self.runners)
    }

    pub(in crate::engine) fn run(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome {
        let mut parallel_done = HashSet::new();

        for (stage_index, stage) in plan.stage_order.iter().enumerate() {
            if parallel_done.contains(stage) {
                continue;
            }
            if parallel::is_connectivity_parallel_plan_stage(plan, stage) {
                let parallel_runners = parallel::runnable_connectivity_parallel_stages(plan, &self.runners);

                if parallel_runners.len() > 1 {
                    if runtime.is_cancelled() || runtime.is_past_deadline() {
                        if runtime.is_past_deadline() && !runtime.is_cancelled() {
                            record_remaining_global_deadline_skips(
                                plan,
                                &self.runners,
                                &plan.stage_order[stage_index..],
                                runtime,
                            );
                        }
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

                    runtime.set_stage_deadline(stage_budget_deadline(plan, &self.runners, stage, runtime));
                    let outcome =
                        parallel::run_connectivity_group(plan, runtime, &self.runners, &parallel_runners, tls_verifier);
                    runtime.set_stage_deadline(None);
                    parallel_done.extend(parallel_runners.iter().copied());
                    if !matches!(outcome, RunnerOutcome::Completed) {
                        if runtime.is_past_deadline() && !runtime.is_cancelled() {
                            for remaining_stage in &plan.stage_order[stage_index..] {
                                if parallel_done.contains(remaining_stage) {
                                    continue;
                                }
                                if let Some(runner) = self.runners.get(remaining_stage) {
                                    let planned_steps = runner.total_steps(plan);
                                    if planned_steps > 0 {
                                        runtime.record_global_deadline_stage(remaining_stage.as_str(), planned_steps);
                                    }
                                }
                            }
                        }
                        return outcome;
                    }
                    continue;
                }
            }
            let Some(runner) = self.runners.get(stage) else {
                continue;
            };
            if runtime.is_cancelled() || runtime.is_past_deadline() {
                if runtime.is_past_deadline() && !runtime.is_cancelled() {
                    record_remaining_global_deadline_skips(
                        plan,
                        &self.runners,
                        &plan.stage_order[stage_index..],
                        runtime,
                    );
                }
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
                    if runtime.is_past_deadline() && !runtime.is_cancelled() {
                        runtime.record_active_global_deadline_skips(
                            planned_stage_steps.saturating_sub(runtime.completed_steps - completed_before_stage),
                        );
                        record_remaining_global_deadline_skips(
                            plan,
                            &self.runners,
                            &plan.stage_order[stage_index + 1..],
                            runtime,
                        );
                    }
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
fn record_remaining_global_deadline_skips(
    plan: &ExecutionPlan,
    runners: &BTreeMap<ExecutionStageId, Box<dyn ExecutionStageRunner + Send + Sync>>,
    remaining_stages: &[ExecutionStageId],
    runtime: &mut ExecutionRuntime,
) {
    let mut recorded = HashSet::new();
    for stage in remaining_stages {
        if !recorded.insert(stage.clone()) {
            continue;
        }
        let Some(runner) = runners.get(stage) else {
            continue;
        };
        let planned_steps = runner.total_steps(plan);
        if planned_steps > 0 {
            runtime.record_global_deadline_stage(stage.as_str(), planned_steps);
        }
    }
}
