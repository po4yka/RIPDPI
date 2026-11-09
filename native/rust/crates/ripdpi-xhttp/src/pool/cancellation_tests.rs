use std::convert::Infallible;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;

use bytes::Bytes;
use http_body_util::BodyExt;
use hyper::service::service_fn;
use hyper_util::rt::{TokioExecutor, TokioIo};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::{Notify, mpsc};

use crate::client::XhttpClient;
use crate::config::{XhttpProtocolMode, XhttpTlsConfig};
use crate::h2_body::ChannelBody;

/// # Cancel safety:
/// NOT cancel-safe: the test owns its local server and joins it after the exchange.
#[tokio::test]
async fn cancelling_an_open_keeps_active_siblings_and_pool_capacity() {
    let (client_io, server_io) = tokio::io::duplex(64 * 1024);
    let pending_seen = Arc::new(Notify::new());
    let server_seen = pending_seen.clone();
    let requests = Arc::new(AtomicUsize::new(0));
    let server_requests = requests.clone();
    let server = tokio::spawn(async move {
        let service = service_fn(move |request: hyper::Request<hyper::body::Incoming>| {
            // This counter selects a request; the H2 exchange publishes its payload.
            let index = server_requests.fetch_add(1, Ordering::Relaxed);
            let seen = server_seen.clone();
            async move {
                if index == 1 {
                    seen.notify_one();
                    std::future::pending::<()>().await;
                }
                let mut body = request.into_body();
                body.frame().await.expect("VLESS request frame").expect("VLESS request body");
                let (outgoing, incoming) = mpsc::channel(8);
                outgoing.send(Ok(Bytes::from_static(&[0, 0]))).await.expect("VLESS response header");
                tokio::spawn(async move {
                    while let Some(Ok(frame)) = body.frame().await {
                        if let Ok(data) = frame.into_data()
                            && outgoing.send(Ok(data)).await.is_err()
                        {
                            break;
                        }
                    }
                });
                Ok::<_, Infallible>(hyper::Response::new(ChannelBody::new(incoming)))
            }
        });
        let _ = hyper::server::conn::http2::Builder::new(TokioExecutor::new())
            .serve_connection(TokioIo::new(server_io), service)
            .await;
    });
    let mut config = XhttpTlsConfig::from_strings(
        "127.0.0.1",
        1,
        "example.com",
        "550e8400-e29b-41d4-a716-446655440000",
        "/xhttp",
        "",
        "chrome_stable",
    )
    .expect("TLS config")
    .with_protocol_mode(XhttpProtocolMode::StreamOne);
    config.xmux.max_connections = 1;
    let client = XhttpClient::new_tls(config);
    let capacity = client.inner.carrier_capacity.clone().acquire_owned().await.expect("physical capacity");
    let pooled = crate::connect::handshake_pooled(
        crate::owned_io::OwnedIo::new(client_io, capacity),
        2,
        Arc::clone(&client.inner.pool_changed),
    )
    .await
    .expect("pooled H2 connection");
    let carrier = Arc::downgrade(&pooled);
    client.inner.state.lock().await.connections.push(pooled);
    let mut active = tokio::time::timeout(Duration::from_secs(2), client.connect("example.net:443"))
        .await
        .expect("first stream deadline")
        .expect("first stream");
    active.write_all(b"before").await.expect("first upload");
    let mut before = [0; 6];
    tokio::time::timeout(Duration::from_secs(2), active.read_exact(&mut before))
        .await
        .expect("first download deadline")
        .expect("first download");
    assert_eq!(&before, b"before");

    let opening = tokio::spawn({
        let client = client.clone();
        async move { client.connect("another.net:443").await }
    });
    tokio::time::timeout(Duration::from_secs(2), pending_seen.notified()).await.expect("second opening reached peer");
    opening.abort();
    assert!(matches!(opening.await, Err(error) if error.is_cancelled()));

    active.write_all(b"after").await.expect("active upload after cancellation");
    let mut after = [0; 5];
    tokio::time::timeout(Duration::from_secs(2), active.read_exact(&mut after))
        .await
        .expect("active download deadline")
        .expect("active download after cancellation");
    assert_eq!(&after, b"after");
    let mut next = tokio::time::timeout(Duration::from_secs(2), client.connect("third.net:443"))
        .await
        .expect("pool capacity was released")
        .expect("third stream on the same carrier");
    next.write_all(b"third").await.expect("third upload");
    let mut third = [0; 5];
    tokio::time::timeout(Duration::from_secs(2), next.read_exact(&mut third))
        .await
        .expect("third download deadline")
        .expect("third download");
    assert_eq!(&third, b"third");
    assert_eq!(requests.load(Ordering::Relaxed), 3);
    drop(next);
    drop(active);
    let error = tokio::time::timeout(Duration::from_secs(2), client.connect("after.net:443"))
        .await
        .expect("replacement must not wait for retired carrier")
        .err()
        .expect("new connection to the closed port fails");
    assert_ne!(error.kind(), std::io::ErrorKind::TimedOut);
    tokio::time::timeout(Duration::from_secs(2), async {
        while carrier.upgrade().is_some() {
            tokio::task::yield_now().await;
        }
    })
    .await
    .expect("carrier released after active streams finish");
    drop(client);
    server.abort();
    let _ = server.await;
}

