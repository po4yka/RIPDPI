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
/// cancel-safe: cancellation drops the owned socket and its partial SOCKS exchange.
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
/// cancel-safe: all client sockets are owned here and dropped on timeout; the owner stops the runtime.
async fn exchange(runtime: &AmneziaWgRuntime, ready: &Notify) -> Result<Option<(SocketAddr, TcpStream)>> {
    if std::env::var("RIPDPI_AWG_SCENARIO").unwrap_or_default() == "timer-retry-exhaust" {
        ensure!(
            timeout(Duration::from_millis(7500), ready.notified()).await.is_err(),
            "lost responses must not establish readiness"
        );
        ensure!(runtime.telemetry().state != "running", "exhausted handshake must stay unready");
        return Ok(None);
    }
    ready.notified().await;
    let telemetry = runtime.telemetry();
    ensure!(telemetry.state == "running", "runtime not ready after authenticated handshake");
    let address: SocketAddr = telemetry.listener_address.context("missing SOCKS listener")?.parse()?;
    ensure!(
        address.ip().is_loopback() && address.port() != 0,
        "runtime must publish actual bound SOCKS port: {address}"
    );
    match std::env::var("RIPDPI_AWG_SCENARIO").unwrap_or_else(|_| "payload".into()).as_str() {
        "small-datagrams" | "timer-retry-loss" => exchange_small_datagrams(address).await?,
        "cookies-enabled" | "cookies-disabled" | "cookies-response-enabled" | "cookies-response-disabled" => {
            exchange_cookies(address).await?;
        }
        "negative-transport" => exchange_negative_transport(address).await?,
        "timer-persistent" => tokio::time::sleep(Duration::from_millis(6200)).await,
        "timer-idle" => {
            exchange_small_datagrams(address).await?;
            tokio::time::sleep(Duration::from_millis(3700)).await;
        }
        "timer-reject" => {
            exchange_small_datagrams(address).await?;
            exchange_expiry(address).await?;
        }
        "timer-rekey" => {
            exchange_small_datagrams(address).await?;
            tokio::time::sleep(Duration::from_millis(2500)).await;
            exchange_small_datagrams(address).await?;
            tokio::time::sleep(Duration::from_millis(300)).await;
            exchange_small_datagrams(address).await?;
        }
        "payload" => exchange_payload(address).await?,
        other => anyhow::bail!("unknown AWG scenario {other}"),
    }
    let accepted = runtime.telemetry().total_sessions;
    let mut stalled = TcpStream::connect(address).await?;
    stalled.write_all(&[5]).await?;
    while runtime.telemetry().total_sessions == accepted {
        tokio::task::yield_now().await;
    }
    Ok(Some((address, stalled)))
}

/// # Cancel safety
/// cancel-safe: each owned socket and partial exchange is dropped on cancellation.
async fn exchange_payload(address: SocketAddr) -> Result<()> {
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
    Ok(())
}

