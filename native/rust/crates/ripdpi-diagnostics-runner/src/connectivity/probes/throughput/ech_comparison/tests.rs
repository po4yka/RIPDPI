use std::io::{Read, Write};

use super::super::https_fixture::with_trusted_https_endpoint;
use super::*;
use crate::connectivity::adapters::transport::direct_transport;
use crate::test_fixtures::read_until_marker;
use crate::types::{DownloadVerification, ECH_TRANSFER_COMPARISON_PROFILE_ID};

const HASH: &str = "2689367b205c16ce32ed4200942b8b8b1e262dfc70d9bc9fbc77c49699a4f1df";

#[test]
fn comparison_distinguishes_a_verified_body_from_a_stall_on_the_same_pinned_peer() {
    with_trusted_https_endpoint(
        "control.invalid",
        |peer| {
            for response in [
                b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".as_slice(),
                b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\no".as_slice(),
            ] {
                let mut stream = peer.accept();
                let request = read_until_marker(&mut stream, b"\r\n\r\n");
                assert!(request.starts_with(b"GET /control.bin HTTP/1.1\r\nHost: control.invalid:"));
                assert_eq!(stream.conn.server_name(), Some("control.invalid"));
                stream.write_all(response).unwrap();
                stream.flush().unwrap();
                let _ = stream.read(&mut [0]);
            }
        },
        |port, verifier| {
            let target = comparison_target(port);
            let cancel = AtomicBool::new(false);
            let result = run(&target, &direct_transport(), None, Some(verifier), &cancel, &mut |progress| {
                assert_eq!(progress.run_count, 3);
                if progress.run_index == 3 {
                    // Stop the final leg before its published ECH configuration would be looked up.
                    cancel.store(true, Ordering::Release);
                }
            });

            assert_eq!(result.outcome, "ech_transfer_comparison_inconclusive");
            let value =
                |key| result.details.iter().find(|detail| detail.key == key).map(|detail| detail.value.as_str());
            assert_eq!(value("tls13Outcome"), Some("verified"));
            assert_eq!(value("tls13Bytes"), Some("2"));
            assert_eq!(value("echGreaseOutcome"), Some("body_timeout"));
            assert_eq!(value("echGreaseBytes"), Some("1"));
            assert_eq!(value("echOutcome"), Some("not_run"));
            assert!(
                result
                    .details
                    .iter()
                    .all(|detail| { !detail.value.contains("control.invalid") && !detail.value.contains(HASH) })
            );
        },
    );
}

#[test]
fn comparison_keeps_no_config_distinct_from_handshake_and_http_failure() {
    assert_eq!(leg_outcome("ech_config_missing", "stream_setup"), "ech_config_missing");
    assert_eq!(leg_outcome("ech_dns_failed", "stream_setup"), "ech_dns_failed");
    assert_eq!(leg_outcome("ech_config_invalid", "stream_setup"), "ech_config_invalid");
    assert_eq!(leg_outcome("ech_rejected", "tls_handshake"), "ech_rejected");
    assert_eq!(leg_outcome("verified_download_failed", "tls_handshake"), "tls_failed");
    assert_eq!(leg_outcome("verified_download_timeout", "response_headers"), "response_timeout");
    assert_eq!(leg_outcome("verified_download_failed", "response_status"), "http_failed");
    assert_eq!(leg_outcome("verified_download_hash_mismatch", "none"), "hash_mismatch");
}

fn comparison_target(port: u16) -> ThroughputTarget {
    ThroughputTarget {
        id: ECH_TRANSFER_COMPARISON_PROFILE_ID.into(),
        label: "ECH transfer comparison".into(),
        url: format!("https://control.invalid:{port}/control.bin"),
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
            sha256: HASH.into(),
            ech_comparison_ip: Some("127.0.0.1".parse().unwrap()),
        }),
    }
}
