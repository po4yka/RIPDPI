use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::time::Duration;

use local_network_fixture::ShadowsocksLoopback;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

use super::{sample_config, shadowsocks_config_mut};
use crate::runtime::RelayRuntime;

/// F14 teardown-ordering regression (audit `claude-rust-critical-problems-audit`).
///
/// The relay-android JNI layer runs a session by building a **fresh**
/// `tokio::runtime::Builder::new_multi_thread().enable_all().build()` per
/// `jniStart`, calling `runtime.block_on(session.run())` on the JNI thread, and
/// dropping that runtime at end-of-scope once `block_on` returns
/// (`ripdpi-relay-android/src/lifecycle.rs:62-75`). `jniStop` only signals the
/// session; `jniDestroy` later drops the registry `Arc`. The existing
/// `shutdown_drain` tests drive `tokio::spawn(run())` on the *test* runtime, so
/// they prove the drain but never exercise the build-a-runtime →
/// `block_on` → drop-the-runtime path that the audit flagged as un-traced.
///
/// This test reproduces that exact path: each cycle builds a fresh multi-thread
/// runtime on a dedicated (JNI-equivalent) `std::thread`, runs the relay to a
/// mid-session `stop()`, joins the thread (so the runtime is dropped
/// *outside* any tokio context — the Mullvad self-deadlock-free pattern), then
/// drops the last `Arc` (`== jniDestroy`). On Linux it asserts that `/proc`
/// fd and thread counts stay bounded across many cycles — a leaked runtime would
/// strand its epoll/eventfd plus worker threads every cycle. On non-Linux hosts
/// the cycles still run as a smoke test (no hang / panic / self-deadlock); only
/// the `/proc`-based leak assertions are skipped.
#[test]
pub(super) fn relay_runtime_start_stop_destroy_cycle_leaks_no_fds_or_threads() {
    const WARMUP_CYCLES: usize = 1;
    const MEASURED_CYCLES: usize = 8;

    // A single long-lived harness runtime drives the *peripheral* async work
    // (fixture start, SOCKS5 clients, telemetry polling). The relay's own
    // runtime is built and dropped per cycle inside `run_one_cycle`, matching
    // `relay_start_entry`. Reusing one harness keeps its threads/fds out of the
    // per-cycle delta.
    let harness = tokio::runtime::Builder::new_multi_thread().enable_all().build().expect("build harness runtime");

    // The fixture spawns its own runtime thread for its lifetime; start it once
    // and keep it alive across every cycle so its fds/threads sit in the
    // baseline, not the per-cycle window.
    let fixture =
        harness.block_on(ShadowsocksLoopback::start("aes-256-gcm", "secret")).expect("start shadowsocks fixture");

    // The first multi-thread runtime build plus the one-time rustls/ring and DNS
    // globals allocate cached fds/threads that never return to baseline; measure
    // deltas across the steady-state cycles only.
    for _ in 0..WARMUP_CYCLES {
        run_one_cycle(&harness, &fixture);
    }

    #[cfg(target_os = "linux")]
    let (fd_before, thread_before) = (sample_fd_count(), sample_thread_count());

    for _ in 0..MEASURED_CYCLES {
        run_one_cycle(&harness, &fixture);
    }

    #[cfg(target_os = "linux")]
    {
        let fd_after = sample_fd_count();
        let thread_after = sample_thread_count();
        assert!(
            fd_after <= fd_before + 4,
            "fd leak across {MEASURED_CYCLES} create->start->stop->destroy cycles: before={fd_before} after={fd_after} \
             (a leaked relay runtime strands its epoll/eventfd + session sockets every cycle)",
        );
        assert!(
            thread_after <= thread_before + 4,
            "thread/runtime leak across {MEASURED_CYCLES} cycles: before={thread_before} after={thread_after} \
             (a leaked multi_thread runtime strands its worker threads every cycle)",
        );
    }
}

