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

#[test]
fn proxy_style_403_is_a_wrong_status_not_a_stall() {
    assert_download(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 2\r\n\r\nno", false, "verified_download_failed", 0);
}

#[test]
fn volume_leg_reports_declared_class_and_asn_only_when_present() {
    for (declared, expected) in [(Some(("cdn", 13335)), Some(("cdn", "13335"))), (None, None)] {
        with_trusted_https_peer(
            |mut stream| {
                read_until_marker(&mut stream, b"\r\n\r\n");
                stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok").unwrap();
                stream.flush().unwrap();
                stream.conn.send_close_notify();
                let _ = stream.flush();
            },
            |port, verifier| {
                let target = ThroughputTarget {
                    id: "transfer-volume-short".into(),
                    label: "Short response".into(),
                    url: format!("https://localhost:{port}/control.bin"),
                    connect_ip: Some("127.0.0.1".into()),
                    connect_ips: vec![],
                    port: None,
                    is_control: false,
                    window_bytes: 3,
                    runs: 1,
                    upload_body_bytes: None,
                    target_class: declared.map(|(class, _)| class.to_string()),
                    destination_asn: declared.map(|(_, asn)| asn),
                    verification: Some(DownloadVerification {
                        expected_body_bytes: 2,
                        sha256: OK_SHA256.into(),
                        ech_comparison_ip: None,
                    }),
                };
                let result = run_throughput_probe_with_progress(
                    &target,
                    &direct_transport(),
                    None,
                    Some(verifier),
                    &AtomicBool::new(false),
                    &mut |_| {},
                );
                let detail = |key: &str| result.details.iter().find(|d| d.key == key).map(|d| d.value.as_str());
                assert_eq!(detail("transferVolumeLeg"), Some("short"));
                assert_eq!(detail("targetClass"), expected.map(|(class, _)| class));
                assert_eq!(detail("destinationAsn"), expected.map(|(_, asn)| asn));
            },
        );
    }
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
                target_class: None,
                destination_asn: None,
                verification: Some(DownloadVerification {
                    expected_body_bytes: 2,
                    sha256: OK_SHA256.into(),
                    ech_comparison_ip: None,
                }),
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
            if outcome == "verified_download_failed" {
                assert!(result.details.iter().any(|d| d.key == "httpStatusCodeReadings" && d.value == "403"));
            }
            let last = progress.last().expect("final transfer evidence");
            assert_eq!(last.received_body_byte_count, received);
            assert_eq!(last.last_body_progress_ms.is_some(), received > 0);
            assert!(!result.details.iter().any(|detail| detail.value.contains(OK_SHA256)));
        },
    );
}
