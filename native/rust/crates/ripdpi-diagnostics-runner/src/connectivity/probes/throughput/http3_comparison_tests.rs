use super::*;
use std::io::{Read, Write};
use std::sync::mpsc;
use std::thread;
use std::time::{Duration, Instant};

use bytes::Bytes;
use ripdpi_diagnostics_contracts::util::with_scan_io_deadline;

use super::https_fixture::with_trusted_https_endpoint;
use crate::connectivity::adapters::transport::direct_transport;
use crate::test_fixtures::read_until_marker;
use crate::types::DownloadVerification;

const HASH: &str = "2689367b205c16ce32ed4200942b8b8b1e262dfc70d9bc9fbc77c49699a4f1df";

enum QuicReply {
    Complete(&'static [u8]),
    Stall,
    Truncate,
    PartialStatus,
    NoResponse,
}

#[derive(Clone, Copy)]
enum TcpReply {
    Complete,
    Stall,
    Truncate,
}

#[test]
fn both_protocols_verify_the_same_pinned_object() {
    let result = run_pair(QuicReply::Complete(b"ok"), TcpReply::Complete);
    assert_eq!(result.outcome, "http3_transfer_comparison_recorded");
    assert_eq!(value(&result, "differenceStage"), "none");
    assert_eq!(value(&result, "tcpOutcome"), "verified");
    assert_eq!(value(&result, "http3Outcome"), "verified");
    assert_eq!(value(&result, "tcpBytes"), "2");
    assert_eq!(value(&result, "http3Bytes"), "2");
}

#[test]
fn tcp_succeeds_when_quic_cannot_connect_within_the_shared_deadline() {
    let result = run_pair(QuicReply::NoResponse, TcpReply::Complete);
    assert_eq!(value(&result, "tcpOutcome"), "verified");
    assert_eq!(value(&result, "differenceStage"), "connect");
    assert_eq!(value(&result, "http3Outcome"), "connection_failed");
    assert_eq!(value(&result, "http3Bytes"), "0");
}

#[test]
fn h3_body_stall_keeps_header_success_and_partial_byte_count() {
    let result = run_pair(QuicReply::Stall, TcpReply::Complete);
    assert_eq!(value(&result, "tcpOutcome"), "verified");
    assert_eq!(value(&result, "differenceStage"), "body");
    assert_eq!(value(&result, "http3Outcome"), "body_timeout");
    assert_eq!(value(&result, "http3Bytes"), "1");
}

#[test]
fn h3_rejects_incorrect_hash_and_bounded_size() {
    for (body, outcome, bytes) in [
        (b"no".as_slice(), "hash_mismatch", "2"),
        (b"x".as_slice(), "size_mismatch", "1"),
        (b"more than the cap".as_slice(), "size_mismatch", "3"),
    ] {
        let result = run_pair(QuicReply::Complete(body), TcpReply::Complete);
        assert_eq!(value(&result, "tcpOutcome"), "verified");
        assert_eq!(value(&result, "differenceStage"), "integrity");
        assert_eq!(value(&result, "http3Outcome"), outcome);
        assert_eq!(value(&result, "http3Bytes"), bytes);
    }
}

#[test]
fn tcp_body_stall_leaves_time_to_verify_h3() {
    let result = run_pair(QuicReply::Complete(b"ok"), TcpReply::Stall);
    assert_eq!(value(&result, "tcpOutcome"), "body_timeout");
    assert_eq!(value(&result, "tcpBytes"), "1");
    assert_eq!(value(&result, "differenceStage"), "body");
    assert_eq!(value(&result, "http3Outcome"), "verified");
}

#[test]
fn truncated_responses_have_matching_body_failures_and_preserve_bytes() {
    let result = run_pair(QuicReply::Truncate, TcpReply::Truncate);
    assert_eq!(value(&result, "differenceStage"), "none");
    for protocol in ["tcp", "http3"] {
        assert_eq!(value(&result, &format!("{protocol}Stage")), "body");
        assert_eq!(value(&result, &format!("{protocol}Outcome")), "early_eof");
        assert_eq!(value(&result, &format!("{protocol}Bytes")), "1");
    }
}

#[test]
fn h3_partial_content_is_not_a_verified_object_even_with_matching_bytes() {
    let result = run_pair(QuicReply::PartialStatus, TcpReply::Complete);
    assert_eq!(value(&result, "tcpOutcome"), "verified");
    assert_eq!(value(&result, "differenceStage"), "headers");
    assert_eq!(value(&result, "http3Outcome"), "http_failed");
    assert_eq!(value(&result, "http3Bytes"), "0");
}

fn value<'a>(result: &'a ProbeResult, key: &str) -> &'a str {
    result.details.iter().find(|detail| detail.key == key).unwrap().value.as_str()
}

