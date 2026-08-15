use std::collections::BTreeMap;

use crate::engine::runtime::{ExecutionPlan, ExecutionStageId, ExecutionStageRunner};

pub(super) fn total_steps(
    plan: &ExecutionPlan,
    runners: &BTreeMap<ExecutionStageId, Box<dyn ExecutionStageRunner + Send + Sync>>,
) -> usize {
    plan.stage_order
        .iter()
        .filter_map(|stage| runners.get(stage))
        .map(|runner| runner.total_steps(plan))
        .sum::<usize>()
        .max(1)
}
