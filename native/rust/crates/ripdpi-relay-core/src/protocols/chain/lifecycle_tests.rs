use super::*;
use local_network_fixture::{AnyTlsLoopback, AnyTlsLoopbackConfig};
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::oneshot;
use tokio::task::JoinHandle;

/// # Cancel safety:
/// NOT cancel-safe: the test must join the returned observer before it ends.
async fn observe_carrier(port: u16) -> (u16, oneshot::Receiver<()>, JoinHandle<()>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.expect("observer listener");
    let address = listener.local_addr().expect("observer address");
    let (accepted, receipt) = oneshot::channel();
    let task = tokio::spawn(async move {
        let (mut inbound, _) = listener.accept().await.expect("accept carrier");
        let mut peer = TcpStream::connect(("127.0.0.1", port)).await.expect("fixture transport");
        accepted.send(()).expect("carrier accepted");
        let (mut client_read, mut client_write) = tokio::io::split(&mut inbound);
        let (mut peer_read, mut peer_write) = tokio::io::split(&mut peer);
        // Observe client EOF even when the peer is still waiting in its delayed TLS handshake.
        tokio::select! {
            biased;
            result = tokio::io::copy(&mut client_read, &mut peer_write) => { result.expect("carrier EOF"); }
            result = tokio::io::copy(&mut peer_read, &mut client_write) => { panic!("peer ended before client shutdown: {result:?}"); }
        }
    });
    (address.port(), receipt, task)
}

fn factory(hops: Vec<ChainHopConnector>) -> ChainRelaySessionFactory {
    ChainRelaySessionFactory {
        hops,
        outbound_bind_ip: None,
        telemetry: ChainHopTelemetryState::default(),
        vless_session_limiter: ripdpi_vless::VlessRealityCarrierLimiter::default(),
    }
}

/// # Cancel safety:
/// NOT cancel-safe: the test owns its streams and observer joins until shutdown completes.
async fn check_shutdown(resume_cancelled_cleanup: bool) {
    let entry = AnyTlsLoopback::start("fixture-password", AnyTlsLoopbackConfig::default()).await.expect("entry");
    let exit = AnyTlsLoopback::start("fixture-password", AnyTlsLoopbackConfig::default()).await.expect("exit");
    let (entry_port, entry_accepted, entry_closed) = observe_carrier(entry.port()).await;
    let (exit_port, exit_accepted, exit_closed) = observe_carrier(exit.port()).await;
    let factory = factory(vec![
        ChainHopConnector::AnyTls(super::tests::anytls_hop_with(&entry, "fixture-password", entry_port)),
        ChainHopConnector::AnyTls(super::tests::anytls_hop_with(&exit, "fixture-password", exit_port)),
    ]);

    // Exercise ownership at the factory boundary; the fixture echoes payload and does not forward a full chain.
    let entry_target = format!("127.0.0.1:{}", entry.target_port());
    let mut idle = factory.hops[0].connect(None, &entry_target, &factory.vless_session_limiter).await.expect("entry");
    entry_accepted.await.expect("entry socket accepted");
    idle.write_all(b"idle").await.expect("entry write");
    let mut echo = [0; 4];
    idle.read_exact(&mut echo).await.expect("entry echo");
    assert_eq!(&echo, b"idle");
    drop(idle);
    tokio::time::timeout(Duration::from_secs(2), async {
        while entry.observed().fin_stream_ids.is_empty() {
            tokio::task::yield_now().await;
        }
    })
    .await
    .expect("entry logical stream closed");
    assert!(!entry_closed.is_finished(), "the entry carrier is still pooled");

    let transport = TcpStream::connect(("127.0.0.1", exit_port)).await.expect("supplied transport");
    let mut active = factory.hops[1]
        .connect_over(Box::new(transport), &format!("127.0.0.1:{}", exit.target_port()))
        .await
        .expect("supplied exit");
    exit_accepted.await.expect("exit socket accepted");
    active.write_all(b"live").await.expect("exit write");
    active.read_exact(&mut echo).await.expect("exit echo");
    assert_eq!(&echo, b"live");

    if resume_cancelled_cleanup {
        let mut shutdown = Box::pin(factory.shutdown());
        std::future::poll_fn(|cx| {
            assert!(std::future::Future::poll(shutdown.as_mut(), cx).is_pending());
            std::task::Poll::Ready(())
        })
        .await;
        drop(shutdown);
    }
    tokio::time::timeout(Duration::from_secs(2), factory.shutdown()).await.expect("shutdown deadline").expect("join");
    tokio::time::timeout(Duration::from_secs(2), entry_closed).await.expect("entry EOF deadline").expect("entry EOF");
    tokio::time::timeout(Duration::from_secs(2), exit_closed).await.expect("exit EOF deadline").expect("exit EOF");
    factory.shutdown().await.expect("repeated shutdown");
    assert!(factory.hops[0].connect(None, &entry_target, &factory.vless_session_limiter).await.is_err());
    assert!(active.read_exact(&mut echo).await.is_err(), "shutdown closes the active supplied stream");
}

