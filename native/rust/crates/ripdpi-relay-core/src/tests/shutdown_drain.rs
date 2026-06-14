use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::time::Duration;

use local_network_fixture::ShadowsocksLoopback;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

use super::{sample_config, shadowsocks_config_mut};
use crate::runtime::RelayRuntime;

/// Reproduce-before-fix for the `RelayRuntime::stop()` session leak
/// (`fix-relay-core-session-leak-on-shutdown`): before the fix
/// `spawn_socks_session` used a bare `tokio::spawn` with no `CancellationToken`
/// and a discarded `JoinHandle`, so in-flight SOCKS5 sessions kept running after
/// `stop()` until their own I/O completed — here, never, because the sessions
/// idle in `copy_bidirectional`. The relay is bound on loopback over a real
/// Shadowsocks backend, three SOCKS5 clients each CONNECT to the fixture echo
/// and then go idle (no bytes flow), and `stop()` must drain every session
/// within the bounded grace window: `run()` returns and `active_sessions()`
/// settles to 0. Without the token+`TaskTracker` drain this test hangs past the
/// grace window (the sessions never finish on their own).
#[tokio::test]
pub(super) async fn relay_runtime_stop_drains_in_flight_sessions_within_grace_window() {
    const SESSION_COUNT: usize = 3;

    let fixture = ShadowsocksLoopback::start("aes-256-gcm", "secret").await.expect("start shadowsocks fixture");

    // Reserve a free loopback port for the relay's local SOCKS listener, then
    // release it so `run()` can bind it (mirrors the dead-port pattern above).
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
    let run_handle = tokio::spawn(Arc::clone(&runtime).run());

    // Wait for the listener to bind.
    let socks_addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), socks_port);
    wait_until(Duration::from_secs(5), || runtime.telemetry().listener_address.is_some())
        .await
        .expect("relay listener must bind");

    // Open SESSION_COUNT SOCKS5 CONNECT sessions to the fixture echo target and
    // leave them idle so each session blocks inside `copy_bidirectional`.
    let target_addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), fixture.target_port());
    let mut clients = Vec::with_capacity(SESSION_COUNT);
    for _ in 0..SESSION_COUNT {
        let mut client = tokio::net::TcpStream::connect(socks_addr).await.expect("connect SOCKS5 client");
        socks5_connect(&mut client, target_addr).await.expect("SOCKS5 CONNECT handshake");
        clients.push(client);
    }

    // All sessions are now in-flight.
    wait_until(Duration::from_secs(5), || runtime.telemetry().active_sessions == SESSION_COUNT as u64)
        .await
        .expect("all sessions must register as active");

    // Stop the runtime; the bounded drain must terminate every session and
    // `run()` must return well within the 5 s grace window.
    runtime.stop();
    let run_result = tokio::time::timeout(Duration::from_secs(10), run_handle)
        .await
        .expect("run() must return within the bounded drain window after stop()")
        .expect("run task must not panic");
    run_result.expect("run() returns Ok after a clean stop");

    assert_eq!(0, runtime.telemetry().active_sessions, "all in-flight sessions must drain to zero after stop()");

    // The client sockets observe EOF/reset because the relay closed its halves.
    for mut client in clients {
        let mut byte = [0_u8; 1];
        let read = tokio::time::timeout(Duration::from_secs(5), client.read(&mut byte))
            .await
            .expect("client read must not hang after relay drain");
        assert!(
            matches!(read, Ok(0) | Err(_)),
            "relay must close the client half on drain (got {read:?}) — confirms the session fd was released",
        );
    }
}

/// Pre-reply drain: a client that connects but never sends the SOCKS5 greeting
/// parks the session inside `handle_client`'s pre-reply negotiation
/// (`negotiate_no_auth`'s `read_exact`). After the cancel-token redesign — which
/// removed the outer drop-on-cancel `select!` in `spawn_socks_session` and moved
/// cancellation *into* `handle_client` — shutdown must still unwind such a
/// pre-reply session within the bounded drain grace window. This locks the
/// regression the redesign could plausibly introduce: a slowloris connect that
/// never produces a reply must drain exactly like one parked in
/// `copy_bidirectional`.
#[tokio::test]
pub(super) async fn relay_runtime_stop_drains_pre_reply_sessions_within_grace_window() {
    const SESSION_COUNT: usize = 3;

    let fixture = ShadowsocksLoopback::start("aes-256-gcm", "secret").await.expect("start shadowsocks fixture");

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
    let run_handle = tokio::spawn(Arc::clone(&runtime).run());

    let socks_addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), socks_port);
    wait_until(Duration::from_secs(5), || runtime.telemetry().listener_address.is_some())
        .await
        .expect("relay listener must bind");

    // Connect SESSION_COUNT clients that send *nothing* — each session parks in
    // the pre-reply `read_exact` of `negotiate_no_auth`, before any SOCKS5 reply.
    let mut clients = Vec::with_capacity(SESSION_COUNT);
    for _ in 0..SESSION_COUNT {
        let client = tokio::net::TcpStream::connect(socks_addr).await.expect("connect slowloris client");
        clients.push(client);
    }

    // The sessions register as active even though no byte was ever sent.
    wait_until(Duration::from_secs(5), || runtime.telemetry().active_sessions == SESSION_COUNT as u64)
        .await
        .expect("pre-reply sessions must register as active");

    runtime.stop();
    let run_result = tokio::time::timeout(Duration::from_secs(10), run_handle)
        .await
        .expect("run() must return within the bounded drain window after stop()")
        .expect("run task must not panic");
    run_result.expect("run() returns Ok after a clean stop");

    assert_eq!(
        0,
        runtime.telemetry().active_sessions,
        "pre-reply sessions must drain to zero after stop() — the cancel token reaches negotiate_no_auth",
    );

    // The relay closed the client half: a read observes EOF/reset, not a hang.
    for mut client in clients {
        let mut byte = [0_u8; 1];
        let read = tokio::time::timeout(Duration::from_secs(5), client.read(&mut byte))
            .await
            .expect("client read must not hang after pre-reply drain");
        assert!(matches!(read, Ok(0) | Err(_)), "relay must close the client half on pre-reply drain (got {read:?})",);
    }
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
/// Returns `Ok(())` on success, `Err(())` on timeout.
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
