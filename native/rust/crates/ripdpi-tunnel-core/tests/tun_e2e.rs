//! TUN end-to-end tests using a socketpair-backed fake TUN device.
//!
//! These tests exercise the full tunnel data path without requiring root
//! or a real TUN device: raw IP packets flow through a SOCK_DGRAM unix
//! socketpair into `run_tunnel()`, through smoltcp, through the SOCKS5
//! proxy, to a local echo server, and back.

mod support;

use std::any::Any;
use std::io;
use std::os::fd::OwnedFd;
use std::os::unix::net::UnixDatagram;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock, mpsc};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use local_network_fixture::{FixtureConfig, FixtureEvent, FixtureManifest, FixtureStack};
use ripdpi_tunnel_core::{Stats, run_tunnel, run_tunnel_with_ready};
use tokio_util::sync::CancellationToken;
use tracing_subscriber::EnvFilter;

use support::config::{test_tunnel_config, test_tunnel_config_with_misc};
use support::fake_tun::{FakeTunHarness, socketpair_tun};
use support::packets::*;

// ── Test serialization ───────────────────────────────────────────────────────
//
// The fixture stack binds fixed ports, so tests must not run concurrently.

static TEST_LOCK: OnceLock<Mutex<()>> = OnceLock::new();
static TRACING_INIT: OnceLock<()> = OnceLock::new();

fn test_guard() -> std::sync::MutexGuard<'static, ()> {
    TEST_LOCK.get_or_init(|| Mutex::new(())).lock().unwrap_or_else(std::sync::PoisonError::into_inner)
}

fn init_test_tracing() {
    TRACING_INIT.get_or_init(|| {
        let _ = tracing_subscriber::fmt()
            .with_env_filter(EnvFilter::new("ripdpi_tunnel_core=debug"))
            .with_test_writer()
            .with_ansi(false)
            .try_init();
    });
}

// ── Fixture helpers ──────────────────────────────────────────────────────────

fn tun_fixture_config() -> FixtureConfig {
    FixtureConfig {
        tcp_echo_port: 0,
        udp_echo_port: 0,
        tls_echo_port: 0,
        dns_udp_port: 0,
        dns_http_port: 0,
        dns_dot_port: 0,
        dns_dnscrypt_port: 0,
        dns_doq_port: 0,
        dns_odoh_proxy_port: 0,
        dns_odoh_target_port: 0,
        socks5_port: 0,
        control_port: 0,
        ..FixtureConfig::default()
    }
}

// ── Packet helpers ───────────────────────────────────────────────────────────

/// Client IP inside the tunnel (the "phone" sending traffic).
const CLIENT_IP: [u8; 4] = [10, 0, 0, 99];
/// Client port for TCP tests.
const CLIENT_PORT: u16 = 40000;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct TcpFourTuple {
    source_ip: [u8; 4],
    source_port: u16,
    destination_ip: [u8; 4],
    destination_port: u16,
}

fn tcp_four_tuple(packet: &[u8]) -> Option<TcpFourTuple> {
    if packet.len() < 40 || packet[0] >> 4 != 4 || packet[9] != 6 {
        return None;
    }
    let ip_header_len = usize::from(packet[0] & 0x0f) * 4;
    if ip_header_len < 20 || packet.len() < ip_header_len + 20 {
        return None;
    }
    Some(TcpFourTuple {
        source_ip: packet[12..16].try_into().expect("validated IPv4 source range"),
        source_port: u16::from_be_bytes(packet[ip_header_len..ip_header_len + 2].try_into().expect("TCP source port")),
        destination_ip: packet[16..20].try_into().expect("validated IPv4 destination range"),
        destination_port: u16::from_be_bytes(
            packet[ip_header_len + 2..ip_header_len + 4].try_into().expect("TCP destination port"),
        ),
    })
}

