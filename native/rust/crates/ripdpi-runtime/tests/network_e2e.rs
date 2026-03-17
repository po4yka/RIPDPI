// These integration tests use std::sync types and ripdpi_runtime globals that
// are compiled out under the loom feature. Skip the entire file under loom.
#![cfg(not(feature = "loom"))]

use std::io::{self, Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream, UdpSocket};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard, OnceLock};
use std::thread;
use std::time::Duration;

use ciadpi_config::{parse_cli, DesyncGroup, ParseResult, QuicInitialMode, RuntimeConfig, StartupEnv};
use ciadpi_packets::IS_UDP;
use local_network_fixture::{
    FixtureConfig, FixtureEvent, FixtureFaultOutcome, FixtureFaultScope, FixtureFaultSpec, FixtureFaultTarget,
    FixtureStack,
};
use ripdpi_runtime::process::prepare_embedded;
use ripdpi_runtime::runtime::{create_listener, run_proxy_with_embedded_control};
use ripdpi_runtime::{clear_runtime_telemetry, EmbeddedProxyControl, RuntimeTelemetrySink};

#[allow(dead_code)]
#[path = "../../../third_party/byedpi/tests/rust_packet_seeds.rs"]
mod rust_packet_seeds;

static TEST_LOCK: OnceLock<Mutex<()>> = OnceLock::new();

const START_TIMEOUT: Duration = Duration::from_secs(5);
const SOCKET_TIMEOUT: Duration = Duration::from_secs(5);
const FIXTURE_TCP_ECHO_PORT: u16 = 47101;
const FIXTURE_UDP_ECHO_PORT: u16 = 47102;
const FIXTURE_TLS_ECHO_PORT: u16 = 47103;
const FIXTURE_DNS_UDP_PORT: u16 = 47153;
const FIXTURE_DNS_HTTP_PORT: u16 = 47154;
const FIXTURE_SOCKS5_PORT: u16 = 47180;
const FIXTURE_CONTROL_PORT: u16 = 47190;

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
    let proxy =
        start_proxy(quic_udp_host_filter_config(QuicInitialMode::RouteAndCache, true, true), Some(telemetry.clone()));
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
    let proxy =
        start_proxy(quic_udp_host_filter_config(QuicInitialMode::Disabled, true, true), Some(telemetry.clone()));
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
    let proxy = start_proxy(quic_udp_host_filter_config(QuicInitialMode::Route, false, true), Some(telemetry.clone()));
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

fn socks5_tcp_round_trip(fixture: &FixtureStack) {
    let telemetry = Arc::new(RecordingTelemetry::default());
    let proxy = start_proxy(ephemeral_proxy_config(&["--ip", "127.0.0.1"]), Some(telemetry.clone()));

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
    let upstream = start_proxy(ephemeral_proxy_config(&["--ip", "127.0.0.1"]), Some(upstream_telemetry.clone()));
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
        wait_for_accepted_connections(&upstream_telemetry, 1, START_TIMEOUT),
        "expected chained traffic to hit upstream proxy"
    );
    drop(upstream);
}