/// # Cancel safety
/// cancel-safe: local association and UDP sockets are dropped on cancellation.
async fn exchange_small_datagrams(address: SocketAddr) -> Result<()> {
    let (_control, relay) = socks_request(address, 3, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
    ensure!(relay.ip().is_loopback() && relay.port() != 0, "unsafe UDP association listener");
    let udp = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await?;
    let frames = [udp_frame(41002, b"awg-padding-check"), udp_frame(41003, b"awg-padding-check")];
    for frame in &frames {
        udp.send_to(frame, relay).await?;
    }
    let mut replies = Vec::new();
    for _ in 0..2 {
        let mut bytes = [0; 2048];
        let (size, source) = udp.recv_from(&mut bytes).await?;
        ensure!(source == relay, "unexpected padding-probe response source");
        replies.push(bytes[..size].to_vec());
    }
    ensure!(frames.iter().all(|frame| replies.contains(frame)), "small payload/source differs");
    Ok(())
}

/// # Cancel safety
/// cancel-safe: recv_from cancellation consumes no datagram. The inner timeout retains both
/// sockets; outer cancellation drops them.
async fn exchange_expiry(address: SocketAddr) -> Result<()> {
    let (_control, relay) = socks_request(address, 3, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
    ensure!(relay.ip().is_loopback() && relay.port() != 0, "unsafe expiry-probe relay");
    let udp = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await?;
    tokio::time::sleep(Duration::from_millis(4500)).await;
    for (phase, payload) in [b"awg-expired-probe", b"awg-recover-probe"].into_iter().enumerate() {
        let frames = [udp_frame(41002, payload), udp_frame(41003, payload)];
        for frame in &frames {
            udp.send_to(frame, relay).await?;
        }
        let mut bytes = [0; 2048];
        if phase == 0 {
            let started = tokio::time::Instant::now();
            ensure!(
                timeout(Duration::from_secs(1), udp.recv_from(&mut bytes)).await.is_err(),
                "expired key delivered data while fresh responses were dropped"
            );
            println!("AWG_EXPIRED_PROBE {{\"no_reply\":true,\"wait_ms\":{}}}", started.elapsed().as_millis());
            tokio::time::sleep(Duration::from_secs(3)).await;
        }
        let mut replies = Vec::new();
        for _ in 0..2 {
            let (size, source) = udp.recv_from(&mut bytes).await?;
            ensure!(source == relay, "unexpected expiry-probe response source");
            replies.push(bytes[..size].to_vec());
        }
        ensure!(frames.iter().all(|frame| replies.contains(frame)), "expiry recovery payload/source differs");
    }
    Ok(())
}

/// # Cancel safety
/// cancel-safe: the owned association and UDP socket drop on outer cancellation.
async fn exchange_cookies(address: SocketAddr) -> Result<()> {
    let (_control, relay) = socks_request(address, 3, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
    ensure!(relay.ip().is_loopback() && relay.port() != 0, "unsafe cookie-probe relay");
    let udp = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await?;
    udp.send_to(&udp_frame(41002, b"ARM:cookie"), relay).await?;
    let scenario = std::env::var("RIPDPI_AWG_SCENARIO").unwrap_or_default();
    let disabled = scenario.ends_with("-disabled");
    let phases: &[&str] = if scenario.starts_with("cookies-response-") && disabled {
        &["invalid-mac1", "admission", "challenge-budget", "recovery"]
    } else if scenario.starts_with("cookies-response-") {
        &["invalid-mac1", "admission", "bad-mac2", "valid-mac2", "challenge-budget", "recovery"]
    } else if disabled {
        &["invalid-mac1", "admission", "exhausted-noise", "challenge-budget", "recovery"]
    } else {
        &["invalid-mac1", "admission", "bad-mac2", "valid-mac2", "challenge-budget", "recovery"]
    };
    for phase in phases {
        let mut bytes = [0; 512];
        let (size, source) = udp.recv_from(&mut bytes).await?;
        ensure!(
            source == relay && bytes[..size] == udp_frame(41002, format!("FENCE:{phase}").as_bytes()),
            "cookie fence source, phase or payload differs"
        );
        udp.send_to(&udp_frame(41002, format!("ACK:{phase}").as_bytes()), relay).await?;
    }
    let mut bytes = [0; 512];
    let (size, source) = udp.recv_from(&mut bytes).await?;
    ensure!(source == relay && bytes[..size] == udp_frame(41002, b"DONE:cookie"), "cookie recovery completion differs");
    Ok(())
}

/// # Cancel safety
/// cancel-safe: owned association and socket drop on timeout; no datagram is consumed by cancellation.
async fn exchange_negative_transport(address: SocketAddr) -> Result<()> {
    let (_control, relay) = socks_request(address, 3, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
    ensure!(relay.ip().is_loopback() && relay.port() != 0, "unsafe negative-probe relay");
    let udp = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await?;
    udp.send_to(&udp_frame(41002, b"ARM:transport"), relay).await?;
    for mode in ["corrupt", "wrong-hp", "foreign-source", "replay", "ambiguous", "malformed-ip"] {
        let mut payload = [b'x'; 256];
        let marker = format!("DATA:{mode}:");
        payload[..marker.len()].copy_from_slice(marker.as_bytes());
        if mode == "replay" || mode == "ambiguous" {
            receive_probe(&udp, relay, &payload).await?;
        }
        receive_probe(&udp, relay, format!("FENCE:{mode}").as_bytes()).await?;
        udp.send_to(&udp_frame(41002, format!("ACK:{mode}").as_bytes()), relay).await?;
        if matches!(mode, "corrupt" | "wrong-hp" | "foreign-source") {
            receive_probe(&udp, relay, &payload).await?;
            udp.send_to(&udp_frame(41002, format!("CONFIRMED:{mode}").as_bytes()), relay).await?;
        }
    }
    receive_probe(&udp, relay, b"DONE:transport").await
}

/// # Cancel safety
/// cancel-safe: recv_from cancellation consumes no datagram; the caller owns the socket.
async fn receive_probe(udp: &UdpSocket, relay: SocketAddr, payload: &[u8]) -> Result<()> {
    let mut bytes = [0; 512];
    let (size, source) = udp.recv_from(&mut bytes).await?;
    ensure!(
        source == relay && bytes[..size] == udp_frame(41002, payload),
        "negative probe delivered a rejected/duplicate packet or lost valid payload"
    );
    Ok(())
}

/// # Cancel safety
/// NOT cancel-safe: this test owns a runtime task and explicitly stops, aborts if needed, and joins it.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn standalone_profile_exchanges_tcp_and_udp_with_independent_awg_peer() -> Result<()> {
    let endpoint: SocketAddr = std::env::var("RIPDPI_AWG_INTEROP_ENDPOINT")
        .context("run this feature through scripts/tests/run-standalone-awg-interop.py")?
        .parse()?;
    ensure!(endpoint.is_ipv4() && endpoint.ip().is_loopback() && endpoint.port() != 0, "peer must be IPv4 loopback");
    let mut config = AmneziaWgProfileConfig {
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
        mtu: std::env::var("RIPDPI_AWG_INTEROP_MTU")?.parse()?,
        local_socks_host: "127.0.0.1".into(),
        local_socks_port: 0,
        amnezia: profile_obfuscation(&std::env::var("RIPDPI_AWG_PROFILE").unwrap_or_default())?,
        ..Default::default()
    };
    match std::env::var("RIPDPI_AWG_SCENARIO").unwrap_or_default().as_str() {
        "cookies-disabled" | "cookies-response-disabled" => config.amnezia.v3.disable_cookies = true,
        "timer-persistent" => {
            config.persistent_keepalive = 1;
            config.persistent_keepalive_max = 2;
        }
        "timer-idle" => config.amnezia.v3.keepalive_timeout = "1".into(),
        "timer-retry-loss" => config.amnezia.v3.rekey_timeout = "1".into(),
        "timer-retry-exhaust" => {
            config.amnezia.v3.rekey_timeout = "1".into();
            config.amnezia.v3.max_handshake_attempts = "1".into();
        }
        "timer-reject" => {
            config.amnezia.v3.reject_after_time = "3-4".into();
            config.amnezia.v3.rekey_timeout = "1".into();
            config.amnezia.v3.keepalive_timeout = "1".into();
        }
        "timer-rekey" => {
            config.amnezia.v3.rekey_after_time = "2".into();
            config.amnezia.v3.rekey_timeout = "1".into();
        }
        _ => {}
    }
    let runtime = AmneziaWgRuntime::new(config);
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
    let Some((address, mut stalled)) = outcome.context("independent AWG exchange deadline exceeded")?? else {
        ensure!(runtime.telemetry().state == "idle", "exhausted runtime did not stop");
        return Ok(());
    };
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

fn profile_obfuscation(name: &str) -> Result<AmneziaWgObfuscation> {
    let mut profile = AmneziaWgObfuscation {
        jc: 4,
        jmin: 64,
        jmax: 96,
        s1: 8,
        s2: 12,
        h1: "100-199".into(),
        h2: "200-299".into(),
        h3: "300-399".into(),
        h4: "400-499".into(),
        i1: "<b 0xf0f1><r 16><rd 8><rc 8><t>".into(),
        i2: "<b 0xf2>".into(),
        i3: "<b 0xf3>".into(),
        i4: "<b 0xf4>".into(),
        i5: "<b 0xf5>".into(),
        ..Default::default()
    };
    if name.is_empty() || name == "awg-3.1-ranges-v1" {
        return Ok(profile);
    }
    ensure!(
        ["amneziavpn-5x", "v3-cpa", "v3-trailers", "v3-cpa-trailers", "v3-aligned", "v3-fixed-headers"].contains(&name),
        "unknown AWG profile {name}"
    );
    profile.s1 = 12;
    profile.s2 = 20;
    profile.s3 = 16;
    profile.s4 = 24;
    if name != "v3-aligned" {
        profile.v3.header_protection_key.0 = STANDARD.encode([3; 32]);
    }
    if name != "v3-aligned" && name != "v3-trailers" {
        profile.v3.content_padding_addition = "16".into();
    }
    profile.v3.random_trailers =
        matches!(name, "v3-trailers" | "v3-cpa-trailers" | "v3-fixed-headers" | "amneziavpn-5x");
    if name == "v3-fixed-headers" {
        profile.h1 = "101".into();
        profile.h2 = "202".into();
        profile.h3 = "303".into();
        profile.h4 = "404".into();
    }
    if name == "amneziavpn-5x" {
        profile.s2 = 12;
        profile.s3 = 12;
        profile.s4 = 12;
        profile.v3.content_padding_addition = "10-100".into();
        profile.v3.rekey_after_time = "100-120".into();
        profile.v3.rekey_timeout = "3-7".into();
        profile.v3.reject_after_time = "150-180".into();
        profile.v3.keepalive_timeout = "5-15".into();
        profile.v3.max_handshake_attempts = "15-20".into();
        profile.v3.disable_cookies = true;
    }
    Ok(profile)
}
