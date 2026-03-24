// These integration tests use std::sync types and ripdpi_runtime globals that
// are compiled out under the loom feature. Skip the entire file under loom.
#![cfg(not(feature = "loom"))]

mod support;

use std::io::{self, Read, Write};
use std::net::{Ipv4Addr, Shutdown, SocketAddr, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use local_network_fixture::{
    FixtureConfig, FixtureFaultOutcome, FixtureFaultScope, FixtureFaultSpec, FixtureFaultTarget, FixtureManifest,
    FixtureStack,
};
use native_soak_support::{
    acquire_global_lock, assert_growth, write_json_artifact, GrowthThresholds, SoakProfile, SoakSampler, WARMUP_WINDOW,
};
use ripdpi_runtime::RuntimeTelemetrySink;
use rustls::pki_types::ServerName;
use rustls::{ClientConfig, ClientConnection, StreamOwned};
use serde_json::json;

use support::proxy::{ephemeral_proxy_config, start_proxy, RunningProxy};
use support::socks5::{recv_exact, socks_connect, socks_udp_associate, udp_proxy_roundtrip};
use support::telemetry::NoCertificateVerification;
use support::SOCKET_TIMEOUT;

const PROGRESS_TIMEOUT: Duration = Duration::from_secs(30);

// ── Tests ──

#[test]
#[ignore = "requires RIPDPI_RUN_SOAK=1"]
fn proxy_restart_soak() {
    if !SoakProfile::is_enabled() {
        eprintln!("skipping proxy_restart_soak because RIPDPI_RUN_SOAK!=1");
        return;
    }

    let _lock = acquire_global_lock().expect("acquire soak lock");
    let profile = SoakProfile::from_env();
    let cycles = profile.pick_count(100, 1_000);

    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    let artifact_guard = FixtureArtifactGuard::new("proxy_restart_soak", fixture.manifest().clone(), fixture.events());
    let telemetry = Arc::new(RecordingTelemetry::default());
    let sampler = start_proxy_sampler("proxy_restart_soak", fixture.events(), telemetry.clone());

    for _ in 0..cycles {
        let socks = start_proxy(
            ephemeral_proxy_config(&["--ip", "127.0.0.1"]),
            Some(telemetry.clone() as Arc<dyn RuntimeTelemetrySink>),
        );
        let tcp_body = format!("restart-tcp-{}", telemetry.accepted.load(Ordering::Relaxed));
        let udp_body = format!("restart-udp-{}", telemetry.accepted.load(Ordering::Relaxed));
        assert_eq!(
            socks_tcp_round_trip(socks.port, fixture.manifest().tcp_echo_port, tcp_body.as_bytes()),
            tcp_body.as_bytes()
        );
        assert_eq!(
            socks_udp_round_trip(socks.port, fixture.manifest().udp_echo_port, udp_body.as_bytes()),
            udp_body.as_bytes()
        );
        drop(socks);

        let connect = start_proxy(
            ephemeral_proxy_config(&["--http-connect", "--ip", "127.0.0.1"]),
            Some(telemetry.clone() as Arc<dyn RuntimeTelemetrySink>),
        );
        let connect_body = format!("connect-plain-{}", telemetry.accepted.load(Ordering::Relaxed));
        let echoed =
            http_connect_plain_round_trip(connect.port, fixture.manifest().tcp_echo_port, connect_body.as_bytes());
        assert_eq!(echoed, connect_body.as_bytes());
        drop(connect);
    }

    let samples = sampler.finish().expect("finish proxy restart sampler");
    artifact_guard.persist();

    assert_growth(
        &samples,
        WARMUP_WINDOW,
        GrowthThresholds { rss_growth_bytes: 16 * 1024 * 1024, fd_growth: 8, thread_growth: 4 },
    )
    .expect("proxy restart growth thresholds");

    let events = fixture.events().snapshot();
    assert_eq!(count_fixture_events(&events, "tcp_echo", "echo"), cycles * 2);
    assert_eq!(count_fixture_events(&events, "udp_echo", "echo"), cycles);
}

#[test]
#[ignore = "requires RIPDPI_RUN_SOAK=1"]
fn proxy_sustained_traffic_soak() {
    if !SoakProfile::is_enabled() {
        eprintln!("skipping proxy_sustained_traffic_soak because RIPDPI_RUN_SOAK!=1");
        return;
    }

    let _lock = acquire_global_lock().expect("acquire soak lock");
    let profile = SoakProfile::from_env();
    let duration = profile.pick_duration(Duration::from_secs(300), Duration::from_secs(1_200));
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    let artifact_guard =
        FixtureArtifactGuard::new("proxy_sustained_traffic_soak", fixture.manifest().clone(), fixture.events());
    let telemetry = Arc::new(RecordingTelemetry::default());

    let socks = start_proxy(
        ephemeral_proxy_config(&["--ip", "127.0.0.1"]),
        Some(telemetry.clone() as Arc<dyn RuntimeTelemetrySink>),
    );
    let connect = start_proxy(
        ephemeral_proxy_config(&["--http-connect", "--ip", "127.0.0.1"]),
        Some(telemetry.clone() as Arc<dyn RuntimeTelemetrySink>),
    );
    let chained = start_proxy(
        ephemeral_proxy_config(&[
            "--ip",
            "127.0.0.1",
            "--to-socks5",
            &format!("127.0.0.1:{}", fixture.manifest().socks5_port),
        ]),
        Some(telemetry.clone() as Arc<dyn RuntimeTelemetrySink>),
    );
    let sampler = start_proxy_sampler("proxy_sustained_traffic_soak", fixture.events(), telemetry.clone());

    let tcp_ok = Arc::new(AtomicUsize::new(0));
    let udp_ok = Arc::new(AtomicUsize::new(0));
    let connect_ok = Arc::new(AtomicUsize::new(0));
    let chain_ok = Arc::new(AtomicUsize::new(0));
    let last_progress_ms = Arc::new(AtomicU64::new(now_ms()));
    let stop = Arc::new(AtomicBool::new(false));
    let deadline = Instant::now() + duration;

    let handles = (0..16)
        .map(|index| {
            let last_progress_ms = last_progress_ms.clone();
            let stop = stop.clone();
            let tcp_ok = tcp_ok.clone();
            let udp_ok = udp_ok.clone();
            let connect_ok = connect_ok.clone();
            let chain_ok = chain_ok.clone();
            let manifest = fixture.manifest().clone();
            let socks_port = socks.port;
            let connect_port = connect.port;
            let chain_port = chained.port;
            thread::spawn(move || {
                let mut counter = 0usize;
                while !stop.load(Ordering::Relaxed) && Instant::now() < deadline {
                    match index % 4 {
                        0 => {
                            let payload = format!("tcp-{index}-{counter}");
                            let echoed = socks_tcp_round_trip(socks_port, manifest.tcp_echo_port, payload.as_bytes());
                            assert_eq!(echoed, payload.as_bytes());
                            tcp_ok.fetch_add(1, Ordering::Relaxed);
                        }
                        1 => {
                            let payload = format!("udp-{index}-{counter}");
                            let echoed = socks_udp_round_trip(socks_port, manifest.udp_echo_port, payload.as_bytes());
                            assert_eq!(echoed, payload.as_bytes());
                            udp_ok.fetch_add(1, Ordering::Relaxed);
                        }
                        2 => {
                            let body = http_connect_tls_probe_with_retry(
                                connect_port,
                                manifest.tls_echo_port,
                                &manifest.fixture_domain,
                            )
                            .expect("connect tls probe");
                            assert_eq!(body, "tls_ok");
                            connect_ok.fetch_add(1, Ordering::Relaxed);
                        }
                        _ => {
                            let payload = format!("chain-{index}-{counter}");
                            let echoed = socks_tcp_round_trip(chain_port, manifest.tcp_echo_port, payload.as_bytes());
                            assert_eq!(echoed, payload.as_bytes());
                            chain_ok.fetch_add(1, Ordering::Relaxed);
                        }
                    }
                    counter += 1;
                    last_progress_ms.store(now_ms(), Ordering::Relaxed);
                }
            })
        })
        .collect::<Vec<_>>();

    while Instant::now() < deadline {
        thread::sleep(Duration::from_secs(5));
        let idle_for = now_ms().saturating_sub(last_progress_ms.load(Ordering::Relaxed));
        assert!(
            idle_for <= PROGRESS_TIMEOUT.as_millis() as u64,
            "proxy sustained traffic made no progress for {idle_for}ms"
        );
    }
    stop.store(true, Ordering::Relaxed);
    for handle in handles {
        handle.join().expect("join proxy sustained worker");
    }

    drop(chained);
    drop(connect);
    drop(socks);

    let samples = sampler.finish().expect("finish proxy sustained sampler");
    artifact_guard.persist();

    assert_growth(
        &samples,
        WARMUP_WINDOW,
        GrowthThresholds { rss_growth_bytes: 16 * 1024 * 1024, fd_growth: 8, thread_growth: 4 },
    )
    .expect("proxy sustained growth thresholds");

    let events = fixture.events().snapshot();
    assert_within_one_percent(
        count_fixture_events(&events, "tcp_echo", "echo"),
        tcp_ok.load(Ordering::Relaxed) + chain_ok.load(Ordering::Relaxed),
    );
    assert_within_one_percent(count_fixture_events(&events, "udp_echo", "echo"), udp_ok.load(Ordering::Relaxed));
    assert_within_one_percent(
        count_fixture_events(&events, "tls_echo", "handshake"),
        connect_ok.load(Ordering::Relaxed),
    );
    assert_within_one_percent(
        count_fixture_events(&events, "socks5_relay", &fixture.manifest().tcp_echo_port.to_string()),
        chain_ok.load(Ordering::Relaxed),
    );
}

#[test]
#[ignore = "requires RIPDPI_RUN_SOAK=1"]
fn proxy_fault_recovery_soak() {
    if !SoakProfile::is_enabled() {
        eprintln!("skipping proxy_fault_recovery_soak because RIPDPI_RUN_SOAK!=1");
        return;
    }

    let _lock = acquire_global_lock().expect("acquire soak lock");
    let profile = SoakProfile::from_env();
    let duration = profile.pick_duration(Duration::from_secs(300), Duration::from_secs(600));
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    let artifact_guard =
        FixtureArtifactGuard::new("proxy_fault_recovery_soak", fixture.manifest().clone(), fixture.events());
    let telemetry = Arc::new(RecordingTelemetry::default());
    let socks = start_proxy(
        ephemeral_proxy_config(&["--ip", "127.0.0.1"]),
        Some(telemetry.clone() as Arc<dyn RuntimeTelemetrySink>),
    );
    let connect = start_proxy(
        ephemeral_proxy_config(&["--http-connect", "--ip", "127.0.0.1"]),
        Some(telemetry.clone() as Arc<dyn RuntimeTelemetrySink>),
    );
    let chained = start_proxy(
        ephemeral_proxy_config(&[
            "--ip",
            "127.0.0.1",
            "--to-socks5",
            &format!("127.0.0.1:{}", fixture.manifest().socks5_port),
        ]),
        Some(telemetry.clone() as Arc<dyn RuntimeTelemetrySink>),
    );
    let sampler = start_proxy_sampler("proxy_fault_recovery_soak", fixture.events(), telemetry.clone());

    let fault_sequence = [
        (
            FixtureFaultSpec {
                target: FixtureFaultTarget::TcpEcho,
                outcome: FixtureFaultOutcome::TcpReset,
                scope: FixtureFaultScope::OneShot,
                delay_ms: None,
            },
            FaultProbe::Tcp,
        ),
        (
            FixtureFaultSpec {
                target: FixtureFaultTarget::UdpEcho,
                outcome: FixtureFaultOutcome::UdpDrop,
                scope: FixtureFaultScope::OneShot,
                delay_ms: None,
            },
            FaultProbe::Udp,
        ),
        (
            FixtureFaultSpec {
                target: FixtureFaultTarget::TlsEcho,
                outcome: FixtureFaultOutcome::TlsAbort,
                scope: FixtureFaultScope::OneShot,
                delay_ms: None,
            },
            FaultProbe::Tls,
        ),
        (
            FixtureFaultSpec {
                target: FixtureFaultTarget::Socks5Relay,
                outcome: FixtureFaultOutcome::SocksRejectConnect,
                scope: FixtureFaultScope::OneShot,
                delay_ms: None,
            },
            FaultProbe::Chain,
        ),
    ];

    let started = Instant::now();
    let mut fault_index = 0usize;
    while started.elapsed() < duration {
        let (fault, probe) = fault_sequence[fault_index % fault_sequence.len()].clone();
        fixture.faults().set(fault);
        assert_fault_is_observed(&fixture, probe, &socks, &connect, &chained);
        recover_clean_traffic_within(Duration::from_secs(5), &fixture, probe, &socks, &connect, &chained);
        fault_index += 1;
        thread::sleep(Duration::from_secs(60).min(duration.saturating_sub(started.elapsed())));
    }

    drop(chained);
    drop(connect);
    drop(socks);

    let samples = sampler.finish().expect("finish proxy fault sampler");
    artifact_guard.persist();

    assert_growth(
        &samples,
        WARMUP_WINDOW,
        GrowthThresholds { rss_growth_bytes: 16 * 1024 * 1024, fd_growth: 8, thread_growth: 4 },
    )
    .expect("proxy fault growth thresholds");
    assert!(telemetry.last_error().is_some(), "expected proxy fault soak to record at least one recoverable error");
}

// ── Soak-local helpers ──

#[derive(Clone)]
struct FixtureArtifactGuard {
    scenario: &'static str,
    manifest: FixtureManifest,
    events: local_network_fixture::EventLog,
}

impl FixtureArtifactGuard {
    fn new(scenario: &'static str, manifest: FixtureManifest, events: local_network_fixture::EventLog) -> Self {
        Self { scenario, manifest, events }
    }

    fn persist(&self) {
        let _ = write_json_artifact(&format!("{}.fixture-manifest.json", self.scenario), &self.manifest);
        let _ = write_json_artifact(&format!("{}.fixture-events.json", self.scenario), &self.events.snapshot());
    }
}

#[derive(Clone, Copy)]
enum FaultProbe {
    Tcp,
    Udp,
    Tls,
    Chain,
}

fn assert_fault_is_observed(
    fixture: &FixtureStack,
    probe: FaultProbe,
    socks: &RunningProxy,
    connect: &RunningProxy,
    chained: &RunningProxy,
) {
    match probe {
        FaultProbe::Tcp => {
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                let payload = b"fault tcp";
                let echoed = socks_tcp_round_trip(socks.port, fixture.manifest().tcp_echo_port, payload);
                assert_eq!(echoed, payload);
            }));
            assert!(result.is_err(), "expected tcp fault to break the probe");
        }
        FaultProbe::Udp => {
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                let payload = b"fault udp";
                let echoed = socks_udp_round_trip(socks.port, fixture.manifest().udp_echo_port, payload);
                assert_eq!(echoed, payload);
            }));
            assert!(result.is_err(), "expected udp fault to break the probe");
        }
        FaultProbe::Tls => {
            assert!(
                attempt_http_connect_tls_probe(
                    connect.port,
                    fixture.manifest().tls_echo_port,
                    &fixture.manifest().fixture_domain,
                )
                .is_err(),
                "expected tls fault to break the probe"
            );
        }
        FaultProbe::Chain => {
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                let payload = b"fault chain";
                let echoed = socks_tcp_round_trip(chained.port, fixture.manifest().tcp_echo_port, payload);
                assert_eq!(echoed, payload);
            }));
            assert!(result.is_err(), "expected chain fault to break the probe");
        }
    }
}

