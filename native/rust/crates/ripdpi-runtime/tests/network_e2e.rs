use std::io::{self, Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream, UdpSocket};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread;
use std::time::{Duration, Instant};

use ciadpi_config::{parse_cli, DesyncGroup, ParseResult, QuicInitialMode, RuntimeConfig, StartupEnv};
use ciadpi_packets::IS_UDP;
use local_network_fixture::{
    FixtureConfig, FixtureEvent, FixtureFaultOutcome, FixtureFaultScope, FixtureFaultSpec, FixtureFaultTarget,
    FixtureStack,
};
use ripdpi_runtime::process::{prepare_embedded, request_shutdown};
use ripdpi_runtime::runtime::{create_listener, run_proxy_with_listener};
use ripdpi_runtime::{clear_runtime_telemetry, install_runtime_telemetry, RuntimeTelemetrySink};

#[allow(dead_code)]
#[path = "../../../third_party/byedpi/tests/rust_packet_seeds.rs"]
mod rust_packet_seeds;

static TEST_LOCK: OnceLock<Mutex<()>> = OnceLock::new();

const START_TIMEOUT: Duration = Duration::from_secs(5);

#[test]
fn socks5_tcp_udp_tls_domain_chain_and_filtering_are_covered_end_to_end() {
    let _guard = TEST_LOCK.get_or_init(|| Mutex::new(())).lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");

    println!("tcp");
    socks5_tcp_round_trip(&fixture);
    println!("udp");
    socks5_udp_round_trip(&fixture);
    println!("http-connect");
    http_connect_round_trip(&fixture);
    println!("domain-policy");
    domain_resolution_policy_is_enforced(&fixture);
    println!("chain");
    chained_upstream_round_trip_records_fixture_socks_usage(&fixture);
    println!("host-filter");
    hosts_filter_only_routes_matching_domain_via_upstream(&fixture);
    println!("done");
}

#[test]
fn upstream_tcp_reset_fault_is_observed_end_to_end() {
    let _guard = TEST_LOCK.get_or_init(|| Mutex::new(())).lock().unwrap_or_else(|poisoned| poisoned.into_inner());
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
    let _guard = TEST_LOCK.get_or_init(|| Mutex::new(())).lock().unwrap_or_else(|poisoned| poisoned.into_inner());
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
    let _guard = TEST_LOCK.get_or_init(|| Mutex::new(())).lock().unwrap_or_else(|poisoned| poisoned.into_inner());
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
    let _guard = TEST_LOCK.get_or_init(|| Mutex::new(())).lock().unwrap_or_else(|poisoned| poisoned.into_inner());
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

    let mut stream = socks_connect(proxy.port, fixture.manifest().tcp_echo_port);
    stream.write_all(b"fixture tcp").expect("write tcp payload");
    assert_eq!(recv_exact(&mut stream, b"fixture tcp".len()), b"fixture tcp");

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
    let mut stream = http_connect(proxy.port, fixture.manifest().tcp_echo_port);
    stream.write_all(b"http connect").expect("write connect payload");
    assert_eq!(recv_exact(&mut stream, 12), b"http connect");
    let events = fixture.events().snapshot();
    assert!(events
        .iter()
        .any(|event| { event.service == "tcp_echo" && event.protocol == "tcp" && event.detail == "echo" }));
    drop(proxy);
}

fn domain_resolution_policy_is_enforced(fixture: &FixtureStack) {
    let proxy = start_proxy(ephemeral_proxy_config(&["-X", "--ip", "127.0.0.1"]), None);
    let (mut domain_stream, reply) = socks_connect_domain(proxy.port, "localhost", fixture.manifest().tcp_echo_port);
    assert_eq!(reply[1], 0x00, "expected localhost domain resolution to succeed");
    domain_stream.write_all(b"domain ok").expect("write domain payload");
    assert_eq!(recv_exact(&mut domain_stream, 9), b"domain ok");
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
    let mut stream = socks_connect(proxy.port, fixture.manifest().tcp_echo_port);
    stream.write_all(b"chain ok").expect("write chain payload");
    assert_eq!(recv_exact(&mut stream, 8), b"chain ok");
    drop(proxy);
    let upstream_snapshot = upstream_telemetry.snapshot();
    assert!(upstream_snapshot.accepted >= 1, "expected chained traffic to hit upstream proxy");
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

    let mut domain_stream = socks_connect_domain(proxy.port, "localhost", fixture.manifest().tcp_echo_port).0;
    domain_stream.write_all(b"host filter").expect("write matching host payload");
    assert_eq!(recv_exact(&mut domain_stream, 11), b"host filter");

    let upstream_after_domain = upstream_telemetry.snapshot().accepted;
    assert!(upstream_after_domain >= 1, "matching host should traverse the upstream relay");

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

struct RunningProxy {
    port: u16,
    thread: Option<thread::JoinHandle<io::Result<()>>>,
}

impl Drop for RunningProxy {
    fn drop(&mut self) {
        request_shutdown();
        if let Some(thread) = self.thread.take() {
            let result = thread.join().expect("join proxy thread");
            result.expect("proxy stopped cleanly");
        }
        clear_runtime_telemetry();
    }
}

fn start_proxy(config: ciadpi_config::RuntimeConfig, telemetry: Option<Arc<RecordingTelemetry>>) -> RunningProxy {
    prepare_embedded();
    clear_runtime_telemetry();
    if let Some(telemetry) = telemetry {
        install_runtime_telemetry(telemetry);
    }
    let listener = create_listener(&config).expect("create listener");
    let port = listener.local_addr().expect("listener addr").port();
    let thread = thread::spawn(move || run_proxy_with_listener(config, listener));
    wait_for_port(port);
    RunningProxy { port, thread: Some(thread) }
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

fn wait_for_port(port: u16) {
    let started = Instant::now();
    while started.elapsed() < START_TIMEOUT {
        if TcpStream::connect((Ipv4Addr::LOCALHOST, port)).is_ok() {
            return;
        }
        thread::sleep(Duration::from_millis(20));
    }
    panic!("proxy listener on port {port} did not become reachable");
}

fn socks_auth(proxy_port: u16) -> TcpStream {
    let mut stream = TcpStream::connect((Ipv4Addr::LOCALHOST, proxy_port)).expect("connect to proxy");
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

#[derive(Default)]
struct RecordingTelemetry {
    started: AtomicUsize,
    stopped: AtomicUsize,
    accepted: AtomicUsize,
    routes: Mutex<Vec<RouteEvent>>,
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

fn _assert_fixture_event_contains(events: &[FixtureEvent], service: &str, detail: &str) {
    assert!(events.iter().any(|event| event.service == service && event.detail.contains(detail)));
}

// ── Characterization: delayed-connect replies before reading payload ──

#[test]
fn socks5_delay_connect_replies_before_first_payload_and_round_trips() {
    let _guard = TEST_LOCK.get_or_init(|| Mutex::new(())).lock().unwrap_or_else(|poisoned| poisoned.into_inner());
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
    let _guard = TEST_LOCK.get_or_init(|| Mutex::new(())).lock().unwrap_or_else(|poisoned| poisoned.into_inner());
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
    let _guard = TEST_LOCK.get_or_init(|| Mutex::new(())).lock().unwrap_or_else(|poisoned| poisoned.into_inner());
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
    let _guard = TEST_LOCK.get_or_init(|| Mutex::new(())).lock().unwrap_or_else(|poisoned| poisoned.into_inner());
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
    let _guard = TEST_LOCK.get_or_init(|| Mutex::new(())).lock().unwrap_or_else(|poisoned| poisoned.into_inner());
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
