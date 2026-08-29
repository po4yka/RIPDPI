//! Run through scripts/tests/run-standalone-awg-interop.py with an independent AWG-Go peer.
#![cfg(feature = "awg-interop")]

use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::time::Duration;

use anyhow::{Context, Result, ensure};
use base64::{Engine as _, engine::general_purpose::STANDARD};
use boringtun::x25519::{PublicKey, StaticSecret};
use ripdpi_warp_core::{AmneziaWgObfuscation, AmneziaWgProfileConfig, AmneziaWgRuntime};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpStream, UdpSocket};
use tokio::sync::Notify;
use tokio::time::timeout;

/// # Cancel safety
/// Cancel-safe: cancellation drops the owned socket and its partial SOCKS exchange.
// cancel-safe: no partially negotiated socket escapes on cancellation.
async fn socks_request(address: SocketAddr, command: u8, target: SocketAddr) -> Result<(TcpStream, SocketAddr)> {
    let mut stream = TcpStream::connect(address).await?;
    stream.write_all(&[5, 1, 0]).await?;
    let mut method = [0; 2];
    stream.read_exact(&mut method).await?;
    ensure!(method == [5, 0], "SOCKS method refused");
    let mut request = vec![5, command, 0];
    match target {
        SocketAddr::V4(value) => {
            request.push(1);
            request.extend_from_slice(&value.ip().octets());
        }
        SocketAddr::V6(value) => {
            request.push(4);
            request.extend_from_slice(&value.ip().octets());
        }
    }
    request.extend_from_slice(&target.port().to_be_bytes());
    stream.write_all(&request).await?;
    let mut reply = [0; 10];
    stream.read_exact(&mut reply).await?;
    ensure!(reply[..4] == [5, 0, 0, 1], "SOCKS command refused");
    let bound = SocketAddr::from((
        Ipv4Addr::new(reply[4], reply[5], reply[6], reply[7]),
        u16::from_be_bytes([reply[8], reply[9]]),
    ));
    Ok((stream, bound))
}

fn udp_frame(port: u16, payload: &[u8]) -> Vec<u8> {
    let mut frame = vec![0, 0, 0, 1, 10, 77, 0, 1];
    frame.extend_from_slice(&port.to_be_bytes());
    frame.extend_from_slice(payload);
    frame
}

