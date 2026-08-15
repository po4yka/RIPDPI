use std::collections::HashSet;
use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::engine::runtime::{ExecutionPlan, ExecutionRuntime, ExecutionStageId, RunnerOutcome};

use super::ExecutionCoordinator;

mod parallel;
mod serial;

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
