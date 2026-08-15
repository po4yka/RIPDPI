use super::artifacts::RunnerArtifacts;
use super::plan::ExecutionPlan;
use super::state::ExecutionRuntime;
use crate::types::ProbeResult;

impl ExecutionRuntime {
    pub(in crate::engine) fn record_stage_budget_skips(
        &mut self,
        plan: &ExecutionPlan,
        phase: &str,
        stage_id: &str,
        count: usize,
    ) {
        for skipped_index in 1..=count {
            let target = format!("{stage_id} stage budget ({skipped_index}/{count})");
            self.record_step(
                plan,
                phase,
                format!("Skipped {target}: stage budget exhausted"),
                Some(target.clone()),
                Some("skipped_by_stage_budget".to_string()),
                None,
                RunnerArtifacts::from_results(
                    vec![ProbeResult {
                        probe_type: stage_id.to_string(),
                        target,
                        outcome: "skipped_by_stage_budget".to_string(),
                        details: Vec::new(),
                    }],
                    "execution_coordinator",
                    "info",
                    "Stage budget exhausted; continuing with the next configured family".to_string(),
                ),
            );
            self.replace_last_executed_stage_step_with_budget_skip();
        }
    }
}
