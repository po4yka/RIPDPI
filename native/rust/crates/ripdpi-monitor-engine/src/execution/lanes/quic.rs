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
        ProbeDetail { key: "measurementScope".to_string(), value: "quic_initial_response".to_string() },
        ProbeDetail { key: "http3Validated".to_string(), value: "false".to_string() },
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::{net::UdpSocket, thread, time::Duration};

    #[test]
    fn strategy_quic_preserves_initial_scope_for_success_and_failure() {
        for valid_response in [true, false] {
            let server = UdpSocket::bind("127.0.0.1:0").unwrap();
            server.set_read_timeout(Some(Duration::from_secs(6))).unwrap();
            let port = server.local_addr().unwrap().port();
            let worker = thread::spawn(move || {
                let mut request = [0; 2048];
                let (size, peer) = server.recv_from(&mut request).unwrap();
                if valid_response {
                    let dcid_len = request[5] as usize;
                    let scid_offset = 6 + dcid_len;
                    let scid_len = request[scid_offset] as usize;
                    let mut response = vec![0x80, 0, 0, 0, 0, scid_len as u8];
                    response.extend_from_slice(&request[scid_offset + 1..scid_offset + 1 + scid_len]);
                    response.push(dcid_len as u8);
                    response.extend_from_slice(&request[6..6 + dcid_len]);
                    response.extend_from_slice(&ripdpi_packets::QUIC_V2_VERSION.to_be_bytes());
                    server.send_to(&response, peer).unwrap();
                } else {
                    server.send_to(&request[..size], peer).unwrap();
                }
            });
            let candidate = crate::candidates::candidate_spec(
                "test",
                "Test",
                "test",
                ripdpi_proxy_config::ProxyUiConfig::default(),
            );
            let target = QuicTarget {
                host: "localhost".into(),
                connect_ip: Some("127.0.0.1".into()),
                connect_ips: vec![],
                port,
            };
            let sample =
                run_quic_strategy_probe(&TransportConfig::Direct { route_experiment: None }, &target, &candidate);
            worker.join().unwrap();
            assert_eq!(sample.success, valid_response);
            assert!(
                sample
                    .result
                    .details
                    .iter()
                    .any(|detail| detail.key == "measurementScope" && detail.value == "quic_initial_response")
            );
            assert!(
                sample.result.details.iter().any(|detail| detail.key == "http3Validated" && detail.value == "false")
            );
        }
    }
}
