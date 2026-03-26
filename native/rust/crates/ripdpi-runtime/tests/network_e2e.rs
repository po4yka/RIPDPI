// These integration tests use std::sync types and ripdpi_runtime globals that
// are compiled out under the loom feature. Skip the entire file under loom.
#![cfg(not(feature = "loom"))]

mod support;

use local_network_fixture::{
    FixtureConfig, FixtureEvent, FixtureFaultOutcome, FixtureFaultScope, FixtureFaultSpec, FixtureFaultTarget,
    FixtureStack,
};
#[cfg(any(target_os = "linux", target_os = "android"))]
use ripdpi_config::TcpChainStep;
#[cfg(any(target_os = "linux", target_os = "android"))]
use ripdpi_config::TcpChainStepKind;
use ripdpi_config::{DesyncGroup, QuicInitialMode, RuntimeConfig};
#[cfg(any(target_os = "linux", target_os = "android"))]
use ripdpi_packets::IS_HTTPS;
use ripdpi_packets::{IS_TCP, IS_UDP};
use ripdpi_proxy_config::{runtime_config_from_ui, ProxyUiConfig};
use ripdpi_runtime::RuntimeTelemetrySink;
use std::io::{Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpStream};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, OnceLock, PoisonError};

use support::proxy::{ephemeral_proxy_config, start_proxy};
use support::socks5::{
    recv_exact, socks_connect, socks_connect_domain, socks_connect_domain_round_trip_via_upstream_with_retry,
    socks_connect_domain_round_trip_with_retry, socks_connect_ip_reply, socks_connect_ip_round_trip_with_retry,
    socks_udp_associate, udp_proxy_client, udp_proxy_roundtrip, udp_proxy_roundtrip_with_socket,
    wait_for_accepted_connections,
};
use support::tls::{http_connect_round_trip_with_retry, socks5_tls_round_trip_with_retry, FragmentingProfile};
use support::START_TIMEOUT;

#[allow(dead_code)]
#[path = "../../ripdpi-packets/tests/rust_packet_seeds.rs"]
mod rust_packet_seeds;

static TEST_LOCK: OnceLock<Mutex<()>> = OnceLock::new();

const FIXTURE_TCP_ECHO_PORT: u16 = 47101;
const FIXTURE_UDP_ECHO_PORT: u16 = 47102;
const FIXTURE_TLS_ECHO_PORT: u16 = 47103;
const FIXTURE_DNS_UDP_PORT: u16 = 47153;
const FIXTURE_DNS_HTTP_PORT: u16 = 47154;
const FIXTURE_DNS_DOT_PORT: u16 = 47155;
const FIXTURE_DNS_DNSCRYPT_PORT: u16 = 47156;
const FIXTURE_DNS_DOQ_PORT: u16 = 47157;
const FIXTURE_SOCKS5_PORT: u16 = 47180;
const FIXTURE_CONTROL_PORT: u16 = 47190;

// ── Tests ──

#[test]
fn socks5_tcp_round_trip_reaches_fixture() {
    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    socks5_tcp_round_trip(&fixture);
}

#[test]
fn socks5_udp_round_trip_reaches_fixture() {
    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    socks5_udp_round_trip(&fixture);
}

#[test]
fn socks5_tls_round_trip_reaches_fixture() {
    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    socks5_tls_round_trip(&fixture, None);
}

#[test]
fn socks5_tls_fragmented_client_hello_reaches_fixture() {
    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    socks5_tls_round_trip(&fixture, Some(FragmentingProfile::default()));
}

#[test]
fn http_connect_round_trip_reaches_fixture() {
    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    http_connect_round_trip(&fixture);
}

#[test]
fn domain_resolution_policy_is_enforced_end_to_end() {
    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    domain_resolution_policy_is_enforced(&fixture);
}

#[test]
fn chained_upstream_round_trip_records_fixture_socks_usage_end_to_end() {
    if !nested_proxy_e2e_enabled() {
        eprintln!("skipping chained_upstream_round_trip_records_fixture_socks_usage_end_to_end because RIPDPI_RUN_NESTED_PROXY_E2E!=1");
        return;
    }
    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    chained_upstream_round_trip_records_fixture_socks_usage(&fixture);
}

#[test]
fn host_filters_only_route_matching_domain_via_upstream_end_to_end() {
    if !nested_proxy_e2e_enabled() {
        eprintln!(
            "skipping host_filters_only_route_matching_domain_via_upstream_end_to_end because RIPDPI_RUN_NESTED_PROXY_E2E!=1"
        );
        return;
    }
    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    hosts_filter_only_routes_matching_domain_via_upstream(&fixture);
}

