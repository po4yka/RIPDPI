use ripdpi_monitor_adapter::proxy_config::ProxyRuntimeContext;
use ripdpi_monitor_adapter::proxy_config::{parse_proxy_config_json, ProxyConfigPayload};

use crate::candidates::{build_strategy_probe_suite, StrategyProbeSuite};
use crate::types::ScanRequest;
use crate::util::probe_session_seed;

pub(in crate::engine) struct StrategyExecutionPlan {
    pub(in crate::engine) suite_id: String,
    pub(in crate::engine) runtime_context: Option<ProxyRuntimeContext>,
    pub(in crate::engine) suite: StrategyProbeSuite,
    pub(in crate::engine) probe_seed: u64,
    pub(in crate::engine) max_candidates: Option<usize>,
}

pub(super) fn build_strategy_execution_plan(
    session_id: &str,
    request: &ScanRequest,
) -> Result<StrategyExecutionPlan, String> {
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
