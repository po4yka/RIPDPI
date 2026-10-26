use std::io;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex as StdMutex};

use hyper::client::conn::http2;
use tokio::sync::{Mutex, Notify, OwnedSemaphorePermit, Semaphore};
use tokio::task::JoinHandle;

use crate::client::XhttpClientInner;
use crate::connect;
use crate::h2_body::XhttpBody;

pub(crate) struct PooledConnection {
    pub(crate) sender: Mutex<http2::SendRequest<XhttpBody>>,
    permits: Arc<Semaphore>,
    closed: AtomicBool,
    driver: StdMutex<Option<AbortOnDrop>>,
}

struct AbortOnDrop(JoinHandle<()>);

impl Drop for AbortOnDrop {
    fn drop(&mut self) {
        self.0.abort();
    }
}

#[derive(Default)]
pub(crate) struct PoolState {
    connections: Vec<Arc<PooledConnection>>,
}

struct CreationSlot {
    creating_connections: Arc<AtomicUsize>,
    /// Wakes queued opens: a released slot either published a connection or
    /// freed capacity for a new one.
    pool_changed: Arc<Notify>,
}

impl CreationSlot {
    fn reserve(creating_connections: Arc<AtomicUsize>, pool_changed: Arc<Notify>) -> Self {
        creating_connections.fetch_add(1, Ordering::AcqRel);
        Self { creating_connections, pool_changed }
    }
}

impl Drop for CreationSlot {
    fn drop(&mut self) {
        let previous = self.creating_connections.fetch_sub(1, Ordering::AcqRel);
        debug_assert!(previous > 0, "xHTTP creation slot released without a matching reservation");
        self.pool_changed.notify_waiters();
    }
}

impl PooledConnection {
    pub(crate) fn new(sender: http2::SendRequest<XhttpBody>, max_concurrent_streams: usize) -> Self {
        Self {
            sender: Mutex::new(sender),
            permits: Arc::new(Semaphore::new(max_concurrent_streams)),
            closed: AtomicBool::new(false),
            driver: StdMutex::new(None),
        }
    }

    pub(crate) fn attach_driver(&self, driver: JoinHandle<()>) {
        let mut guard = self.driver.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        debug_assert!(guard.is_none(), "xHTTP connection driver attached twice");
        *guard = Some(AbortOnDrop(driver));
    }

    pub(crate) fn mark_closed(&self) {
        self.closed.store(true, Ordering::SeqCst);
        self.permits.close();
    }

    pub(crate) fn is_closed(&self) -> bool {
        self.closed.load(Ordering::SeqCst)
    }

    /// NOT cancel-safe: cancellation may reset a partially submitted H2
    /// request. Dropping the request future and its body tears that stream down.
    pub(crate) async fn send_request(
        &self,
        request: http::Request<XhttpBody>,
    ) -> Result<http::Response<hyper::body::Incoming>, hyper::Error> {
        let mut sender = self.sender.lock().await.clone();
        sender.send_request(request).await
    }
}