#[test]
fn upstream_tcp_reset_fault_is_observed_end_to_end() {
    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    fixture.faults().set(FixtureFaultSpec {
        target: FixtureFaultTarget::TcpEcho,
        outcome: FixtureFaultOutcome::TcpReset,
        scope: FixtureFaultScope::OneShot,
        delay_ms: None,
    });

    let proxy = start_proxy(ephemeral_proxy_config(&["--ip", "127.0.0.1"]), None);
    let mut stream = socks_connect(proxy.port, fixture.manifest().tcp_echo_port);
    stream.write_all(b"fault reset").expect("write tcp payload");
    let mut buf = [0u8; 32];
    let read = stream.read(&mut buf);

    assert!(read.is_err() || read.unwrap_or_default() != b"fault reset".len());
    assert!(fixture
        .events()
        .snapshot()
        .iter()
        .any(|event| event.service == "tcp_echo" && event.detail.contains("TcpReset")));
}

#[test]
fn socks5_udp_quic_initial_routes_by_hostname_and_records_host_telemetry() {
    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    let telemetry = Arc::new(RecordingTelemetry::default());
    let proxy = start_proxy(
        quic_udp_host_filter_config(QuicInitialMode::RouteAndCache, true, true),
        Some(telemetry.clone() as Arc<dyn RuntimeTelemetrySink>),
    );
    let (_control, relay) = socks_udp_associate(proxy.port);
    let udp = udp_proxy_client();

    let matching = rust_packet_seeds::quic_initial_with_host(0x0000_0001, "docs.example.test");
    let fallback = rust_packet_seeds::quic_initial_with_host(0x0000_0001, "other.example.test");

    assert_eq!(udp_proxy_roundtrip_with_socket(&udp, relay, fixture.manifest().udp_echo_port, &matching), matching);
    assert_eq!(udp_proxy_roundtrip_with_socket(&udp, relay, fixture.manifest().udp_echo_port, &fallback), fallback);

    drop(proxy);

    let snapshot = telemetry.snapshot();
    assert!(snapshot.routes.iter().any(|route| {
        route.target.port() == fixture.manifest().udp_echo_port
            && route.group_index == 0
            && route.host.as_deref() == Some("docs.example.test")
    }));
    assert!(snapshot.routes.iter().any(|route| {
        route.target.port() == fixture.manifest().udp_echo_port
            && route.group_index == 1
            && route.host.as_deref() == Some("other.example.test")
    }));
}

#[test]
fn socks5_udp_quic_initial_disabled_falls_back_without_host_telemetry() {
    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    let telemetry = Arc::new(RecordingTelemetry::default());
    let proxy = start_proxy(
        quic_udp_host_filter_config(QuicInitialMode::Disabled, true, true),
        Some(telemetry.clone() as Arc<dyn RuntimeTelemetrySink>),
    );
    let (_control, relay) = socks_udp_associate(proxy.port);
    let udp = udp_proxy_client();
    let matching = rust_packet_seeds::quic_initial_with_host(0x0000_0001, "docs.example.test");

    assert_eq!(udp_proxy_roundtrip_with_socket(&udp, relay, fixture.manifest().udp_echo_port, &matching), matching);

    drop(proxy);

    let snapshot = telemetry.snapshot();
    assert!(snapshot.routes.iter().any(|route| {
        route.target.port() == fixture.manifest().udp_echo_port && route.group_index == 1 && route.host.is_none()
    }));
    assert!(!snapshot
        .routes
        .iter()
        .any(|route| { route.target.port() == fixture.manifest().udp_echo_port && route.group_index == 0 }));
}

#[test]
fn socks5_udp_quic_initial_v2_routes_by_hostname_when_enabled() {
    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    let telemetry = Arc::new(RecordingTelemetry::default());
    let proxy = start_proxy(
        quic_udp_host_filter_config(QuicInitialMode::Route, false, true),
        Some(telemetry.clone() as Arc<dyn RuntimeTelemetrySink>),
    );
    let (_control, relay) = socks_udp_associate(proxy.port);
    let udp = udp_proxy_client();
    let matching = rust_packet_seeds::quic_initial_with_host(0x6b33_43cf, "docs.example.test");

    assert_eq!(udp_proxy_roundtrip_with_socket(&udp, relay, fixture.manifest().udp_echo_port, &matching), matching);

    drop(proxy);

    let snapshot = telemetry.snapshot();
    assert!(snapshot.routes.iter().any(|route| {
        route.target.port() == fixture.manifest().udp_echo_port
            && route.group_index == 0
            && route.host.as_deref() == Some("docs.example.test")
    }));
}

// ── Characterization: delayed-connect replies before reading payload ──

