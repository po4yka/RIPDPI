use ripdpi_packets::{QUIC_V1_VERSION, QuicResponseKind, build_probe_quic_initial, validate_quic_response};

use crate::connectivity::adapters::transport::{TransportConfig, quic_connect_targets, relay_udp_payload_observed};
use crate::connectivity::adapters::util::now_ms;
use crate::types::{ProbeDetail, ProbeResult, QuicTarget};

use super::support::append_route_details;

pub fn run_quic_probe(target: &QuicTarget, transport: &TransportConfig) -> ProbeResult {
    let started = now_ms();
    let connect_targets = quic_connect_targets(target);
    let payload = build_probe_quic_initial(QUIC_V1_VERSION, Some(target.host.as_str()));
    // The direct transport retransmits a lost Initial once within the same wait.
    let response = payload
        .as_deref()
        .ok_or_else(|| std::io::Error::other("QUIC Initial generation failed").into())
        .and_then(|payload| relay_udp_payload_observed(&connect_targets, target.port, transport, payload));
    let kind = response
        .as_ref()
        .ok()
        .and_then(|result| payload.as_deref().and_then(|request| validate_quic_response(request, &result.payload)));
    let latency_ms = now_ms().saturating_sub(started);
    let (outcome, status, error, connected_addr, local_addr, route_report) = match response {
        Ok(result) if kind == Some(QuicResponseKind::Initial) => (
            "quic_initial_response".to_string(),
            "quic_initial_response".to_string(),
            "none".to_string(),
            result.connected_addr,
            result.local_addr,
            result.route_report,
        ),
        Ok(result) if kind.is_some() => (
            "quic_response".to_string(),
            "quic_response".to_string(),
            "none".to_string(),
            result.connected_addr,
            result.local_addr,
            result.route_report,
        ),
        Ok(result) => (
            if result.payload.is_empty() { "quic_empty" } else { "quic_error" }.to_string(),
            if result.payload.is_empty() { "quic_empty" } else { "quic_error" }.to_string(),
            if result.payload.is_empty() { "none" } else { "invalid QUIC response" }.to_string(),
            result.connected_addr,
            result.local_addr,
            result.route_report,
        ),
        Err(err) => ("quic_error".to_string(), "quic_error".to_string(), err.to_string(), None, None, None),
    };
    let mut result = ProbeResult {
        probe_type: "quic_reachability".to_string(),
        target: target.host.clone(),
        outcome,
        details: vec![
            ProbeDetail { key: "measurementScope".to_string(), value: "quic_initial_response".to_string() },
            ProbeDetail { key: "http3Validated".to_string(), value: "false".to_string() },
            ProbeDetail { key: "status".to_string(), value: status },
            ProbeDetail { key: "error".to_string(), value: error },
            ProbeDetail { key: "latencyMs".to_string(), value: latency_ms.to_string() },
            ProbeDetail { key: "port".to_string(), value: target.port.to_string() },
        ],
    };
    if let Some(addr) = connected_addr {
        result.details.push(ProbeDetail { key: "connectedIp".to_string(), value: addr.ip().to_string() });
    }
    append_route_details(&mut result.details, "", local_addr, route_report.as_ref());
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{net::UdpSocket, thread};

    #[test]
    fn quic_probe_uses_later_pinned_address() {
        let server = UdpSocket::bind("127.0.0.1:0").unwrap();
        server.set_read_timeout(Some(std::time::Duration::from_secs(6))).unwrap();
        let port = server.local_addr().unwrap().port();
        let worker = thread::spawn(move || {
            let mut request = [0; 2048];
            let Ok((_, peer)) = server.recv_from(&mut request) else {
                return;
            };
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
        let target = QuicTarget {
            host: "localhost".into(),
            connect_ip: Some("::1".into()),
            connect_ips: vec!["127.0.0.1".into()],
            port,
        };
        let result = run_quic_probe(&target, &TransportConfig::Direct { route_experiment: None });
        worker.join().unwrap();
        assert_eq!(result.outcome, "quic_response");
        assert!(
            result
                .details
                .iter()
                .any(|detail| detail.key == "measurementScope" && detail.value == "quic_initial_response")
        );
        assert!(result.details.iter().any(|detail| detail.key == "http3Validated" && detail.value == "false"));
    }

    #[test]
    fn quic_probe_retransmits_after_a_lost_initial() {
        let server = UdpSocket::bind("127.0.0.1:0").unwrap();
        server.set_read_timeout(Some(std::time::Duration::from_secs(6))).unwrap();
        let port = server.local_addr().unwrap().port();
        let worker = thread::spawn(move || {
            let mut request = [0; 2048];
            // Drop the first Initial; answer the retransmission.
            let Ok(_) = server.recv_from(&mut request) else {
                return 0;
            };
            let Ok((_, peer)) = server.recv_from(&mut request) else {
                return 1;
            };
            let dcid_len = request[5] as usize;
            let scid_offset = 6 + dcid_len;
            let scid_len = request[scid_offset] as usize;
            let mut response = vec![0x80, 0, 0, 0, 0, scid_len as u8];
            response.extend_from_slice(&request[scid_offset + 1..scid_offset + 1 + scid_len]);
            response.push(dcid_len as u8);
            response.extend_from_slice(&request[6..6 + dcid_len]);
            response.extend_from_slice(&ripdpi_packets::QUIC_V2_VERSION.to_be_bytes());
            server.send_to(&response, peer).unwrap();
            2
        });
        // An IP host keeps the single loopback candidate (no ::1 fallback).
        let target =
            QuicTarget { host: "127.0.0.1".into(), connect_ip: Some("127.0.0.1".into()), connect_ips: vec![], port };
        let result = run_quic_probe(&target, &TransportConfig::Direct { route_experiment: None });

        assert_eq!(worker.join().unwrap(), 2, "the server must see the retransmitted Initial");
        assert_eq!(result.outcome, "quic_response");
    }

    // A blocked path drops every Initial. The retransmit must fit in the wait
    // of a single exchange, not add a second full receive timeout.
    #[test]
    fn quic_probe_on_a_blackholed_path_keeps_one_receive_wait() {
        let server = UdpSocket::bind("127.0.0.1:0").unwrap();
        server.set_read_timeout(Some(std::time::Duration::from_secs(4))).unwrap();
        let port = server.local_addr().unwrap().port();
        let worker = thread::spawn(move || {
            let mut request = [0; 2048];
            let mut seen = 0;
            while server.recv_from(&mut request).is_ok() {
                seen += 1;
            }
            seen
        });
        let target =
            QuicTarget { host: "127.0.0.1".into(), connect_ip: Some("127.0.0.1".into()), connect_ips: vec![], port };

        let started = std::time::Instant::now();
        let result = run_quic_probe(&target, &TransportConfig::Direct { route_experiment: None });
        let elapsed = started.elapsed();

        assert_ne!(result.outcome, "quic_response");
        let one_wait = crate::connectivity::adapters::util::IO_TIMEOUT;
        assert!(elapsed < one_wait + std::time::Duration::from_millis(400), "probe waited {elapsed:?}");
        assert_eq!(worker.join().unwrap(), 2, "the Initial must still be retransmitted once");
    }

    #[test]
    fn quic_probe_rejects_reflected_initial() {
        let server = UdpSocket::bind("127.0.0.1:0").unwrap();
        let port = server.local_addr().unwrap().port();
        let worker = thread::spawn(move || {
            let mut payload = [0; 2048];
            let (size, peer) = server.recv_from(&mut payload).unwrap();
            server.send_to(&payload[..size], peer).unwrap();
        });
        let target =
            QuicTarget { host: "localhost".into(), connect_ip: Some("127.0.0.1".into()), connect_ips: vec![], port };
        let result = run_quic_probe(&target, &TransportConfig::Direct { route_experiment: None });
        worker.join().unwrap();
        assert_eq!(result.outcome, "quic_error");
        assert!(
            result
                .details
                .iter()
                .any(|detail| detail.key == "measurementScope" && detail.value == "quic_initial_response")
        );
        assert!(result.details.iter().any(|detail| detail.key == "http3Validated" && detail.value == "false"));
    }
}