/// # Cancel safety:
/// NOT cancel-safe: check_shutdown retains all test resources until its joins complete.
#[tokio::test]
async fn chain_shutdown_resumes_cancelled_cleanup_and_closes_anytls_carriers() {
    check_shutdown(true).await;
}

/// # Cancel safety:
/// NOT cancel-safe: check_shutdown retains all test resources until its joins complete.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn chain_shutdown_closes_anytls_carriers_on_multi_thread_runtime() {
    check_shutdown(false).await;
}

/// # Cancel safety:
/// NOT cancel-safe: the test retains the observer and factory until both joins finish.
#[tokio::test]
async fn supplied_anytls_stream_drop_does_not_pool_the_previous_hop_transport() {
    let peer = AnyTlsLoopback::start("fixture-password", AnyTlsLoopbackConfig::default()).await.expect("peer");
    let (port, accepted, closed) = observe_carrier(peer.port()).await;
    let connector = ChainHopConnector::AnyTls(super::tests::anytls_hop_with(&peer, "fixture-password", port));
    let factory = factory(vec![connector.clone(), connector.clone()]);
    let transport = TcpStream::connect(("127.0.0.1", port)).await.expect("supplied transport");
    let mut stream = connector
        .connect_over(Box::new(transport), &format!("127.0.0.1:{}", peer.target_port()))
        .await
        .expect("supplied stream");
    accepted.await.expect("accepted carrier");
    stream.write_all(b"drop").await.expect("write");
    let mut echo = [0; 4];
    stream.read_exact(&mut echo).await.expect("echo");
    assert_eq!(&echo, b"drop");
    drop(stream);
    tokio::time::timeout(Duration::from_secs(2), closed)
        .await
        .expect("the supplied carrier must close without factory shutdown")
        .expect("carrier EOF");
    factory.shutdown().await.expect("join remaining workers");
}

/// # Cancel safety:
/// NOT cancel-safe: the test retains the observer and factory until both joins finish.
#[tokio::test]
async fn supplied_anytls_rejection_does_not_pool_the_previous_hop_transport() {
    let peer = AnyTlsLoopback::start(
        "fixture-password",
        AnyTlsLoopbackConfig {
            reject_next_synack: Some("supplied destination refused".to_owned()),
            ..AnyTlsLoopbackConfig::default()
        },
    )
    .await
    .expect("rejecting peer");
    let (port, accepted, closed) = observe_carrier(peer.port()).await;
    let connector = ChainHopConnector::AnyTls(super::tests::anytls_hop_with(&peer, "fixture-password", port));
    let factory = factory(vec![connector.clone(), connector.clone()]);
    let transport = TcpStream::connect(("127.0.0.1", port)).await.expect("supplied transport");
    let rejected = connector.connect_over(Box::new(transport), "127.0.0.1:9").await;
    let error = rejected.err().expect("SYNACK destination rejection");
    assert!(error.to_string().contains("supplied destination refused"), "{error}");
    accepted.await.expect("accepted carrier");
    tokio::time::timeout(Duration::from_secs(2), closed)
        .await
        .expect("the rejected supplied carrier must close without factory shutdown")
        .expect("carrier EOF");
    factory.shutdown().await.expect("join remaining workers");
}

/// # Cancel safety:
/// NOT cancel-safe: the test joins each open and observer after factory shutdown.
#[tokio::test]
async fn chain_shutdown_cancels_direct_and_supplied_anytls_handshakes() {
    for supplied in [false, true] {
        let peer = AnyTlsLoopback::start(
            "fixture-password",
            AnyTlsLoopbackConfig { tls_handshake_delay: Duration::from_secs(10), ..AnyTlsLoopbackConfig::default() },
        )
        .await
        .expect("delayed peer");
        let (port, accepted, closed) = observe_carrier(peer.port()).await;
        let connector = ChainHopConnector::AnyTls(super::tests::anytls_hop_with(&peer, "fixture-password", port));
        let factory = factory(vec![connector.clone(), connector.clone()]);
        let open = tokio::spawn(async move {
            if supplied {
                let transport = TcpStream::connect(("127.0.0.1", port)).await.expect("supplied transport");
                connector.connect_over(Box::new(transport), "127.0.0.1:9").await
            } else {
                connector.connect(None, "127.0.0.1:9", &ripdpi_vless::VlessRealityCarrierLimiter::default()).await
            }
        });
        accepted.await.expect("handshake has a real carrier");
        tokio::time::timeout(Duration::from_secs(2), factory.shutdown())
            .await
            .expect("shutdown stalled handshake")
            .expect("shutdown");
        assert!(
            tokio::time::timeout(Duration::from_secs(2), open)
                .await
                .expect("open deadline")
                .expect("open task")
                .is_err()
        );
        tokio::time::timeout(Duration::from_secs(2), closed).await.expect("carrier EOF deadline").expect("carrier EOF");
        factory.shutdown().await.expect("repeat shutdown");
    }
}