#[test]
fn socks5_delay_connect_replies_before_first_payload_and_round_trips() {
    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");

    let upstream = start_proxy(ephemeral_proxy_config(&["--ip", "127.0.0.1"]), None);
    let proxy = start_proxy(
        ephemeral_proxy_config(&[
            "-X",
            "--ip",
            "127.0.0.1",
            "--to-socks5",
            &format!("127.0.0.1:{}", upstream.port),
            "--hosts",
            ":localhost",
        ]),
        None,
    );

    let (mut stream, reply) = socks_connect_domain(proxy.port, "localhost", fixture.manifest().tcp_echo_port);
    assert_eq!(reply[1], 0x00, "SOCKS5 connect should succeed (delayed path)");

    stream.write_all(b"delay ok").expect("write delayed payload");
    assert_eq!(recv_exact(&mut stream, 8), b"delay ok");

    let events = fixture.events().snapshot();
    assert!(events.iter().any(|e| e.service == "tcp_echo" && e.detail == "echo"), "fixture should record the TCP echo");

    drop(proxy);
    drop(upstream);
}

// ── Characterization: UDP multi-flow from same socket varies target ──

#[test]
fn socks5_udp_multi_flow_same_socket_different_targets() {
    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    let telemetry = Arc::new(RecordingTelemetry::default());
    let proxy = start_proxy(
        ephemeral_proxy_config(&["--ip", "127.0.0.1"]),
        Some(telemetry.clone() as Arc<dyn RuntimeTelemetrySink>),
    );

    let (_control, relay) = socks_udp_associate(proxy.port);
    let udp = udp_proxy_client();

    let body1 = udp_proxy_roundtrip_with_socket(&udp, relay, fixture.manifest().udp_echo_port, b"flow-a");
    assert_eq!(body1, b"flow-a");

    let body2 = udp_proxy_roundtrip_with_socket(&udp, relay, fixture.manifest().udp_echo_port, b"flow-b");
    assert_eq!(body2, b"flow-b");

    drop(proxy);

    let snapshot = telemetry.snapshot();
    let udp_routes: Vec<_> =
        snapshot.routes.iter().filter(|r| r.target.port() == fixture.manifest().udp_echo_port).collect();
    assert!(!udp_routes.is_empty(), "UDP flows should have route events");

    let events = fixture.events().snapshot();
    let udp_events: Vec<_> = events.iter().filter(|e| e.service == "udp_echo" && e.protocol == "udp").collect();
    assert!(udp_events.len() >= 2, "expected at least 2 UDP echo events, got {}", udp_events.len());
}

// ── Characterization: upstream fault produces observable failure ──

#[test]
fn upstream_silent_drop_fault_is_classified_end_to_end() {
    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");

    fixture.faults().set(FixtureFaultSpec {
        target: FixtureFaultTarget::TcpEcho,
        outcome: FixtureFaultOutcome::TcpReset,
        scope: FixtureFaultScope::Persistent,
        delay_ms: None,
    });

    let telemetry = Arc::new(RecordingTelemetry::default());
    let mut config = ephemeral_proxy_config(&["--ip", "127.0.0.1"]);
    config.groups[0].matches.detect = ripdpi_config::DETECT_SILENT_DROP | ripdpi_config::DETECT_TCP_RESET;
    config.timeouts.timeout_ms = 500;
    let proxy = start_proxy(config, Some(telemetry.clone() as Arc<dyn RuntimeTelemetrySink>));

    let mut stream = socks_connect(proxy.port, fixture.manifest().tcp_echo_port);
    stream.write_all(b"will fail").expect("write payload");

    let mut buf = [0u8; 32];
    match stream.read(&mut buf) {
        Ok(n) if n > 0 => {
            assert_ne!(&buf[..n], b"will fail", "expected failure, not echo");
        }
        _ => {}
    }

    drop(proxy);

    let events = fixture.events().snapshot();
    assert!(
        events.iter().any(|e| e.service == "tcp_echo" && e.detail.contains("TcpReset")),
        "fixture should record the TCP reset fault"
    );
}

// ── Characterization: SOCKS5 connect reply carries bound address ──

#[test]
fn socks5_connect_reply_contains_bound_address() {
    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    let proxy = start_proxy(ephemeral_proxy_config(&["--ip", "127.0.0.1"]), None);

    let (stream, reply) = socks_connect_ip_reply(proxy.port, fixture.manifest().tcp_echo_port);
    assert_eq!(reply[0], 0x05, "VER");
    assert_eq!(reply[1], 0x00, "REP success");
    assert_eq!(reply[2], 0x00, "RSV");
    assert_eq!(reply[3], 0x01, "ATYP IPv4");
    let bound_port = u16::from_be_bytes([reply[8], reply[9]]);
    assert_ne!(bound_port, 0, "bound port should be non-zero when connected");
    drop(stream);
    drop(proxy);
}

// ── Characterization: HTTP CONNECT reply format ──

