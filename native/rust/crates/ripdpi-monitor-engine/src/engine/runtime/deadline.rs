use std::collections::BTreeMap;
use std::time::Instant;

use super::plan::ExecutionPlan;
use super::stage::{ExecutionStageId, ExecutionStageRunner};
use super::state::ExecutionRuntime;

impl ExecutionRuntime {
    pub(in crate::engine) fn set_stage_deadline(&mut self, deadline: Option<Instant>) {
        self.stage_deadline = deadline;
    }

    pub(in crate::engine) fn stage_deadline(&self) -> Option<Instant> {
        self.stage_deadline
    }

    pub(in crate::engine) fn is_past_stage_deadline(&self) -> bool {
        self.stage_deadline.is_some_and(|deadline| Instant::now() >= deadline)
    }
}

pub(super) fn stage_budget_deadline(
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
