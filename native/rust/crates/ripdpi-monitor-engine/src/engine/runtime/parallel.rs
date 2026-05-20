use std::collections::BTreeMap;

use super::plan::ExecutionPlan;
use super::stage::{ExecutionStageId, ExecutionStageRunner};

type RunnerMap = BTreeMap<ExecutionStageId, Box<dyn ExecutionStageRunner + Send + Sync>>;

const CONNECTIVITY_PARALLEL_GROUP: &[ExecutionStageId] =
    &[ExecutionStageId::Dns, ExecutionStageId::Tcp, ExecutionStageId::Quic];

pub(super) fn is_connectivity_parallel_stage(stage: &ExecutionStageId) -> bool {
    CONNECTIVITY_PARALLEL_GROUP.contains(stage)
}

pub(super) fn is_connectivity_parallel_plan_stage(plan: &ExecutionPlan, stage: &ExecutionStageId) -> bool {
    matches!(plan.request.kind, crate::types::ScanKind::Connectivity) && is_connectivity_parallel_stage(stage)
}

pub(super) fn runnable_connectivity_parallel_stages<'a>(
    plan: &'a ExecutionPlan,
    runners: &RunnerMap,
) -> Vec<&'a ExecutionStageId> {
    plan.stage_order
        .iter()
        .filter(|candidate| {
            is_connectivity_parallel_stage(candidate)
                && runners.get(candidate).is_some_and(|runner| runner.total_steps(plan) > 0)
        })
        .collect()
}

pub(super) fn total_steps(stages: &[&ExecutionStageId], runners: &RunnerMap, plan: &ExecutionPlan) -> usize {
    stages.iter().filter_map(|stage| runners.get(stage)).map(|runner| runner.total_steps(plan)).sum::<usize>()
}
