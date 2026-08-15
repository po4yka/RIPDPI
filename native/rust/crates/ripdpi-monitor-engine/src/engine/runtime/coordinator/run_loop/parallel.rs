use std::collections::HashSet;
use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::engine::runtime::deadline::stage_budget_deadline;
use crate::engine::runtime::parallel;
use crate::engine::runtime::{ExecutionPlan, ExecutionRuntime, ExecutionStageId, RunnerOutcome};

use super::super::ExecutionCoordinator;

impl ExecutionCoordinator {
    pub(super) fn run_parallel_stage(
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
}