/// cancel-safe: creation reservations and acquired permits are RAII guards;
/// cancelling drops a partial carrier and restores pool capacity.
pub(crate) async fn acquire_connection(
    inner: &XhttpClientInner,
) -> io::Result<(Arc<PooledConnection>, OwnedSemaphorePermit)> {
    loop {
        // Registered before any pool check, so a slot released after the
        // checks below still wakes this open instead of being missed.
        let pool_changed = inner.pool_changed.notified();
        tokio::pin!(pool_changed);
        pool_changed.as_mut().enable();

        if let Some((connection, permit)) = try_acquire_existing(inner).await {
            return Ok((connection, permit));
        }

        let should_create = {
            let mut state = inner.state.lock().await;
            state.connections.retain(|connection| !connection.is_closed());
            if state.connections.len() + inner.creating_connections.load(Ordering::Acquire) < inner.max_connections {
                Some(CreationSlot::reserve(inner.creating_connections.clone(), inner.pool_changed.clone()))
            } else {
                None
            }
        };

        if let Some(creation_slot) = should_create {
            match connect::create_connection(&inner.mode, inner.max_concurrent_streams).await {
                Ok(connection) => {
                    let permit = connection
                        .permits
                        .clone()
                        .try_acquire_owned()
                        .map_err(|_| io::Error::other("xHTTP connection created without stream capacity"))?;
                    let mut state = inner.state.lock().await;
                    state.connections.push(connection.clone());
                    drop(creation_slot);
                    return Ok((connection, permit));
                }
                Err(error) => {
                    let state = inner.state.lock().await;
                    drop(creation_slot);
                    if state.connections.is_empty() {
                        return Err(error);
                    }
                }
            }
        }

        let waiter = {
            let state = inner.state.lock().await;
            state
                .connections
                .iter()
                .find(|connection| !connection.is_closed())
                .map(|connection| (connection.clone(), connection.permits.clone()))
        };
        let Some((connection, permits)) = waiter else {
            // Only an in-flight creation can change the pool from here; with
            // none left, the next pass prunes closed carriers and creates one.
            if inner.creating_connections.load(Ordering::Acquire) > 0 {
                pool_changed.await;
            }
            continue;
        };
        let permit =
            permits.acquire_owned().await.map_err(|_| io::Error::other("xHTTP stream permit channel closed"))?;
        if connection.is_closed() {
            drop(permit);
            continue;
        }
        return Ok((connection, permit));
    }
}

/// cancel-safe: Tokio mutex and semaphore acquisition are cancel-safe, and a
/// successfully acquired permit is returned or dropped in this poll cycle.
async fn try_acquire_existing(inner: &XhttpClientInner) -> Option<(Arc<PooledConnection>, OwnedSemaphorePermit)> {
    let state = inner.state.lock().await;
    state.connections.iter().find_map(|connection| {
        if connection.is_closed() {
            return None;
        }
        connection.permits.clone().try_acquire_owned().ok().map(|permit| (connection.clone(), permit))
    })
}

#[cfg(test)]
mod tests {
    use std::convert::Infallible;
    use std::sync::Arc;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::time::Duration;

    use bytes::Bytes;
    use http_body_util::{BodyExt, Empty, Full};
    use hyper::Request;
    use hyper::service::service_fn;
    use hyper_util::rt::{TokioExecutor, TokioIo};
    use tokio::sync::{Notify, mpsc, oneshot};

    use crate::h2_body::XhttpBody;

    use super::{CreationSlot, PooledConnection};

    #[tokio::test]
    async fn creation_slot_releases_capacity_when_creation_task_is_cancelled() {
        let creating_connections = Arc::new(AtomicUsize::new(0));

        let task_connections = creating_connections.clone();
        let task = tokio::spawn(async move {
            let _slot = CreationSlot::reserve(task_connections, Arc::new(Notify::new()));
            std::future::pending::<()>().await;
        });
        while creating_connections.load(Ordering::Acquire) == 0 {
            tokio::task::yield_now().await;
        }

        task.abort();
        let _ = task.await;
        assert_eq!(0, creating_connections.load(Ordering::Acquire));
    }

    struct DropNotice(Option<oneshot::Sender<()>>);

    impl Drop for DropNotice {
        fn drop(&mut self) {
            if let Some(sender) = self.0.take() {
                let _ = sender.send(());
            }
        }
    }

    #[tokio::test]
    async fn dropping_pooled_connection_aborts_attached_driver() {
        let (client_io, _server_io) = tokio::io::duplex(4096);
        let (sender, _connection) =
            hyper::client::conn::http2::handshake(TokioExecutor::new(), TokioIo::new(client_io))
                .await
                .expect("client h2 handshake");
        let pooled = Arc::new(PooledConnection::new(sender, 1));
        let (started_tx, started_rx) = oneshot::channel();
        let (dropped_tx, dropped_rx) = oneshot::channel();
        pooled.attach_driver(tokio::spawn(async move {
            let _notice = DropNotice(Some(dropped_tx));
            let _ = started_tx.send(());
            std::future::pending::<()>().await;
        }));
        started_rx.await.expect("driver started");

        drop(pooled);

        tokio::time::timeout(Duration::from_secs(1), dropped_rx)
            .await
            .expect("pooled connection drop must abort driver")
            .expect("driver drop notification");
    }

