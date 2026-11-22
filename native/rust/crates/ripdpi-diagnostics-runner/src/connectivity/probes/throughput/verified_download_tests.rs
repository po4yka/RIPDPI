use std::io::{Read, Write};

use crate::connectivity::adapters::transport::direct_transport;
use crate::test_fixtures::read_until_marker;
use crate::types::DownloadVerification;

use super::https_fixture::with_trusted_https_peer;
use super::*;

const OK_SHA256: &str = "2689367b205c16ce32ed4200942b8b8b1e262dfc70d9bc9fbc77c49699a4f1df";

#[test]
fn complete_short_https_response_is_verified() {
    assert_download(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok", false, "verified_download_complete", 2);
    assert_download(
        b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1\r\no\r\n1\r\nk\r\n0\r\n\r\n",
        false,
        "verified_download_complete",
        2,
    );
}

#[test]
fn https_headers_without_body_progress_time_out() {
    assert_download(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n", true, "verified_download_timeout", 0);
}

#[test]
fn early_https_eof_keeps_counted_partial_body() {
    assert_download(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\no", false, "verified_download_early_eof", 1);
    assert_download(b"HTTP/1.1 200 OK\r\nConnection: close\r\n\r\no", false, "verified_download_early_eof", 1);
}

#[test]
fn complete_https_response_with_wrong_sha256_fails_verification() {
    assert_download(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nno", false, "verified_download_hash_mismatch", 2);
}

#[test]
fn differently_sized_https_response_stops_at_the_overrun_byte() {
    assert_download(b"HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nokay!", false, "verified_download_size_mismatch", 3);
}

fn assert_download(response: &[u8], hold_open: bool, outcome: &str, received: u64) {
    with_trusted_https_peer(
        |mut stream| {
            assert!(read_until_marker(&mut stream, b"\r\n\r\n").starts_with(b"GET /control.bin HTTP/1.1\r\n"));
            stream.write_all(response).unwrap();
            stream.flush().unwrap();
            if hold_open {
                let _ = stream.read(&mut [0]);
            } else {
                stream.conn.send_close_notify();
                let _ = stream.flush();
            }
        },
        |port, verifier| {
            let target = ThroughputTarget {
                id: "verified-download".into(),
                label: "Verified HTTPS download".into(),
                url: format!("https://localhost:{port}/control.bin"),
                connect_ip: None,
                connect_ips: vec![],
                port: None,
                is_control: false,
                window_bytes: 3,
                runs: 1,
                upload_body_bytes: None,
                verification: Some(DownloadVerification { expected_body_bytes: 2, sha256: OK_SHA256.into() }),
            };
            let mut progress = Vec::new();
            let result = run_throughput_probe_with_progress(
                &target,
                &direct_transport(),
                None,
                Some(verifier),
                &AtomicBool::new(false),
                &mut |measurement| progress.push(measurement.clone()),
            );
            assert_eq!(result.outcome, outcome, "{:?}", result.details);
            let last = progress.last().expect("final transfer evidence");
            assert_eq!(last.received_body_byte_count, received);
            assert_eq!(last.last_body_progress_ms.is_some(), received > 0);
            assert!(!result.details.iter().any(|detail| detail.value.contains(OK_SHA256)));
        },
    );
}
