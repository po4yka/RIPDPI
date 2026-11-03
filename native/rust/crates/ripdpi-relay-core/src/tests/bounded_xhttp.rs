use super::*;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};

use crate::protocols::{XhttpSessionFactory, XhttpSessionMode};
use ripdpi_relay_mux::{RelaySession, RelaySessionFactory};

/// # Cancel safety
/// cancel-safe: this test does not open sockets.
#[tokio::test]
async fn xhttp_pool_admission_rejects_invalid_limit_and_mode_before_dial() {
    for (mode, limit) in [("stream-up", 0), ("stream-up", 5), ("auto", 2), ("stream-one", 2)] {
        let mut config = sample_config("vless");
        let vless = plain_vless_config_mut(&mut config);
        vless.vless_flow = String::new();
        vless.xhttp_mode = mode.to_owned();
        vless.xhttp_max_connections = Some(limit);
        let error = build_backend(&config).await.err().expect("unsupported pool must fail");
        assert_eq!(io::ErrorKind::InvalidInput, error.kind());
    }
}

struct ActiveCarrier(Arc<AtomicUsize>);

impl Drop for ActiveCarrier {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::SeqCst);
    }
}

/// # Cancel safety
/// NOT cancel-safe: the test owns and discards loopback forwarding tasks and all streams.
#[tokio::test]
async fn bounded_xhttp_pool_is_shared_across_sessions_and_closes_on_shutdown() {
    let server = XhttpRealityLoopback::start().await.expect("start xHTTP fixture");
    let listener = tokio::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("bind counter proxy");
    let port = listener.local_addr().expect("counter address").port();
    let accepted = Arc::new(AtomicUsize::new(0));
    let active = Arc::new(AtomicUsize::new(0));
    let proxy_accepted = accepted.clone();
    let proxy_active = active.clone();
    let server_port = server.port();
    let proxy = tokio::spawn(async move {
        while let Ok((mut incoming, _)) = listener.accept().await {
            proxy_accepted.fetch_add(1, Ordering::SeqCst);
            proxy_active.fetch_add(1, Ordering::SeqCst);
            let active = proxy_active.clone();
            tokio::spawn(async move {
                let _active = ActiveCarrier(active);
                let mut upstream = tokio::net::TcpStream::connect((Ipv4Addr::LOCALHOST, server_port))
                    .await
                    .expect("forward to fixture");
                let _ = tokio::io::copy_bidirectional(&mut incoming, &mut upstream).await;
            });
        }
    });
    let factory = reality_factory(&server, port, 2, 1);
    let target = format!("127.0.0.1:{}", server.target_port());
    let first = factory.create_session().await.expect("first session");
    let second = factory.create_session().await.expect("replacement session");
    let third = factory.create_session().await.expect("third session");
    let mut first_stream = first.open_stream(&target).await.expect("first stream");
    let mut second_stream = second.open_stream(&target).await.expect("second stream");
    for stream in [&mut first_stream, &mut second_stream] {
        stream.write_all(b"bounded transfer").await.expect("write payload");
        let mut echoed = [0; 16];
        stream.read_exact(&mut echoed).await.expect("read payload");
        assert_eq!(&echoed, b"bounded transfer");
    }
    assert!(
        tokio::time::timeout(Duration::from_millis(100), third.open_stream(&target)).await.is_err(),
        "a new outer session must wait for capacity in the same pool"
    );
    assert_eq!(2, accepted.load(Ordering::SeqCst), "all outer sessions share the two carrier bound");
    drop(first_stream);
    let mut third_stream = tokio::time::timeout(Duration::from_secs(2), third.open_stream(&target))
        .await
        .expect("released permit")
        .expect("reuse bounded carrier");
    third_stream.write_all(b"reused").await.expect("write reused payload");
    let mut echoed = [0; 6];
    third_stream.read_exact(&mut echoed).await.expect("read reused payload");
    assert_eq!(&echoed, b"reused");
    assert_eq!(2, accepted.load(Ordering::SeqCst));
    drop((second_stream, third_stream, first, second, third));
    factory.shutdown().await.expect("shutdown pool owner");
    assert!(factory.create_session().await.is_err(), "shutdown closes admission");
    tokio::time::timeout(Duration::from_secs(2), async {
        while active.load(Ordering::SeqCst) != 0 {
            tokio::task::yield_now().await;
        }
    })
    .await
    .expect("shutdown closes both carriers");
    proxy.abort();
}