/// Read packets from the harness until we find one with the expected TCP flags,
/// or timeout after `max_wait`.
fn recv_tcp_with_flags(harness: &FakeTunHarness, expected_flags_mask: u8, max_wait: Duration) -> Option<Vec<u8>> {
    let deadline = std::time::Instant::now() + max_wait;
    let step = Duration::from_millis(50);
    while std::time::Instant::now() < deadline {
        let remaining = deadline.saturating_duration_since(std::time::Instant::now());
        let timeout = remaining.min(step);
        match harness.recv_packet(timeout) {
            Ok(pkt) if pkt.len() >= 34 && (tcp_flags(&pkt) & expected_flags_mask) == expected_flags_mask => {
                return Some(pkt);
            }
            Ok(_) => continue, // wrong packet type, keep reading
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock || e.kind() == std::io::ErrorKind::TimedOut => {
                continue;
            }
            Err(_) => return None,
        }
    }
    None
}

fn tcp_packet_summary(pkt: &[u8]) -> String {
    if pkt.len() < 40 {
        return format!("short_packet(len={})", pkt.len());
    }
    let flags = tcp_flags(pkt);
    let (seq, ack) = tcp_seq_ack(pkt);
    let payload_len = tcp_payload(pkt).len();
    format!(
        "tuple={:?},flags=0x{flags:02x},seq={seq},ack={ack},payload_len={payload_len},len={}",
        tcp_four_tuple(pkt),
        pkt.len()
    )
}

fn tcp_echo_endpoint(manifest: &FixtureManifest) -> String {
    let host = manifest.bind_host.parse::<std::net::IpAddr>().expect("fixture bind host must be an IP address");
    std::net::SocketAddr::new(host, manifest.tcp_echo_port).to_string()
}

fn socks_connected_to_echo(event: &FixtureEvent, echo_endpoint: &str) -> bool {
    event.service == "socks5_relay" && event.protocol == "tcp" && event.detail == echo_endpoint
}

fn tcp_echo_payload_bytes(events: &[FixtureEvent], echo_endpoint: &str) -> usize {
    events.iter().filter(|event| tcp_echo_payload_event(event, echo_endpoint)).map(|event| event.bytes).sum()
}

fn tcp_echo_payload_event(event: &FixtureEvent, echo_endpoint: &str) -> bool {
    event.service == "tcp_echo" && event.protocol == "tcp" && event.target == echo_endpoint && event.detail == "echo"
}

fn wait_for_thread_finish<T>(thread: &JoinHandle<T>, timeout: Duration) -> bool {
    let deadline = Instant::now() + timeout;
    while !thread.is_finished() && Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(10));
    }
    thread.is_finished()
}

fn assert_prequeued_packet_reaches_worker_fd(tunnel_fd: &OwnedFd, harness: &FakeTunHarness, packet: &[u8]) {
    let probe = UnixDatagram::from(tunnel_fd.try_clone().expect("clone worker-side fake TUN fd"));
    probe.set_read_timeout(Some(Duration::from_secs(1))).expect("set probe read timeout");

    let mut observed = vec![0_u8; packet.len() + 1];
    let len = probe.recv(&mut observed).expect("prequeued packet must be readable on worker-side fd before start");
    assert_eq!(&observed[..len], packet, "worker-side fake TUN fd must receive the prequeued packet bytes");

    harness.inject_packet(packet).expect("restore prequeued packet before tunnel worker starts");
}

fn panic_message(payload: &(dyn Any + Send)) -> String {
    payload
        .downcast_ref::<&str>()
        .map(|message| (*message).to_string())
        .or_else(|| payload.downcast_ref::<String>().cloned())
        .unwrap_or_else(|| "non-string panic payload".to_string())
}

#[derive(Debug)]
enum TunnelWorkerTerminal {
    Returned(io::Result<()>),
    Panicked(String),
}

