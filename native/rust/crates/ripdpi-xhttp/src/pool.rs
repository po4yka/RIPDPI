use std::io;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex as StdMutex};

use hyper::client::conn::http2;
use tokio::sync::{Mutex, OwnedSemaphorePermit, Semaphore};
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
}

impl CreationSlot {
    fn reserve(creating_connections: Arc<AtomicUsize>) -> Self {
        creating_connections.fetch_add(1, Ordering::AcqRel);
        Self { creating_connections }
    }
}

impl Drop for CreationSlot {
    fn drop(&mut self) {
        let previous = self.creating_connections.fetch_sub(1, Ordering::AcqRel);
        debug_assert!(previous > 0, "xHTTP creation slot released without a matching reservation");
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

    fn is_closed(&self) -> bool {
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
        if let Some((connection, permit)) = try_acquire_existing(inner).await {
            return Ok((connection, permit));
        }

        let should_create = {
            let mut state = inner.state.lock().await;
            state.connections.retain(|connection| !connection.is_closed());
            if state.connections.len() + inner.creating_connections.load(Ordering::Acquire) < inner.max_connections {
                Some(CreationSlot::reserve(inner.creating_connections.clone()))
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
            tokio::task::yield_now().await;
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
            let _slot = CreationSlot::reserve(task_connections);
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

    fn empty_request() -> Request<XhttpBody> {
        let body = Empty::<Bytes>::new().map_err(|never| match never {}).boxed();
        Request::builder().uri("https://example.com/xhttp").body(body).expect("request")
    }
}
