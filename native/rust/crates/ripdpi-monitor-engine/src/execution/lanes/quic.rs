use ripdpi_packets::{QUIC_V1_VERSION, build_probe_quic_initial};

mod candidate_execution;
mod outcome;

use crate::candidates::StrategyCandidateSpec;
use crate::execution::scoring::ProbeSample;
use crate::transport::{TransportConfig, quic_connect_targets, relay_udp_payload_observed};
use crate::types::{ProbeDetail, ProbeResult, QuicTarget};
use crate::util::now_ms;

use self::outcome::classify_quic_response;
use super::support::candidate_probe_details;

pub use candidate_execution::execute_quic_candidate;

pub(super) fn run_quic_strategy_probe(
    transport: &TransportConfig,
    target: &QuicTarget,
    candidate: &StrategyCandidateSpec,
) -> ProbeSample {
    let started = now_ms();
    let payload = build_probe_quic_initial(QUIC_V1_VERSION, Some(target.host.as_str()));
    let response = payload
        .as_deref()
        .ok_or_else(|| std::io::Error::other("QUIC Initial generation failed").into())
        .and_then(|payload| relay_udp_payload_observed(&quic_connect_targets(target), target.port, transport, payload));
    let latency_ms = now_ms().saturating_sub(started);
    let outcome = classify_quic_response(response, payload.as_deref().unwrap_or_default());
    let mut details = candidate_probe_details(candidate, "QUIC", latency_ms);
    details.extend([
        ProbeDetail { key: "port".to_string(), value: target.port.to_string() },
        ProbeDetail { key: "status".to_string(), value: outcome.status.clone() },
        ProbeDetail { key: "error".to_string(), value: outcome.error },
    ]);
    if let Some(addr) = outcome.connected_addr {
        details.push(ProbeDetail { key: "connectedIp".to_string(), value: addr.ip().to_string() });
        if let Some(provider) = crate::cdn_ech::opportunistic_ech_provider_for_ip(addr.ip()) {
            details.push(ProbeDetail { key: "cdnProvider".to_string(), value: provider.to_string() });
        }
    }
    ProbeSample {
        result: ProbeResult {
            probe_type: "strategy_quic".to_string(),
            target: format!("{} · {}", candidate.label, target.host),
            outcome: outcome.kind.clone(),
            details,
        },
        success: matches!(outcome.kind.as_str(), "quic_initial_response" | "quic_response"),
        weight: 2,
        domain: Some(target.host.clone()),
        is_control: false,
        attempt_token: None,
        quality: match outcome.kind.as_str() {
            "quic_initial_response" => 4,
            "quic_response" => 3,
            _ => 0,
        },
        latency_ms,
    }
}
