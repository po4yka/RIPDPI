use crate::connectivity::ProbeExecutionContext;
use crate::transport::TransportConfig;
use crate::types::ScanRequest;

use super::stage::ExecutionStageId;
use crate::engine::strategy_plan::StrategyExecutionPlan;

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
