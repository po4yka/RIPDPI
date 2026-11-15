use std::io::ErrorKind;

use super::*;

#[test]
fn response_io_classification_preserves_observed_kind() {
    use super::classification::classify_response_io_error;

    for (kind, expected) in [
        (ErrorKind::TimedOut, FatHeaderStatus::Timeout),
        (ErrorKind::WouldBlock, FatHeaderStatus::Timeout),
        (ErrorKind::ConnectionReset, FatHeaderStatus::Reset),
        (ErrorKind::UnexpectedEof, FatHeaderStatus::Inconclusive),
        (ErrorKind::BrokenPipe, FatHeaderStatus::Inconclusive),
        (ErrorKind::InvalidData, FatHeaderStatus::Inconclusive),
    ] {
        assert_eq!(classify_response_io_error(kind), expected);
    }
}

/// Serves one plaintext fat-header connection: answers request N with `responses[N]`, then closes
/// after the last one. Returns the Host header of every request it read.
fn serve_responses(responses: Vec<&'static [u8]>) -> (u16, std::thread::JoinHandle<Vec<String>>) {
    use std::io::{Read, Write};

    let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind fixture");
    let port = listener.local_addr().expect("fixture addr").port();
    let handle = std::thread::spawn(move || {
        let (mut stream, _) = listener.accept().expect("accept probe");
        let mut hosts = Vec::new();
        let mut pending = Vec::new();
        let mut chunk = [0u8; 4096];
        for response in responses {
            let end = loop {
                if let Some(end) = pending.windows(4).position(|window| window == b"\r\n\r\n") {
                    break end + 4;
                }
                let read = stream.read(&mut chunk).expect("read request");
                assert_ne!(read, 0, "probe closed before sending a full request");
                pending.extend_from_slice(&chunk[..read]);
            };
            let request: Vec<u8> = pending.drain(..end).collect();
            hosts.extend(
                String::from_utf8_lossy(&request)
                    .lines()
                    .filter_map(|line| line.strip_prefix("Host: ").map(str::to_owned)),
            );
            stream.write_all(response).expect("write response");
        }
        hosts
    });
    (port, handle)
}

const OK_KEEP_ALIVE: &[u8] = b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n";
const BAD_REQUEST_CLOSE: &[u8] = b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
const FORBIDDEN_CLOSE: &[u8] = b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";

fn local_target(port: u16, provider: &str) -> crate::types::TcpTarget {
    crate::types::TcpTarget {
        id: "CDN-01".to_string(),
        provider: provider.to_string(),
        ip: "127.0.0.1".to_string(),
        port,
        sni: None,
        asn: None,
        host_header: None,
        fat_header_requests: None,
        alt_port: None,
    }
}

#[test]
fn server_announced_close_is_not_reported_as_a_16kb_cutoff() {
    let (port, server) = serve_responses(vec![BAD_REQUEST_CLOSE]);
    let target = local_target(port, "CDN77 #1 HTTP");
    let transport = crate::transport::TransportConfig::Direct { route_experiment: None };

    // The runner falls back to the provider label when the target has no host header or SNI.
    let observation = run_fat_header_attempt(&target, &transport, "", &target.provider);

    assert_eq!(server.join().expect("fixture thread"), vec!["127.0.0.1".to_string()]);
    assert_eq!(observation.responses_seen, 1);
    assert_eq!(classify_fat_header_outcome(&observation.status), "inconclusive");
    assert_eq!(observation.failure_stage, Some("http_response"));
    let reason = observation.error.expect("the close reason reaches the probe detail");
    assert!(reason.contains("announced close") && reason.contains("HTTP 400"), "unexpected reason: {reason}");
}

#[test]
fn error_status_with_close_past_the_threshold_stays_inconclusive() {
    // Two keep-alive answers carry the probe past the threshold; then a block-page style 403 closes.
    let (port, server) = serve_responses(vec![OK_KEEP_ALIVE, OK_KEEP_ALIVE, FORBIDDEN_CLOSE]);
    let target = local_target(port, "Fixture");
    let transport = crate::transport::TransportConfig::Direct { route_experiment: None };

    let observation = run_fat_header_attempt(&target, &transport, "", "fixture.test");

    server.join().expect("fixture thread");
    assert_eq!(observation.responses_seen, 3);
    assert!(observation.threshold_covered);
    assert!(crate::util::fat_threshold_reached(observation.bytes_sent), "fixture must pass the threshold");
    assert_eq!(classify_fat_header_outcome(&observation.status), "inconclusive");
    assert_eq!(observation.failure_stage, Some("http_response"));
    let reason = observation.error.expect("the close reason reaches the probe detail");
    assert!(reason.contains("HTTP 403"), "unexpected reason: {reason}");
}

