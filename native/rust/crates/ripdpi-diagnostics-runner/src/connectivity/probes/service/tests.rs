use std::net::UdpSocket;

use super::*;
use crate::test_fixtures::HttpTextServer;

fn detail<'a>(result: &'a ProbeResult, key: &str) -> &'a str {
    result.details.iter().find(|detail| detail.key == key).map_or("", |detail| detail.value.as_str())
}

// The bootstrap server answered 503 while the QUIC leg failed: the server
// responded, so the service is partial, not blocked.
#[test]
fn server_error_bootstrap_with_a_failed_leg_is_partial() {
    let bootstrap = HttpTextServer::start_text("HTTP/1.1 503 Service Unavailable", "busy");
    let closed_udp_port = UdpSocket::bind("127.0.0.1:0").expect("bind").local_addr().expect("addr").port();
    let target = ServiceTarget {
        id: "svc".to_string(),
        service: "svc".to_string(),
        bootstrap_url: Some(format!("http://127.0.0.1:{}/", bootstrap.port())),
        media_url: None,
        tcp_endpoint_host: None,
        tcp_endpoint_ip: None,
        tcp_endpoint_port: 443,
        tls_server_name: None,
        quic_host: Some("localhost".to_string()),
        quic_connect_ip: Some("127.0.0.1".to_string()),
        quic_port: closed_udp_port,
    };

    let result = run_service_probe(
        &target,
        &TransportConfig::Direct { route_experiment: None },
        None,
        None,
        &AtomicBool::new(false),
    );

    assert_eq!(detail(&result, "bootstrapStatus"), "http_status_503");
    assert_ne!(detail(&result, "quicStatus"), "quic_response");
    assert_eq!(result.outcome, "service_partial");
}

// A redirect answer (for example a 302 to an ISP stub page) is not a
// healthy bootstrap: the service is partial, not ok.
#[test]
fn redirect_bootstrap_is_partial() {
    let bootstrap = HttpTextServer::start_text("HTTP/1.1 302 Found", "moved");
    let target = ServiceTarget {
        id: "svc".to_string(),
        service: "svc".to_string(),
        bootstrap_url: Some(format!("http://127.0.0.1:{}/", bootstrap.port())),
        media_url: None,
        tcp_endpoint_host: None,
        tcp_endpoint_ip: None,
        tcp_endpoint_port: 443,
        tls_server_name: None,
        quic_host: None,
        quic_connect_ip: None,
        quic_port: 443,
    };

    let result = run_service_probe(
        &target,
        &TransportConfig::Direct { route_experiment: None },
        None,
        None,
        &AtomicBool::new(false),
    );

    assert_eq!(detail(&result, "bootstrapStatus"), "http_status_302");
    assert_eq!(result.outcome, "service_partial");
}

fn service_target() -> ServiceTarget {
    ServiceTarget {
        id: "service".into(),
        service: "service".into(),
        bootstrap_url: None,
        media_url: None,
        tcp_endpoint_host: None,
        tcp_endpoint_ip: None,
        tcp_endpoint_port: 443,
        tls_server_name: None,
        quic_host: None,
        quic_connect_ip: None,
        quic_port: 443,
    }
}

fn circumvention_target() -> CircumventionTarget {
    CircumventionTarget {
        id: "tool".into(),
        tool: "tool".into(),
        bootstrap_url: None,
        handshake_host: None,
        handshake_ip: None,
        handshake_port: 443,
        tls_server_name: None,
    }
}

#[test]
fn quic_retry_and_version_negotiation_are_responses_but_not_completed_endpoint_checks() {
    for status in ["quic_retry", "quic_version_negotiation"] {
        assert!(matches!(endpoint_evidence(&[socket_leg(status)]), EndpointEvidence::Partial));
    }
}

#[test]
fn quic_setup_error_does_not_downgrade_a_reached_service() {
    assert!(matches!(
        endpoint_evidence(&[EndpointLeg::Reached, socket_leg("quic_setup_error")]),
        EndpointEvidence::Reached
    ));
    assert!(matches!(
        endpoint_evidence(&[EndpointLeg::Reached, socket_leg("quic_timeout")]),
        EndpointEvidence::Partial
    ));
}

#[test]
fn no_measured_endpoint_is_inconclusive() {
    let transport = TransportConfig::Direct { route_experiment: None };
    let cancel = AtomicBool::new(false);
    let service = run_service_probe(&service_target(), &transport, None, None, &cancel);
    let circumvention = run_circumvention_probe(&circumvention_target(), &transport, None, None, &cancel);
    for row in [&service, &circumvention] {
        assert_eq!(row.outcome, "inconclusive");
        assert_eq!(detail(row, "bootstrapStatus"), "not_run");
    }

    let target = ServiceTarget { bootstrap_url: Some("ftp://127.0.0.1/".into()), ..service_target() };
    let row = run_service_probe(&target, &transport, None, None, &cancel);
    assert_eq!(row.outcome, "inconclusive");
    assert_eq!(detail(&row, "bootstrapStatus"), "invalid_target");
    assert_eq!(detail(&row, "bootstrapDetail"), "unsupported_url_scheme");
}

