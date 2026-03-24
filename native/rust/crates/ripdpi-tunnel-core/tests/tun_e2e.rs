//! TUN end-to-end tests using a socketpair-backed fake TUN device.
//!
//! These tests exercise the full tunnel data path without requiring root
//! or a real TUN device: raw IP packets flow through a SOCK_DGRAM unix
//! socketpair into `run_tunnel()`, through smoltcp, through the SOCKS5
//! proxy, to a local echo server, and back.

mod support;

use std::sync::atomic::Ordering;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use local_network_fixture::{FixtureConfig, FixtureStack};
use ripdpi_tunnel_core::{run_tunnel, Stats};
use tokio_util::sync::CancellationToken;

use support::config::test_tunnel_config;
use support::fake_tun::socketpair_tun;
use support::packets::*;

// ── Test serialization ───────────────────────────────────────────────────────
//
// The fixture stack binds fixed ports, so tests must not run concurrently.

static TEST_LOCK: OnceLock<Mutex<()>> = OnceLock::new();

fn test_guard() -> std::sync::MutexGuard<'static, ()> {
    TEST_LOCK
        .get_or_init(|| Mutex::new(()))
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

// ── Fixture helpers ──────────────────────────────────────────────────────────

/// Port range for TUN E2E tests (disjoint from proxy E2E ports).
const TUN_FIXTURE_TCP_ECHO_PORT: u16 = 47201;
const TUN_FIXTURE_UDP_ECHO_PORT: u16 = 47202;
const TUN_FIXTURE_TLS_ECHO_PORT: u16 = 47203;
const TUN_FIXTURE_DNS_UDP_PORT: u16 = 47253;
const TUN_FIXTURE_DNS_HTTP_PORT: u16 = 47254;
const TUN_FIXTURE_SOCKS5_PORT: u16 = 47280;
const TUN_FIXTURE_CONTROL_PORT: u16 = 47290;

fn tun_fixture_config() -> FixtureConfig {
    FixtureConfig {
        tcp_echo_port: TUN_FIXTURE_TCP_ECHO_PORT,
        udp_echo_port: TUN_FIXTURE_UDP_ECHO_PORT,
        tls_echo_port: TUN_FIXTURE_TLS_ECHO_PORT,
        dns_udp_port: TUN_FIXTURE_DNS_UDP_PORT,
        dns_http_port: TUN_FIXTURE_DNS_HTTP_PORT,
        socks5_port: TUN_FIXTURE_SOCKS5_PORT,
        control_port: TUN_FIXTURE_CONTROL_PORT,
        ..FixtureConfig::default()
    }
}

// ── Packet helpers ───────────────────────────────────────────────────────────

/// Client IP inside the tunnel (the "phone" sending traffic).
const CLIENT_IP: [u8; 4] = [10, 0, 0, 99];
/// Client port for TCP tests.
const CLIENT_PORT: u16 = 40000;

/// Read packets from the harness until we find one with the expected TCP flags,
/// or timeout after `max_wait`.
fn recv_tcp_with_flags(
    harness: &support::fake_tun::FakeTunHarness,
    expected_flags_mask: u8,
    max_wait: Duration,
) -> Option<Vec<u8>> {
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
                continue
            }
            Err(_) => return None,
        }
    }
    None
}

// ── Tests ────────────────────────────────────────────────────────────────────