fn recover_clean_traffic_within(
    timeout: Duration,
    fixture: &FixtureStack,
    probe: FaultProbe,
    socks: &RunningProxy,
    connect: &RunningProxy,
    chained: &RunningProxy,
) {
    let deadline = Instant::now() + timeout;
    while Instant::now() < deadline {
        let recovered = match probe {
            FaultProbe::Tcp => std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                socks_tcp_round_trip(socks.port, fixture.manifest().tcp_echo_port, b"recover tcp")
            }))
            .map(|echoed| echoed == b"recover tcp")
            .unwrap_or(false),
            FaultProbe::Udp => std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                socks_udp_round_trip(socks.port, fixture.manifest().udp_echo_port, b"recover udp")
            }))
            .map(|echoed| echoed == b"recover udp")
            .unwrap_or(false),
            FaultProbe::Tls => http_connect_tls_probe_with_retry(
                connect.port,
                fixture.manifest().tls_echo_port,
                &fixture.manifest().fixture_domain,
            )
            .map(|body| body == "tls_ok")
            .unwrap_or(false),
            FaultProbe::Chain => std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                socks_tcp_round_trip(chained.port, fixture.manifest().tcp_echo_port, b"recover chain")
            }))
            .map(|echoed| echoed == b"recover chain")
            .unwrap_or(false),
        };
        if recovered {
            return;
        }
    }
    panic!("clean traffic did not recover within {timeout:?}");
}

