use std::collections::HashSet;
use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::engine::runtime::deadline::stage_budget_deadline;
use crate::engine::runtime::parallel;
use crate::engine::runtime::{ExecutionPlan, ExecutionRuntime, ExecutionStageId, RunnerOutcome};

use super::ExecutionCoordinator;

impl ExecutionCoordinator {
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
            if let Some(outcome) =
                self.run_parallel_stage(plan, runtime, tls_verifier, stage_index, stage, &mut parallel_done)
            {
                if !matches!(outcome, RunnerOutcome::Completed) {
                    return outcome;
                }
                continue;
            }
            match self.run_serial_stage(plan, runtime, tls_verifier, stage_index, stage) {
                RunnerOutcome::Completed => {}
                outcome => return outcome,
            }
        }
        RunnerOutcome::Completed
    }

    fn run_parallel_stage(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
        stage_index: usize,
        stage: &ExecutionStageId,
        parallel_done: &mut HashSet<ExecutionStageId>,
    ) -> Option<RunnerOutcome> {
        if !parallel::is_connectivity_parallel_plan_stage(plan, stage) {
            return None;
        }
        let parallel_runners = parallel::runnable_connectivity_parallel_stages(plan, &self.runners);
        if parallel_runners.len() <= 1 {
            return None;
        }
        if runtime.is_cancelled() || runtime.is_past_deadline() {
            self.record_deadline_skips(plan, runtime, &plan.stage_order[stage_index..], &HashSet::new());
            return Some(RunnerOutcome::Cancelled);
        }
        self.publish_parallel_progress(plan, runtime, &parallel_runners);
        runtime.set_stage_deadline(stage_budget_deadline(plan, &self.runners, stage, runtime));
        let outcome = parallel::run_connectivity_group(plan, runtime, &self.runners, &parallel_runners, tls_verifier);
        runtime.set_stage_deadline(None);
        parallel_done.extend(parallel_runners.iter().map(|stage| (*stage).clone()));
        if !matches!(outcome, RunnerOutcome::Completed) {
            self.record_deadline_skips(plan, runtime, &plan.stage_order[stage_index..], parallel_done);
        }
        Some(outcome)
    }

    fn run_serial_stage(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
        stage_index: usize,
        stage: &ExecutionStageId,
    ) -> RunnerOutcome {
        let Some(runner) = self.runners.get(stage) else {
            return RunnerOutcome::Completed;
        };
        if runtime.is_cancelled() || runtime.is_past_deadline() {
            self.record_deadline_skips(plan, runtime, &plan.stage_order[stage_index..], &HashSet::new());
            return RunnerOutcome::Cancelled;
        }
        if runner.total_steps(plan) == 0 {
            return RunnerOutcome::Completed;
        }
        let completed_before_stage = runtime.completed_steps;
        let planned_stage_steps = runner.total_steps(plan);
        runtime.begin_stage(stage.as_str(), planned_stage_steps);
        runtime.set_stage_deadline(stage_budget_deadline(plan, &self.runners, stage, runtime));
        let outcome = runner.run(plan, runtime, tls_verifier);
        let stage_exhausted = runtime.is_past_stage_deadline() && !runtime.is_past_deadline();
        runtime.set_stage_deadline(None);
        let outcome = match outcome {
            RunnerOutcome::Cancelled if stage_exhausted => {
                runtime.record_stage_budget_skips(
                    plan,
                    runner.phase(),
                    stage.as_str(),
                    planned_stage_steps.saturating_sub(runtime.completed_steps - completed_before_stage),
                );
                RunnerOutcome::Completed
            }
            RunnerOutcome::Cancelled => {
                if runtime.is_past_deadline() && !runtime.is_cancelled() {
                    runtime.record_active_global_deadline_skips(
                        planned_stage_steps.saturating_sub(runtime.completed_steps - completed_before_stage),
                    );
                    self.record_deadline_skips(plan, runtime, &plan.stage_order[stage_index + 1..], &HashSet::new());
                }
                RunnerOutcome::Cancelled
            }
            outcome => outcome,
        };
        runtime.finish_stage();
        outcome
    }

    fn publish_parallel_progress(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        parallel_runners: &[&ExecutionStageId],
    ) {
        let target_count = parallel::total_steps(parallel_runners, &self.runners, plan);
        runtime.publish_progress(
            plan,
            "parallel_connectivity",
            runtime.completed_steps,
            format!("Running DNS, TCP, and QUIC probes ({target_count} targets)"),
            None,
            None,
            None,
        );
    }

    fn record_deadline_skips(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        remaining_stages: &[ExecutionStageId],
        excluded: &HashSet<ExecutionStageId>,
    ) {
        if !runtime.is_past_deadline() || runtime.is_cancelled() {
            return;
        }
        let mut recorded = HashSet::new();
        for stage in remaining_stages {
            if excluded.contains(stage) || !recorded.insert(stage.clone()) {
                continue;
            }
            let Some(runner) = self.runners.get(stage) else {
                continue;
            };
            let planned_steps = runner.total_steps(plan);
            if planned_steps > 0 {
                runtime.record_global_deadline_stage(stage.as_str(), planned_steps);
            }
        }
    }
}
