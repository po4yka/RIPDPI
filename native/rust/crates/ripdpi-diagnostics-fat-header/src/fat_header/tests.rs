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
fn classify_fat_error_message_timed_out() {
    assert_eq!(classify_fat_error_message("timed out", 0, 0), FatHeaderStatus::Timeout);
}

#[test]
fn classify_fat_error_message_timeout_before_threshold_unchanged() {
    assert_eq!(classify_fat_error_message("timed out", 100, 0), FatHeaderStatus::Timeout);
}

#[test]
fn classify_fat_error_message_freeze_after_threshold() {
    assert_eq!(
        classify_fat_error_message("timed out", FAT_HEADER_THRESHOLD_BYTES, 0),
        FatHeaderStatus::FreezeAfterThreshold
    );
}

#[test]
fn classify_fat_error_message_freeze_after_threshold_with_responses() {
    assert_eq!(classify_fat_error_message("timed out", 8 * 1024, 1), FatHeaderStatus::FreezeAfterThreshold);
}

#[test]
fn classify_fat_error_message_connection_reset_early() {
    assert_eq!(classify_fat_error_message("connection reset", 100, 0), FatHeaderStatus::Reset);
}

#[test]
fn classify_fat_error_message_connection_reset_late() {
    assert_eq!(
        classify_fat_error_message("connection reset", FAT_HEADER_THRESHOLD_BYTES, 0),
        FatHeaderStatus::ThresholdCutoff
    );
}

#[test]
fn classify_fat_error_message_broken_pipe() {
    assert_eq!(classify_fat_error_message("broken pipe", 8 * 1024, 1), FatHeaderStatus::ThresholdCutoff);
}

#[test]
fn classify_fat_error_message_unknown() {
    assert_eq!(classify_fat_error_message("something else", 0, 0), FatHeaderStatus::ConnectFailed);
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

/// Serves one plaintext fat-header connection like a strict origin: answer the first request with
/// `400` and `Connection: close`, then close. Returns the Host header of every request it read.
fn serve_close_after_first_response() -> (u16, std::thread::JoinHandle<Vec<String>>) {
    use std::io::{Read, Write};

    let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind fixture");
    let port = listener.local_addr().expect("fixture addr").port();
    let handle = std::thread::spawn(move || {
        let (mut stream, _) = listener.accept().expect("accept probe");
        let mut request = Vec::new();
        let mut chunk = [0u8; 4096];
        while !request.windows(4).any(|window| window == b"\r\n\r\n") {
            let read = stream.read(&mut chunk).expect("read request");
            assert_ne!(read, 0, "probe closed before sending a full request");
            request.extend_from_slice(&chunk[..read]);
        }
        stream
            .write_all(b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            .expect("write response");
        String::from_utf8_lossy(&request)
            .lines()
            .filter_map(|line| line.strip_prefix("Host: ").map(str::to_owned))
            .collect()
    });
    (port, handle)
}

#[test]
fn server_announced_close_is_not_reported_as_a_16kb_cutoff() {
    let (port, server) = serve_close_after_first_response();
    let target = crate::types::TcpTarget {
        id: "CDN-01".to_string(),
        provider: "CDN77 #1 HTTP".to_string(),
        ip: "127.0.0.1".to_string(),
        port,
        sni: None,
        asn: None,
        host_header: None,
        fat_header_requests: None,
        alt_port: None,
    };
    let transport = crate::transport::TransportConfig::Direct { route_experiment: None };

    // The runner falls back to the provider label when the target has no host header or SNI.
    let observation = run_fat_header_attempt(&target, &transport, "", &target.provider);

    assert_eq!(server.join().expect("fixture thread"), vec!["127.0.0.1".to_string()]);
    assert_eq!(observation.responses_seen, 1);
    assert_ne!(observation.status, FatHeaderStatus::ThresholdCutoff);
    assert_eq!(classify_fat_header_outcome(&observation.status), "tcp_connect_failed");
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