fn start_proxy_sampler(
    scenario: &str,
    events: local_network_fixture::EventLog,
    telemetry: Arc<RecordingTelemetry>,
) -> SoakSampler {
    SoakSampler::start(scenario, move || {
        let snapshot = telemetry.snapshot();
        json!({
            "fixtureEventCount": events.snapshot().len(),
            "acceptedClients": snapshot.accepted,
            "listenerStarts": snapshot.started,
            "listenerStops": snapshot.stopped,
            "routeSelections": snapshot.route_count,
            "lastError": snapshot.last_error,
        })
    })
    .expect("start soak sampler")
}

// ── Soak-local roundtrip wrappers ──

fn socks_tcp_round_trip(proxy_port: u16, dst_port: u16, payload: &[u8]) -> Vec<u8> {
    let mut stream = socks_connect(proxy_port, dst_port);
    stream.set_read_timeout(Some(SOCKET_TIMEOUT)).expect("tcp read timeout");
    stream.set_write_timeout(Some(SOCKET_TIMEOUT)).expect("tcp write timeout");
    stream.write_all(payload).expect("write tcp payload");
    recv_exact(&mut stream, payload.len())
}

fn socks_udp_round_trip(proxy_port: u16, dst_port: u16, payload: &[u8]) -> Vec<u8> {
    let (_control, relay) = socks_udp_associate(proxy_port);
    udp_proxy_roundtrip(relay, dst_port, payload)
}

