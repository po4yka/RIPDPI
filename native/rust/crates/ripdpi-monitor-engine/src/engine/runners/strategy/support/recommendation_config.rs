use crate::candidates::{strategy_probe_config_json, StrategyCandidateSpec};
use crate::types::StrategyProbeCandidateSummary as CandidateSummary;

use super::capabilities::capability_available;

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

pub(in crate::engine::runners::strategy) fn select_promotable_candidate_index(
    candidates: &[CandidateSummary],
    specs: &[StrategyCandidateSpec],
    fake_ttl_available: bool,
    tcp_fast_open_available: bool,
    ipfrag_caps: ripdpi_runtime_platform::raw_packet::IpFragmentationCapabilities,
) -> Option<usize> {
    crate::execution::winning_candidate_index_with(candidates, |candidate| {
        let Some(spec) = specs.iter().find(|spec| spec.id == candidate.id) else {
            return false;
        };
        candidate_is_promotable_for_path(spec, fake_ttl_available, tcp_fast_open_available, ipfrag_caps)
    })
}

fn candidate_is_promotable_for_path(
    spec: &StrategyCandidateSpec,
    fake_ttl_available: bool,
    tcp_fast_open_available: bool,
    ipfrag_caps: ripdpi_runtime_platform::raw_packet::IpFragmentationCapabilities,
) -> bool {
    if spec.requires_fake_ttl && !fake_ttl_available {
        return false;
    }
    if spec.requires_tcp_fast_open && !tcp_fast_open_available {
        return false;
    }
    if matches!(spec.emitter_tier, crate::types::StrategyEmitterTier::LabDiagnosticsOnly) {
        return false;
    }
    spec.requires_capabilities
        .iter()
        .all(|&capability| capability_available(capability, fake_ttl_available, ipfrag_caps))
}
