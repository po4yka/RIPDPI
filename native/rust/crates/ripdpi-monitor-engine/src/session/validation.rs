use ripdpi_monitor_adapter::proxy_config::{parse_proxy_config_json, ProxyConfigPayload};

use crate::types::{EngineScanRequestWire, ScanKind, ScanPathMode, DIAGNOSTICS_ENGINE_SCHEMA_VERSION};

pub(crate) fn validate_scan_request(request: &EngineScanRequestWire) -> Result<(), String> {
    if request.schema_version != DIAGNOSTICS_ENGINE_SCHEMA_VERSION {
        return Err(format!(
            "Unsupported diagnostics schema {} (expected {})",
            request.schema_version, DIAGNOSTICS_ENGINE_SCHEMA_VERSION
        ));
    }
    if request.kind == ScanKind::StrategyProbe {
        let strategy_probe = request
            .strategy_probe
            .as_ref()
            .ok_or_else(|| "strategy_probe scan requires strategyProbe request".to_string())?;
        let Some(config_json) = strategy_probe.base_proxy_config_json.as_deref() else {
            return Err("strategy_probe scan requires baseProxyConfigJson".to_string());
        };
        match parse_proxy_config_json(config_json).map_err(|err| err.to_string())? {
            ProxyConfigPayload::Ui { .. } => {}
            ProxyConfigPayload::CommandLine { .. } => {
                return Err("strategy_probe scans only support UI proxy config".to_string());
            }
        }
    }

    match (request.proxy_host.as_deref(), request.proxy_port) {
        (Some(host), Some(port)) if !host.trim().is_empty() && port > 0 => Ok(()),
        (None, None) if request.path_mode == ScanPathMode::RawPath => Ok(()),
        _ => Err("IN_PATH diagnostics require proxyHost/proxyPort".to_string()),
    }
}