/// Run one full `create -> start -> (mid-session) stop -> join -> destroy` cycle,
/// reproducing the relay-android JNI lifecycle ordering. Returns only after the
/// per-cycle runtime has been dropped and the last `Arc` released.
fn run_one_cycle(harness: &tokio::runtime::Runtime, fixture: &ShadowsocksLoopback) {
    const SESSION_COUNT: usize = 3;

    // Reserve then release a loopback port so the relay can bind it (the
    // dead-port pattern from `shutdown_drain`).
    let socks_port = {
        let probe = std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind probe listener");
        probe.local_addr().expect("probe addr").port()
    };

    let mut config = sample_config("shadowsocks");
    config.common.server = "127.0.0.1".to_string();
    config.common.server_port = i32::from(fixture.port());
    config.common.local_socks_host = "127.0.0.1".to_string();
    config.common.local_socks_port = i32::from(socks_port);
    shadowsocks_config_mut(&mut config).method = "aes-256-gcm".to_string();

    let runtime = RelayRuntime::new(config);

    // The JNI-equivalent thread: build a fresh multi-thread runtime, run the
    // relay to completion, then drop the runtime at end-of-scope (outside any
    // tokio context) — exactly `relay_start_entry`.
    let relay_for_thread = Arc::clone(&runtime);
    let jni_thread = std::thread::Builder::new()
        .name("ripdpi-relay-jni-sim".to_string())
        .spawn(move || {
            let relay_runtime = tokio::runtime::Builder::new_multi_thread()
                .enable_all()
                .build()
                .expect("build per-session relay runtime");
            let result = relay_runtime.block_on(relay_for_thread.run());
            // `relay_runtime` drops here: its worker threads must join before
            // this thread exits, so `join()` below observes a fully torn-down
            // runtime.
            result
        })
        .expect("spawn jni-sim thread");

    harness.block_on(async {
        let socks_addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), socks_port);
        wait_until(Duration::from_secs(5), || runtime.telemetry().listener_address.is_some())
            .await
            .expect("relay listener must bind");

        // Open SESSION_COUNT idle CONNECT sessions so `stop()` fires mid-session.
        let target_addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), fixture.target_port());
        let mut clients = Vec::with_capacity(SESSION_COUNT);
        for _ in 0..SESSION_COUNT {
            let mut client = tokio::net::TcpStream::connect(socks_addr).await.expect("connect SOCKS5 client");
            socks5_connect(&mut client, target_addr).await.expect("SOCKS5 CONNECT handshake");
            clients.push(client);
        }
        wait_until(Duration::from_secs(5), || runtime.telemetry().active_sessions == SESSION_COUNT as u64)
            .await
            .expect("all sessions must register as active");

        // Stop while the sessions are still connected and idle.
        runtime.stop();
        drop(clients);
    });

    // Join the JNI-equivalent thread: `block_on(run())` returned and the
    // per-session runtime was dropped, joining its workers.
    let run_result = jni_thread.join().expect("jni-sim thread must not panic");
    run_result.expect("run() returns Ok after a clean stop");
    assert_eq!(0, runtime.telemetry().active_sessions, "all in-flight sessions must drain to zero after stop()");

    // Drop the last `Arc` == `jniDestroy`: the RelayRuntime and its cached
    // upstream session fd are released here.
    drop(runtime);
}

/// Count entries under `/proc/self/fd` — the open file descriptors of this
/// process. Linux-only (the leak gate runs on CI ubuntu).
#[cfg(target_os = "linux")]
fn sample_fd_count() -> usize {
    std::fs::read_dir("/proc/self/fd").map_or(0, Iterator::count)
}

/// Count entries under `/proc/self/task` — the live threads of this process.
/// Linux-only.
#[cfg(target_os = "linux")]
fn sample_thread_count() -> usize {
    std::fs::read_dir("/proc/self/task").map_or(0, Iterator::count)
}

/// Drive a SOCKS5 no-auth greeting + CONNECT handshake against `client`,
/// targeting `target`, and read back the 10-byte success reply.
async fn socks5_connect(client: &mut tokio::net::TcpStream, target: SocketAddr) -> io::Result<()> {
    client.write_all(&[0x05, 0x01, 0x00]).await?;
    let mut method_reply = [0_u8; 2];
    client.read_exact(&mut method_reply).await?;
    if method_reply != [0x05, 0x00] {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "unexpected SOCKS5 method reply"));
    }

    let SocketAddr::V4(v4) = target else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "test SOCKS5 helper only supports IPv4 targets"));
    };
    let mut request = vec![0x05, 0x01, 0x00, 0x01];
    request.extend_from_slice(&v4.ip().octets());
    request.extend_from_slice(&v4.port().to_be_bytes());
    client.write_all(&request).await?;

    let mut reply = [0_u8; 10];
    client.read_exact(&mut reply).await?;
    if reply[1] != 0x00 {
        return Err(io::Error::new(io::ErrorKind::ConnectionRefused, "SOCKS5 CONNECT was not granted"));
    }
    Ok(())
}

/// Poll `predicate` every 20 ms until it returns `true` or `timeout` elapses.
async fn wait_until<F>(timeout: Duration, mut predicate: F) -> Result<(), ()>
where
    F: FnMut() -> bool,
{
    let deadline = tokio::time::Instant::now() + timeout;
    loop {
        if predicate() {
            return Ok(());
        }
        if tokio::time::Instant::now() >= deadline {
            return Err(());
        }
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
}
