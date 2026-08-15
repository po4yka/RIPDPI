use std::sync::Arc;

use crate::execution::{CandidateRuntimeLauncher, CandidateRuntimeSupervisor, DefaultStrategyLaneExecutor};

pub(in crate::engine::runners) struct StrategyTcpRunner {
    pub(super) lane_executor: DefaultStrategyLaneExecutor,
}

impl StrategyTcpRunner {
    pub(in crate::engine::runners) fn new(
        candidate_runtime_launcher: Arc<dyn CandidateRuntimeLauncher>,
        supervisor: Arc<CandidateRuntimeSupervisor>,
    ) -> Self {
        Self { lane_executor: DefaultStrategyLaneExecutor::new(candidate_runtime_launcher, supervisor) }
    }
}
