use std::collections::BTreeMap;

use super::plan::ExecutionPlan;
use super::stage::{ExecutionStageId, ExecutionStageRunner};

mod constructor;
mod run_loop;
mod steps;

pub(in crate::engine) struct ExecutionCoordinator {
    runners: BTreeMap<ExecutionStageId, Box<dyn ExecutionStageRunner + Send + Sync>>,
}

impl ExecutionCoordinator {
    pub(in crate::engine) fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        steps::total_steps(plan, &self.runners)
    }
}
