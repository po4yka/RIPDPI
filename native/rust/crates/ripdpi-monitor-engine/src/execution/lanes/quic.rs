use std::sync::atomic::AtomicBool;

mod candidate_execution;

use crate::candidates::StrategyCandidateSpec;
use crate::execution::scoring::ProbeSample;
use crate::transport::TransportConfig;
use crate::types::QuicTarget;

use super::support::candidate_probe_details;

pub use candidate_execution::execute_quic_candidate;

pub(super) fn run_quic_strategy_probe(
    transport: &TransportConfig,
    target: &QuicTarget,
    candidate: &StrategyCandidateSpec,
    cancel: &AtomicBool,
) -> ProbeSample {
    let mut result = crate::connectivity::run_quic_probe(target, transport, cancel);
    let latency_ms = result
        .details
        .iter()
        .find(|detail| detail.key == "latencyMs")
        .and_then(|detail| detail.value.parse().ok())
        .unwrap_or_default();
    let mut details = candidate_probe_details(candidate, "QUIC", latency_ms);
    details.extend(result.details.into_iter().filter(|detail| detail.key != "latencyMs"));
    if let Some(provider) = details
        .iter()
        .find(|detail| detail.key == "connectedIp")
        .and_then(|detail| detail.value.parse().ok())
        .and_then(crate::cdn_ech::opportunistic_ech_provider_for_ip)
    {
        details.push(crate::types::ProbeDetail { key: "cdnProvider".to_string(), value: provider.to_string() });
    }
    result.probe_type = "strategy_quic".to_string();
    result.target = format!("{} · {}", candidate.label, target.host);
    result.details = details;
    let quality = match result.outcome.as_str() {
        "quic_initial_response" => 4,
        "quic_initial_ack"
        | "quic_initial_other"
        | "quic_initial_close"
        | "quic_retry"
        | "quic_version_negotiation" => 3,
        _ => 0,
    };
    ProbeSample {
        result,
        success: quality > 0,
        weight: 2,
        domain: Some(target.host.clone()),
        is_control: false,
        attempt_token: None,
        quality,
        latency_ms,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{net::UdpSocket, thread, time::Duration};

    #[test]
    fn strategy_quic_keeps_measuring_after_an_uncorrelated_reply() {
        let server = UdpSocket::bind("127.0.0.1:0").unwrap();
        server.set_read_timeout(Some(Duration::from_secs(6))).unwrap();
        let port = server.local_addr().unwrap().port();
        let worker = thread::spawn(move || {
            let mut request = [0; 2048];
            let (size, peer) = server.recv_from(&mut request).unwrap();
            server.send_to(&request[..size], peer).unwrap();
            let dcid_len = request[5] as usize;
            let scid_offset = 6 + dcid_len;
            let scid_len = request[scid_offset] as usize;
            let mut response = vec![0x80, 0, 0, 0, 0, scid_len as u8];
            response.extend_from_slice(&request[scid_offset + 1..scid_offset + 1 + scid_len]);
            response.push(dcid_len as u8);
            response.extend_from_slice(&request[6..6 + dcid_len]);
            response.extend_from_slice(&ripdpi_packets::QUIC_V2_VERSION.to_be_bytes());
            server.send_to(&response, peer).unwrap();
        });
        let candidate =
            crate::candidates::candidate_spec("test", "Test", "test", ripdpi_proxy_config::ProxyUiConfig::default());
        let target =
            QuicTarget { host: "localhost".into(), connect_ip: Some("127.0.0.1".into()), connect_ips: vec![], port };
        let sample = run_quic_strategy_probe(
            &TransportConfig::Direct { route_experiment: None },
            &target,
            &candidate,
            &AtomicBool::new(false),
        );
        worker.join().unwrap();
        assert_eq!(sample.result.outcome, "quic_version_negotiation");
        assert!(
            sample
                .result
                .details
                .iter()
                .any(|detail| detail.key == "measurementScope" && detail.value == "quic_initial_response")
        );
        assert!(sample.result.details.iter().any(|detail| detail.key == "http3Validated" && detail.value == "false"));
    }
}