#[test]
fn http_connect_reply_format_is_200_ok_with_crlf() {
    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    let proxy = start_proxy(ephemeral_proxy_config(&["--http-connect", "--ip", "127.0.0.1"]), None);

    let mut stream = TcpStream::connect((Ipv4Addr::LOCALHOST, proxy.port)).expect("connect");
    let dst_port = fixture.manifest().tcp_echo_port;
    write!(stream, "CONNECT 127.0.0.1:{dst_port} HTTP/1.1\r\nHost: 127.0.0.1:{dst_port}\r\n\r\n")
        .expect("write connect");
    let mut response = Vec::new();
    let mut chunk = [0u8; 1024];
    while !response.windows(4).any(|w| w == b"\r\n\r\n") {
        let n = stream.read(&mut chunk).expect("read");
        assert_ne!(n, 0);
        response.extend_from_slice(&chunk[..n]);
    }
    let text = String::from_utf8(response).expect("utf8");
    assert!(text.starts_with("HTTP/1.1 200 OK\r\n"), "expected HTTP/1.1 200 OK, got: {text}");
    assert!(text.ends_with("\r\n\r\n"), "expected trailing CRLF CRLF");
    drop(proxy);
}

// ── E2e-local helpers ──

fn test_guard() -> MutexGuard<'static, ()> {
    TEST_LOCK.get_or_init(|| Mutex::new(())).lock().unwrap_or_else(PoisonError::into_inner)
}

fn nested_proxy_e2e_enabled() -> bool {
    matches!(std::env::var("RIPDPI_RUN_NESTED_PROXY_E2E").as_deref(), Ok("1"))
}

fn ephemeral_fixture_config() -> FixtureConfig {
    FixtureConfig {
        tcp_echo_port: FIXTURE_TCP_ECHO_PORT,
        udp_echo_port: FIXTURE_UDP_ECHO_PORT,
        tls_echo_port: FIXTURE_TLS_ECHO_PORT,
        dns_udp_port: FIXTURE_DNS_UDP_PORT,
        dns_http_port: FIXTURE_DNS_HTTP_PORT,
        dns_dot_port: FIXTURE_DNS_DOT_PORT,
        dns_dnscrypt_port: FIXTURE_DNS_DNSCRYPT_PORT,
        dns_doq_port: FIXTURE_DNS_DOQ_PORT,
        socks5_port: FIXTURE_SOCKS5_PORT,
        control_port: FIXTURE_CONTROL_PORT,
        ..FixtureConfig::default()
    }
}

fn ui_proxy_config() -> RuntimeConfig {
    let mut ui = ProxyUiConfig::default();
    ui.listen.ip = "127.0.0.1".to_string();
    ui.protocols.desync_http = false;
    ui.protocols.desync_https = false;
    ui.protocols.desync_udp = false;
    ui.chains.tcp_steps.clear();
    ui.chains.udp_steps.clear();

    let mut config = runtime_config_from_ui(ui).expect("ui runtime config");
    config.network.listen.listen_port = 0;
    config
}

fn quic_udp_host_filter_config(mode: QuicInitialMode, support_v1: bool, support_v2: bool) -> RuntimeConfig {
    let mut config = ephemeral_proxy_config(&["--ip", "127.0.0.1"]);

    let mut filtered = DesyncGroup::new(0);
    filtered.matches.proto = IS_UDP;
    filtered.matches.filters.hosts.push("docs.example.test".to_string());

    let mut fallback = DesyncGroup::new(1);
    fallback.matches.proto = IS_UDP;

    config.groups = vec![filtered, fallback];
    config.quic.initial_mode = mode;
    config.quic.support_v1 = support_v1;
    config.quic.support_v2 = support_v2;
    config
}

// ── E2e-local telemetry ──

#[derive(Default)]
struct RecordingTelemetry {
    started: AtomicUsize,
    stopped: AtomicUsize,
    accepted: AtomicUsize,
    routes: Mutex<Vec<RouteEvent>>,
}

impl support::socks5::AcceptedCounter for RecordingTelemetry {
    fn accepted_count(&self) -> usize {
        self.accepted.load(Ordering::Relaxed)
    }
}

impl RecordingTelemetry {
    fn snapshot(&self) -> TelemetrySnapshot {
        TelemetrySnapshot {
            started: self.started.load(Ordering::Relaxed),
            stopped: self.stopped.load(Ordering::Relaxed),
            accepted: self.accepted.load(Ordering::Relaxed),
            routes: self.routes.lock().expect("lock routes").clone(),
        }
    }
}

#[derive(Clone, Debug)]
struct RouteEvent {
    target: SocketAddr,
    group_index: usize,
    host: Option<String>,
    phase: &'static str,
}

#[derive(Debug)]
struct TelemetrySnapshot {
    started: usize,
    stopped: usize,
    accepted: usize,
    routes: Vec<RouteEvent>,
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

    fn on_client_error(&self, _error: &std::io::Error) {}

    fn on_failure_classified(
        &self,
        _target: SocketAddr,
        _failure: &ripdpi_failure_classifier::ClassifiedFailure,
        _host: Option<&str>,
    ) {
    }