fn http_connect_plain_round_trip(proxy_port: u16, dst_port: u16, payload: &[u8]) -> Vec<u8> {
    let mut stream = http_connect_stream(proxy_port, dst_port).expect("http connect stream");
    stream.write_all(payload).expect("write connect payload");
    recv_exact(&mut stream, payload.len())
}

// ── Soak-local TLS/HTTP helpers ──

fn http_connect_tls_probe_with_retry(proxy_port: u16, dst_port: u16, server_name: &str) -> Result<String, String> {
    let mut last_error = None;
    for _ in 0..5 {
        match attempt_http_connect_tls_probe(proxy_port, dst_port, server_name) {
            Ok(value) => return Ok(value),
            Err(err) => {
                last_error = Some(err);
                thread::sleep(Duration::from_millis(50));
            }
        }
    }
    Err(last_error.unwrap_or_else(|| "connect tls probe failed".to_string()))
}

fn attempt_http_connect_tls_probe(proxy_port: u16, dst_port: u16, server_name: &str) -> Result<String, String> {
    let stream = http_connect_stream(proxy_port, dst_port)?;

    let config = ClientConfig::builder()
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(NoCertificateVerification))
        .with_no_client_auth();
    let server_name = ServerName::try_from(server_name.to_string()).map_err(|err| err.to_string())?;
    let connection = ClientConnection::new(Arc::new(config), server_name).map_err(|err| err.to_string())?;
    let mut tls = StreamOwned::new(connection, stream);
    while tls.conn.is_handshaking() {
        tls.conn.complete_io(&mut tls.sock).map_err(|err| err.to_string())?;
    }
    let _ = tls.sock.set_read_timeout(Some(Duration::from_secs(1)));
    let mut buf = [0u8; 128];
    match tls.read(&mut buf) {
        Ok(_) => {}
        Err(err)
            if err.to_string().to_ascii_lowercase().contains("unexpected eof")
                || err.kind() == io::ErrorKind::UnexpectedEof => {}
        Err(err) => return Err(format!("tls response read failed: {err}")),
    }

    let _ = tls.sock.shutdown(Shutdown::Both);
    Ok("tls_ok".to_string())
}

