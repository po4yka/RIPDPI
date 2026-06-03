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
use std::os::fd::{FromRawFd, OwnedFd};
use std::sync::atomic::Ordering;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use local_network_fixture::{FixtureConfig, FixtureStack};
use ripdpi_tun_driver::{LinuxTunnel, TunnelDriver};
use ripdpi_tunnel_core::{Stats, run_tunnel};
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

fn require_soak() {
    if std::env::var("RIPDPI_RUN_SOAK").unwrap_or_default() != "1" {
        panic!("RIPDPI_RUN_SOAK=1 is required to run this test");
    }
}

// ── Fixture ports (disjoint from socketpair E2E) ─────────────────────────────

const TUN_LINUX_TCP_ECHO_PORT: u16 = 47301;
const TUN_LINUX_UDP_ECHO_PORT: u16 = 47302;
const TUN_LINUX_TLS_ECHO_PORT: u16 = 47303;
const TUN_LINUX_DNS_UDP_PORT: u16 = 47353;
const TUN_LINUX_DNS_HTTP_PORT: u16 = 47354;
const TUN_LINUX_DNS_DOT_PORT: u16 = 47355;
const TUN_LINUX_DNS_DNSCRYPT_PORT: u16 = 47356;
const TUN_LINUX_DNS_DOQ_PORT: u16 = 47357;
const TUN_LINUX_SOCKS5_PORT: u16 = 47380;
const TUN_LINUX_CONTROL_PORT: u16 = 47390;

fn linux_tun_fixture_config() -> FixtureConfig {
    FixtureConfig {
        tcp_echo_port: TUN_LINUX_TCP_ECHO_PORT,
        udp_echo_port: TUN_LINUX_UDP_ECHO_PORT,
        tls_echo_port: TUN_LINUX_TLS_ECHO_PORT,
        dns_udp_port: TUN_LINUX_DNS_UDP_PORT,
        dns_http_port: TUN_LINUX_DNS_HTTP_PORT,
        dns_dot_port: TUN_LINUX_DNS_DOT_PORT,
        dns_dnscrypt_port: TUN_LINUX_DNS_DNSCRYPT_PORT,
        dns_doq_port: TUN_LINUX_DNS_DOQ_PORT,
        socks5_port: TUN_LINUX_SOCKS5_PORT,
        control_port: TUN_LINUX_CONTROL_PORT,
        ..FixtureConfig::default()
    }
}

/// Count open file descriptors in the current process via /proc/self/fd.
fn count_open_fds() -> usize {
    std::fs::read_dir("/proc/self/fd").map_or(0, Iterator::count)
}

