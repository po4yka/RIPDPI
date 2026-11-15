use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Instant;

use ripdpi_packets::{
    QUIC_V1_VERSION, QuicInitialPacketLayout, QuicProbeResponseKind, build_probe_quic_initial,
    canonical_quic_probe_host, packetize_quic_initial, parse_quic_initial_seed, validate_quic_probe_response,
};

use crate::connectivity::adapters::transport::{
    TransportConfig, TransportError, UdpRelayResult, quic_connect_targets, relay_quic_probe_observed,
};
use crate::types::{ProbeDetail, ProbeResult, QuicTarget};

use super::support::append_route_details;

pub fn run_quic_probe(target: &QuicTarget, transport: &TransportConfig, cancel: &AtomicBool) -> ProbeResult {
    let started = Instant::now();
    let response = if cancel.load(Ordering::Acquire) {
        Err(TransportError::ProbeAborted("cancelled"))
    } else if target.port == 0 {
        Err(std::io::Error::new(std::io::ErrorKind::InvalidInput, "QUIC target port must be nonzero").into())
    } else if canonical_quic_probe_host(&target.host).is_none() {
        Err(std::io::Error::new(std::io::ErrorKind::InvalidInput, "invalid QUIC target host").into())
    } else {
        relay_quic_probe_observed(
            &quic_connect_targets(target),
            target.port,
            transport,
            cancel,
            &mut || build_probe_flight(&target.host),
            &|request, response| validate_quic_probe_response(request, response).is_some(),
        )
    };
    let mut details = vec![
        ProbeDetail { key: "measurementScope".to_string(), value: "quic_initial_response".to_string() },
        ProbeDetail { key: "http3Validated".to_string(), value: "false".to_string() },
        ProbeDetail { key: "latencyMs".to_string(), value: started.elapsed().as_millis().to_string() },
        ProbeDetail { key: "port".to_string(), value: target.port.to_string() },
    ];
    let (outcome, error) = match response {
        Ok((observation, request)) => record_reply(&mut details, &request, observation),
        Err(error) => (transport_outcome(&error), error.to_string()),
    };
    details.push(ProbeDetail { key: "status".to_string(), value: outcome.to_string() });
    details.push(ProbeDetail { key: "error".to_string(), value: error });
    ProbeResult {
        probe_type: "quic_reachability".to_string(),
        target: target.host.clone(),
        outcome: outcome.into(),
        details,
    }
}

fn build_probe_flight(host: &str) -> Option<(Vec<u8>, Vec<u8>)> {
    let initial = build_probe_quic_initial(QUIC_V1_VERSION, Some(host))?;
    let seed = parse_quic_initial_seed(&initial)?;
    let mut layout = QuicInitialPacketLayout::contiguous(initial.len());
    layout.packet_number = 1;
    let repeat = packetize_quic_initial(&seed, &layout)?;
    Some((initial, repeat))
}

fn record_reply(details: &mut Vec<ProbeDetail>, request: &[u8], observation: UdpRelayResult) -> (&'static str, String) {
    let outcome = match validate_quic_probe_response(request, &observation.payload) {
        Some(QuicProbeResponseKind::InitialCrypto) => "quic_initial_response",
        Some(QuicProbeResponseKind::InitialAck) => "quic_initial_ack",
        Some(QuicProbeResponseKind::InitialOther) => "quic_initial_other",
        Some(QuicProbeResponseKind::InitialClose) => "quic_initial_close",
        Some(QuicProbeResponseKind::Retry) => "quic_retry",
        Some(QuicProbeResponseKind::VersionNegotiation) => "quic_version_negotiation",
        None => return ("quic_invalid_response", "invalid QUIC response".to_string()),
    };
    if let Some(addr) = observation.connected_addr {
        details.push(ProbeDetail { key: "connectedIp".to_string(), value: addr.ip().to_string() });
    }
    append_route_details(details, "", observation.local_addr, observation.route_report.as_ref());
    (outcome, "none".to_string())
}