/// E2E-01: TCP round-trip through the full tunnel data path.
///
/// Verifies: TUN read -> smoltcp 3WHS -> session spawn -> SOCKS5 connect ->
/// TCP echo server -> response back through smoltcp -> TUN write.
#[test]
fn tcp_round_trip_through_tunnel() {
    let _guard = test_guard();

    let fixture = FixtureStack::start(tun_fixture_config()).expect("start fixture");
    let manifest = fixture.manifest();
    let socks5_addr = format!("{}:{}", manifest.bind_host, manifest.socks5_port)
        .parse()
        .expect("parse socks5 addr");

    let (tunnel_fd, harness) = socketpair_tun().expect("create socketpair");
    let config = test_tunnel_config(socks5_addr);
    let cancel = CancellationToken::new();
    let stats = Arc::new(Stats::new());

    let cancel_clone = cancel.clone();
    let stats_clone = stats.clone();

    // Run the tunnel on a dedicated tokio runtime (it blocks until cancelled).
    let tunnel_thread = std::thread::spawn(move || {
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("build tokio runtime");
        rt.block_on(run_tunnel(config, tunnel_fd, cancel_clone, stats_clone))
    });

    // Give the tunnel event loop time to initialize.
    std::thread::sleep(Duration::from_millis(100));

    // The echo server listens on 127.0.0.1:<tcp_echo_port>.
    // The tunnel intercepts any destination (set_any_ip=true) and forwards
    // through SOCKS5, so we target 127.0.0.1 directly.
    let dst_ip: [u8; 4] = [127, 0, 0, 1];
    let dst_port = manifest.tcp_echo_port;

    // Step 1: SYN
    let syn = build_tcp_syn(CLIENT_IP, dst_ip, CLIENT_PORT, dst_port);
    harness.inject_packet(&syn).expect("inject SYN");

    // Step 2: Wait for SYN-ACK from smoltcp
    let syn_ack = recv_tcp_with_flags(&harness, TCP_SYN | TCP_ACK, Duration::from_secs(3))
        .expect("expected SYN-ACK from tunnel");
    let (server_seq, _) = tcp_seq_ack(&syn_ack);

    // Step 3: Complete 3WHS with ACK
    let ack = build_tcp_ack(CLIENT_IP, dst_ip, CLIENT_PORT, dst_port, 1, server_seq + 1);
    harness.inject_packet(&ack).expect("inject ACK");

    // Give the tunnel time to spawn the session and connect through SOCKS5.
    std::thread::sleep(Duration::from_millis(500));

    // Step 4: Send data (PSH+ACK)
    let payload = b"hello tunnel e2e";
    let psh = build_tcp_psh(
        CLIENT_IP,
        dst_ip,
        CLIENT_PORT,
        dst_port,
        1,
        server_seq + 1,
        payload,
    );
    harness.inject_packet(&psh).expect("inject PSH");

    // Step 5: Read echoed data from tunnel
    // The echo server reflects the payload, so we should see it come back.
    let mut echoed_data = Vec::new();
    let deadline = std::time::Instant::now() + Duration::from_secs(5);
    while echoed_data.len() < payload.len() && std::time::Instant::now() < deadline {
        let remaining = deadline.saturating_duration_since(std::time::Instant::now());
        match harness.recv_packet(remaining.min(Duration::from_millis(200))) {
            Ok(pkt) if pkt.len() > 40 && (tcp_flags(&pkt) & TCP_PSH) != 0 => {
                let data = tcp_payload(&pkt);
                echoed_data.extend_from_slice(data);
            }
            Ok(pkt) if pkt.len() > 40 && (tcp_flags(&pkt) & TCP_ACK) != 0 => {
                // Pure ACK or data ACK -- check for payload
                let data = tcp_payload(&pkt);
                if !data.is_empty() {
                    echoed_data.extend_from_slice(data);
                }
            }
            Ok(_) => {} // other packet, ignore
            Err(_) => {}
        }
    }

    assert_eq!(
        echoed_data,
        payload,
        "echo server must reflect the payload through the tunnel"
    );

    // Verify stats were updated
    assert!(
        stats.tx_packets.load(Ordering::Relaxed) > 0,
        "tx_packets must be non-zero after traffic"
    );

    // Shutdown
    cancel.cancel();
    let result = tunnel_thread.join().expect("tunnel thread panicked");
    assert!(result.is_ok(), "run_tunnel should return Ok after cancel: {result:?}");
}

/// E2E-02: Tunnel shuts down cleanly when CancellationToken is triggered.
#[test]
fn tunnel_shutdown_on_cancel() {
    let _guard = test_guard();

    let fixture = FixtureStack::start(tun_fixture_config()).expect("start fixture");
    let manifest = fixture.manifest();
    let socks5_addr = format!("{}:{}", manifest.bind_host, manifest.socks5_port)
        .parse()
        .expect("parse socks5 addr");

    let (tunnel_fd, _harness) = socketpair_tun().expect("create socketpair");
    let config = test_tunnel_config(socks5_addr);
    let cancel = CancellationToken::new();
    let stats = Arc::new(Stats::new());

    let cancel_clone = cancel.clone();
    let stats_clone = stats.clone();

    let tunnel_thread = std::thread::spawn(move || {
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("build tokio runtime");
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
    assert!(
        elapsed < Duration::from_secs(2),
        "tunnel should shut down within 2s, took {elapsed:?}"
    );
}

/// E2E-03: Stats counters are populated after tunnel traffic.
#[test]
fn stats_count_packets_and_bytes() {
    let _guard = test_guard();

    let fixture = FixtureStack::start(tun_fixture_config()).expect("start fixture");
    let manifest = fixture.manifest();
    let socks5_addr = format!("{}:{}", manifest.bind_host, manifest.socks5_port)
        .parse()
        .expect("parse socks5 addr");

    let (tunnel_fd, harness) = socketpair_tun().expect("create socketpair");
    let config = test_tunnel_config(socks5_addr);
    let cancel = CancellationToken::new();
    let stats = Arc::new(Stats::new());

    let cancel_clone = cancel.clone();
    let stats_clone = stats.clone();

    let tunnel_thread = std::thread::spawn(move || {
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("build tokio runtime");
        rt.block_on(run_tunnel(config, tunnel_fd, cancel_clone, stats_clone))
    });

    std::thread::sleep(Duration::from_millis(100));

    // Inject a SYN packet to generate traffic
    let syn = build_tcp_syn(CLIENT_IP, [127, 0, 0, 1], CLIENT_PORT, manifest.tcp_echo_port);
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
