use std::io::{Read, Write};
use std::net::{Ipv4Addr, TcpListener};
use std::sync::{Mutex, atomic::AtomicBool};
use std::thread;
use std::time::{Duration, Instant};

use rustls::client::WebPkiServerVerifier;
use rustls::{RootCertStore, ServerConfig, ServerConnection, StreamOwned};

use crate::types::{ProbeResult, SharedState, TransferEvidence, TransferMeasurement};

use super::*;

const SHORT: &[u8] = b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok";
const LONG: &[u8] = b"HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\nlonger";
const SHORT_PREFIX: &[u8] = b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\no";
const LONG_PREFIX: &[u8] = b"HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\nlon";
const LONG_WRONG: &[u8] = b"HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\nwrong!";
const LONG_WRONG_SIZE: &[u8] = b"HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nlon";

#[test]
fn verified_short_response_and_stalled_long_body_keep_separate_measurements() {
    let results = run_pair((SHORT, false), (LONG_PREFIX, true), true);
    let short = assert_result(&results[0], "short", "verified_download_complete", 2);
    let long = assert_result(&results[1], "long", "verified_download_timeout", 3);
    assert!(short.response_complete && !long.response_complete);
    assert_eq!(long.termination_reason.as_deref(), Some("idle_timeout"));
}

#[test]
fn short_stall_still_leaves_time_for_a_verified_long_response() {
    let results = run_pair((SHORT_PREFIX, true), (LONG, false), false);
    let short = assert_result(&results[0], "short", "verified_download_timeout", 1);
    assert_eq!(short.termination_reason.as_deref(), Some("deadline"));
    assert_result(&results[1], "long", "verified_download_complete", 6);
}

#[test]
fn complete_size_hash_and_early_eof_have_distinct_results() {
    for (response, outcome, bytes) in [
        (LONG, "verified_download_complete", 6),
        (LONG_PREFIX, "verified_download_early_eof", 3),
        (LONG_WRONG, "verified_download_hash_mismatch", 6),
        (LONG_WRONG_SIZE, "verified_download_size_mismatch", 3),
    ] {
        let results = run_pair((SHORT, false), (response, false), false);
        assert_result(&results[0], "short", "verified_download_complete", 2);
        assert_result(&results[1], "long", outcome, bytes);
    }
}

fn assert_result(probe: &ProbeResult, leg: &str, outcome: &str, received: u64) -> TransferMeasurement {
    assert_eq!(probe.outcome, outcome, "{probe:?}");
    let detail = |key| &probe.details.iter().find(|detail| detail.key == key).unwrap().value;
    assert_eq!(detail("transferVolumeLeg"), leg);
    let evidence: TransferEvidence = serde_json::from_str(detail("transferEvidence")).unwrap();
    let [measurement]: [TransferMeasurement; 1] = evidence.runs.try_into().unwrap();
    assert_eq!(measurement.received_body_byte_count, received);
    let first = measurement.first_body_byte_ms.unwrap();
    let last = measurement.last_body_progress_ms.unwrap();
    assert!(first <= last && last <= measurement.elapsed_ms);
    measurement
}

fn run_pair(short: (&[u8], bool), long: (&[u8], bool), collect: bool) -> Vec<ProbeResult> {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    listener.set_nonblocking(true).unwrap();
    let port = listener.local_addr().unwrap().port();
    let (certificate, key) = crate::test_fixtures::make_cert(&["control.invalid".into()]);
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let config = ServerConfig::builder_with_provider(provider.clone())
        .with_safe_default_protocol_versions()
        .unwrap()
        .with_no_client_auth()
        .with_single_cert(vec![certificate.clone()], key)
        .unwrap();
    let mut roots = RootCertStore::empty();
    roots.add(certificate).unwrap();
    let verifier: Arc<dyn ServerCertVerifier> =
        WebPkiServerVerifier::builder_with_provider(Arc::new(roots), provider).build().unwrap();
    let mut runtime =
        ExecutionRuntime::new(Arc::new(Mutex::new(SharedState::default())), Arc::new(AtomicBool::new(false)));
    let deadline = Instant::now() + Duration::from_millis(1_800);
    runtime.set_scan_deadline(deadline);
    thread::scope(|scope| {
        let server = scope.spawn(|| {
            let config = Arc::new(config);
            for (path, response) in [("short", short), ("long", long)] {
                serve(&listener, &config, path, port, response);
            }
        });
        let request = serde_json::from_value(serde_json::json!({
            "profileId": "transfer-volume-comparison", "displayName": "Volume", "pathMode": "RAW_PATH",
            "domainTargets":[], "dnsTargets":[], "tcpTargets":[], "whitelistSni":[],
            "throughputTargets": [
                target("short", port, 2, "2689367b205c16ce32ed4200942b8b8b1e262dfc70d9bc9fbc77c49699a4f1df"),
                target("long", port, 6, "73834addcec75fafd7d73717ca1614a1324652ffa70b4b6c44fa359ae5a69dab")
            ]
        }))
        .unwrap();
        let plan = crate::engine::plan::build_execution_plan(
            "volume-test".into(),
            request,
            0,
            crate::transport::direct_transport(),
        )
        .unwrap();
        if collect {
            let outcome = with_scan_io_deadline(Some(deadline), || {
                ThroughputRunner.run_collecting(&plan, runtime.cancel_token(), Some(&verifier))
            });
            let (CollectedStageOutcome::Completed(steps) | CollectedStageOutcome::Cancelled(steps)) = outcome;
            runtime.results.extend(steps.into_iter().flat_map(|step| step.artifacts.probe_results));
        } else {
            ThroughputRunner.run(&plan, &mut runtime, Some(&verifier));
        }
        assert!(Instant::now() < deadline + Duration::from_millis(500), "pair exceeded its deadline");
        server.join().unwrap();
    });
    assert_eq!(runtime.results.len(), 2);
    runtime.results
}

fn serve(listener: &TcpListener, config: &Arc<ServerConfig>, path: &str, port: u16, reply: (&[u8], bool)) {
    let accept_deadline = Instant::now() + Duration::from_secs(3);
    let socket = loop {
        match listener.accept() {
            Ok((socket, _)) => break socket,
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock && Instant::now() < accept_deadline => {
                thread::sleep(Duration::from_millis(5));
            }
            Err(error) => panic!("fixture connection missing: {error}"),
        }
    };
    socket.set_nonblocking(false).unwrap();
    socket.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
    let mut stream = StreamOwned::new(ServerConnection::new(config.clone()).unwrap(), socket);
    let request = crate::test_fixtures::read_until_marker(&mut stream, b"\r\n\r\n");
    let request_line = format!("GET /{path}.bin HTTP/1.1\r\nHost: control.invalid:{port}\r\n");
    assert!(request.starts_with(request_line.as_bytes()));
    stream.write_all(reply.0).unwrap();
    if reply.1 {
        stream.flush().unwrap();
        let _ = stream.read(&mut [0]);
    } else {
        stream.conn.send_close_notify();
        stream.flush().unwrap();
    }
}

fn target(leg: &str, port: u16, bytes: usize, digest: &str) -> serde_json::Value {
    let id = format!("transfer-volume-{leg}");
    serde_json::json!({
        "id": id, "label": leg, "url": format!("https://control.invalid:{port}/{leg}.bin"),
        "connectIp": "127.0.0.1", "windowBytes": bytes + 1, "runs": 1,
        "verification": {"expectedBodyBytes": bytes, "sha256": digest}
    })
}
