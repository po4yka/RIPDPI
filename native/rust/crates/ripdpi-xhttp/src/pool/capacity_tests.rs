use std::future::{Future, poll_fn};
use std::sync::Arc;
use std::task::Poll;
use std::time::Duration;

use hyper_util::rt::{TokioExecutor, TokioIo};
use tokio::io::DuplexStream;

use crate::client::XhttpClient;
use crate::config::XhttpTlsConfig;

use super::{PooledConnection, acquire_connection};

/// # Cancel safety:
/// cancel-safe: this test owns all permits and drops the queued acquisition before return.
#[tokio::test]
async fn queued_acquisition_uses_capacity_released_by_second_carrier() {
    check_second_carrier_capacity().await;
}

/// # Cancel safety:
/// cancel-safe: this test owns all permits and drops the queued acquisition before return.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn queued_acquisition_uses_second_carrier_on_multithread_runtime() {
    check_second_carrier_capacity().await;
}

/// # Cancel safety:
/// cancel-safe: all pending acquisitions, carriers, and permits are local to the test.
async fn check_second_carrier_capacity() {
    let (client, connections, _peers) = full_pool().await;
    let first = connections[0].permits.clone().acquire_owned().await.expect("first carrier permit");
    let second = connections[1].permits.clone().acquire_owned().await.expect("second carrier permit");
    let mut queued = Box::pin(acquire_connection(&client.inner));
    assert!(poll_fn(|cx| Poll::Ready(queued.as_mut().poll(cx))).await.is_pending());

    drop(second);

    let (connection, permit) = tokio::time::timeout(Duration::from_secs(1), queued)
        .await
        .expect("the pending acquisition must use the released second carrier")
        .expect("second carrier acquisition");
    assert!(Arc::ptr_eq(&connection, &connections[1]));
    assert_eq!(connections[0].permits.available_permits(), 0, "first carrier stays busy");
    drop(permit);
    drop(first);
}

/// # Cancel safety:
/// cancel-safe: dropping the queued future must remove every semaphore reservation.
#[tokio::test]
async fn cancelling_pool_waiter_releases_all_carrier_reservations() {
    let (client, connections, _peers) = full_pool().await;
    let first = connections[0].permits.clone().acquire_owned().await.expect("first carrier permit");
    let second = connections[1].permits.clone().acquire_owned().await.expect("second carrier permit");
    let mut cancelled = Box::pin(acquire_connection(&client.inner));
    assert!(poll_fn(|cx| Poll::Ready(cancelled.as_mut().poll(cx))).await.is_pending());
    drop(cancelled);
    drop((first, second));

    let (first_connection, first) = acquire_connection(&client.inner).await.expect("first available carrier");
    let (second_connection, second) = acquire_connection(&client.inner).await.expect("second available carrier");
    assert!(!Arc::ptr_eq(&first_connection, &second_connection), "both permits remain available");
    drop((first, second));
    assert!(connections.iter().all(|connection| connection.permits.available_permits() == 1));
}

/// # Cancel safety:
/// cancel-safe: each acquired permit and every remaining acquisition belongs to the test.
#[tokio::test]
async fn completed_pool_waiter_releases_other_carrier_reservations() {
    let (client, connections, _peers) = full_pool().await;
    let first = connections[0].permits.clone().acquire_owned().await.expect("first carrier permit");
    let second = connections[1].permits.clone().acquire_owned().await.expect("second carrier permit");
    let mut queued = Box::pin(acquire_connection(&client.inner));
    assert!(poll_fn(|cx| Poll::Ready(queued.as_mut().poll(cx))).await.is_pending());
    drop((first, second));

    let (first_connection, first) = tokio::time::timeout(Duration::from_secs(1), queued)
        .await
        .expect("one queued acquisition completes")
        .expect("first available carrier");
    let (second_connection, second) =
        acquire_connection(&client.inner).await.expect("unused carrier remains available");
    assert!(!Arc::ptr_eq(&first_connection, &second_connection));
    drop((first, second));
    assert!(connections.iter().all(|connection| connection.permits.available_permits() == 1));
}

/// # Cancel safety:
/// cancel-safe: the returned setup owns each sender, peer pipe and semaphore.
async fn full_pool() -> (XhttpClient, Vec<Arc<PooledConnection>>, Vec<DuplexStream>) {
    let mut config = XhttpTlsConfig::from_strings(
        "127.0.0.1",
        1,
        "example.com",
        "550e8400-e29b-41d4-a716-446655440000",
        "/xhttp",
        "",
        "chrome_stable",
    )
    .expect("xHTTP TLS config");
    config.xmux.max_connections = 2;
    config.xmux.max_concurrent_streams = 1;
    let client = XhttpClient::new_tls(config);
    let mut connections = Vec::new();
    let mut peers = Vec::new();
    for _ in 0..2 {
        let (client_io, server_io) = tokio::io::duplex(4096);
        let (sender, _connection) =
            hyper::client::conn::http2::handshake(TokioExecutor::new(), TokioIo::new(client_io))
                .await
                .expect("client H2 handshake");
        connections.push(Arc::new(PooledConnection::new(sender, 1, client.inner.pool_changed.clone())));
        peers.push(server_io);
    }
    client.inner.state.lock().await.connections = connections.clone();
    (client, connections, peers)
}