#[derive(Debug)]
struct TunnelWorkerShutdown {
    stopped_within_timeout: bool,
    close_io_panic: Option<String>,
    join_panic: Option<String>,
    terminal: Option<TunnelWorkerTerminal>,
}

struct TunnelWorkerGuard {
    harness: Option<FakeTunHarness>,
    release_tx: mpsc::SyncSender<()>,
    cancel: CancellationToken,
    thread: Option<JoinHandle<()>>,
    terminal_rx: mpsc::Receiver<TunnelWorkerTerminal>,
    cleanup_complete: bool,
}

impl TunnelWorkerGuard {
    fn new(
        harness: FakeTunHarness,
        release_tx: mpsc::SyncSender<()>,
        cancel: CancellationToken,
        thread: JoinHandle<()>,
        terminal_rx: mpsc::Receiver<TunnelWorkerTerminal>,
    ) -> Self {
        Self { harness: Some(harness), release_tx, cancel, thread: Some(thread), terminal_rx, cleanup_complete: false }
    }

    fn harness(&self) -> &FakeTunHarness {
        self.harness.as_ref().expect("fake TUN harness is owned until worker shutdown")
    }

    fn release_ready(&self) {
        self.release_tx.send(()).expect("release ready callback");
    }

    fn shutdown(mut self) -> TunnelWorkerShutdown {
        self.shutdown_inner(Duration::from_secs(2))
    }

    fn shutdown_inner(&mut self, timeout: Duration) -> TunnelWorkerShutdown {
        let _ = self.release_tx.try_send(());
        self.cancel.cancel();
        let close_io_panic = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| drop(self.harness.take())))
            .err()
            .map(|panic| panic_message(panic.as_ref()));

        let (stopped_within_timeout, join_panic) = if let Some(thread) = self.thread.take() {
            let stopped_within_timeout = wait_for_thread_finish(&thread, timeout);
            let join_panic = thread.join().err().map(|panic| panic_message(panic.as_ref()));
            (stopped_within_timeout, join_panic)
        } else {
            (true, None)
        };
        let terminal = self.terminal_rx.try_recv().ok();
        self.cleanup_complete = true;

        TunnelWorkerShutdown { stopped_within_timeout, close_io_panic, join_panic, terminal }
    }
}

impl Drop for TunnelWorkerGuard {
    fn drop(&mut self) {
        if self.cleanup_complete {
            return;
        }
        let shutdown = self.shutdown_inner(Duration::from_secs(2));
        report_tunnel_worker_drop_issue(&shutdown);
    }
}

fn report_tunnel_worker_drop_issue(shutdown: &TunnelWorkerShutdown) {
    if shutdown.close_io_panic.is_some()
        || shutdown.join_panic.is_some()
        || matches!(shutdown.terminal, Some(TunnelWorkerTerminal::Panicked(_)) | None)
        || matches!(shutdown.terminal, Some(TunnelWorkerTerminal::Returned(Err(_))))
        || !shutdown.stopped_within_timeout
    {
        eprintln!("tunnel worker fallback cleanup observed non-clean shutdown: {shutdown:?}");
    }
}

