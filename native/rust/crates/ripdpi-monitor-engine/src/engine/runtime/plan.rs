use crate::connectivity::ProbeExecutionContext;
use crate::engine::strategy_plan::StrategyExecutionPlan;
use crate::transport::TransportConfig;
use crate::types::ScanRequest;

use super::stage::ExecutionStageId;

mod snapshot;

pub(in crate::engine) struct ExecutionPlan {
    pub(in crate::engine) session_id: String,
    pub(in crate::engine) request: ScanRequest,
    pub(in crate::engine) started_at: u64,
    pub(in crate::engine) total_steps: usize,
    pub(in crate::engine) transport: TransportConfig,
    pub(in crate::engine) probe_context: ProbeExecutionContext,
    pub(in crate::engine) stage_order: Vec<ExecutionStageId>,
    pub(in crate::engine) strategy: Option<StrategyExecutionPlan>,
}

impl ExecutionPlan {
    pub(in crate::engine) fn strategy_candidate_steps(&self) -> (usize, usize) {
        let Some(strategy) = &self.strategy else {
            return (0, 0);
        };
        let cap = strategy.max_candidates.unwrap_or(usize::MAX);
        (
            if self.request.domain_targets.is_empty() { 0 } else { strategy.suite.tcp_candidates.len().min(cap) },
            if self.request.quic_targets.is_empty() { 0 } else { strategy.suite.quic_candidates.len().min(cap) },
        )
    }
}