/// # Cancel safety
/// Cancel-safe: all client sockets are owned here and dropped on timeout; the owner stops the runtime.
// cancel-safe: a failed exchange never reuses a partially consumed stream.
async fn exchange(runtime: &AmneziaWgRuntime, ready: &Notify) -> Result<(SocketAddr, TcpStream)> {
    ready.notified().await;
    let telemetry = runtime.telemetry();
    ensure!(telemetry.state == "running", "runtime not ready after authenticated handshake");
    let address: SocketAddr = telemetry.listener_address.context("missing SOCKS listener")?.parse()?;
    ensure!(
        address.ip().is_loopback() && address.port() != 0,
        "runtime must publish actual bound SOCKS port: {address}"
    );
    let (mut tcp, _) = socks_request(address, 1, SocketAddr::from((Ipv4Addr::new(10, 77, 0, 1), 41001))).await?;
    let payload: Vec<u8> = (0..4096).map(|index| (index % 251) as u8).collect();
    tcp.write_u32(payload.len() as u32).await?;
    tcp.write_all(&payload).await?;
    let mut echoed = vec![0; payload.len()];
    tcp.read_exact(&mut echoed).await?;
    ensure!(echoed == payload, "independent peer TCP payload mismatch");
    let (_control, relay) = socks_request(address, 3, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
    ensure!(relay.ip().is_loopback() && relay.port() != 0, "unsafe UDP association listener");
    let udp = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await?;
    let first = udp_frame(41002, b"standalone-awg-first-destination");
    let second = udp_frame(41003, b"standalone-awg-second-destination");
    udp.send_to(&first, relay).await?;
    udp.send_to(&second, relay).await?;
    let mut responses = Vec::new();
    for _ in 0..2 {
        let mut buffer = [0; 2048];
        let (size, source) = udp.recv_from(&mut buffer).await?;
        ensure!(source == relay, "unexpected UDP response socket");
        responses.push(buffer[..size].to_vec());
    }
    ensure!(
        responses.contains(&first) && responses.contains(&second),
        "UDP replies must preserve each actual remote source"
    );
    let (mut tcp6, _) = socks_request(address, 1, "[fd77::1]:41004".parse()?).await.context("IPv6 SOCKS connect")?;
    tcp6.write_u32(payload.len() as u32).await?;
    tcp6.write_all(&payload).await?;
    tcp6.read_exact(&mut echoed).await?;
    ensure!(echoed == payload, "independent peer IPv6 TCP payload mismatch");
    let mut first6 = vec![0, 0, 0, 4];
    first6.extend_from_slice(&"fd77::1".parse::<std::net::Ipv6Addr>()?.octets());
    first6.extend_from_slice(&41005_u16.to_be_bytes());
    first6.extend_from_slice(b"standalone-awg-ipv6-first");
    let mut second6 = first6[..20].to_vec();
    second6.extend_from_slice(&41006_u16.to_be_bytes());
    second6.extend_from_slice(b"standalone-awg-ipv6-second");
    udp.send_to(&first6, relay).await?;
    udp.send_to(&second6, relay).await?;
    responses.clear();
    for _ in 0..2 {
        let mut buffer = [0; 2048];
        let (size, source) = udp.recv_from(&mut buffer).await?;
        ensure!(source == relay, "unexpected IPv6 UDP response socket");
        responses.push(buffer[..size].to_vec());
    }
    ensure!(
        responses.contains(&first6) && responses.contains(&second6),
        "IPv6 UDP replies must preserve each actual remote source"
    );
    let accepted = runtime.telemetry().total_sessions;
    let mut stalled = TcpStream::connect(address).await?;
    stalled.write_all(&[5]).await?;
    while runtime.telemetry().total_sessions == accepted {
        tokio::task::yield_now().await;
    }
    Ok((address, stalled))
}

/// # Cancel safety
/// Not cancel-safe: this test owns a runtime task and explicitly stops, aborts if needed, and joins it.
// NOT cancel-safe: libtest owns this complete lifecycle; no select wraps the test.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn standalone_profile_exchanges_tcp_and_udp_with_independent_awg_peer() -> Result<()> {
    let endpoint: SocketAddr = std::env::var("RIPDPI_AWG_INTEROP_ENDPOINT")
        .context("run this feature through scripts/tests/run-standalone-awg-interop.py")?
        .parse()?;
    ensure!(endpoint.is_ipv4() && endpoint.ip().is_loopback() && endpoint.port() != 0, "peer must be IPv4 loopback");
    let runtime = AmneziaWgRuntime::new(AmneziaWgProfileConfig {
        enabled: true,
        profile_id: "awg-independent-loopback-interop".into(),
        private_key: STANDARD.encode([7; 32]),
        peer_public_key: STANDARD.encode(PublicKey::from(&StaticSecret::from([9; 32])).as_bytes()),
        preshared_key: STANDARD.encode([5; 32]),
        endpoint_host: endpoint.ip().to_string(),
        endpoint_ipv4: endpoint.ip().to_string(),
        endpoint_port: i32::from(endpoint.port()),
        interface_address_v4: "10.77.0.2/32".into(),
        interface_address_v6: "fd77::2/128".into(),
        mtu: 1420,
        local_socks_host: "127.0.0.1".into(),
        local_socks_port: 0,
        amnezia: AmneziaWgObfuscation {
            jc: 4,
            jmin: 64,
            jmax: 96,
            s1: 8,
            s2: 12,
            h1: 268435457,
            h2: 268435458,
            h3: 268435459,
            h4: 268435460,
            i1: "deadbeef".into(),
            ..Default::default()
        },
        ..Default::default()
    });
    let ready = Arc::new(Notify::new());
    let signal = Arc::clone(&ready);
    runtime.set_readiness_observer(Arc::new(move || signal.notify_one()));
    let worker = Arc::clone(&runtime);
    let mut task = tokio::spawn(async move { worker.run().await });
    let outcome = timeout(Duration::from_secs(20), exchange(&runtime, &ready)).await;
    runtime.stop();
    let stopped = timeout(Duration::from_secs(2), &mut task).await;
    if stopped.is_err() {
        task.abort();
        let _ = task.await;
        anyhow::bail!("runtime shutdown exceeded deadline");
    }
    stopped.context("shutdown timeout")??.context("runtime failed")?;
    let (address, mut stalled) = outcome.context("independent AWG exchange deadline exceeded")??;
    ensure!(runtime.telemetry().state == "idle", "runtime did not stop");
    let mut byte = [0];
    let read = timeout(Duration::from_millis(500), stalled.read(&mut byte)).await;
    let closed = match &read {
        Ok(Ok(0)) => true,
        Ok(Err(error)) => error.kind() == std::io::ErrorKind::ConnectionReset,
        _ => false,
    };
    ensure!(closed, "stalled SOCKS greeting must close before run completes, got {read:?}");
    ensure!(runtime.telemetry().active_sessions == 0, "accepted handlers remain after shutdown");
    ensure!(TcpStream::connect(address).await.is_err(), "SOCKS listener leaked after shutdown");
    Ok(())
}