fn assert_tunnel_worker_clean(shutdown: TunnelWorkerShutdown) {
    if let Some(panic) = shutdown.close_io_panic {
        panic!("closing tunnel test I/O panicked: {panic}");
    }
    if let Some(panic) = shutdown.join_panic {
        panic!("tunnel worker thread panicked outside terminal capture: {panic}");
    }
    match shutdown.terminal {
        Some(TunnelWorkerTerminal::Panicked(panic)) => {
            panic!("tunnel worker panicked: {panic}");
        }
        Some(TunnelWorkerTerminal::Returned(result)) => {
            assert!(result.is_ok(), "run_tunnel_with_ready should return Ok after cancel: {result:?}");
        }
        None => panic!("tunnel worker exited without publishing a terminal outcome"),
    }
    assert!(
        shutdown.stopped_within_timeout,
        "tunnel thread did not stop within 2s after release, cancellation, and closed TUN harness"
    );
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[test]
fn run_tunnel_emits_runtime_ready_only_after_device_and_routes_initialize() {
    let (tunnel_fd, _harness) = socketpair_tun().expect("create socketpair");
    let config = test_tunnel_config("127.0.0.1:9".parse().expect("SOCKS address"));
    let cancel = CancellationToken::new();
    let worker_cancel = cancel.clone();
    let (ready_tx, ready_rx) = mpsc::sync_channel(1);
    let tunnel_thread = std::thread::spawn(move || {
        let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().expect("build tokio runtime");
        rt.block_on(run_tunnel_with_ready(config, tunnel_fd, worker_cancel, Arc::new(Stats::new()), move || {
            ready_tx.send(()).expect("publish readiness");
        }))
    });

    ready_rx.recv_timeout(Duration::from_secs(2)).expect("readiness after packet-loop setup");
    cancel.cancel();
    let result = tunnel_thread.join().expect("tunnel thread panicked");
    assert!(result.is_ok(), "ready tunnel must stop cleanly: {result:?}");
}

#[test]
fn tunnel_initialization_failure_never_emits_runtime_ready() {
    let (tunnel_fd, _harness) = socketpair_tun().expect("create socketpair");
    let base = test_tunnel_config("127.0.0.1:9".parse().expect("SOCKS address"));
    let mut invalid = (*base).clone();
    invalid.tunnel.mtu = 0;
    let ready = Arc::new(AtomicBool::new(false));
    let ready_observer = Arc::clone(&ready);
    let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().expect("build tokio runtime");

    let result = rt.block_on(run_tunnel_with_ready(
        Arc::new(invalid),
        tunnel_fd,
        CancellationToken::new(),
        Arc::new(Stats::new()),
        move || ready_observer.store(true, Ordering::Relaxed),
    ));

    assert!(result.is_err(), "invalid setup must fail");
    assert!(!ready.load(Ordering::Relaxed), "failed initialization must never publish readiness");
}

#[test]
fn packet_loop_setup_failure_never_emits_runtime_ready() {
    let (tunnel_fd, _harness) = socketpair_tun().expect("create socketpair");
    let base = test_tunnel_config("127.0.0.1:9".parse().expect("SOCKS address"));
    let mut setup_failure = (*base).clone();
    setup_failure.socks5.address = "not-an-ip-address".to_string();
    setup_failure.validate().expect("non-IP SOCKS host passes the envelope validation stage");
    let ready = Arc::new(AtomicBool::new(false));
    let ready_observer = Arc::clone(&ready);
    let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().expect("build tokio runtime");

    let result = rt.block_on(run_tunnel_with_ready(
        Arc::new(setup_failure),
        tunnel_fd,
        CancellationToken::new(),
        Arc::new(Stats::new()),
        move || ready_observer.store(true, Ordering::Relaxed),
    ));

    let error = result.expect_err("packet-loop setup must reject a non-IP SOCKS address");
    assert!(error.to_string().contains("resolve SOCKS5 proxy address"), "unexpected setup error: {error}");
    assert!(!ready.load(Ordering::Relaxed), "fallible packet-loop setup must complete before readiness");
}

/// E2E-01: The runtime-ready callback is a first-packet barrier for the full
/// TCP tunnel data path.
///
/// Verifies that a SYN queued after TUN establishment is not read until the
/// ready callback returns, then completes TUN read -> smoltcp 3WHS -> session
/// spawn -> SOCKS5 connect -> TCP echo -> smoltcp -> TUN write.
#[test]
fn tcp_round_trip_waits_for_runtime_ready_before_first_packet() {
    let _guard = test_guard();
    init_test_tracing();

    let fixture = FixtureStack::start(tun_fixture_config()).expect("start fixture");
    let manifest = fixture.manifest();
    let socks5_addr = format!("{}:{}", manifest.bind_host, manifest.socks5_port).parse().expect("parse socks5 addr");

    let (tunnel_fd, harness) = socketpair_tun().expect("create socketpair");
    let dst_ip = manifest.fixture_ipv4.parse::<std::net::Ipv4Addr>().expect("fixture ipv4").octets();
    let dst_port = manifest.tcp_echo_port;
    let echo_endpoint = tcp_echo_endpoint(manifest);
    let syn = build_tcp_syn(CLIENT_IP, dst_ip, CLIENT_PORT, dst_port);
    let (client_syn_seq, _) = tcp_seq_ack(&syn);
    let client_next_seq = client_syn_seq.wrapping_add(1);
    let response_flow = TcpFourTuple {
        source_ip: dst_ip,
        source_port: dst_port,
        destination_ip: CLIENT_IP,
        destination_port: CLIENT_PORT,
    };
    let payload = b"hello tunnel e2e";

    // Queue the first packet before the native worker exists. This models the
    // packet that can arrive after VpnService establishes the TUN but before
    // native setup publishes readiness.
    harness.inject_packet(&syn).expect("queue SYN before tunnel worker starts");
    assert_prequeued_packet_reaches_worker_fd(&tunnel_fd, &harness, &syn);

    let config = test_tunnel_config(socks5_addr);
    let cancel = CancellationToken::new();
    let stats = Arc::new(Stats::new());
    let (barrier_entered_tx, barrier_entered_rx) = mpsc::sync_channel(1);
    let (release_tx, release_rx) = mpsc::sync_channel(1);
    let (terminal_tx, terminal_rx) = mpsc::sync_channel(1);

    let cancel_clone = cancel.clone();
    let stats_clone = stats.clone();

    // Run the tunnel on a dedicated tokio runtime (it blocks until cancelled).
    let tunnel_thread = std::thread::spawn(move || {
        let terminal = match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().expect("build tokio runtime");
            rt.block_on(run_tunnel_with_ready(config, tunnel_fd, cancel_clone, stats_clone, move || {
                let _ = barrier_entered_tx.send(());
                release_rx.recv_timeout(Duration::from_secs(5)).expect("ready barrier release");
            }))
        })) {
            Ok(result) => TunnelWorkerTerminal::Returned(result),
            Err(panic) => TunnelWorkerTerminal::Panicked(panic_message(panic.as_ref())),
        };
        let _ = terminal_tx.send(terminal);
    });
    let worker = TunnelWorkerGuard::new(harness, release_tx, cancel.clone(), tunnel_thread, terminal_rx);

    let test_result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        barrier_entered_rx.recv_timeout(Duration::from_secs(2)).expect("ready callback must enter the barrier");

        let harness = worker.harness();
        let pre_release_recv = harness.recv_packet(Duration::from_millis(200));
        assert!(
            matches!(
                &pre_release_recv,
                Err(error)
                    if matches!(error.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut)
            ),
            "TUN must not produce a packet before runtime-ready release: {pre_release_recv:?}"
        );
        assert_eq!(stats.tx_packets.load(Ordering::Relaxed), 0, "queued SYN must not reach the packet loop");
        assert_eq!(stats.tx_bytes.load(Ordering::Relaxed), 0, "queued SYN bytes must not be counted");
        assert_eq!(stats.rx_packets.load(Ordering::Relaxed), 0, "smoltcp must not write a packet before release");
        assert_eq!(stats.rx_bytes.load(Ordering::Relaxed), 0, "smoltcp bytes must remain zero before release");

        let pre_release_events = fixture.events().snapshot();
        assert!(
            pre_release_events.iter().all(|event| !event.service.starts_with("socks5") && event.service != "tcp_echo"),
            "fixture must not observe SOCKS or TCP traffic before release: {pre_release_events:?}"
        );

        worker.release_ready();

        let syn_ack = recv_tcp_with_flags(harness, TCP_SYN | TCP_ACK, Duration::from_secs(3))
            .expect("expected SYN-ACK after ready release");
        assert_eq!(tcp_flags(&syn_ack), TCP_SYN | TCP_ACK, "SYN-ACK must not carry unrelated TCP flags");
        assert_eq!(tcp_four_tuple(&syn_ack), Some(response_flow), "SYN-ACK must belong to the queued client flow");
        let (server_seq, syn_ack_ack) = tcp_seq_ack(&syn_ack);
        assert_eq!(syn_ack_ack, client_next_seq, "SYN-ACK must acknowledge the queued client SYN exactly");
        let server_next_seq = server_seq.wrapping_add(1);

        let ack = build_tcp_ack(CLIENT_IP, dst_ip, CLIENT_PORT, dst_port, client_next_seq, server_next_seq);
        harness.inject_packet(&ack).expect("inject ACK");

        let socks_deadline = Instant::now() + Duration::from_secs(3);
        loop {
            let events = fixture.events().snapshot();
            if events.iter().any(|event| socks_connected_to_echo(event, &echo_endpoint)) {
                break;
            }
            assert!(Instant::now() < socks_deadline, "SOCKS fixture did not observe the TCP session: {events:?}");
            std::thread::sleep(Duration::from_millis(10));
        }

        let psh = build_tcp_psh(CLIENT_IP, dst_ip, CLIENT_PORT, dst_port, client_next_seq, server_next_seq, payload);
        harness.inject_packet(&psh).expect("inject PSH");

        let mut echoed_data = Vec::new();
        let mut observed_packets = Vec::new();
        let mut expected_echo_seq = server_next_seq;
        let expected_echo_ack = client_next_seq.wrapping_add(u32::try_from(payload.len()).expect("payload fits u32"));
        let deadline = Instant::now() + Duration::from_secs(5);
        while echoed_data.len() < payload.len() && Instant::now() < deadline {
            let remaining = deadline.saturating_duration_since(Instant::now());
            match harness.recv_packet(remaining.min(Duration::from_millis(200))) {
                Ok(pkt) => {
                    observed_packets.push(tcp_packet_summary(&pkt));
                    if tcp_four_tuple(&pkt) != Some(response_flow) {
                        continue;
                    }
                    let data = tcp_payload(&pkt);
                    if data.is_empty() {
                        continue;
                    }
                    let (sequence, acknowledgement) = tcp_seq_ack(&pkt);
                    if sequence != expected_echo_seq || acknowledgement != expected_echo_ack {
                        continue;
                    }
                    echoed_data.extend_from_slice(data);
                    expected_echo_seq = expected_echo_seq
                        .wrapping_add(u32::try_from(data.len()).expect("echo segment length fits u32"));
                }
                Err(error) if matches!(error.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut) => {
                }
                Err(error) => panic!("receive echoed TUN packet: {error}"),
            }
        }

        let fixture_events = fixture.events().snapshot();
        let residual_packets = harness.drain();
        assert_eq!(
            echoed_data, payload,
            "echo server must reflect the exact payload after release; observed_packets={observed_packets:?}; fixture_events={fixture_events:?}; residual_packets={residual_packets:?}"
        );
        assert!(
            fixture_events.iter().any(|event| socks_connected_to_echo(event, &echo_endpoint)),
            "SOCKS fixture must observe the released TCP session: {fixture_events:?}"
        );
        assert_eq!(
            tcp_echo_payload_bytes(&fixture_events, &echo_endpoint),
            payload.len(),
            "TCP fixture must observe the full echo payload byte count: {fixture_events:?}"
        );
        assert!(stats.tx_packets.load(Ordering::Relaxed) >= 3, "SYN, ACK, and PSH must be counted as TUN reads");
        assert!(stats.tx_bytes.load(Ordering::Relaxed) >= (syn.len() + ack.len() + psh.len()) as u64);
        assert!(stats.rx_packets.load(Ordering::Relaxed) >= 2, "SYN-ACK and echoed data must be counted as TUN writes");
        assert!(stats.rx_bytes.load(Ordering::Relaxed) >= (syn_ack.len() + payload.len()) as u64);
    }));

    let shutdown = worker.shutdown();
    assert_tunnel_worker_clean(shutdown);
    if let Err(panic) = test_result {
        std::panic::resume_unwind(panic);
    }
}