#[test]
fn error_status_without_close_is_inconclusive() {
    let (port, server) =
        serve_responses(vec![b"HTTP/1.1 451 Unavailable For Legal Reasons\r\nContent-Length: 0\r\n\r\n"]);
    let target = local_target(port, "Fixture");
    let transport = crate::transport::TransportConfig::Direct { route_experiment: None };

    let observation = run_fat_header_attempt(&target, &transport, "", "fixture.test");

    server.join().expect("fixture thread");
    assert_eq!(observation.responses_seen, 1);
    assert_eq!(classify_fat_header_outcome(&observation.status), "inconclusive");
    assert_eq!(observation.failure_stage, Some("http_response"));
    let reason = observation.error.expect("the status reaches the probe detail");
    assert!(reason.contains("HTTP 451") && !reason.contains("closed"), "unexpected reason: {reason}");
}

#[test]
fn unparsed_status_line_is_inconclusive() {
    let (port, server) = serve_responses(vec![b"NOT-HTTP\r\nContent-Length: 0\r\n\r\n"]);
    let target = local_target(port, "Fixture");
    let transport = crate::transport::TransportConfig::Direct { route_experiment: None };

    let observation = run_fat_header_attempt(&target, &transport, "", "fixture.test");

    server.join().expect("fixture thread");
    assert_eq!(observation.responses_seen, 0);
    assert_eq!(classify_fat_header_outcome(&observation.status), "inconclusive");
    assert_eq!(observation.failure_stage, Some("response_read"));
    let reason = observation.error.expect("the reason reaches the probe detail");
    assert!(reason.contains("invalid_framing"), "unexpected reason: {reason}");
}

#[test]
fn host_header_falls_back_to_the_target_ip_for_labels_and_invalid_values() {
    use super::request_io::effective_host_header;

    let mut target = crate::types::TcpTarget {
        id: "T".to_string(),
        provider: "Cloudflare".to_string(),
        ip: "2001:db8::1".to_string(),
        port: 80,
        sni: None,
        asn: None,
        host_header: None,
        fat_header_requests: None,
        alt_port: None,
    };
    assert_eq!(effective_host_header(&target, "Cloudflare"), "[2001:db8::1]");
    assert_eq!(effective_host_header(&target, ""), "[2001:db8::1]");
    assert_eq!(effective_host_header(&target, "Hetzner Cloud 2"), "[2001:db8::1]");
    assert_eq!(effective_host_header(&target, "example.com"), "example.com");
    target.ip = "192.0.2.7".to_string();
    assert_eq!(effective_host_header(&target, "CDN77 #1 HTTP"), "192.0.2.7");
    assert_eq!(effective_host_header(&target, "www.example.com:8080"), "www.example.com:8080");
    // A target whose explicit host header equals its label keeps it.
    target.host_header = Some("Cloudflare".to_string());
    assert_eq!(effective_host_header(&target, "Cloudflare"), "Cloudflare");
}

#[test]
fn malformed_host_authority_is_replaced_before_sending_a_head() {
    let (port, server) = serve_responses(vec![OK_KEEP_ALIVE]);
    let mut target = local_target(port, "Fixture");
    target.fat_header_requests = Some(1);
    let transport = crate::transport::TransportConfig::Direct { route_experiment: None };

    let observation = run_fat_header_attempt(&target, &transport, "", "fixture.test:abc");

    assert_eq!(server.join().expect("fixture thread"), ["127.0.0.1"]);
    assert_eq!(observation.responses_seen, 1);
}

