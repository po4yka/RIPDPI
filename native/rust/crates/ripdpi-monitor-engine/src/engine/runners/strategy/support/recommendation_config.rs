use crate::candidates::{strategy_probe_config_json, StrategyCandidateSpec};
use crate::types::StrategyProbeCandidateSummary as CandidateSummary;

pub(in crate::engine::runners::strategy) fn resolve_recommended_proxy_config_json(
    quic_candidate: &CandidateSummary,
    fallback_quic_spec: &StrategyCandidateSpec,
) -> String {
    quic_candidate
        .proxy_config_json
        .as_deref()
        .filter(|value| !value.trim().is_empty())
        .map_or_else(|| strategy_probe_config_json(&fallback_quic_spec.config), str::to_owned)
}