/// # Cancel safety:
/// NOT cancel-safe: the test owns the local server and all spawned openings.
#[tokio::test]
async fn concurrent_open_cancellation_unblocks_waiter_after_carrier_drains() {
    let (client_io, server_io) = tokio::io::duplex(64 * 1024);
    let (seen_tx, mut seen_rx) = mpsc::unbounded_channel();
    let server = tokio::spawn(async move {
        let service = service_fn(move |_request: hyper::Request<hyper::body::Incoming>| {
            let seen = seen_tx.clone();
            async move {
                seen.send(()).expect("request received");
                std::future::pending::<Result<hyper::Response<ChannelBody>, Infallible>>().await
            }
        });
        let _ = hyper::server::conn::http2::Builder::new(TokioExecutor::new())
            .serve_connection(TokioIo::new(server_io), service)
            .await;
    });
    let mut config = XhttpTlsConfig::from_strings(
        "127.0.0.1",
        1,
        "example.com",
        "550e8400-e29b-41d4-a716-446655440000",
        "/xhttp",
        "",
        "chrome_stable",
    )
    .expect("TLS config")
    .with_protocol_mode(XhttpProtocolMode::StreamOne);
    config.xmux.max_connections = 1;
    config.xmux.max_concurrent_streams = 2;
    let client = XhttpClient::new_tls(config);
    let capacity = client.inner.carrier_capacity.clone().acquire_owned().await.expect("physical capacity");
    let pooled = crate::connect::handshake_pooled(
        crate::owned_io::OwnedIo::new(client_io, capacity),
        2,
        Arc::clone(&client.inner.pool_changed),
    )
    .await
    .expect("pooled H2 connection");
    client.inner.state.lock().await.connections.push(pooled);
    let first = tokio::spawn({
        let client = client.clone();
        async move { client.connect("first.net:443").await }
    });
    let second = tokio::spawn({
        let client = client.clone();
        async move { client.connect("second.net:443").await }
    });
    tokio::time::timeout(Duration::from_secs(2), async {
        seen_rx.recv().await.expect("first request");
        seen_rx.recv().await.expect("second request");
    })
    .await
    .expect("both openings reached the server");
    let mut waiter = tokio::spawn({
        let client = client.clone();
        async move { client.connect("third.net:443").await }
    });
    assert!(tokio::time::timeout(Duration::from_millis(100), &mut waiter).await.is_err());
    first.abort();
    assert!(matches!(first.await, Err(error) if error.is_cancelled()));
    assert!(
        tokio::time::timeout(Duration::from_millis(100), &mut waiter).await.is_err(),
        "waiter must not replace the carrier while the second opening holds it"
    );
    second.abort();
    assert!(matches!(second.await, Err(error) if error.is_cancelled()));
    let error = tokio::time::timeout(Duration::from_secs(2), waiter)
        .await
        .expect("waiter unblocked")
        .expect("waiter task")
        .err()
        .expect("the new connection to the closed port must fail");
    assert_ne!(error.kind(), std::io::ErrorKind::TimedOut, "capacity must be released");
    assert!(seen_rx.try_recv().is_err(), "the retired carrier received no third request");
    server.abort();
    let _ = server.await;
}