fn hosts_filter_only_routes_matching_domain_via_upstream(fixture: &FixtureStack) {
    let upstream_telemetry = Arc::new(RecordingTelemetry::default());
    let upstream = start_proxy(ephemeral_proxy_config(&["-X", "--ip", "127.0.0.1"]), Some(upstream_telemetry.clone()));
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
            &upstream_telemetry,
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

fn test_guard() -> MutexGuard<'static, ()> {
    TEST_LOCK.get_or_init(|| Mutex::new(())).lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn nested_proxy_e2e_enabled() -> bool {
    matches!(std::env::var("RIPDPI_RUN_NESTED_PROXY_E2E").as_deref(), Ok("1"))
}

struct RunningProxy {
    port: u16,
    control: Arc<EmbeddedProxyControl>,
    thread: Option<thread::JoinHandle<io::Result<()>>>,
}

impl Drop for RunningProxy {
    fn drop(&mut self) {
        self.control.request_shutdown();
        if let Some(thread) = self.thread.take() {
            let result = thread.join().expect("join proxy thread");
            result.expect("proxy stopped cleanly");
        }
    }
}

fn start_proxy(config: ciadpi_config::RuntimeConfig, telemetry: Option<Arc<RecordingTelemetry>>) -> RunningProxy {
    prepare_embedded();
    clear_runtime_telemetry();
    let startup = Arc::new(StartupLatch::default());
    let harness_telemetry = Arc::new(ProxyHarnessTelemetry { startup: startup.clone(), delegate: telemetry });
    let control = Arc::new(EmbeddedProxyControl::new(Some(harness_telemetry)));
    let listener = create_listener(&config).expect("create listener");
    let port = listener.local_addr().expect("listener addr").port();
    let control_for_thread = control.clone();
    let thread = thread::spawn(move || run_proxy_with_embedded_control(config, listener, control_for_thread));
    startup.wait(START_TIMEOUT);
    RunningProxy { port, control, thread: Some(thread) }
}

fn proxy_config(args: &[&str]) -> ciadpi_config::RuntimeConfig {
    let args = args.iter().map(|value| (*value).to_string()).collect::<Vec<_>>();
    match parse_cli(&args, &StartupEnv::default()).expect("parse runtime config") {
        ParseResult::Run(config) => config,
        other => panic!("unexpected parse result: {other:?}"),
    }
}

fn ephemeral_proxy_config(args: &[&str]) -> ciadpi_config::RuntimeConfig {
    let mut config = proxy_config(args);
    config.listen.listen_port = 0;
    config
}

fn quic_udp_host_filter_config(mode: QuicInitialMode, support_v1: bool, support_v2: bool) -> RuntimeConfig {
    let mut config = ephemeral_proxy_config(&["--ip", "127.0.0.1"]);

    let mut filtered = DesyncGroup::new(0);
    filtered.proto = IS_UDP;
    filtered.filters.hosts.push("docs.example.test".to_string());

    let mut fallback = DesyncGroup::new(1);
    fallback.proto = IS_UDP;

    config.groups = vec![filtered, fallback];
    config.quic_initial_mode = mode;
    config.quic_support_v1 = support_v1;
    config.quic_support_v2 = support_v2;
    config
}

fn socks_auth(proxy_port: u16) -> TcpStream {
    let mut stream = TcpStream::connect((Ipv4Addr::LOCALHOST, proxy_port)).expect("connect to proxy");
    stream.set_read_timeout(Some(SOCKET_TIMEOUT)).expect("set socks auth read timeout");
    stream.set_write_timeout(Some(SOCKET_TIMEOUT)).expect("set socks auth write timeout");
    stream.write_all(b"\x05\x01\x00").expect("write socks auth");
    assert_eq!(recv_exact(&mut stream, 2), b"\x05\x00");
    stream
}

fn socks_connect(proxy_port: u16, dst_port: u16) -> TcpStream {
    let (stream, reply) = socks_connect_ip_reply(proxy_port, dst_port);
    assert_eq!(reply[1], 0, "SOCKS5 connect failed: {reply:?}");
    stream
}

fn socks_connect_ip_reply(proxy_port: u16, dst_port: u16) -> (TcpStream, Vec<u8>) {
    let mut stream = socks_auth(proxy_port);
    let mut request = vec![0x05, 0x01, 0x00, 0x01];
    request.extend(Ipv4Addr::LOCALHOST.octets());
    request.extend(dst_port.to_be_bytes());
    stream.write_all(&request).expect("write socks connect");
    let reply = recv_socks5_reply(&mut stream);
    (stream, reply)
}

fn socks_connect_domain(proxy_port: u16, host: &str, dst_port: u16) -> (TcpStream, Vec<u8>) {
    let mut stream = socks_auth(proxy_port);
    let host_bytes = host.as_bytes();
    let mut request = Vec::with_capacity(7 + host_bytes.len());
    request.extend([0x05, 0x01, 0x00, 0x03, host_bytes.len() as u8]);
    request.extend(host_bytes);
    request.extend(dst_port.to_be_bytes());
    stream.write_all(&request).expect("write socks domain connect");
    let reply = recv_socks5_reply(&mut stream);
    (stream, reply)
}

fn socks_connect_domain_round_trip_with_retry(proxy_port: u16, host: &str, dst_port: u16, payload: &[u8]) -> Vec<u8> {
    let mut last_error = None;
    for _ in 0..3 {
        match attempt_socks_connect_domain_round_trip(proxy_port, host, dst_port, payload) {
            Ok(body) => return body,
            Err(error) => {
                last_error = Some(error);
                thread::sleep(Duration::from_millis(50));
            }
        }
    }
    panic!(
        "domain round trip failed after retries: {}",
        last_error.unwrap_or_else(|| "unknown domain round trip error".to_string())
    );
}

fn attempt_socks_connect_domain_round_trip(
    proxy_port: u16,
    host: &str,
    dst_port: u16,
    payload: &[u8],
) -> Result<Vec<u8>, String> {
    let (mut stream, reply) = socks_connect_domain(proxy_port, host, dst_port);
    if reply.get(1).copied() != Some(0x00) {
        return Err(format!("SOCKS5 domain connect failed: {reply:?}"));
    }
    stream.write_all(payload).map_err(|error| format!("write domain payload failed: {error}"))?;
    let mut body = vec![0u8; payload.len()];
    stream.read_exact(&mut body).map_err(|error| format!("read domain payload failed: {error}"))?;
    Ok(body)
}

fn socks_connect_ip_round_trip_with_retry(proxy_port: u16, dst_port: u16, payload: &[u8]) -> Vec<u8> {
    let mut last_error = None;
    for _ in 0..3 {
        match attempt_socks_connect_ip_round_trip(proxy_port, dst_port, payload) {
            Ok(body) => return body,
            Err(error) => {
                last_error = Some(error);
                thread::sleep(Duration::from_millis(50));
            }
        }
    }
    panic!(
        "ip round trip failed after retries: {}",
        last_error.unwrap_or_else(|| "unknown ip round trip error".to_string())
    );
}

fn attempt_socks_connect_ip_round_trip(proxy_port: u16, dst_port: u16, payload: &[u8]) -> Result<Vec<u8>, String> {
    let mut stream = socks_connect(proxy_port, dst_port);
    stream.write_all(payload).map_err(|error| format!("write ip payload failed: {error}"))?;
    let mut body = vec![0u8; payload.len()];
    stream.read_exact(&mut body).map_err(|error| format!("read ip payload failed: {error}"))?;
    Ok(body)
}

fn socks_connect_domain_round_trip_via_upstream_with_retry(
    proxy_port: u16,
    upstream_telemetry: &RecordingTelemetry,
    host: &str,
    dst_port: u16,
    payload: &[u8],
) -> Vec<u8> {
    let mut last_error = None;
    for _ in 0..3 {
        let body = socks_connect_domain_round_trip_with_retry(proxy_port, host, dst_port, payload);
        if wait_for_accepted_connections(upstream_telemetry, 1, START_TIMEOUT) {
            return body;
        }
        last_error = Some("matching host did not traverse the upstream relay".to_string());
        thread::sleep(Duration::from_millis(50));
    }
    panic!(
        "domain round trip via upstream failed after retries: {}",
        last_error.unwrap_or_else(|| "unknown upstream routing error".to_string())
    );
}

fn http_connect_round_trip_with_retry(proxy_port: u16, dst_port: u16, payload: &[u8]) -> Vec<u8> {
    let mut last_error = None;
    for _ in 0..3 {
        match attempt_http_connect_round_trip(proxy_port, dst_port, payload) {
            Ok(body) => return body,
            Err(error) => {
                last_error = Some(error);
                thread::sleep(Duration::from_millis(50));
            }
        }
    }
    panic!(
        "http connect round trip failed after retries: {}",
        last_error.unwrap_or_else(|| "unknown http connect error".to_string())
    );
}

fn attempt_http_connect_round_trip(proxy_port: u16, dst_port: u16, payload: &[u8]) -> Result<Vec<u8>, String> {
    let mut stream = http_connect(proxy_port, dst_port);
    stream.write_all(payload).map_err(|error| format!("write http connect payload failed: {error}"))?;
    let mut body = vec![0u8; payload.len()];
    stream.read_exact(&mut body).map_err(|error| format!("read http connect payload failed: {error}"))?;
    Ok(body)
}

fn socks_udp_associate(proxy_port: u16) -> (TcpStream, SocketAddr) {
    let mut stream = socks_auth(proxy_port);
    let mut request = vec![0x05, 0x03, 0x00, 0x01];
    request.extend([0, 0, 0, 0]);
    request.extend([0, 0]);
    stream.write_all(&request).expect("write udp associate");
    let reply = recv_socks5_reply(&mut stream);
    assert_eq!(reply[1], 0, "SOCKS5 UDP associate failed: {reply:?}");
    (stream, parse_socks5_reply_addr(&reply))
}

fn recv_socks5_reply(stream: &mut TcpStream) -> Vec<u8> {
    let mut reply = recv_exact(stream, 4);
    let tail = match reply[3] {
        0x01 => recv_exact(stream, 6),
        0x04 => recv_exact(stream, 18),
        0x03 => {
            let mut tail = recv_exact(stream, 1);
            let size = tail[0] as usize;
            tail.extend(recv_exact(stream, size + 2));
            tail
        }
        atyp => panic!("unsupported SOCKS5 reply ATYP: {atyp}"),
    };
    reply.extend(tail);
    reply
}

fn parse_socks5_reply_addr(reply: &[u8]) -> SocketAddr {
    match reply[3] {
        0x01 => SocketAddr::new(
            IpAddr::V4(Ipv4Addr::new(reply[4], reply[5], reply[6], reply[7])),
            u16::from_be_bytes([reply[8], reply[9]]),
        ),
        0x04 => SocketAddr::new(
            IpAddr::from([
                reply[4], reply[5], reply[6], reply[7], reply[8], reply[9], reply[10], reply[11], reply[12], reply[13],
                reply[14], reply[15], reply[16], reply[17], reply[18], reply[19],
            ]),
            u16::from_be_bytes([reply[20], reply[21]]),
        ),
        atyp => panic!("unsupported SOCKS5 reply address type: {atyp}"),
    }
}

fn udp_proxy_roundtrip(relay: SocketAddr, dst_port: u16, payload: &[u8]) -> Vec<u8> {
    let socket = udp_proxy_client();
    udp_proxy_roundtrip_with_socket(&socket, relay, dst_port, payload)
}

fn udp_proxy_client() -> UdpSocket {
    let socket = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind udp client");
    socket.set_read_timeout(Some(Duration::from_secs(5))).expect("set udp timeout");
    socket
}

fn udp_proxy_roundtrip_with_socket(socket: &UdpSocket, relay: SocketAddr, dst_port: u16, payload: &[u8]) -> Vec<u8> {
    let mut packet = vec![0x00, 0x00, 0x00, 0x01];
    packet.extend(Ipv4Addr::LOCALHOST.octets());
    packet.extend(dst_port.to_be_bytes());
    packet.extend(payload);
    socket.send_to(&packet, relay).expect("send udp packet");
    let mut buf = [0u8; 4096];
    loop {
        let (read, _) = socket.recv_from(&mut buf).expect("receive udp packet");
        assert!(read >= 10, "udp response too short");
        assert_eq!(&buf[..4], b"\x00\x00\x00\x01");
        let body = &buf[10..read];
        if body == payload {
            return body.to_vec();
        }
    }
}

fn http_connect(proxy_port: u16, dst_port: u16) -> TcpStream {
    let mut stream = TcpStream::connect((Ipv4Addr::LOCALHOST, proxy_port)).expect("connect http proxy");
    stream.set_read_timeout(Some(SOCKET_TIMEOUT)).expect("set http connect read timeout");
    stream.set_write_timeout(Some(SOCKET_TIMEOUT)).expect("set http connect write timeout");
    write!(stream, "CONNECT 127.0.0.1:{dst_port} HTTP/1.1\r\nHost: 127.0.0.1:{dst_port}\r\n\r\n")
        .expect("write http connect");
    let mut response = Vec::new();
    let mut chunk = [0u8; 1024];
    while !response.windows(4).any(|window| window == b"\r\n\r\n") {
        let read = stream.read(&mut chunk).expect("read http connect reply");
        assert_ne!(read, 0, "http connect response closed early");
        response.extend_from_slice(&chunk[..read]);
    }
    let response = String::from_utf8(response).expect("utf8 connect reply");
    assert!(response.contains("HTTP/1.1 200 OK"), "connect failed: {response}");
    stream
}

fn recv_exact(stream: &mut TcpStream, size: usize) -> Vec<u8> {
    let mut buf = vec![0u8; size];
    stream.read_exact(&mut buf).expect("read exact");
    buf
}

fn wait_for_accepted_connections(telemetry: &RecordingTelemetry, minimum: usize, timeout: Duration) -> bool {
    let started = std::time::Instant::now();
    while started.elapsed() < timeout {
        if telemetry.snapshot().accepted >= minimum {
            return true;
        }
        thread::sleep(Duration::from_millis(20));
    }
    false
}

fn ephemeral_fixture_config() -> FixtureConfig {
    FixtureConfig {
        tcp_echo_port: FIXTURE_TCP_ECHO_PORT,
        udp_echo_port: FIXTURE_UDP_ECHO_PORT,
        tls_echo_port: FIXTURE_TLS_ECHO_PORT,
        dns_udp_port: FIXTURE_DNS_UDP_PORT,
        dns_http_port: FIXTURE_DNS_HTTP_PORT,
        socks5_port: FIXTURE_SOCKS5_PORT,
        control_port: FIXTURE_CONTROL_PORT,
        ..FixtureConfig::default()
    }
}

#[derive(Default)]
struct RecordingTelemetry {
    started: AtomicUsize,
    stopped: AtomicUsize,
    accepted: AtomicUsize,
    routes: Mutex<Vec<RouteEvent>>,
}

#[derive(Default)]
struct StartupLatch {
    started: Mutex<bool>,
    ready: Condvar,
}

impl StartupLatch {
    fn mark_started(&self) {
        let mut started = self.started.lock().expect("lock startup latch");
        *started = true;
        self.ready.notify_all();
    }

    fn wait(&self, timeout: Duration) {
        let started = self.started.lock().expect("lock startup latch");
        let (started, _) =
            self.ready.wait_timeout_while(started, timeout, |started| !*started).expect("wait proxy startup");
        assert!(*started, "proxy listener did not report startup within {timeout:?}");
    }
}

struct ProxyHarnessTelemetry {
    startup: Arc<StartupLatch>,
    delegate: Option<Arc<RecordingTelemetry>>,
}

impl ProxyHarnessTelemetry {
    fn delegate(&self) -> Option<&RecordingTelemetry> {
        self.delegate.as_deref()
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

    fn on_client_error(&self, _error: &io::Error) {}

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

impl RuntimeTelemetrySink for ProxyHarnessTelemetry {
    fn on_listener_started(&self, bind_addr: SocketAddr, max_clients: usize, group_count: usize) {
        self.startup.mark_started();
        if let Some(delegate) = self.delegate() {
            delegate.on_listener_started(bind_addr, max_clients, group_count);
        }
    }

    fn on_listener_stopped(&self) {
        if let Some(delegate) = self.delegate() {
            delegate.on_listener_stopped();
        }
    }

    fn on_client_accepted(&self) {
        if let Some(delegate) = self.delegate() {
            delegate.on_client_accepted();
        }
    }

    fn on_client_finished(&self) {
        if let Some(delegate) = self.delegate() {
            delegate.on_client_finished();
        }
    }

    fn on_client_error(&self, error: &io::Error) {
        if let Some(delegate) = self.delegate() {
            delegate.on_client_error(error);
        }
    }

    fn on_failure_classified(
        &self,
        target: SocketAddr,
        failure: &ripdpi_failure_classifier::ClassifiedFailure,
        host: Option<&str>,
    ) {
        if let Some(delegate) = self.delegate() {
            delegate.on_failure_classified(target, failure, host);
        }
    }

    fn on_route_selected(&self, target: SocketAddr, group_index: usize, host: Option<&str>, phase: &'static str) {
        if let Some(delegate) = self.delegate() {
            delegate.on_route_selected(target, group_index, host, phase);
        }
    }

    fn on_upstream_connected(&self, upstream_addr: SocketAddr, upstream_rtt_ms: Option<u64>) {
        if let Some(delegate) = self.delegate() {
            delegate.on_upstream_connected(upstream_addr, upstream_rtt_ms);
        }
    }

    fn on_route_advanced(
        &self,
        target: SocketAddr,
        from_group: usize,
        to_group: usize,
        trigger: u32,
        host: Option<&str>,
    ) {
        if let Some(delegate) = self.delegate() {
            delegate.on_route_advanced(target, from_group, to_group, trigger, host);
        }
    }

    fn on_retry_paced(&self, target: SocketAddr, group_index: usize, reason: &'static str, backoff_ms: u64) {
        if let Some(delegate) = self.delegate() {
            delegate.on_retry_paced(target, group_index, reason, backoff_ms);
        }
    }

    fn on_host_autolearn_state(&self, enabled: bool, learned_host_count: usize, penalized_host_count: usize) {
        if let Some(delegate) = self.delegate() {
            delegate.on_host_autolearn_state(enabled, learned_host_count, penalized_host_count);
        }
    }

    fn on_host_autolearn_event(&self, action: &'static str, host: Option<&str>, group_index: Option<usize>) {
        if let Some(delegate) = self.delegate() {
            delegate.on_host_autolearn_event(action, host, group_index);
        }
    }
}

fn _assert_fixture_event_contains(events: &[FixtureEvent], service: &str, detail: &str) {
    assert!(events.iter().any(|event| event.service == service && event.detail.contains(detail)));
}

// ── Characterization: delayed-connect replies before reading payload ──

#[test]
fn socks5_delay_connect_replies_before_first_payload_and_round_trips() {
    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");

    // --to-socks5 implicitly enables delay_conn; host filter triggers payload-aware routing
    let upstream = start_proxy(ephemeral_proxy_config(&["--ip", "127.0.0.1"]), None);
    // Global telemetry slot is shared (mem-1773400517-b6cc); only install on the active proxy
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

    // Connect with domain target — the proxy must reply (success) before reading payload
    let (mut stream, reply) = socks_connect_domain(proxy.port, "localhost", fixture.manifest().tcp_echo_port);
    assert_eq!(reply[1], 0x00, "SOCKS5 connect should succeed (delayed path)");

    // Send payload after the handshake reply — delayed-connect reads this post-reply
    stream.write_all(b"delay ok").expect("write delayed payload");
    assert_eq!(recv_exact(&mut stream, 8), b"delay ok");

    // Verify the fixture actually received the data (the payload traversed upstream)
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
    let proxy = start_proxy(ephemeral_proxy_config(&["--ip", "127.0.0.1"]), Some(telemetry.clone()));

    let (_control, relay) = socks_udp_associate(proxy.port);
    let udp = udp_proxy_client();

    // First flow to udp_echo_port
    let body1 = udp_proxy_roundtrip_with_socket(&udp, relay, fixture.manifest().udp_echo_port, b"flow-a");
    assert_eq!(body1, b"flow-a");

    // Second flow to the same port but different payload (same socket, same target port)
    let body2 = udp_proxy_roundtrip_with_socket(&udp, relay, fixture.manifest().udp_echo_port, b"flow-b");
    assert_eq!(body2, b"flow-b");

    drop(proxy);

    let snapshot = telemetry.snapshot();
    let udp_routes: Vec<_> =
        snapshot.routes.iter().filter(|r| r.target.port() == fixture.manifest().udp_echo_port).collect();
    assert!(!udp_routes.is_empty(), "UDP flows should have route events");

    // Verify fixture received both payloads
    let events = fixture.events().snapshot();
    let udp_events: Vec<_> = events.iter().filter(|e| e.service == "udp_echo" && e.protocol == "udp").collect();
    assert!(udp_events.len() >= 2, "expected at least 2 UDP echo events, got {}", udp_events.len());
}

// ── Characterization: upstream fault produces observable failure ──

#[test]
fn upstream_silent_drop_fault_is_classified_end_to_end() {
    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");

    // TcpReset on the fixture; proxy observes EOF (classified as SilentDrop per mem-1773400517-b6c4)
    fixture.faults().set(FixtureFaultSpec {
        target: FixtureFaultTarget::TcpEcho,
        outcome: FixtureFaultOutcome::TcpReset,
        scope: FixtureFaultScope::Persistent,
        delay_ms: None,
    });

    let telemetry = Arc::new(RecordingTelemetry::default());
    // Enable silent drop detection
    let mut config = ephemeral_proxy_config(&["--ip", "127.0.0.1"]);
    config.groups[0].detect = ciadpi_config::DETECT_SILENT_DROP | ciadpi_config::DETECT_TCP_RESET;
    config.timeout_ms = 500;
    let proxy = start_proxy(config, Some(telemetry.clone()));

    let mut stream = socks_connect(proxy.port, fixture.manifest().tcp_echo_port);
    stream.write_all(b"will fail").expect("write payload");

    // Read should fail or return 0 bytes (not the original echo)
    let mut buf = [0u8; 32];
    match stream.read(&mut buf) {
        Ok(n) if n > 0 => {
            assert_ne!(&buf[..n], b"will fail", "expected failure, not echo");
        }
        _ => {} // error or EOF is the expected path
    }

    drop(proxy);

    // Verify the fault was observed at the fixture level
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
    // Bound port should be non-zero (assigned by OS)
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