/// E2E-01b: SYN-ACK strategy enabled without a raw-protected injector still
/// forwards the original packet, so the app TCP handshake and data path remain
/// intact on devices where raw injection is unavailable.
#[test]
fn tcp_round_trip_with_synack_strategy_passthrough() {
    let _guard = test_guard();
    init_test_tracing();

    let fixture = FixtureStack::start(tun_fixture_config()).expect("start fixture");
    let manifest = fixture.manifest();
    let socks5_addr = format!("{}:{}", manifest.bind_host, manifest.socks5_port).parse().expect("parse socks5 addr");

    let (tunnel_fd, harness) = socketpair_tun().expect("create socketpair");
    let config = test_tunnel_config_with_misc(socks5_addr, |misc| {
        misc.strategy_chain_yaml = Some(
            "version: 1\nstrategies:\n  - id: vpn-synack\n    steps:\n      - type: synack\n        ttl: 5\n"
                .to_string(),
        );
    });
    let cancel = CancellationToken::new();
    let stats = Arc::new(Stats::new());

    let cancel_clone = cancel.clone();
    let stats_clone = stats.clone();

    let tunnel_thread = std::thread::spawn(move || {
        let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().expect("build tokio runtime");
        rt.block_on(run_tunnel(config, tunnel_fd, cancel_clone, stats_clone))
    });

    std::thread::sleep(Duration::from_millis(100));

    let dst_ip = manifest.fixture_ipv4.parse::<std::net::Ipv4Addr>().expect("fixture ipv4").octets();
    let dst_port = manifest.tcp_echo_port;
    let syn = build_tcp_syn(CLIENT_IP, dst_ip, CLIENT_PORT, dst_port);
    harness.inject_packet(&syn).expect("inject SYN");

    let syn_ack =
        recv_tcp_with_flags(&harness, TCP_SYN | TCP_ACK, Duration::from_secs(3)).expect("expected SYN-ACK from tunnel");
    let (server_seq, _) = tcp_seq_ack(&syn_ack);

    let ack = build_tcp_ack(CLIENT_IP, dst_ip, CLIENT_PORT, dst_port, 1, server_seq + 1);
    harness.inject_packet(&ack).expect("inject ACK");
    std::thread::sleep(Duration::from_millis(500));

    let payload = b"synack strategy passthrough";
    let psh = build_tcp_psh(CLIENT_IP, dst_ip, CLIENT_PORT, dst_port, 1, server_seq + 1, payload);
    harness.inject_packet(&psh).expect("inject PSH");

    let mut echoed_data = Vec::new();
    let deadline = std::time::Instant::now() + Duration::from_secs(5);
    while echoed_data.len() < payload.len() && std::time::Instant::now() < deadline {
        let remaining = deadline.saturating_duration_since(std::time::Instant::now());
        if let Ok(pkt) = harness.recv_packet(remaining.min(Duration::from_millis(200))) {
            let data = tcp_payload(&pkt);
            if !data.is_empty() {
                echoed_data.extend_from_slice(data);
            }
        }
    }

    assert_eq!(echoed_data, payload, "SYN-ACK strategy passthrough must not break tunnel TCP data");

    cancel.cancel();
    let result = tunnel_thread.join().expect("tunnel thread panicked");
    assert!(result.is_ok(), "run_tunnel should return Ok after cancel: {result:?}");
}