#[test]
fn late_read_timeout_stays_a_timeout() {
    use std::io::{Read, Write};

    // Answers the first fat request, then keeps the connection open and never answers again:
    // the next header read ends with the socket read timeout.
    let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind fixture");
    let port = listener.local_addr().expect("fixture addr").port();
    let server = std::thread::spawn(move || {
        let (mut stream, _) = listener.accept().expect("accept probe");
        let mut request = Vec::new();
        let mut chunk = [0u8; 4096];
        while !request.windows(4).any(|window| window == b"\r\n\r\n") {
            let read = stream.read(&mut chunk).expect("read request");
            assert_ne!(read, 0, "probe closed before sending a full request");
            request.extend_from_slice(&chunk[..read]);
        }
        stream.write_all(OK_KEEP_ALIVE).expect("write response");
        stream.set_read_timeout(Some(std::time::Duration::from_secs(4))).expect("fixture timeout");
        while matches!(stream.read(&mut chunk), Ok(1..)) {}
    });
    let target = local_target(port, "127.0.0.1");
    let transport = crate::transport::TransportConfig::Direct { route_experiment: None };

    let observation = run_fat_header_attempt(&target, &transport, "", "127.0.0.1");

    assert_eq!(observation.responses_seen, 1, "{observation:?}");
    assert_eq!(observation.status, FatHeaderStatus::Timeout, "{observation:?}");
    assert!(observation.bytes_sent >= 16 * 1024);
    assert_eq!(classify_fat_header_outcome(&observation.status), "tcp_timeout");
    assert_eq!(observation.failure_stage, Some("response_read"));
    assert_eq!(observation.rst_timing_ms, None);
    server.join().expect("fixture thread");
}

#[test]
fn slow_drip_response_stops_at_the_scan_deadline() {
    use std::io::{Read, Write};
    use std::time::{Duration, Instant};

    // Answers the first request one byte every 100 ms: no read reaches the socket timeout.
    let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind fixture");
    let port = listener.local_addr().expect("fixture addr").port();
    let server = std::thread::spawn(move || {
        let (mut stream, _) = listener.accept().expect("accept probe");
        let _ = stream.read(&mut [0u8; 4096]);
        let started = Instant::now();
        for byte in b"HTTP/1.1 200 OK\r\nX-Slow: ".iter().chain(std::iter::repeat(&b'a')) {
            if started.elapsed() > Duration::from_secs(4) || stream.write_all(&[*byte]).is_err() {
                return;
            }
            std::thread::sleep(Duration::from_millis(100));
        }
    });
    let target = local_target(port, "127.0.0.1");
    let transport = crate::transport::TransportConfig::Direct { route_experiment: None };
    let started = Instant::now();

    let observation = ripdpi_diagnostics_contracts::util::with_scan_io_deadline(
        Some(Instant::now() + Duration::from_millis(500)),
        || run_fat_header_attempt(&target, &transport, "", "127.0.0.1"),
    );

    let elapsed = started.elapsed();
    assert_eq!(observation.error.as_deref(), Some("scan_deadline_exceeded"), "{observation:?}");
    assert_eq!(observation.status, FatHeaderStatus::Inconclusive);
    assert_eq!(observation.failure_stage, Some("administrative_stop"));
    assert!(elapsed < Duration::from_secs(2), "probe ran for {elapsed:?}");
    server.join().expect("fixture thread");
}

#[test]
fn informational_response_does_not_hide_next_request_error() {
    let (port, server) = serve_responses(vec![
        b"HTTP/1.1 103 Early Hints\r\n\r\nHTTP/1.1 200 OK\r\nTransfer-Encoding: gzip, chunked\r\n\r\n",
        FORBIDDEN_CLOSE,
    ]);
    let mut target = local_target(port, "Fixture");
    target.fat_header_requests = Some(2);
    let transport = crate::transport::TransportConfig::Direct { route_experiment: None };
    let observation = run_fat_header_attempt(&target, &transport, "", "fixture.test");
    server.join().expect("fixture thread");
    assert_eq!(observation.responses_seen, 2);
    assert_eq!(observation.status, FatHeaderStatus::Inconclusive);
    assert_eq!(observation.failure_stage, Some("http_response"));
    assert!(observation.error.expect("second response error").contains("HTTP 403"));
}

#[test]
fn malformed_head_framing_is_not_counted_as_a_response() {
    for response in [
        &b"HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\n"[..],
        b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 0\r\n\r\n",
        b"HTTP/1.1 101 Switching Protocols\r\n\r\n",
    ] {
        let (port, server) = serve_responses(vec![response]);
        let mut target = local_target(port, "Fixture");
        target.fat_header_requests = Some(1);
        let transport = crate::transport::TransportConfig::Direct { route_experiment: None };
        let observation = run_fat_header_attempt(&target, &transport, "", "fixture.test");
        server.join().expect("fixture thread");
        assert_eq!(observation.responses_seen, 0);
        assert_eq!(observation.status, FatHeaderStatus::Inconclusive);
        assert!(observation.error.expect("framing error").contains("invalid_framing"));
    }
}

