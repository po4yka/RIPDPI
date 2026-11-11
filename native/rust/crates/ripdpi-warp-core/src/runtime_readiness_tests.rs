use super::*;
use base64::{Engine as _, engine::general_purpose::STANDARD};
use boringtun::noise::{Tunn, TunnResult};
use boringtun::x25519::{PublicKey, StaticSecret};
use tokio::net::UdpSocket;
use tokio::sync::Notify;

fn config_for_peer(endpoint: std::net::SocketAddr, peer_public_key: PublicKey) -> ResolvedWarpRuntimeConfig {
    let private_key = StaticSecret::from([7; 32]);
    let mut config = super::tests::dummy_config();
    config.enabled = true;
    config.private_key = STANDARD.encode(private_key.to_bytes());
    config.public_key = STANDARD.encode(PublicKey::from(&private_key).as_bytes());
    config.peer_public_key = STANDARD.encode(peer_public_key.as_bytes());
    config.endpoint.host = endpoint.ip().to_string();
    config.endpoint.ipv4 = Some(endpoint.ip().to_string());
    config.endpoint.ipv6 = None;
    config.endpoint.port = i32::from(endpoint.port());
    config.local_socks_port = 0;
    config
}

#[test]
fn poisoned_snapshots_remain_readable_without_panicking() {
    let runtime = WarpRuntime::new(super::tests::dummy_config());
    let owner = Arc::clone(&runtime);
    let panicked = std::thread::spawn(move || {
        let mut listener = owner.listener_address.lock().expect("listener snapshot");
        let mut error = owner.last_error.lock().expect("error snapshot");
        *listener = Some("127.0.0.1:11888".into());
        *error = Some("test writer stopped".into());
        panic!("poison independent snapshots");
    })
    .join();
    assert!(panicked.is_err());
    let telemetry = runtime.telemetry();
    assert_eq!(telemetry.listener_address.as_deref(), Some("127.0.0.1:11888"));
    assert_eq!(telemetry.last_error.as_deref(), Some("test writer stopped"));
    assert_eq!(telemetry.health, "failed");
}

/// # Cancel safety:
/// cancel-safe: the helper owns its backend and borrows only the caller's socket.
async fn authenticate_peer(socket: &UdpSocket) -> anyhow::Result<()> {
    let mut peer =
        Tunn::new(StaticSecret::from([9; 32]), PublicKey::from(&StaticSecret::from([7; 32])), None, None, 1, None);
    let mut packet = [0; 2048];
    let mut output = [0; 2048];
    let (size, client) = timeout(Duration::from_secs(2), socket.recv_from(&mut packet)).await??;
    let TunnResult::WriteToNetwork(response) = peer.decapsulate(None, &packet[..size], &mut output) else {
        anyhow::bail!("the configured peer did not authenticate the initiation");
    };
    socket.send_to(response, client).await?;
    let (size, source) = timeout(Duration::from_secs(2), socket.recv_from(&mut packet)).await??;
    anyhow::ensure!(source == client && packet[..4] == [4, 0, 0, 0], "missing key confirmation");
    anyhow::ensure!(matches!(peer.decapsulate(None, &packet[..size], &mut output), TunnResult::Done));
    anyhow::ensure!(peer.time_since_last_handshake().is_some(), "peer authentication was not confirmed");
    Ok(())
}

/// # Cancel safety:
/// NOT cancel-safe: the caller retains this cleanup future until the worker is joined.
async fn stop_and_join(runtime: &WarpRuntime, mut worker: JoinHandle<io::Result<()>>) -> io::Result<()> {
    runtime.stop();
    match timeout(Duration::from_secs(2), &mut worker).await {
        Ok(result) => result.map_err(io::Error::other)?,
        Err(_) => {
            worker.abort();
            let _ = worker.await;
            Err(io::Error::new(io::ErrorKind::TimedOut, "runtime stop timed out"))
        }
    }
}

/// # Cancel safety:
/// NOT cancel-safe: the test owns the runtime worker and stops and joins it before asserting.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn silent_peer_cannot_publish_readiness() {
    let silent_peer = UdpSocket::bind("127.0.0.1:0").await.expect("silent peer");
    let config =
        config_for_peer(silent_peer.local_addr().expect("peer address"), PublicKey::from(&StaticSecret::from([9; 32])));
    let runtime = WarpRuntime::new(config);
    let ready = Arc::new(AtomicBool::new(false));
    let observed = Arc::clone(&ready);
    // The atomic records only a callback; it publishes no other data.
    runtime.set_readiness_observer(Arc::new(move || observed.store(true, Ordering::Relaxed)));
    let worker = tokio::spawn(Arc::clone(&runtime).run());
    tokio::time::sleep(Duration::from_millis(350)).await;
    let published = ready.load(Ordering::Relaxed);
    stop_and_join(&runtime, worker).await.expect("stop");
    assert!(!published, "a bound socket cannot prove that the peer authenticated");
    assert_eq!(runtime.telemetry().state, "idle");
}