/// E2E-02: Tunnel shuts down cleanly when CancellationToken is triggered.
#[test]
fn tunnel_shutdown_on_cancel() {
    let _guard = test_guard();
    init_test_tracing();

    let fixture = FixtureStack::start(tun_fixture_config()).expect("start fixture");
    let manifest = fixture.manifest();
    let socks5_addr = format!("{}:{}", manifest.bind_host, manifest.socks5_port).parse().expect("parse socks5 addr");

    let (tunnel_fd, _harness) = socketpair_tun().expect("create socketpair");
    let config = test_tunnel_config(socks5_addr);
    let cancel = CancellationToken::new();
    let stats = Arc::new(Stats::new());

    let cancel_clone = cancel.clone();
    let stats_clone = stats.clone();

    let tunnel_thread = std::thread::spawn(move || {
        let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().expect("build tokio runtime");
        rt.block_on(run_tunnel(config, tunnel_fd, cancel_clone, stats_clone))
    });

    // Let the tunnel start
    std::thread::sleep(Duration::from_millis(100));

    // Cancel and verify it shuts down within 2 seconds
    cancel.cancel();

    let start = std::time::Instant::now();
    let result = tunnel_thread.join().expect("tunnel thread panicked");
    let elapsed = start.elapsed();

    assert!(result.is_ok(), "run_tunnel should return Ok after cancel: {result:?}");
    assert!(elapsed < Duration::from_secs(2), "tunnel should shut down within 2s, took {elapsed:?}");
}