#[test]
fn http_10_keep_alive_is_read_from_combined_connection_headers() {
    let (port, server) = serve_responses(vec![
        b"HTTP/1.0 200 OK\r\nConnection: extension\r\nConnection: keep-alive\r\n\r\n",
        b"HTTP/1.0 200 OK\r\n\r\n",
    ]);
    let mut target = local_target(port, "Fixture");
    target.fat_header_requests = Some(2);
    let transport = crate::transport::TransportConfig::Direct { route_experiment: None };
    let observation = run_fat_header_attempt(&target, &transport, "", "fixture.test");
    server.join().expect("fixture thread");
    assert_eq!(observation.responses_seen, 2);
    assert_eq!(observation.status, FatHeaderStatus::Success);
}

#[test]
fn std01_tls_name_on_a_custom_port_sends_client_hello_before_http() {
    use std::io::{Read, Write};
    use std::time::Duration;

    let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind peer");
    let port = listener.local_addr().expect("peer address").port();
    let server = std::thread::spawn(move || {
        let (mut peer, _) = listener.accept().expect("accept probe");
        peer.set_read_timeout(Some(Duration::from_secs(2))).expect("peer timeout");
        let mut first_bytes = [0u8; 6];
        peer.read_exact(&mut first_bytes).expect("read first flight");
        // A plaintext answer must never establish a successful TLS diagnostic.
        let _ = peer.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        first_bytes
    });
    let mut target = local_target(port, "TLS fixture");
    target.sni = Some("fixture.test".to_string());
    target.fat_header_requests = Some(1);
    let transport = crate::transport::TransportConfig::Direct { route_experiment: None };

    let observation = run_fat_header_attempt(&target, &transport, "fixture.test", "fixture.test");

    let first_bytes = server.join().expect("peer thread");
    assert_eq!(&first_bytes[..2], &[0x16, 0x03], "first flight must be a TLS handshake record");
    assert_eq!(first_bytes[5], 0x01, "first handshake message must be ClientHello");
    assert_eq!(observation.status, FatHeaderStatus::HandshakeFailed);
    assert_eq!(observation.failure_stage, Some("tls_handshake"));
    assert_eq!(observation.responses_seen, 0);
    assert_eq!(observation.bytes_sent, 0, "no HTTP payload may be sent before TLS");
}

#[test]
fn valid_responses_count_even_when_coverage_is_incomplete() {
    let close = &b"HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n"[..];
    for (replies, planned, covered, error) in [
        (vec![close], 1, false, "threshold_not_covered"),
        (vec![OK_KEEP_ALIVE, close], 16, true, "peer_closed_before_series_end"),
    ] {
        let seen = replies.len();
        let (port, server) = serve_responses(replies);
        let mut target = local_target(port, "Fixture");
        target.fat_header_requests = Some(planned);
        let transport = crate::transport::TransportConfig::Direct { route_experiment: None };
        let result = run_fat_header_attempt(&target, &transport, "", "fixture.test");
        assert_eq!(server.join().unwrap().len(), seen);
        assert_eq!(result.responses_seen, seen);
        assert_eq!(result.requests_written, seen);
        assert_eq!(result.http_status, Some(200));
        assert_eq!(result.status, FatHeaderStatus::Inconclusive);
        assert_eq!(result.threshold_covered, covered);
        assert_eq!(result.error.as_deref(), Some(error));
    }
}

#[test]
fn refusal_at_the_byte_boundary_does_not_confirm_coverage() {
    let (port, peer) = serve_responses(vec![OK_KEEP_ALIVE, FORBIDDEN_CLOSE]);
    let target = local_target(port, "Fixture");
    let transport = crate::transport::TransportConfig::Direct { route_experiment: None };
    let result = run_fat_header_attempt(&target, &transport, "", "fixture.test");
    assert_eq!(peer.join().unwrap().len(), 2);
    assert_eq!(result.responses_seen, 2);
    assert!(result.bytes_at_last_response >= crate::util::FAT_HEADER_THRESHOLD_BYTES);
    assert_eq!(result.http_status, Some(403));
    assert!(!result.threshold_covered);
    assert_eq!(result.status, FatHeaderStatus::Inconclusive);
}