fn transport_outcome(error: &TransportError) -> &'static str {
    match error {
        TransportError::ProbeAborted("cancelled") => "quic_cancelled",
        TransportError::ProbeAborted("deadline_exceeded") | TransportError::ScanDeadlineExceeded => {
            "quic_deadline_exceeded"
        }
        TransportError::UdpRecvTimeout | TransportError::UdpRecvWouldBlock => "quic_timeout",
        TransportError::UdpRecvUnmatched => "quic_invalid_response",
        TransportError::UdpRecvFailed { .. } => "quic_error",
        _ => "quic_setup_error",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{net::UdpSocket, thread};

    #[test]
    fn repeated_flight_uses_the_same_initial_context() {
        let (initial, repeat) = build_probe_flight("localhost").unwrap();
        assert_eq!((initial.len(), repeat.len()), (1200, 1200));
        assert_ne!(initial, repeat);
        assert_eq!(parse_quic_initial_seed(&initial), parse_quic_initial_seed(&repeat));
    }

    #[test]
    fn invalid_quic_target_is_reported_as_setup_failure() {
        let raw = TransportConfig::Direct { route_experiment: None };
        for (host, port) in [("invalid host", 443), ("", 443), ("localhost", 0)] {
            let target = QuicTarget { host: host.into(), connect_ip: None, connect_ips: vec![], port };
            let result = run_quic_probe(&target, &raw, &AtomicBool::new(false));
            assert_eq!(result.outcome, "quic_setup_error");
        }
    }

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
        let result =
            run_quic_probe(&target, &TransportConfig::Direct { route_experiment: None }, &AtomicBool::new(false));
        worker.join().unwrap();
        assert_eq!(result.outcome, "quic_version_negotiation");
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
        let result =
            run_quic_probe(&target, &TransportConfig::Direct { route_experiment: None }, &AtomicBool::new(false));

        assert_eq!(worker.join().unwrap(), 2, "the server must see the retransmitted Initial");
        assert_eq!(result.outcome, "quic_version_negotiation");
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
        let result =
            run_quic_probe(&target, &TransportConfig::Direct { route_experiment: None }, &AtomicBool::new(false));
        let elapsed = started.elapsed();

        assert_eq!(result.outcome, "quic_timeout");
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
            server
        });
        let target =
            QuicTarget { host: "127.0.0.1".into(), connect_ip: Some("127.0.0.1".into()), connect_ips: vec![], port };
        let result =
            run_quic_probe(&target, &TransportConfig::Direct { route_experiment: None }, &AtomicBool::new(false));
        worker.join().unwrap();
        assert_eq!(result.outcome, "quic_invalid_response");
        assert!(
            result
                .details
                .iter()
                .any(|detail| detail.key == "measurementScope" && detail.value == "quic_initial_response")
        );
        assert!(result.details.iter().any(|detail| detail.key == "http3Validated" && detail.value == "false"));
    }

    #[test]
    fn dpi24_quic_probe_ignores_unrelated_vn_before_correlated_reply() {
        let server = UdpSocket::bind("127.0.0.1:0").unwrap();
        server.set_read_timeout(Some(std::time::Duration::from_secs(1))).unwrap();
        let port = server.local_addr().unwrap().port();
        let worker = thread::spawn(move || {
            let mut request = [0; 2048];
            let (_, peer) = server.recv_from(&mut request).unwrap();
            let dcid_len = usize::from(request[5]);
            let scid_offset = 6 + dcid_len;
            let scid_len = usize::from(request[scid_offset]);
            let mut response = vec![0x80, 0, 0, 0, 0, scid_len as u8];
            response.extend_from_slice(&request[scid_offset + 1..scid_offset + 1 + scid_len]);
            response.push(dcid_len as u8);
            response.extend_from_slice(&request[6..6 + dcid_len]);
            response.extend_from_slice(&ripdpi_packets::QUIC_V2_VERSION.to_be_bytes());
            let mut unrelated = response.clone();
            unrelated[6] ^= 1;
            server.send_to(&unrelated, peer).unwrap();
            server.send_to(&response, peer).unwrap();
        });
        let target =
            QuicTarget { host: "localhost".into(), connect_ip: Some("127.0.0.1".into()), connect_ips: vec![], port };
        let result =
            run_quic_probe(&target, &TransportConfig::Direct { route_experiment: None }, &AtomicBool::new(false));
        worker.join().unwrap();
        assert_eq!(result.outcome, "quic_version_negotiation");
    }
}