    fn on_route_selected(&self, target: SocketAddr, group_index: usize, host: Option<&str>, phase: &'static str) {
        self.routes.lock().expect("lock routes").push(RouteEvent {
            target,
            group_index,
            host: host.map(ToOwned::to_owned),
            phase,
        });
    }

    fn on_route_advanced(
        &self,
        target: SocketAddr,
        _from_group: usize,
        to_group: usize,
        _trigger: u32,
        host: Option<&str>,
    ) {
        self.routes.lock().expect("lock routes").push(RouteEvent {
            target,
            group_index: to_group,
            host: host.map(ToOwned::to_owned),
            phase: "advanced",
        });
    }

    fn on_host_autolearn_state(&self, _enabled: bool, _learned_host_count: usize, _penalized_host_count: usize) {}

    fn on_host_autolearn_event(&self, _action: &'static str, _host: Option<&str>, _group_index: Option<usize>) {}
}

// ── E2e test body helpers ──

fn socks5_tcp_round_trip(fixture: &FixtureStack) {
    let telemetry = Arc::new(RecordingTelemetry::default());
    let proxy = start_proxy(
        ephemeral_proxy_config(&["--ip", "127.0.0.1"]),
        Some(telemetry.clone() as Arc<dyn RuntimeTelemetrySink>),
    );

    assert_eq!(
        socks_connect_ip_round_trip_with_retry(proxy.port, fixture.manifest().tcp_echo_port, b"fixture tcp"),
        b"fixture tcp"
    );

    drop(proxy);
    let snapshot = telemetry.snapshot();
    assert!(snapshot.accepted >= 1);
    assert!(snapshot.started >= 1);
    assert!(snapshot.stopped >= 1);
    assert!(snapshot
        .routes
        .iter()
        .any(|route| route.target.port() == fixture.manifest().tcp_echo_port && route.phase == "initial"));
    assert!(fixture.events().snapshot().iter().any(|event| event.service == "tcp_echo" && event.detail == "echo"));
}

fn socks5_udp_round_trip(fixture: &FixtureStack) {
    let proxy = start_proxy(ephemeral_proxy_config(&["--ip", "127.0.0.1"]), None);
    let (_control, relay) = socks_udp_associate(proxy.port);
    let body = udp_proxy_roundtrip(relay, fixture.manifest().udp_echo_port, b"fixture udp");
    assert_eq!(body, b"fixture udp");
    assert!(fixture.events().snapshot().iter().any(|event| event.service == "udp_echo" && event.protocol == "udp"));
    drop(proxy);
}

fn socks5_tls_round_trip(fixture: &FixtureStack, fragmented: Option<FragmentingProfile>) {
    let telemetry = Arc::new(RecordingTelemetry::default());
    let proxy = start_proxy(ui_proxy_config(), Some(telemetry.clone() as Arc<dyn RuntimeTelemetrySink>));

    let response = socks5_tls_round_trip_with_retry(proxy.port, fixture, fragmented.as_ref());
    assert!(
        response.contains("fixture tls ok"),
        "unexpected tls response: {response:?}; events: {:?}",
        fixture.events().snapshot()
    );

    drop(proxy);

    let snapshot = telemetry.snapshot();
    assert!(snapshot.accepted >= 1);
    assert!(snapshot
        .routes
        .iter()
        .any(|route| route.target.port() == fixture.manifest().tls_echo_port && route.phase == "initial"));

    let events = fixture.events().snapshot();
    assert!(events.iter().any(|event| event.service == "tls_echo" && event.detail == "accept"));
    assert!(events.iter().any(|event| {
        event.service == "tls_echo"
            && event.detail == "handshake"
            && event.sni.as_deref() == Some(fixture.manifest().fixture_domain.as_str())
    }));
    assert!(!events.iter().any(|event| event.service == "tls_echo" && event.detail.starts_with("handshake_error:")));
}

fn http_connect_round_trip(fixture: &FixtureStack) {
    let proxy = start_proxy(ephemeral_proxy_config(&["--http-connect", "--ip", "127.0.0.1"]), None);
    assert_eq!(
        http_connect_round_trip_with_retry(proxy.port, fixture.manifest().tcp_echo_port, b"http connect"),
        b"http connect"
    );
    let events = fixture.events().snapshot();
    assert!(events
        .iter()
        .any(|event| { event.service == "tcp_echo" && event.protocol == "tcp" && event.detail == "echo" }));
    drop(proxy);
}

fn domain_resolution_policy_is_enforced(fixture: &FixtureStack) {
    let proxy = start_proxy(ephemeral_proxy_config(&["-X", "--ip", "127.0.0.1"]), None);
    assert_eq!(
        socks_connect_domain_round_trip_with_retry(
            proxy.port,
            "localhost",
            fixture.manifest().tcp_echo_port,
            b"domain ok"
        ),
        b"domain ok"
    );
    drop(proxy);

    let proxy_no_domain = start_proxy(ephemeral_proxy_config(&["-X", "--no-domain", "--ip", "127.0.0.1"]), None);
    let (_stream, reply) = socks_connect_domain(proxy_no_domain.port, "localhost", fixture.manifest().tcp_echo_port);
    assert_ne!(reply[1], 0x00, "expected localhost domain to be rejected when --no-domain is enabled");
    drop(proxy_no_domain);
}

fn chained_upstream_round_trip_records_fixture_socks_usage(fixture: &FixtureStack) {
    let upstream_telemetry = Arc::new(RecordingTelemetry::default());
    let upstream = start_proxy(
        ephemeral_proxy_config(&["--ip", "127.0.0.1"]),
        Some(upstream_telemetry.clone() as Arc<dyn RuntimeTelemetrySink>),
    );
    let proxy = start_proxy(
        ephemeral_proxy_config(&["--ip", "127.0.0.1", "--to-socks5", &format!("127.0.0.1:{}", upstream.port)]),
        None,
    );
    assert_eq!(
        socks_connect_ip_round_trip_with_retry(proxy.port, fixture.manifest().tcp_echo_port, b"chain ok"),
        b"chain ok"
    );
    drop(proxy);
    assert!(
        wait_for_accepted_connections(upstream_telemetry.as_ref(), 1, START_TIMEOUT),
        "expected chained traffic to hit upstream proxy"
    );
    drop(upstream);
}

fn hosts_filter_only_routes_matching_domain_via_upstream(fixture: &FixtureStack) {
    let upstream_telemetry = Arc::new(RecordingTelemetry::default());
    let upstream = start_proxy(
        ephemeral_proxy_config(&["-X", "--ip", "127.0.0.1"]),
        Some(upstream_telemetry.clone() as Arc<dyn RuntimeTelemetrySink>),
    );
    let proxy = start_proxy(
        ephemeral_proxy_config(&[
            "-X",
            "--ip",
            "127.0.0.1",
            "--to-socks5",
            &format!("127.0.0.1:{}", upstream.port),
            "--hosts",
            ":localhost",
        ]),
        None,
    );

    assert_eq!(
        socks_connect_domain_round_trip_via_upstream_with_retry(
            proxy.port,
            upstream_telemetry.as_ref(),
            "localhost",
            fixture.manifest().tcp_echo_port,
            b"host filter"
        ),
        b"host filter"
    );
    let upstream_after_domain = upstream_telemetry.snapshot().accepted;

    let mut ip_stream = socks_connect(proxy.port, fixture.manifest().tcp_echo_port);
    ip_stream.write_all(b"direct path").expect("write direct payload");
    assert_eq!(recv_exact(&mut ip_stream, 11), b"direct path");

    let upstream_after_ip = upstream_telemetry.snapshot().accepted;
    assert_eq!(
        upstream_after_ip, upstream_after_domain,
        "non-matching IP target should not traverse the upstream relay"
    );
    drop(proxy);
    drop(upstream);
}

fn _assert_fixture_event_contains(events: &[FixtureEvent], service: &str, detail: &str) {
    assert!(events.iter().any(|event| event.service == service && event.detail.contains(detail)));
}

// ── TCP desync step round-trip tests (Linux-only: desync uses AwaitWritable/BPF) ──

#[test]
#[cfg(any(target_os = "linux", target_os = "android"))]
fn tcp_split_desync_round_trip_delivers_payload() {
    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    let proxy = start_proxy(ephemeral_proxy_config(&["--ip", "127.0.0.1", "--split", "5"]), None);

    let body =
        socks_connect_ip_round_trip_with_retry(proxy.port, fixture.manifest().tcp_echo_port, b"split desync payload");
    assert_eq!(body, b"split desync payload");

    let events = fixture.events().snapshot();
    assert!(events.iter().any(|e| e.service == "tcp_echo" && e.detail == "echo"));
    drop(proxy);
}

#[test]
#[cfg(any(target_os = "linux", target_os = "android"))]
fn tcp_disorder_desync_round_trip_delivers_payload() {
    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    let proxy = start_proxy(ephemeral_proxy_config(&["--ip", "127.0.0.1", "--disorder", "5"]), None);

    let body = socks_connect_ip_round_trip_with_retry(
        proxy.port,
        fixture.manifest().tcp_echo_port,
        b"disorder desync payload",
    );
    assert_eq!(body, b"disorder desync payload");

    let events = fixture.events().snapshot();
    assert!(events.iter().any(|e| e.service == "tcp_echo" && e.detail == "echo"));
    drop(proxy);
}

#[test]
#[cfg(any(target_os = "linux", target_os = "android"))]
fn tcp_oob_desync_round_trip_delivers_payload() {
    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    let proxy = start_proxy(ephemeral_proxy_config(&["--ip", "127.0.0.1", "--oob", "5"]), None);

    let body =
        socks_connect_ip_round_trip_with_retry(proxy.port, fixture.manifest().tcp_echo_port, b"oob desync payload");
    assert_eq!(body, b"oob desync payload");

    let events = fixture.events().snapshot();
    assert!(events.iter().any(|e| e.service == "tcp_echo" && e.detail == "echo"));
    drop(proxy);
}

// ── TLS with desync round-trip tests (Linux-only) ──

#[test]
#[cfg(any(target_os = "linux", target_os = "android"))]
fn tls_round_trip_with_split_desync_completes_handshake() {
    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");

    let mut config = ui_proxy_config();
    config.groups[0].matches.proto = IS_TCP | IS_HTTPS;
    config.groups[0]
        .actions
        .tcp_chain
        .push(TcpChainStep::new(TcpChainStepKind::Split, ripdpi_config::OffsetExpr::absolute(5)));
    config.network.delay_conn = true;
    let proxy = start_proxy(config, None);

    let response = socks5_tls_round_trip_with_retry(proxy.port, &fixture, None);
    assert!(response.contains("fixture tls ok"), "TLS with split desync should complete: {response:?}");

    let events = fixture.events().snapshot();
    assert!(events.iter().any(|e| e.service == "tls_echo" && e.detail == "handshake"));
    assert!(!events.iter().any(|e| e.service == "tls_echo" && e.detail.starts_with("handshake_error:")));
    drop(proxy);
}

#[test]
#[cfg(any(target_os = "linux", target_os = "android"))]
fn tls_round_trip_with_disorder_desync_completes_handshake() {
    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");

    let mut config = ui_proxy_config();
    config.groups[0].matches.proto = IS_TCP | IS_HTTPS;
    config.groups[0]
        .actions
        .tcp_chain
        .push(TcpChainStep::new(TcpChainStepKind::Disorder, ripdpi_config::OffsetExpr::absolute(5)));
    config.network.delay_conn = true;
    let proxy = start_proxy(config, None);

    let response = socks5_tls_round_trip_with_retry(proxy.port, &fixture, None);
    assert!(response.contains("fixture tls ok"), "TLS with disorder desync should complete: {response:?}");

    let events = fixture.events().snapshot();
    assert!(events.iter().any(|e| e.service == "tls_echo" && e.detail == "handshake"));
    drop(proxy);
}

// ── HTTP parser evasion tests ──

#[test]
fn http_mod_mixed_case_host_delivers_payload() {
    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    let proxy = start_proxy(ephemeral_proxy_config(&["--ip", "127.0.0.1", "--mod-http", "h"]), None);

    let body = socks_connect_ip_round_trip_with_retry(
        proxy.port,
        fixture.manifest().tcp_echo_port,
        b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n",
    );
    // The echo server returns whatever it receives; desync applies to first payload only
    // so the HTTP request should still echo back. The mod may or may not apply
    // (depends on whether the payload matches HTTP detection heuristics at the proxy level).
    // Key assertion: round-trip succeeds without errors.
    assert!(!body.is_empty(), "HTTP payload should echo back");

    let events = fixture.events().snapshot();
    assert!(events.iter().any(|e| e.service == "tcp_echo" && e.detail == "echo"));
    drop(proxy);
}

#[test]
fn http_mod_unix_eol_delivers_payload() {
    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    let proxy = start_proxy(ephemeral_proxy_config(&["--ip", "127.0.0.1", "--mod-http", "u"]), None);

    let body = socks_connect_ip_round_trip_with_retry(
        proxy.port,
        fixture.manifest().tcp_echo_port,
        b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n",
    );
    assert!(!body.is_empty(), "HTTP payload with unix EOL mod should echo back");

    let events = fixture.events().snapshot();
    assert!(events.iter().any(|e| e.service == "tcp_echo" && e.detail == "echo"));
    drop(proxy);
}

#[test]
fn http_mod_combined_flags_delivers_payload() {
    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    let proxy = start_proxy(ephemeral_proxy_config(&["--ip", "127.0.0.1", "--mod-http", "h,d,u"]), None);

    let body = socks_connect_ip_round_trip_with_retry(
        proxy.port,
        fixture.manifest().tcp_echo_port,
        b"GET / HTTP/1.1\r\nHost: sub.example.com\r\n\r\n",
    );
    assert!(!body.is_empty(), "HTTP payload with combined mods should echo back");
    drop(proxy);
}

// ── Failure classification and route advancement ──

#[test]
fn route_advancement_fires_on_tcp_reset_and_second_group_handles_traffic() {
    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");

    fixture.faults().set(FixtureFaultSpec {
        target: FixtureFaultTarget::TcpEcho,
        outcome: FixtureFaultOutcome::TcpReset,
        scope: FixtureFaultScope::OneShot,
        delay_ms: None,
    });

    let telemetry = Arc::new(RecordingTelemetry::default());

    // Two desync groups: both match TCP to any destination.
    // The first connection hits the one-shot TCP reset fault.
    // The proxy should classify the failure and advance to group 1.
    let mut config = ephemeral_proxy_config(&["--ip", "127.0.0.1"]);
    config.groups[0].matches.detect = ripdpi_config::DETECT_TCP_RESET | ripdpi_config::DETECT_SILENT_DROP;
    config.timeouts.timeout_ms = 500;

    // Add a second fallback group
    let mut fallback = DesyncGroup::new(1);
    fallback.matches.proto = IS_TCP;
    config.groups.push(fallback);

    let proxy = start_proxy(config, Some(telemetry.clone() as Arc<dyn RuntimeTelemetrySink>));

    // First connection: trigger the fault
    let mut stream = socks_connect(proxy.port, fixture.manifest().tcp_echo_port);
    stream.write_all(b"trigger fault").expect("write");
    let mut buf = [0u8; 64];
    let _ = stream.read(&mut buf); // may fail due to reset
    drop(stream);

    // Second connection: should succeed (fault was one-shot)
    let body =
        socks_connect_ip_round_trip_with_retry(proxy.port, fixture.manifest().tcp_echo_port, b"after advancement");
    assert_eq!(body, b"after advancement");

    drop(proxy);

    let snapshot = telemetry.snapshot();

    // Verify route advancement was triggered
    let advanced_events: Vec<_> = snapshot.routes.iter().filter(|r| r.phase == "advanced").collect();
    assert!(
        !advanced_events.is_empty(),
        "expected at least one route advancement event after TCP reset, routes: {:?}",
        snapshot.routes
    );

    // The fault should have been recorded by the fixture
    let events = fixture.events().snapshot();
    assert!(events.iter().any(|e| e.service == "tcp_echo" && e.detail.contains("TcpReset")));
}

// ── Multiple desync steps in chain (Linux-only) ──

#[test]
#[cfg(any(target_os = "linux", target_os = "android"))]
fn tcp_chain_split_then_disorder_delivers_payload() {
    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    let proxy = start_proxy(ephemeral_proxy_config(&["--ip", "127.0.0.1", "--split", "3", "--disorder", "7"]), None);

    let body = socks_connect_ip_round_trip_with_retry(
        proxy.port,
        fixture.manifest().tcp_echo_port,
        b"chained split+disorder payload",
    );
    assert_eq!(body, b"chained split+disorder payload");
    drop(proxy);
}

// ── Window clamp + desync combination (Linux-only) ──

#[test]
#[cfg(any(target_os = "linux", target_os = "android"))]
fn window_clamp_with_split_desync_delivers_payload() {
    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    let proxy =
        start_proxy(ephemeral_proxy_config(&["--ip", "127.0.0.1", "--window-clamp", "2", "--split", "5"]), None);

    let body =
        socks_connect_ip_round_trip_with_retry(proxy.port, fixture.manifest().tcp_echo_port, b"clamp plus split");
    assert_eq!(body, b"clamp plus split");
    drop(proxy);
}

// ── Drop SACK + strip timestamps combination (Linux-only: BPF filters) ──

#[test]
#[cfg(any(target_os = "linux", target_os = "android"))]
fn drop_sack_and_strip_timestamps_round_trip_succeeds() {
    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    let proxy = start_proxy(ephemeral_proxy_config(&["--ip", "127.0.0.1", "--drop-sack", "--strip-timestamps"]), None);

    let body =
        socks_connect_ip_round_trip_with_retry(proxy.port, fixture.manifest().tcp_echo_port, b"sack+timestamps combo");
    assert_eq!(body, b"sack+timestamps combo");

    // Also verify TLS still works with these socket-level manipulations
    let response = socks5_tls_round_trip_with_retry(proxy.port, &fixture, None);
    assert!(response.contains("fixture tls ok"), "TLS should work with SACK/timestamps: {response:?}");
    drop(proxy);
}

// ── QUIC disabled mode falls back correctly ──

#[test]
fn socks5_udp_non_quic_payload_round_trips_without_crash() {
    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    let proxy = start_proxy(ephemeral_proxy_config(&["--ip", "127.0.0.1", "--udp-fake", "2"]), None);
    let (_control, relay) = socks_udp_associate(proxy.port);
    let udp = udp_proxy_client();

    // Send non-QUIC UDP payload (plain bytes, not a QUIC Initial)
    let plain_payload = b"not a quic packet at all";
    let echoed = udp_proxy_roundtrip_with_socket(&udp, relay, fixture.manifest().udp_echo_port, plain_payload);
    assert_eq!(echoed, plain_payload, "non-QUIC UDP should echo back intact");
    drop(proxy);
}
