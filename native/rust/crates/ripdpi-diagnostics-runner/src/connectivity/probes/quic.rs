use ripdpi_packets::{QUIC_V1_VERSION, QuicResponseKind, build_probe_quic_initial, validate_quic_response};

use crate::connectivity::adapters::transport::{TransportConfig, quic_connect_targets, relay_udp_payload_observed};
use crate::connectivity::adapters::util::now_ms;
use crate::types::{ProbeDetail, ProbeResult, QuicTarget};

use super::support::append_route_details;

pub fn run_quic_probe(target: &QuicTarget, transport: &TransportConfig) -> ProbeResult {
    let started = now_ms();
    let connect_targets = quic_connect_targets(target);
    let payload = build_probe_quic_initial(QUIC_V1_VERSION, Some(target.host.as_str()));
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
    }
}