fn run_pair(reply: QuicReply, tcp_reply: TcpReply) -> ProbeResult {
    let (ready, waiting) = mpsc::sync_channel(1);
    let (stop, stopped) = tokio::sync::oneshot::channel();
    let mut result = None;
    with_trusted_https_endpoint(
        "control.invalid",
        move |peer| {
            let address = peer.address();
            let mut tls_config = peer.server_config();
            tls_config.alpn_protocols = vec![b"h3".to_vec()];
            thread::scope(|scope| {
                scope.spawn(move || {
                    let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
                    runtime.block_on(async {
                        if matches!(reply, QuicReply::NoResponse) {
                            let _socket = std::net::UdpSocket::bind(address).unwrap();
                            ready.send(()).unwrap();
                            let _ = stopped.await;
                            return;
                        }
                        let crypto = quinn::crypto::rustls::QuicServerConfig::try_from(tls_config).unwrap();
                        let endpoint =
                            quinn::Endpoint::server(quinn::ServerConfig::with_crypto(Arc::new(crypto)), address)
                                .unwrap();
                        ready.send(()).unwrap();
                        tokio::select! {
                            _ = stopped => (),
                            _ = serve_http3(&endpoint, reply) => (),
                        }
                    });
                });
                let mut stream = peer.accept();
                let request = read_until_marker(&mut stream, b"\r\n\r\n");
                let request_line = format!("GET /control.bin HTTP/1.1\r\nHost: control.invalid:{}\r\n", address.port());
                assert!(request.starts_with(request_line.as_bytes()));
                stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\no").unwrap();
                match tcp_reply {
                    TcpReply::Complete => stream.write_all(b"k").unwrap(),
                    TcpReply::Stall => {}
                    TcpReply::Truncate => stream.conn.send_close_notify(),
                }
                stream.flush().unwrap();
                let _ = stream.read(&mut [0]);
            });
        },
        |port, verifier| {
            waiting.recv_timeout(Duration::from_secs(5)).unwrap();
            let target = target(port);
            let started = Instant::now();
            result = Some(with_scan_io_deadline(Some(started + Duration::from_secs(2)), || {
                run_throughput_probe_with_progress(
                    &target,
                    &direct_transport(),
                    None,
                    Some(verifier),
                    &AtomicBool::new(false),
                    &mut |_| {},
                )
            }));
            let _ = stop.send(());
            assert!(started.elapsed() < Duration::from_secs(3), "the pair exceeded its shared deadline");
        },
    );
    result.unwrap()
}

/// # Cancel safety:
/// cancel-safe: dropping the fixture future drops its connection and stream handles.
async fn serve_http3(endpoint: &quinn::Endpoint, reply: QuicReply) {
    let connection = endpoint.accept().await.unwrap().await.unwrap();
    let mut server = h3::server::Connection::new(h3_quinn::Connection::new(connection.clone())).await.unwrap();
    let (request, mut stream) = server.accept().await.unwrap().unwrap().resolve_request().await.unwrap();
    assert_eq!(request.method(), "GET");
    assert_eq!(request.uri().host(), Some("control.invalid"));
    assert_eq!(request.uri().port_u16(), Some(endpoint.local_addr().unwrap().port()));
    assert_eq!(request.uri().path(), "/control.bin");
    assert_eq!(request.headers().get(http::header::ACCEPT_ENCODING).unwrap(), "identity");
    let mut response =
        http::Response::builder().status(if matches!(reply, QuicReply::PartialStatus) { 206 } else { 200 });
    if matches!(reply, QuicReply::Truncate) {
        response = response.header(http::header::CONTENT_LENGTH, 2);
    }
    stream.send_response(response.body(()).unwrap()).await.unwrap();
    match reply {
        QuicReply::Complete(body) => {
            stream.send_data(Bytes::from_static(body)).await.unwrap();
            let _ = stream.finish().await;
        }
        QuicReply::Stall => {
            stream.send_data(Bytes::from_static(b"o")).await.unwrap();
        }
        QuicReply::Truncate => {
            stream.send_data(Bytes::from_static(b"o")).await.unwrap();
            let _ = stream.finish().await;
        }
        QuicReply::PartialStatus => {
            if stream.send_data(Bytes::from_static(b"ok")).await.is_ok() {
                let _ = stream.finish().await;
            }
        }
        QuicReply::NoResponse => unreachable!(),
    }
    let _ = connection.closed().await;
}

fn target(port: u16) -> ThroughputTarget {
    ThroughputTarget {
        id: HTTP3_TRANSFER_COMPARISON_PROFILE_ID.into(),
        label: "Transport comparison".into(),
        url: format!("https://control.invalid:{port}/control.bin"),
        connect_ip: Some("127.0.0.1".into()),
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
            ech_comparison_ip: None,
        }),
    }
}