/// # Cancel safety:
/// NOT cancel-safe: the test owns a runtime worker and joins it on every outcome.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn silent_peer_fails_with_bounded_handshake_error() {
    let peer = UdpSocket::bind("127.0.0.1:0").await.expect("silent peer");
    let config =
        config_for_peer(peer.local_addr().expect("peer address"), PublicKey::from(&StaticSecret::from([9; 32])));
    let runtime = WarpRuntime::new(config);
    let mut worker = tokio::spawn(Arc::clone(&runtime).run());
    let result = timeout(Duration::from_secs(3), &mut worker).await;
    if result.is_err() {
        stop_and_join(&runtime, worker).await.expect("stop timed-out worker");
        panic!("a silent peer must fail within the handshake deadline");
    }
    let error = result.expect("deadline").expect("runtime worker").expect_err("handshake failure");
    assert_eq!(error.kind(), io::ErrorKind::TimedOut);
    let telemetry = runtime.telemetry();
    assert_eq!(telemetry.health, "failed");
    assert!(telemetry.listener_address.is_none());
    assert!(telemetry.last_error.as_deref().is_some_and(|error| error.contains("handshake timed out")));
}

/// # Cancel safety:
/// NOT cancel-safe: the test retains its runtime worker through bounded cleanup.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn authenticated_peer_publishes_bound_listener_and_stops() {
    let peer = UdpSocket::bind("127.0.0.1:0").await.expect("peer");
    let config =
        config_for_peer(peer.local_addr().expect("peer address"), PublicKey::from(&StaticSecret::from([9; 32])));
    let runtime = WarpRuntime::new(config);
    let ready = Arc::new(Notify::new());
    let signal = Arc::clone(&ready);
    runtime.set_readiness_observer(Arc::new(move || signal.notify_one()));
    let worker = tokio::spawn(Arc::clone(&runtime).run());
    let exchange = authenticate_peer(&peer).await;
    let readiness = timeout(Duration::from_secs(2), ready.notified()).await;
    let telemetry = runtime.telemetry();
    let stopped = stop_and_join(&runtime, worker).await;
    exchange.expect("authenticated exchange");
    readiness.expect("authenticated readiness");
    assert_eq!(telemetry.state, "running");
    let listener: std::net::SocketAddr = telemetry.listener_address.expect("listener").parse().expect("address");
    assert!(listener.ip().is_loopback());
    assert_ne!(listener.port(), 0);
    stopped.expect("stop");
    assert!(runtime.telemetry().listener_address.is_none());
}

/// # Cancel safety:
/// NOT cancel-safe: the test owns the runtime worker and joins it before assertions.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn unauthenticated_response_cannot_publish_readiness() {
    let peer = UdpSocket::bind("127.0.0.1:0").await.expect("peer");
    let config =
        config_for_peer(peer.local_addr().expect("peer address"), PublicKey::from(&StaticSecret::from([9; 32])));
    let runtime = WarpRuntime::new(config);
    let ready = Arc::new(Notify::new());
    let signal = Arc::clone(&ready);
    runtime.set_readiness_observer(Arc::new(move || signal.notify_one()));
    let mut worker = tokio::spawn(Arc::clone(&runtime).run());
    let mut packet = [0; 2048];
    let received = timeout(Duration::from_secs(2), peer.recv_from(&mut packet)).await;
    if let Ok(Ok((_, client))) = received {
        // A type-2 frame with no valid peer MAC or encrypted handshake material.
        let mut invalid_response = [0; 92];
        invalid_response[0] = 2;
        peer.send_to(&invalid_response, client).await.expect("unauthenticated reply");
    }
    let result = timeout(Duration::from_secs(3), &mut worker).await;
    if result.is_err() {
        stop_and_join(&runtime, worker).await.expect("stop timed-out worker");
        panic!("an unauthenticated reply must not keep startup pending");
    }
    assert!(received.is_ok(), "runtime did not initiate a handshake");
    let error = result.expect("deadline").expect("worker").expect_err("unauthenticated peer");
    assert_eq!(error.kind(), io::ErrorKind::TimedOut);
    assert!(timeout(Duration::from_millis(20), ready.notified()).await.is_err());
    assert_eq!(runtime.telemetry().health, "failed");
}

/// # Cancel safety:
/// NOT cancel-safe: the test owns its runtime worker through failure or bounded cleanup.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn peer_expiry_fails_a_previously_ready_runtime() {
    let peer = UdpSocket::bind("127.0.0.1:0").await.expect("peer");
    let mut config =
        config_for_peer(peer.local_addr().expect("peer address"), PublicKey::from(&StaticSecret::from([9; 32])));
    config.amnezia.enabled = true;
    // With no further traffic the backend clears keys at 3 * RejectAfterTime.
    // A three-second expiry leaves room for its timer tick and runtime polling.
    config.amnezia.v3.reject_after_time = "1".into();
    let runtime = WarpRuntime::new(config);
    let ready = Arc::new(Notify::new());
    let signal = Arc::clone(&ready);
    runtime.set_readiness_observer(Arc::new(move || signal.notify_one()));
    let mut worker = tokio::spawn(Arc::clone(&runtime).run());
    let exchange = authenticate_peer(&peer).await;
    let readiness = timeout(Duration::from_secs(2), ready.notified()).await;
    if exchange.is_err() || readiness.is_err() {
        let _ = stop_and_join(&runtime, worker).await;
        exchange.expect("authenticated exchange");
        readiness.expect("initial readiness");
        return;
    }
    // The peer remains bound but sends no further authenticated traffic.
    let result = timeout(Duration::from_secs(6), &mut worker).await;
    if result.is_err() {
        stop_and_join(&runtime, worker).await.expect("stop expired worker");
        panic!("expired peer state must terminate the ready runtime");
    }
    let error = result.expect("deadline").expect("worker").expect_err("peer expiry");
    assert_eq!(error.kind(), io::ErrorKind::ConnectionAborted);
    assert_eq!(runtime.telemetry().health, "failed");
    assert!(runtime.telemetry().last_error.as_deref().is_some_and(|error| error.contains("session expired")));
}