    #[tokio::test]
    async fn waiting_for_response_headers_does_not_block_another_h2_request() {
        let (client_io, server_io) = tokio::io::duplex(4096);
        let (sender, connection) = hyper::client::conn::http2::handshake(TokioExecutor::new(), TokioIo::new(client_io))
            .await
            .expect("client h2 handshake");
        tokio::spawn(async move { connection.await.expect("client h2 connection") });

        let (request_tx, mut request_rx) = mpsc::unbounded_channel();
        let release_first = Arc::new(Notify::new());
        let request_index = Arc::new(AtomicUsize::new(0));
        let server_release = Arc::clone(&release_first);
        let server_index = Arc::clone(&request_index);
        tokio::spawn(async move {
            let service = service_fn(move |_request| {
                let index = server_index.fetch_add(1, Ordering::SeqCst);
                let request_tx = request_tx.clone();
                let release = Arc::clone(&server_release);
                async move {
                    request_tx.send(index).expect("report received request");
                    if index == 0 {
                        release.notified().await;
                    }
                    Ok::<_, Infallible>(http::Response::new(Full::new(Bytes::new())))
                }
            });
            hyper::server::conn::http2::Builder::new(TokioExecutor::new())
                .serve_connection(TokioIo::new(server_io), service)
                .await
                .expect("server h2 connection");
        });

        let pooled = Arc::new(PooledConnection::new(sender, 2));
        let first = tokio::spawn({
            let pooled = Arc::clone(&pooled);
            async move { pooled.send_request(empty_request()).await }
        });
        assert_eq!(request_rx.recv().await, Some(0));

        let second = tokio::spawn({
            let pooled = Arc::clone(&pooled);
            async move { pooled.send_request(empty_request()).await }
        });
        let second_arrival = tokio::time::timeout(Duration::from_millis(100), request_rx.recv()).await;

        release_first.notify_one();
        first.await.expect("first request task").expect("first response");
        second.await.expect("second request task").expect("second response");
        assert_eq!(second_arrival.expect("second request must not wait for first response headers"), Some(1));
    }

    /// A queued open must park while the only creation slot runs a stalled
    /// handshake. With a paused clock the runtime only advances time when
    /// every task is parked, so a spinning waiter would never time out.
    #[test]
    fn queued_open_parks_while_the_only_creation_slot_is_busy() {
        let (done_tx, done_rx) = std::sync::mpsc::channel();
        std::thread::spawn(move || {
            let runtime = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .start_paused(true)
                .build()
                .expect("test runtime");
            runtime.block_on(async {
                // Accepts TCP and never answers, so the TLS handshake stalls.
                let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.expect("bind silent server");
                let port = listener.local_addr().expect("silent server address").port();
                let _server = tokio::spawn(async move {
                    let _held = listener.accept().await;
                    std::future::pending::<()>().await;
                });
                let mut config = crate::config::XhttpTlsConfig::from_strings(
                    "127.0.0.1",
                    i32::from(port),
                    "example.com",
                    "550e8400-e29b-41d4-a716-446655440000",
                    "/xhttp",
                    "",
                    "chrome_stable",
                )
                .expect("xHTTP TLS config");
                config.xmux.max_connections = 1;
                let client = crate::client::XhttpClient::new_tls(config);
                let creator = tokio::spawn({
                    let client = client.clone();
                    async move { super::acquire_connection(&client.inner).await.map(|_| ()) }
                });
                while client.inner.creating_connections.load(Ordering::Acquire) == 0 {
                    tokio::task::yield_now().await;
                }

                let queued =
                    tokio::time::timeout(Duration::from_secs(5), super::acquire_connection(&client.inner)).await;

                assert!(queued.is_err(), "the queued open must still wait for the stalled creator");
                creator.abort();
            });
            let _ = done_tx.send(());
        });
        done_rx
            .recv_timeout(Duration::from_secs(20))
            .expect("a queued xHTTP open spun on the runtime instead of parking");
    }

    fn empty_request() -> Request<XhttpBody> {
        let body = Empty::<Bytes>::new().map_err(|never| match never {}).boxed();
        Request::builder().uri("https://example.com/xhttp").body(body).expect("request")
    }
}
