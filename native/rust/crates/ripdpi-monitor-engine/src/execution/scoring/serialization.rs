use ripdpi_proxy_config::ProxyConfigPayload;

use crate::candidates::StrategyCandidateSpec;

pub fn candidate_proxy_config_json(spec: &StrategyCandidateSpec) -> Option<String> {
    serde_json::to_string(&ProxyConfigPayload::Ui {
        strategy_preset: None,
        config: spec.config.clone(),
        runtime_context: None,
        log_context: None,
        session_overrides: None,
    })
    .ok()
}