#[test]
fn service_and_circumvention_accept_204_and_report_media_redirect_as_partial() {
    let ok = HttpTextServer::start(|_| b"HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".to_vec());
    let redirect = HttpTextServer::start(|_| b"HTTP/1.1 302 Found\r\nContent-Length: 0\r\n\r\n".to_vec());
    let transport = TransportConfig::Direct { route_experiment: None };
    let cancel = AtomicBool::new(false);
    let url = format!("http://127.0.0.1:{}/", ok.port());
    let mut service = ServiceTarget { bootstrap_url: Some(url.clone()), ..service_target() };
    let row = run_service_probe(&service, &transport, None, None, &cancel);
    assert_eq!(row.outcome, "service_ok");
    assert_eq!(detail(&row, "bootstrapStatus"), "http_status_204");

    service.media_url = Some(format!("http://127.0.0.1:{}/", redirect.port()));
    let row = run_service_probe(&service, &transport, None, None, &cancel);
    assert_eq!(row.outcome, "service_partial");
    assert_eq!(detail(&row, "mediaStatus"), "http_status_302");

    let target = CircumventionTarget { bootstrap_url: Some(url), ..circumvention_target() };
    let row = run_circumvention_probe(&target, &transport, None, None, &cancel);
    assert_eq!(row.outcome, "circumvention_ok");
    assert_eq!(detail(&row, "probeRetryCount"), "0");
}

#[test]
fn http_451_is_a_responding_endpoint_without_path_attribution() {
    let bootstrap = HttpTextServer::start_text("HTTP/1.1 451 Unavailable For Legal Reasons", "");
    let target =
        ServiceTarget { bootstrap_url: Some(format!("http://127.0.0.1:{}/", bootstrap.port())), ..service_target() };
    let transport = TransportConfig::Direct { route_experiment: None };
    let row = run_service_probe(&target, &transport, None, None, &AtomicBool::new(false));
    assert_eq!(row.outcome, "service_partial");
    assert_eq!(detail(&row, "bootstrapStatus"), "http_status_451");
}

#[test]
fn cancelling_bootstrap_closes_active_connection_and_prevents_followup() {
    use std::io::{BufRead, BufReader, Read};
    use std::net::TcpListener;
    use std::sync::atomic::Ordering;
    use std::time::Duration;

    let bootstrap = TcpListener::bind("127.0.0.1:0").unwrap();
    let following = TcpListener::bind("127.0.0.1:0").unwrap();
    following.set_nonblocking(true).unwrap();
    let bootstrap_addr = bootstrap.local_addr().unwrap();
    let following_addr = following.local_addr().unwrap();
    let cancel = AtomicBool::new(false);
    std::thread::scope(|scope| {
        let peer = scope.spawn(|| {
            let (peer, _) = bootstrap.accept().unwrap();
            peer.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
            let mut reader = BufReader::new(peer);
            loop {
                let mut header = String::new();
                assert!(reader.read_line(&mut header).unwrap() > 0);
                if header == "\r\n" {
                    break;
                }
            }
            cancel.store(true, Ordering::Release);
            assert_eq!(reader.read(&mut [0]).unwrap(), 0, "cancel must shut down active I/O");
        });
        let target = CircumventionTarget {
            bootstrap_url: Some(format!("http://{bootstrap_addr}/")),
            handshake_ip: Some(following_addr.ip().to_string()),
            handshake_port: following_addr.port(),
            ..circumvention_target()
        };
        let transport = TransportConfig::Direct { route_experiment: None };
        let row = run_circumvention_probe(&target, &transport, None, None, &cancel);
        peer.join().unwrap();
        assert_eq!(detail(&row, "bootstrapStatus"), "interrupted");
        assert_eq!(detail(&row, "handshakeStatus"), "interrupted");
        assert_eq!(detail(&row, "probeRetryCount"), "0");
        assert_eq!(row.outcome, "inconclusive");
        assert_eq!(following.accept().unwrap_err().kind(), std::io::ErrorKind::WouldBlock);
    });
}

#[test]
fn retry_preserves_both_bootstrap_attempts() {
    use std::sync::atomic::{AtomicUsize, Ordering};

    let attempts = Arc::new(AtomicUsize::new(0));
    let responses = Arc::clone(&attempts);
    let bootstrap = HttpTextServer::start(move |_| match responses.fetch_add(1, Ordering::Relaxed) {
        0 => b"HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\nServer: first\r\n\r\n".to_vec(),
        _ => b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nServer: retry\r\n\r\nok".to_vec(),
    });
    let transport = TransportConfig::Direct { route_experiment: None };
    let target = CircumventionTarget {
        bootstrap_url: Some(format!("http://127.0.0.1:{}/", bootstrap.port())),
        ..circumvention_target()
    };
    let row = run_circumvention_probe(&target, &transport, None, None, &AtomicBool::new(false));
    assert_eq!(attempts.load(Ordering::Relaxed), 2);
    assert_eq!(row.outcome, "circumvention_ok");
    assert_eq!(detail(&row, "bootstrapStatus"), "http_ok");
    assert_eq!(detail(&row, "bootstrapDetail"), "200 OK retry");
    assert_eq!(detail(&row, "bootstrapInitialStatus"), "http_status_503");
    assert_eq!(detail(&row, "bootstrapInitialDetail"), "503 Unavailable first");
    assert_eq!(detail(&row, "probeRetryCount"), "1");
}
