use std::fmt::Write as _;
use std::io::{Read, Write};
use std::net::TcpStream;

use ring::digest::{SHA256, digest};
use rustls::{ServerConnection, StreamOwned};

use crate::connectivity::adapters::transport::direct_transport;
use crate::test_fixtures::read_until_marker;
use crate::types::MAX_VERIFIED_UPLOAD_BYTES;

use super::https_fixture::with_trusted_https_peer;
use super::*;

type HttpsStream = StreamOwned<ServerConnection, TcpStream>;

#[test]
fn upload_verifies_a_complete_receipt_for_the_full_byte_budget() {
    assert_upload(
        MAX_VERIFIED_UPLOAD_BYTES,
        |stream, size, hash| {
            write_receipt(stream, &format!(r#"{{"bytes":{size},"sha256":"{hash}"}}"#));
        },
        "verified_upload_complete",
        Some(MAX_VERIFIED_UPLOAD_BYTES),
    );
}

#[test]
fn upload_rejects_the_wrong_server_byte_count_or_digest() {
    assert_upload(
        32,
        |stream, size, hash| {
            write_receipt(stream, &format!(r#"{{"bytes":{},"sha256":"{hash}"}}"#, size - 1));
        },
        "verified_upload_size_mismatch",
        Some(31),
    );
    assert_upload(
        32,
        |stream, size, hash| {
            let other_first_digit = if hash.starts_with('0') { '1' } else { '0' };
            write_receipt(stream, &format!(r#"{{"bytes":{size},"sha256":"{other_first_digit}{}"}}"#, &hash[1..]));
        },
        "verified_upload_hash_mismatch",
        Some(32),
    );
}

#[test]
fn upload_distinguishes_a_stalled_receipt_from_an_early_eof() {
    assert_upload(
        32,
        |stream, _, _| {
            stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 90\r\n\r\n").unwrap();
            stream.flush().unwrap();
            let _ = stream.read(&mut [0u8; 1]);
        },
        "verified_upload_timeout",
        None,
    );
    assert_upload(
        32,
        |stream, _, _| {
            stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 90\r\n\r\n{\"bytes\":32").unwrap();
        },
        "verified_upload_early_eof",
        None,
    );
}

#[test]
fn upload_rejects_http_success_without_a_valid_complete_receipt() {
    assert_upload(32, |stream, _, _| write_receipt(stream, r#"{"bytes":32}"#), "verified_upload_failed", None);
    assert_upload(
        32,
        |stream, size, hash| {
            let mut body = format!(r#"{{"bytes":{size},"sha256":"{hash}"}}"#);
            body.push_str(&" ".repeat(512));
            write_receipt(stream, &body);
        },
        "verified_upload_failed",
        None,
    );
    assert_upload(
        32,
        |stream, _, _| {
            stream
                .write_all(b"HTTP/1.1 307 Temporary Redirect\r\nLocation: /other\r\nContent-Length: 0\r\n\r\n")
                .unwrap();
        },
        "verified_upload_failed",
        None,
    );
}

fn assert_upload(
    body_bytes: usize,
    reply: impl FnOnce(&mut HttpsStream, usize, &str) + Send,
    outcome: &str,
    received: Option<usize>,
) {
    with_trusted_https_peer(
        move |mut stream| {
            let headers = read_until_marker(&mut stream, b"\r\n\r\n");
            let headers = std::str::from_utf8(&headers).unwrap();
            assert!(headers.starts_with("POST /control-upload HTTP/1.1\r\n"), "{headers}");
            assert!(headers.contains(&format!("\r\nContent-Length: {body_bytes}\r\n")));
            assert!(headers.contains("\r\nContent-Type: application/octet-stream\r\n"));
            assert!(!headers.to_ascii_lowercase().contains("sha256"));
            let mut body = vec![0; body_bytes];
            stream.read_exact(&mut body).unwrap();
            let sha256 = digest(&SHA256, &body).as_ref().iter().fold(String::new(), |mut hex, byte| {
                write!(hex, "{byte:02x}").unwrap();
                hex
            });
            reply(&mut stream, body.len(), &sha256);
            stream.conn.send_close_notify();
            let _ = stream.flush();
        },
        |port, verifier| {
            let target = ThroughputTarget {
                id: "verified-https-upload".into(),
                label: "Verified HTTPS upload".into(),
                url: format!("https://localhost:{port}/control-upload"),
                connect_ip: None,
                connect_ips: vec![],
                port: None,
                is_control: false,
                window_bytes: body_bytes,
                runs: 1,
                verification: None,
                upload_body_bytes: Some(body_bytes),
            };
            let result = run_throughput_probe_with_progress(
                &target,
                &direct_transport(),
                None,
                Some(verifier),
                &AtomicBool::new(false),
                &mut |_| {},
            );
            assert_eq!(result.outcome, outcome, "{:?}", result.details);
            let detail =
                |key| result.details.iter().find(|detail| detail.key == key).map(|detail| detail.value.as_str());
            assert_eq!(detail("expectedBodyBytes"), Some(body_bytes.to_string().as_str()));
            assert_eq!(detail("receivedBodyBytes"), received.map(|bytes| bytes.to_string()).as_deref());
            assert!(result.details.iter().all(|detail| {
                !detail.value.contains("control-upload") && !detail.value.contains("sha256") && detail.value.len() < 64
            }));
        },
    );
}

fn write_receipt(stream: &mut HttpsStream, body: &str) {
    write!(stream, "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\n\r\n{body}", body.len())
        .unwrap();
    stream.flush().unwrap();
}
