use crate::candidates::StrategyProbeSuite;
use crate::connectivity::ProbeExecutionContext;
use crate::transport::TransportConfig;
use crate::types::ScanRequest;

use ripdpi_monitor_adapter::proxy_config::ProxyRuntimeContext;

use super::stage::ExecutionStageId;

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

pub(in crate::engine) struct StrategyExecutionPlan {
    pub(in crate::engine) suite_id: String,
    pub(in crate::engine) runtime_context: Option<ProxyRuntimeContext>,
    pub(in crate::engine) suite: StrategyProbeSuite,
    pub(in crate::engine) probe_seed: u64,
    pub(in crate::engine) max_candidates: Option<usize>,
}