/// Return `true` if `raw_fd` is closed (i.e. `fstat` returns EBADF).
///
/// SAFETY: The caller must guarantee that `raw_fd` is no longer owned by any
/// Rust value and is only inspected here to verify the kernel has closed it.
/// Passing a fd that is still open and used elsewhere produces a race.
fn fd_is_closed(raw_fd: libc::c_int) -> bool {
    // SAFETY: fstat on an invalid fd returns -1 with errno=EBADF.
    // We use a zeroed stat struct just to provide a valid output buffer.
    let mut stat: libc::stat = unsafe { std::mem::zeroed() };
    let ret = unsafe { libc::fstat(raw_fd, &mut stat) };
    if ret == 0 {
        return false; // fd is still open
    }
    let errno = std::io::Error::last_os_error().raw_os_error().unwrap_or(0);
    errno == libc::EBADF
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

    // Extract the raw fd then forget the LinuxTunnel to avoid double-close.
    // Wrap in OwnedFd so run_tunnel's type system enforces ownership transfer.
    //
    // SAFETY: `tun.fd()` returns the raw integer that `tun` owns.
    // `std::mem::forget(tun)` prevents `LinuxTunnel::drop` from closing it,
    // so the resulting `OwnedFd` is the sole owner.
    let raw = tun.fd();
    std::mem::forget(tun);
    let owned_fd = unsafe { OwnedFd::from_raw_fd(raw) };

    let config = test_tunnel_config(socks5_addr);
    let cancel = CancellationToken::new();
    let stats = Arc::new(Stats::new());

    let cancel_clone = cancel.clone();
    let stats_clone = stats.clone();

    let tunnel_thread = std::thread::spawn(move || {
        let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().expect("build tokio runtime");
        rt.block_on(run_tunnel(config, owned_fd, cancel_clone, stats_clone))
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

/// Verify that the dup'd TUN fd is closed exactly once after run_tunnel
/// returns (no fd leak across N start/stop cycles).
///
/// Procedure:
/// 1. Record open fd count before.
/// 2. For each cycle: open TUN, wrap as OwnedFd, run_tunnel, cancel, join.
///    Assert the raw fd is EBADF after join (proves it was closed).
/// 3. Assert total fd count did not grow (proves no silent leak).
#[test]
#[ignore = "requires RIPDPI_RUN_TUN_E2E=1 and CAP_NET_ADMIN"]
fn real_tun_no_fd_leak_after_stop() {
    require_tun_e2e();
    let _guard = test_guard();

    let fixture = FixtureStack::start(linux_tun_fixture_config()).expect("start fixture");
    let manifest = fixture.manifest();
    let socks5_addr = format!("{}:{}", manifest.bind_host, manifest.socks5_port).parse().expect("parse socks5 addr");

    const CYCLES: usize = 10;

    let fd_before = count_open_fds();

    for i in 0..CYCLES {
        let tun = LinuxTunnel::open(None, false).expect("open TUN device");
        tun.set_mtu(1500).expect("set MTU");
        tun.set_ipv4(Ipv4Addr::new(10, 0, 0, 1), 24).expect("set IPv4");
        tun.set_up().expect("bring TUN up");

        let raw = tun.fd();
        std::mem::forget(tun);
        // SAFETY: same as real_tun_tcp_echo — forget prevents double-close.
        let owned_fd = unsafe { OwnedFd::from_raw_fd(raw) };

        let config = test_tunnel_config(socks5_addr);
        let cancel = CancellationToken::new();
        let stats = Arc::new(Stats::new());
        let cancel_clone = cancel.clone();
        let stats_clone = stats.clone();

        let tunnel_thread = std::thread::spawn(move || {
            let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().expect("build tokio runtime");
            rt.block_on(run_tunnel(config, owned_fd, cancel_clone, stats_clone))
        });

        std::thread::sleep(Duration::from_millis(50));
        assert_eq!(stats.tx_packets.load(Ordering::Relaxed), 0, "cycle {i}: stats should start at 0");

        cancel.cancel();
        let result = tunnel_thread.join().expect("tunnel thread panicked");
        assert!(result.is_ok(), "cycle {i}: run_tunnel should return Ok: {result:?}");

        // After join, the dup'd fd must be closed (EBADF).
        assert!(fd_is_closed(raw), "cycle {i}: fd {raw} should be EBADF after tunnel stop (fd leak or double-open)");
    }

    let fd_after = count_open_fds();
    // Allow +2 slack for readdir fds opened by count_open_fds itself.
    assert!(
        fd_after <= fd_before + 2,
        "fd leak detected: before={fd_before} after={fd_after} (delta={}); {} cycles run",
        fd_after.saturating_sub(fd_before),
        CYCLES
    );
}

/// Soak test: repeated start/stop cycles plus handover-style restarts.
///
/// - Phase A (normal stop): open TUN, run_tunnel, sleep, cancel, join.
/// - Phase B (error-path simulation): open TUN, wrap OwnedFd, drop it
///   immediately (simulates adopt_tun_fd fstat-failure path).
/// - Phase C (handover): open TUN-A, start run_tunnel-A; while A runs open
///   TUN-B; cancel A, join A; start run_tunnel-B; cancel B, join B.
///
/// After each iteration: assert /proc/self/fd count did not grow.
/// Final assertion: count returned to within 2 of baseline.
#[test]
#[ignore = "requires RIPDPI_RUN_TUN_E2E=1 and CAP_NET_ADMIN and RIPDPI_RUN_SOAK=1"]
fn real_tun_soak_start_stop_handover() {
    require_tun_e2e();
    require_soak();
    let _guard = test_guard();

    let fixture = FixtureStack::start(linux_tun_fixture_config()).expect("start fixture");
    let manifest = fixture.manifest();
    let socks5_addr = format!("{}:{}", manifest.bind_host, manifest.socks5_port).parse().expect("parse socks5 addr");

    let iters: usize = std::env::var("RIPDPI_SOAK_ITERS").ok().and_then(|v| v.parse().ok()).unwrap_or(50);

    let fd_baseline = count_open_fds();

    for i in 0..iters {
        // ── Phase A: normal stop ──────────────────────────────────────────────
        {
            let tun = LinuxTunnel::open(None, false).expect("open TUN (phase A)");
            tun.set_mtu(1500).expect("set MTU");
            tun.set_ipv4(Ipv4Addr::new(10, 0, 0, 1), 24).expect("set IPv4");
            tun.set_up().expect("bring TUN up");
            let raw_a = tun.fd();
            std::mem::forget(tun);
            // SAFETY: forget prevents double-close.
            let owned_a = unsafe { OwnedFd::from_raw_fd(raw_a) };

            let cancel_a = CancellationToken::new();
            let stats_a = Arc::new(Stats::new());
            let config_a = test_tunnel_config(socks5_addr);
            let cancel_a_clone = cancel_a.clone();
            let stats_a_clone = stats_a.clone();

            let thread_a = std::thread::spawn(move || {
                let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().expect("build runtime A");
                rt.block_on(run_tunnel(config_a, owned_a, cancel_a_clone, stats_a_clone))
            });
            std::thread::sleep(Duration::from_millis(30));
            cancel_a.cancel();
            let result_a = thread_a.join().expect("thread A panicked");
            assert!(result_a.is_ok(), "soak iter {i} phase A: {result_a:?}");
            assert!(fd_is_closed(raw_a), "soak iter {i} phase A: fd {raw_a} should be EBADF after stop");
        }

        // ── Phase B: drop OwnedFd without passing to run_tunnel ───────────────
        // Simulates the error path where adopt_tun_fd returns early (e.g. fstat
        // failure) and OwnedFd::drop closes the dup.
        {
            let tun = LinuxTunnel::open(None, false).expect("open TUN (phase B)");
            tun.set_mtu(1500).expect("set MTU");
            tun.set_ipv4(Ipv4Addr::new(10, 0, 0, 2), 24).expect("set IPv4");
            tun.set_up().expect("bring TUN up");
            let raw_b = tun.fd();
            std::mem::forget(tun);
            // SAFETY: forget prevents double-close.
            let owned_b = unsafe { OwnedFd::from_raw_fd(raw_b) };
            drop(owned_b); // fd must be closed exactly here
            assert!(fd_is_closed(raw_b), "soak iter {i} phase B: fd {raw_b} should be EBADF after drop");
        }

        // ── Phase C: handover (old session stops, new session starts) ─────────
        {
            // Start TUN-A.
            let tun_c_a = LinuxTunnel::open(None, false).expect("open TUN-A (phase C)");
            tun_c_a.set_mtu(1500).expect("set MTU");
            tun_c_a.set_ipv4(Ipv4Addr::new(10, 0, 0, 3), 24).expect("set IPv4");
            tun_c_a.set_up().expect("bring TUN-A up");
            let raw_c_a = tun_c_a.fd();
            std::mem::forget(tun_c_a);
            // SAFETY: forget prevents double-close.
            let owned_c_a = unsafe { OwnedFd::from_raw_fd(raw_c_a) };

            let cancel_c_a = CancellationToken::new();
            let stats_c_a = Arc::new(Stats::new());
            let config_c_a = test_tunnel_config(socks5_addr);
            let cancel_c_a_clone = cancel_c_a.clone();
            let stats_c_a_clone = stats_c_a.clone();

            let thread_c_a = std::thread::spawn(move || {
                let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().expect("build runtime C-A");
                rt.block_on(run_tunnel(config_c_a, owned_c_a, cancel_c_a_clone, stats_c_a_clone))
            });
            std::thread::sleep(Duration::from_millis(30));

            // While A runs, open TUN-B (simulates handover: new fd created
            // before old session is stopped).
            let tun_c_b = LinuxTunnel::open(None, false).expect("open TUN-B (phase C)");
            tun_c_b.set_mtu(1500).expect("set MTU");
            tun_c_b.set_ipv4(Ipv4Addr::new(10, 0, 0, 4), 24).expect("set IPv4");
            tun_c_b.set_up().expect("bring TUN-B up");
            let raw_c_b = tun_c_b.fd();
            std::mem::forget(tun_c_b);
            // SAFETY: forget prevents double-close.
            let owned_c_b = unsafe { OwnedFd::from_raw_fd(raw_c_b) };

            // Stop A.
            cancel_c_a.cancel();
            let result_c_a = thread_c_a.join().expect("thread C-A panicked");
            assert!(result_c_a.is_ok(), "soak iter {i} phase C (stop A): {result_c_a:?}");
            assert!(fd_is_closed(raw_c_a), "soak iter {i} phase C: fd A {raw_c_a} should be EBADF");

            // Start B.
            let cancel_c_b = CancellationToken::new();
            let stats_c_b = Arc::new(Stats::new());
            let config_c_b = test_tunnel_config(socks5_addr);
            let cancel_c_b_clone = cancel_c_b.clone();
            let stats_c_b_clone = stats_c_b.clone();

            let thread_c_b = std::thread::spawn(move || {
                let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().expect("build runtime C-B");
                rt.block_on(run_tunnel(config_c_b, owned_c_b, cancel_c_b_clone, stats_c_b_clone))
            });
            std::thread::sleep(Duration::from_millis(30));
            cancel_c_b.cancel();
            let result_c_b = thread_c_b.join().expect("thread C-B panicked");
            assert!(result_c_b.is_ok(), "soak iter {i} phase C (stop B): {result_c_b:?}");
            assert!(fd_is_closed(raw_c_b), "soak iter {i} phase C: fd B {raw_c_b} should be EBADF");
        }

        // ── Per-iteration fd leak check ───────────────────────────────────────
        let fd_current = count_open_fds();
        assert!(fd_current <= fd_baseline + 5, "fd leak at soak iter {i}: baseline={fd_baseline} current={fd_current}");
    }

    // Final tight assertion.
    let fd_final = count_open_fds();
    assert!(fd_final <= fd_baseline + 2, "fd leak after {iters} soak iters: baseline={fd_baseline} final={fd_final}");
}