/// E2E-03: Stats counters are populated after tunnel traffic.
#[test]
fn stats_count_packets_and_bytes() {
    let _guard = test_guard();
    init_test_tracing();

    let fixture = FixtureStack::start(tun_fixture_config()).expect("start fixture");
    let manifest = fixture.manifest();
    let socks5_addr = format!("{}:{}", manifest.bind_host, manifest.socks5_port).parse().expect("parse socks5 addr");

    let (tunnel_fd, harness) = socketpair_tun().expect("create socketpair");
    let config = test_tunnel_config(socks5_addr);
    let cancel = CancellationToken::new();
    let stats = Arc::new(Stats::new());

    let cancel_clone = cancel.clone();
    let stats_clone = stats.clone();

    let tunnel_thread = std::thread::spawn(move || {
        let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().expect("build tokio runtime");
        rt.block_on(run_tunnel(config, tunnel_fd, cancel_clone, stats_clone))
    });

    std::thread::sleep(Duration::from_millis(100));

    // Inject a SYN packet to generate traffic
    let dst_ip = manifest.fixture_ipv4.parse::<std::net::Ipv4Addr>().expect("fixture ipv4").octets();
    let syn = build_tcp_syn(CLIENT_IP, dst_ip, CLIENT_PORT, manifest.tcp_echo_port);
    harness.inject_packet(&syn).expect("inject SYN");

    // Wait for smoltcp to process and respond
    std::thread::sleep(Duration::from_millis(500));

    let tx_pkts = stats.tx_packets.load(Ordering::Relaxed);
    let tx_bytes = stats.tx_bytes.load(Ordering::Relaxed);
    let rx_pkts = stats.rx_packets.load(Ordering::Relaxed);
    let rx_bytes = stats.rx_bytes.load(Ordering::Relaxed);

    // The SYN injection should register as at least 1 tx packet.
    assert!(tx_pkts >= 1, "tx_packets should be >= 1, got {tx_pkts}");
    assert!(tx_bytes >= 40, "tx_bytes should be >= 40 (one SYN), got {tx_bytes}");

    // smoltcp should have produced at least a SYN-ACK (1 rx packet written to TUN).
    assert!(rx_pkts >= 1, "rx_packets should be >= 1, got {rx_pkts}");
    assert!(rx_bytes >= 40, "rx_bytes should be >= 40, got {rx_bytes}");

    cancel.cancel();
    tunnel_thread.join().expect("tunnel thread panicked").expect("tunnel error");
}