fn http_connect_stream(proxy_port: u16, dst_port: u16) -> Result<TcpStream, String> {
    let mut stream = TcpStream::connect((Ipv4Addr::LOCALHOST, proxy_port)).map_err(|err| err.to_string())?;
    stream.set_read_timeout(Some(SOCKET_TIMEOUT)).map_err(|err| err.to_string())?;
    stream.set_write_timeout(Some(SOCKET_TIMEOUT)).map_err(|err| err.to_string())?;
    write!(stream, "CONNECT 127.0.0.1:{dst_port} HTTP/1.1\r\nHost: 127.0.0.1:{dst_port}\r\n\r\n")
        .map_err(|err| err.to_string())?;

    let mut response = Vec::new();
    let mut chunk = [0u8; 1024];
    while !response.windows(4).any(|window| window == b"\r\n\r\n") {
        let read = stream.read(&mut chunk).map_err(|err| err.to_string())?;
        if read == 0 {
            return Err("http connect response closed early".to_string());
        }
        response.extend_from_slice(&chunk[..read]);
    }
    let response_text = String::from_utf8(response).map_err(|err| err.to_string())?;
    if !response_text.contains("HTTP/1.1 200 OK") {
        return Err(format!("connect failed: {response_text}"));
    }
    Ok(stream)
}

