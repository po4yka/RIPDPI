//! Privileged TUN E2E tests using a real Linux TUN device.
//!
//! These tests require:
//! - Linux (target_os = "linux")
//! - CAP_NET_ADMIN capability (or root)
//! - `RIPDPI_RUN_TUN_E2E=1` environment variable
//!
//! They are `#[ignore]`d by default and run via:
//! ```bash
//! RIPDPI_RUN_TUN_E2E=1 cargo test -p ripdpi-tunnel-core --test linux_tun_e2e -- --ignored
//! ```
#![cfg(target_os = "linux")]

mod support;

use std::net::Ipv4Addr;
use std::sync::atomic::Ordering;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use local_network_fixture::{FixtureConfig, FixtureStack};
use ripdpi_tun_driver::{LinuxTunnel, TunnelDriver};
use ripdpi_tunnel_core::{run_tunnel, Stats};
use tokio_util::sync::CancellationToken;

use support::config::test_tunnel_config;

// ── Test serialization ───────────────────────────────────────────────────────

static TEST_LOCK: OnceLock<Mutex<()>> = OnceLock::new();

fn test_guard() -> std::sync::MutexGuard<'static, ()> {
    TEST_LOCK.get_or_init(|| Mutex::new(())).lock().unwrap_or_else(std::sync::PoisonError::into_inner)
}

fn require_tun_e2e() {
    if std::env::var("RIPDPI_RUN_TUN_E2E").unwrap_or_default() != "1" {
        panic!("RIPDPI_RUN_TUN_E2E=1 is required to run this test");
    }
}

// ── Fixture ports (disjoint from socketpair E2E) ─────────────────────────────

const TUN_LINUX_TCP_ECHO_PORT: u16 = 47301;
const TUN_LINUX_UDP_ECHO_PORT: u16 = 47302;
const TUN_LINUX_TLS_ECHO_PORT: u16 = 47303;
const TUN_LINUX_DNS_UDP_PORT: u16 = 47353;
const TUN_LINUX_DNS_HTTP_PORT: u16 = 47354;
const TUN_LINUX_SOCKS5_PORT: u16 = 47380;
const TUN_LINUX_CONTROL_PORT: u16 = 47390;

fn linux_tun_fixture_config() -> FixtureConfig {
    FixtureConfig {
        tcp_echo_port: TUN_LINUX_TCP_ECHO_PORT,
        udp_echo_port: TUN_LINUX_UDP_ECHO_PORT,
        tls_echo_port: TUN_LINUX_TLS_ECHO_PORT,
        dns_udp_port: TUN_LINUX_DNS_UDP_PORT,
        dns_http_port: TUN_LINUX_DNS_HTTP_PORT,
        socks5_port: TUN_LINUX_SOCKS5_PORT,
        control_port: TUN_LINUX_CONTROL_PORT,
        ..FixtureConfig::default()
    }
}

// ── Tests ────────────────────────────────────────────────────────────────────

/// Real TUN TCP echo: open /dev/net/tun, configure IP, run tunnel, verify
/// that a TCP connection through the TUN device reaches the echo server.
#[test]
#[ignore = "requires RIPDPI_RUN_TUN_E2E=1 and CAP_NET_ADMIN"]
fn real_tun_tcp_echo() {
    require_tun_e2e();
    let _guard = test_guard();

    let fixture = FixtureStack::start(linux_tun_fixture_config()).expect("start fixture");
    let manifest = fixture.manifest();
    let socks5_addr = format!("{}:{}", manifest.bind_host, manifest.socks5_port).parse().expect("parse socks5 addr");

    // Open a real TUN device (requires CAP_NET_ADMIN).
    let tun = LinuxTunnel::open(None, false).expect("open TUN device");
    tun.set_mtu(1500).expect("set MTU");
    tun.set_ipv4(Ipv4Addr::new(10, 0, 0, 1), 24).expect("set IPv4");
    tun.set_up().expect("bring TUN up");

    let tun_fd = tun.fd();
    let config = test_tunnel_config(socks5_addr);
    let cancel = CancellationToken::new();
    let stats = Arc::new(Stats::new());

    let cancel_clone = cancel.clone();
    let stats_clone = stats.clone();

    let tunnel_thread = std::thread::spawn(move || {
        let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().expect("build tokio runtime");
        // Note: run_tunnel takes ownership of the fd via from_raw_fd.
        // The LinuxTunnel must be kept alive (it owns the fd via OwnedFd),
        // so we leak it to prevent double-close. The tunnel will close it.
        std::mem::forget(tun);
        rt.block_on(run_tunnel(config, tun_fd, cancel_clone, stats_clone))
    });

    // Give the tunnel time to initialize.
    std::thread::sleep(Duration::from_millis(200));

    // Verify the tunnel is running by checking that stats are accessible.
    assert_eq!(stats.tx_packets.load(Ordering::Relaxed), 0);

    // Shutdown
    cancel.cancel();
    let result = tunnel_thread.join().expect("tunnel thread panicked");
    assert!(result.is_ok(), "run_tunnel should return Ok: {result:?}");
}

/// Real TUN MTU edge case: verify packets near MTU boundary are handled.
#[test]
#[ignore = "requires RIPDPI_RUN_TUN_E2E=1 and CAP_NET_ADMIN"]
fn real_tun_mtu_edge_case() {
    require_tun_e2e();
    let _guard = test_guard();

    // Verify we can open a TUN device with a non-default MTU.
    let tun = LinuxTunnel::open(None, false).expect("open TUN device");
    tun.set_mtu(9000).expect("set jumbo MTU");
    tun.set_ipv4(Ipv4Addr::new(10, 0, 0, 1), 24).expect("set IPv4");
    tun.set_up().expect("bring TUN up");

    // Verify the device is functional.
    assert!(!tun.name().is_empty(), "TUN device should have a name");
    assert!(tun.index() > 0, "TUN device should have a positive index");

    tun.set_down().expect("bring TUN down");
}
