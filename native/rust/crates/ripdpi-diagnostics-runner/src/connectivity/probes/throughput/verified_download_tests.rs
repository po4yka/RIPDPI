use std::io::{self, Read, Write};
use std::net::TcpListener;
use std::thread;
use std::time::{Duration, Instant};

use rustls::client::WebPkiServerVerifier;
use rustls::{RootCertStore, ServerConfig, ServerConnection, StreamOwned};

use crate::connectivity::adapters::transport::direct_transport;
use crate::test_fixtures::{make_cert, read_until_marker};
use crate::types::DownloadVerification;

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
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    listener.set_nonblocking(true).unwrap();
    let port = listener.local_addr().unwrap().port();
    let (cert, key) = make_cert(&["localhost".into()]);
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let config = ServerConfig::builder_with_provider(provider.clone())
        .with_safe_default_protocol_versions()
        .unwrap()
        .with_no_client_auth()
        .with_single_cert(vec![cert.clone()], key)
        .unwrap();
    let mut roots = RootCertStore::empty();
    roots.add(cert).unwrap();
    let verifier: Arc<dyn ServerCertVerifier> =
        WebPkiServerVerifier::builder_with_provider(Arc::new(roots), provider).build().unwrap();
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
        verification: Some(DownloadVerification { expected_body_bytes: 2, sha256: OK_SHA256.into() }),
    };
    thread::scope(|scope| {
        let peer = scope.spawn(|| {
            let deadline = Instant::now() + Duration::from_secs(5);
            let socket = loop {
                match listener.accept() {
                    Ok((socket, _)) => break socket,
                    Err(error) if error.kind() == io::ErrorKind::WouldBlock && Instant::now() < deadline => {
                        thread::sleep(Duration::from_millis(5));
                    }
                    Err(error) => panic!("local TLS peer did not receive the request: {error}"),
                }
            };
            socket.set_nonblocking(false).unwrap();
            socket.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
            let mut stream = StreamOwned::new(ServerConnection::new(Arc::new(config)).unwrap(), socket);
            assert!(read_until_marker(&mut stream, b"\r\n\r\n").starts_with(b"GET /control.bin HTTP/1.1\r\n"));
            stream.write_all(response).unwrap();
            stream.flush().unwrap();
            if hold_open {
                let _ = stream.read(&mut [0]);
            } else {
                stream.conn.send_close_notify();
                let _ = stream.flush();
            }
        });
        let mut progress = Vec::new();
        let result = run_throughput_probe_with_progress(
            &target,
            &direct_transport(),
            None,
            Some(&verifier),
            &AtomicBool::new(false),
            &mut |measurement| progress.push(measurement.clone()),
        );
        peer.join().unwrap();
        assert_eq!(result.outcome, outcome, "{:?}", result.details);
        let last = progress.last().expect("final transfer evidence");
        assert_eq!(last.received_body_byte_count, received);
        assert_eq!(last.last_body_progress_ms.is_some(), received > 0);
        assert!(!result.details.iter().any(|detail| detail.value.contains(OK_SHA256)));
    });
}