// ── Soak-local utility ──

fn count_fixture_events(events: &[local_network_fixture::FixtureEvent], service: &str, detail: &str) -> usize {
    events.iter().filter(|event| event.service == service && event.detail.contains(detail)).count()
}

fn assert_within_one_percent(actual: usize, expected: usize) {
    let allowed = ((expected as f64) * 0.01).ceil() as usize;
    let delta = actual.abs_diff(expected);
    assert!(
        delta <= allowed,
        "event count outside 1% tolerance: actual={actual} expected={expected} allowed_delta={allowed}"
    );
}

fn ephemeral_fixture_config() -> FixtureConfig {
    FixtureConfig {
        tcp_echo_port: free_port(),
        udp_echo_port: free_port(),
        tls_echo_port: free_port(),
        dns_udp_port: free_port(),
        dns_http_port: free_port(),
        socks5_port: free_port(),
        control_port: free_port(),
        ..FixtureConfig::default()
    }
}

fn free_port() -> u16 {
    std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0))
        .expect("bind free port")
        .local_addr()
        .expect("local addr")
        .port()
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_else(|_| Duration::from_secs(0)).as_millis() as u64
}

// ── Soak-local telemetry ──

#[derive(Default)]
struct RecordingTelemetry {
    started: AtomicUsize,
    stopped: AtomicUsize,
    accepted: AtomicUsize,
    route_count: AtomicUsize,
    last_error: Mutex<Option<String>>,
}

impl RecordingTelemetry {
    fn snapshot(&self) -> TelemetrySnapshot {
        TelemetrySnapshot {
            started: self.started.load(Ordering::Relaxed),
            stopped: self.stopped.load(Ordering::Relaxed),
            accepted: self.accepted.load(Ordering::Relaxed),
            route_count: self.route_count.load(Ordering::Relaxed),
            last_error: self.last_error.lock().expect("lock last error").clone(),
        }
    }

    fn last_error(&self) -> Option<String> {
        self.last_error.lock().expect("lock last error").clone()
    }
}

#[derive(Debug)]
struct TelemetrySnapshot {
    started: usize,
    stopped: usize,
    accepted: usize,
    route_count: usize,
    last_error: Option<String>,
}

impl RuntimeTelemetrySink for RecordingTelemetry {
    fn on_listener_started(&self, _bind_addr: SocketAddr, _max_clients: usize, _group_count: usize) {
        self.started.fetch_add(1, Ordering::Relaxed);
    }

    fn on_listener_stopped(&self) {
        self.stopped.fetch_add(1, Ordering::Relaxed);
    }

    fn on_client_accepted(&self) {
        self.accepted.fetch_add(1, Ordering::Relaxed);
    }

    fn on_client_finished(&self) {}

    fn on_client_error(&self, error: &io::Error) {
        *self.last_error.lock().expect("lock last error") = Some(error.to_string());
    }

    fn on_failure_classified(
        &self,
        _target: SocketAddr,
        _failure: &ripdpi_failure_classifier::ClassifiedFailure,
        _host: Option<&str>,
    ) {
    }

    fn on_route_selected(&self, target: SocketAddr, _group_index: usize, _host: Option<&str>, phase: &'static str) {
        let _ = (target, phase);
        self.route_count.fetch_add(1, Ordering::Relaxed);
    }

    fn on_route_advanced(
        &self,
        target: SocketAddr,
        _from_group: usize,
        _to_group: usize,
        _trigger: u32,
        _host: Option<&str>,
    ) {
        let _ = target;
        self.route_count.fetch_add(1, Ordering::Relaxed);
    }

    fn on_host_autolearn_state(&self, _enabled: bool, _learned_host_count: usize, _penalized_host_count: usize) {}

    fn on_host_autolearn_event(&self, _action: &'static str, _host: Option<&str>, _group_index: Option<usize>) {}
}
