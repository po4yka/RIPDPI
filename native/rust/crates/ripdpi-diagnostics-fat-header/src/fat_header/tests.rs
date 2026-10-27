use std::io::ErrorKind;

use crate::util::FAT_HEADER_THRESHOLD_BYTES;

use super::*;

#[test]
fn classify_fat_io_error_timeout() {
    assert_eq!(classify_fat_io_error(ErrorKind::TimedOut, 0, 0), FatHeaderStatus::Timeout);
    assert_eq!(classify_fat_io_error(ErrorKind::WouldBlock, 0, 0), FatHeaderStatus::Timeout);
}

#[test]
fn classify_fat_io_error_timeout_before_threshold_unchanged() {
    assert_eq!(classify_fat_io_error(ErrorKind::TimedOut, 100, 0), FatHeaderStatus::Timeout);
}

#[test]
fn classify_fat_io_error_freeze_after_threshold_bytes() {
    assert_eq!(
        classify_fat_io_error(ErrorKind::TimedOut, FAT_HEADER_THRESHOLD_BYTES, 0),
        FatHeaderStatus::FreezeAfterThreshold
    );
}

#[test]
fn classify_fat_io_error_freeze_after_threshold_with_responses() {
    assert_eq!(classify_fat_io_error(ErrorKind::WouldBlock, 8 * 1024, 1), FatHeaderStatus::FreezeAfterThreshold);
}

#[test]
fn classify_fat_io_error_reset_early() {
    assert_eq!(classify_fat_io_error(ErrorKind::ConnectionReset, 100, 0), FatHeaderStatus::Reset);
    assert_eq!(classify_fat_io_error(ErrorKind::BrokenPipe, 100, 0), FatHeaderStatus::Reset);
}

#[test]
fn classify_fat_io_error_threshold_cutoff_late() {
    assert_eq!(
        classify_fat_io_error(ErrorKind::ConnectionReset, FAT_HEADER_THRESHOLD_BYTES, 0),
        FatHeaderStatus::ThresholdCutoff
    );
}

#[test]
fn classify_fat_io_error_threshold_cutoff_with_responses() {
    assert_eq!(classify_fat_io_error(ErrorKind::UnexpectedEof, 8 * 1024, 1), FatHeaderStatus::ThresholdCutoff);
}

#[test]
fn classify_fat_io_error_unknown_is_connect_failed() {
    assert_eq!(classify_fat_io_error(ErrorKind::Other, 0, 0), FatHeaderStatus::ConnectFailed);
}

#[test]
fn classify_fat_header_outcome_all_variants() {
    assert_eq!(classify_fat_header_outcome(&FatHeaderStatus::Success), "tcp_fat_header_ok");
    assert_eq!(classify_fat_header_outcome(&FatHeaderStatus::ThresholdCutoff), "tcp_16kb_blocked");
    assert_eq!(classify_fat_header_outcome(&FatHeaderStatus::FreezeAfterThreshold), "tcp_freeze_after_threshold");
    assert_eq!(classify_fat_header_outcome(&FatHeaderStatus::Reset), "tcp_reset");
    assert_eq!(classify_fat_header_outcome(&FatHeaderStatus::Timeout), "tcp_timeout");
    assert_eq!(classify_fat_header_outcome(&FatHeaderStatus::ConnectFailed), "tcp_connect_failed");
    assert_eq!(classify_fat_header_outcome(&FatHeaderStatus::HandshakeFailed), "tls_handshake_failed");
}

#[test]
fn classify_tcp_block_method_all_variants() {
    assert_eq!(classify_tcp_block_method(&FatHeaderStatus::Success), "none");
    assert_eq!(classify_tcp_block_method(&FatHeaderStatus::Reset), "rst_injection");
    assert_eq!(classify_tcp_block_method(&FatHeaderStatus::ThresholdCutoff), "window_cap");
    assert_eq!(classify_tcp_block_method(&FatHeaderStatus::FreezeAfterThreshold), "window_cap");
    assert_eq!(classify_tcp_block_method(&FatHeaderStatus::Timeout), "timeout");
    assert_eq!(classify_tcp_block_method(&FatHeaderStatus::ConnectFailed), "connection_refused");
    assert_eq!(classify_tcp_block_method(&FatHeaderStatus::HandshakeFailed), "connection_refused");
}

#[test]
fn classify_rst_origin_in_path() {
    // RST at 10ms with RTT of 8ms => within 2x => in-path.
    assert_eq!(classify_rst_origin(Some(8), Some(10)), "in_path_rst");
    // RST at exactly 2x RTT => still in-path.
    assert_eq!(classify_rst_origin(Some(10), Some(20)), "in_path_rst");
}

#[test]
fn classify_rst_origin_server() {
    // RST at 50ms with RTT of 10ms => beyond 2x => server.
    assert_eq!(classify_rst_origin(Some(10), Some(50)), "server_rst");
}

#[test]
fn classify_rst_origin_unknown() {
    assert_eq!(classify_rst_origin(None, Some(10)), "unknown");
    assert_eq!(classify_rst_origin(Some(10), None), "unknown");
    assert_eq!(classify_rst_origin(None, None), "unknown");
}

#[test]
fn classify_rst_origin_zero_rtt_is_server() {
    // Zero RTT means we cannot meaningfully compare, falls through to server_rst.
    assert_eq!(classify_rst_origin(Some(0), Some(5)), "server_rst");
}

#[test]
fn fat_status_label_all_variants() {
    assert_eq!(fat_status_label(&FatHeaderStatus::Success), "ok");
    assert_eq!(fat_status_label(&FatHeaderStatus::ThresholdCutoff), "threshold_cutoff");
    assert_eq!(fat_status_label(&FatHeaderStatus::FreezeAfterThreshold), "freeze_after_threshold");
    assert_eq!(fat_status_label(&FatHeaderStatus::Reset), "reset");
    assert_eq!(fat_status_label(&FatHeaderStatus::Timeout), "timeout");
    assert_eq!(fat_status_label(&FatHeaderStatus::ConnectFailed), "connect_failed");
    assert_eq!(fat_status_label(&FatHeaderStatus::HandshakeFailed), "tls_failed");
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
    assert_ne!(observation.status, FatHeaderStatus::ThresholdCutoff);
    assert_eq!(classify_fat_header_outcome(&observation.status), "tcp_connect_failed");
    let reason = observation.error.expect("the close reason reaches the probe detail");
    assert!(reason.contains("server closed") && reason.contains("HTTP 400"), "unexpected reason: {reason}");
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
    assert!(crate::util::fat_threshold_reached(observation.bytes_sent), "fixture must pass the threshold");
    assert_eq!(classify_fat_header_outcome(&observation.status), "tcp_connect_failed");
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
    assert_eq!(classify_fat_header_outcome(&observation.status), "tcp_connect_failed");
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
    assert_eq!(observation.responses_seen, 1);
    assert_eq!(classify_fat_header_outcome(&observation.status), "tcp_connect_failed");
    let reason = observation.error.expect("the reason reaches the probe detail");
    assert!(reason.contains("without a valid HTTP status line"), "unexpected reason: {reason}");
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
fn silent_drop_after_the_first_response_is_a_freeze_after_threshold() {
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
    assert_eq!(observation.status, FatHeaderStatus::FreezeAfterThreshold, "{observation:?}");
    assert_eq!(classify_fat_header_outcome(&observation.status), "tcp_freeze_after_threshold");
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
    assert_eq!(observation.status, FatHeaderStatus::ConnectFailed);
    assert!(elapsed < Duration::from_secs(2), "probe ran for {elapsed:?}");
    server.join().expect("fixture thread");
}
