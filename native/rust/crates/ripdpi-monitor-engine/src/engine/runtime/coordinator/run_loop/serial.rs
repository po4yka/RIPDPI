use std::collections::HashSet;
use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::engine::runtime::deadline::stage_budget_deadline;
use crate::engine::runtime::{ExecutionPlan, ExecutionRuntime, ExecutionStageId, RunnerOutcome};

use super::super::ExecutionCoordinator;

impl ExecutionCoordinator {
    pub(super) fn run_serial_stage(
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
}
