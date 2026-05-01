use crate::types::{ObservationKind, ProbeObservation, ProbeResult, StrategyObservationFact, StrategyProbeProtocol};

use super::common::{base_observation, detail_value, strategy_status, tls_status, transport_failure};

const PROBE_TYPES: &[&str] = &["strategy_http", "strategy_https", "strategy_quic"];

pub(crate) fn build_observation(result: &ProbeResult) -> Option<ProbeObservation> {
    PROBE_TYPES.contains(&result.probe_type.as_str()).then(|| build_strategy_observation(result))
}

pub(crate) fn build_strategy_observation(result: &ProbeResult) -> ProbeObservation {
    let mut observation = base_observation(result, ObservationKind::Strategy);
    observation.strategy = Some(StrategyObservationFact {
        candidate_id: detail_value(result, "candidateId").map(str::to_string),
        candidate_label: detail_value(result, "candidateLabel").map(str::to_string),
        candidate_family: detail_value(result, "candidateFamily").map(str::to_string),
        protocol: strategy_protocol(&result.probe_type),
        status: strategy_status(&result.outcome),
        tls_ech_status: tls_status(detail_value(result, "tlsEchStatus")),
        tls_ech_version: detail_value(result, "tlsEchVersion").filter(|value| *value != "unknown").map(str::to_string),
        tls_ech_error: detail_value(result, "tlsEchError").filter(|value| *value != "none").map(str::to_string),
        tls_ech_resolution_detail: detail_value(result, "tlsEchResolutionDetail")
            .filter(|value| *value != "none")
            .map(str::to_string),
        transport_failure: transport_failure(
            detail_value(result, "error").or_else(|| detail_value(result, "tlsError")).unwrap_or("none"),
        ),
        tls_error: if result.probe_type == "strategy_https" {
            detail_value(result, "tlsError")
                .or_else(|| detail_value(result, "error"))
                .filter(|v| *v != "none")
                .map(str::to_string)
        } else {
            None
        },
        h3_advertised: detail_value(result, "h3Advertised") == Some("true"),
    });
    observation
}

fn strategy_protocol(probe_type: &str) -> StrategyProbeProtocol {
    match probe_type {
        "strategy_http" => StrategyProbeProtocol::Http,
        "strategy_https" => StrategyProbeProtocol::Https,
        "strategy_quic" => StrategyProbeProtocol::Quic,
        _ => StrategyProbeProtocol::Candidate,
    }
}
