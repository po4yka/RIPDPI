use ripdpi_diagnostics_contracts::types::ExecutionStageSnapshot;

use super::state::ExecutionRuntime;

impl ExecutionRuntime {
    pub(in crate::engine) fn begin_stage(&mut self, stage_id: &str, planned_steps: usize) {
        self.stage_executions.push(ExecutionStageSnapshot {
            stage_id: stage_id.to_string(),
            planned_steps,
            executed_steps: 0,
            skipped_by_stage_budget_steps: 0,
            skipped_by_global_deadline_steps: 0,
            candidate_ids_omitted_by_cap: Vec::new(),
        });
        self.active_stage = Some(self.stage_executions.len() - 1);
    }

    pub(in crate::engine) fn finish_stage(&mut self) {
        self.active_stage = None;
    }

    pub(in crate::engine) fn record_global_deadline_stage(&mut self, stage_id: &str, planned_steps: usize) {
        self.stage_executions.push(ExecutionStageSnapshot {
            stage_id: stage_id.to_string(),
            planned_steps,
            executed_steps: 0,
            skipped_by_stage_budget_steps: 0,
            skipped_by_global_deadline_steps: planned_steps,
            candidate_ids_omitted_by_cap: Vec::new(),
        });
    }

    pub(in crate::engine) fn record_active_global_deadline_skips(&mut self, skipped_steps: usize) {
        if let Some(index) = self.active_stage
            && let Some(stage) = self.stage_executions.get_mut(index)
        {
            stage.skipped_by_global_deadline_steps += skipped_steps;
        }
    }

    pub(in crate::engine) fn record_stage_step(&mut self, skipped_by_stage_budget: bool) {
        if let Some(index) = self.active_stage
            && let Some(stage) = self.stage_executions.get_mut(index)
        {
            if skipped_by_stage_budget {
                stage.skipped_by_stage_budget_steps += 1;
            } else {
                stage.executed_steps += 1;
            }
        }
    }

    pub(in crate::engine) fn record_candidate_cap_omissions(&mut self, candidate_ids: Vec<String>) {
        if let Some(index) = self.active_stage
            && let Some(stage) = self.stage_executions.get_mut(index)
        {
            stage.candidate_ids_omitted_by_cap = candidate_ids;
        }
    }

    pub(in crate::engine) fn stage_executions(&self) -> Vec<ExecutionStageSnapshot> {
        self.stage_executions.clone()
    }
}