fn reality_factory(server: &XhttpRealityLoopback, port: u16, limit: usize, streams: usize) -> XhttpSessionFactory {
    let vless = ripdpi_vless::config::VlessRealityConfig::from_strings(
        "127.0.0.1",
        i32::from(port),
        "11111111-1111-1111-1111-111111111111",
        server.server_name(),
        &valid_reality_public_key(),
        "",
        "chrome_stable",
    )
    .expect("Reality config")
    .with_flow_str("")
    .expect("flowless XHTTP");
    XhttpSessionFactory::new(
        XhttpSessionMode::Reality(ripdpi_xhttp::XhttpRealityConfig {
            vless,
            path: "/tunnel".to_owned(),
            host: None,
            bind_ip: None,
            socket_protector: None,
            xmux: ripdpi_xhttp::XmuxConfig { max_connections: limit, max_concurrent_streams: streams },
            finalmask: ripdpi_xhttp::FinalmaskConfig::default(),
            protocol_mode: ripdpi_xhttp::XhttpProtocolMode::StreamUp,
        }),
        false,
    )
}

/// # Cancel safety
/// NOT cancel-safe: the test owns and discards all loopback sockets and forwarding tasks.
#[tokio::test]
async fn bounded_xhttp_pool_replaces_a_cancelled_blackhole_before_reusing_capacity() {
    let server = XhttpRealityLoopback::start().await.expect("start xHTTP fixture");
    let listener = tokio::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("bind proxy");
    let port = listener.local_addr().expect("proxy address").port();
    let upstream_port = server.port();
    let (stall_tx, stall_rx) = tokio::sync::oneshot::channel();
    let (stalled_tx, stalled_rx) = tokio::sync::oneshot::channel();
    let (old_closed_tx, old_closed_rx) = tokio::sync::oneshot::channel();
    let proxy = tokio::spawn(async move {
        let (mut first, _) = listener.accept().await.expect("first carrier");
        let mut upstream =
            tokio::net::TcpStream::connect((Ipv4Addr::LOCALHOST, upstream_port)).await.expect("upstream");
        {
            tokio::select! {
                _ = tokio::io::copy_bidirectional(&mut first, &mut upstream) => panic!("first carrier closed before stall"),
                _ = stall_rx => {}
            }
        }
        stalled_tx.send(()).expect("stall acknowledged");
        let blackhole = tokio::spawn(async move {
            let _keep_upstream = upstream;
            let mut bytes = [0_u8; 4096];
            while first.read(&mut bytes).await.expect("blackhole reads") != 0 {}
            let _ = old_closed_tx.send(());
        });
        let (mut replacement, _) = listener.accept().await.expect("replacement carrier");
        let mut upstream =
            tokio::net::TcpStream::connect((Ipv4Addr::LOCALHOST, upstream_port)).await.expect("new upstream");
        let _ = tokio::io::copy_bidirectional(&mut replacement, &mut upstream).await;
        let _ = blackhole.await;
    });
    let factory = reality_factory(&server, port, 1, 4);
    let target = format!("127.0.0.1:{}", server.target_port());
    let session = factory.create_session().await.expect("session");
    let mut stream = session.open_stream(&target).await.expect("initial stream");
    stream.write_all(b"before").await.expect("initial upload");
    let mut echoed = [0; 6];
    stream.read_exact(&mut echoed).await.expect("initial download");
    assert_eq!(&echoed, b"before");
    drop(stream);
    stall_tx.send(()).expect("stop forwarding without closing TCP");
    stalled_rx.await.expect("proxy is blackholing");
    assert!(tokio::time::timeout(Duration::from_millis(100), session.open_stream(&target)).await.is_err());
    let replacement = factory.create_session().await.expect("replacement session");
    let mut recovered = tokio::time::timeout(Duration::from_secs(2), replacement.open_stream(&target))
        .await
        .expect("fresh carrier deadline")
        .expect("fresh carrier");
    recovered.write_all(b"after").await.expect("recovery upload");
    let mut echoed = [0; 5];
    recovered.read_exact(&mut echoed).await.expect("recovery download");
    assert_eq!(&echoed, b"after");
    tokio::time::timeout(Duration::from_secs(2), old_closed_rx)
        .await
        .expect("old carrier closed")
        .expect("closure notice");
    drop((recovered, replacement, session));
    factory.shutdown().await.expect("shutdown");
    tokio::time::timeout(Duration::from_secs(2), proxy).await.expect("proxy drained").expect("proxy task");
}
