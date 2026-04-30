use ripdpi_proxy_config::{parse_proxy_config_json, ProxyConfigPayload};

use crate::candidates::build_strategy_probe_suite;
use crate::transport::TransportConfig;
use crate::types::{ProbeTaskFamily, ScanKind, ScanRequest};
use crate::util::probe_session_seed;

use super::runtime::{ExecutionPlan, ExecutionStageId, StrategyExecutionPlan};

pub(super) fn build_execution_plan(
    session_id: String,
    request: ScanRequest,
    started_at: u64,
    transport: TransportConfig,
) -> Result<ExecutionPlan, String> {
    let strategy = if matches!(request.kind, ScanKind::StrategyProbe) {
        Some(build_strategy_execution_plan(&session_id, &request)?)
    } else {
        None
    };
    let stage_order = match request.kind {
        ScanKind::Connectivity => connectivity_stage_order(&request),
        ScanKind::StrategyProbe => vec![
            ExecutionStageId::Environment,
            ExecutionStageId::StrategyDnsBaseline,
            ExecutionStageId::StrategyTcpCandidates,
            ExecutionStageId::StrategyQuicCandidates,
            ExecutionStageId::StrategyRecommendation,
        ],
    };
    Ok(ExecutionPlan { session_id, request, started_at, total_steps: 0, transport, stage_order, strategy })
}

fn build_strategy_execution_plan(session_id: &str, request: &ScanRequest) -> Result<StrategyExecutionPlan, String> {
    let sp = request.strategy_probe.clone().ok_or_else(|| "missing strategyProbe settings".to_string())?;
    let json = sp
        .base_proxy_config_json
        .as_deref()
        .filter(|v| !v.trim().is_empty())
        .ok_or_else(|| "strategy_probe scan requires baseProxyConfigJson".to_string())?;
    let (cfg, runtime_context) = match parse_proxy_config_json(json).map_err(|e| e.to_string())? {
        ProxyConfigPayload::Ui { config, runtime_context, .. } => (config, runtime_context),
        ProxyConfigPayload::CommandLine { .. } => {
            return Err("strategy_probe scans only support UI proxy config".into())
        }
    };
    let suite = build_strategy_probe_suite(&sp.suite_id, &cfg)?;
    let probe_seed = probe_session_seed(cfg.host_autolearn.network_scope_key.as_deref(), session_id);
    let (max_candidates, suite_id) = (sp.max_candidates, sp.suite_id);
    Ok(StrategyExecutionPlan { suite_id, probe_seed, runtime_context, suite, max_candidates })
}

pub(super) fn connectivity_stage_order(request: &ScanRequest) -> Vec<ExecutionStageId> {
    let mut ordered = vec![ExecutionStageId::Environment];
    if !request.probe_tasks.is_empty() {
        for task in &request.probe_tasks {
            let stage = match task.family {
                ProbeTaskFamily::Dns => ExecutionStageId::Dns,
                ProbeTaskFamily::Web => ExecutionStageId::Web,
                ProbeTaskFamily::Quic => ExecutionStageId::Quic,
                ProbeTaskFamily::Tcp => ExecutionStageId::Tcp,
                ProbeTaskFamily::Service => ExecutionStageId::Service,
                ProbeTaskFamily::Circumvention => ExecutionStageId::Circumvention,
                ProbeTaskFamily::Telegram => ExecutionStageId::Telegram,
                ProbeTaskFamily::Throughput => ExecutionStageId::Throughput,
            };
            if !ordered.contains(&stage) {
                ordered.push(stage);
            }
        }
        return ordered;
    }
    ordered.extend([
        ExecutionStageId::Dns,
        ExecutionStageId::Web,
        ExecutionStageId::Quic,
        ExecutionStageId::Tcp,
        ExecutionStageId::Service,
        ExecutionStageId::Circumvention,
        ExecutionStageId::Telegram,
        ExecutionStageId::Throughput,
    ]);
    ordered
}
