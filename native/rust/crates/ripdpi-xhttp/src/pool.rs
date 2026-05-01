use std::io;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use hyper::client::conn::http2;
use tokio::sync::{Mutex, OwnedSemaphorePermit, Semaphore};

use crate::client::XhttpClientInner;
use crate::connect;
use crate::h2_body::XhttpBody;

pub(crate) struct PooledConnection {
    pub(crate) sender: Mutex<http2::SendRequest<XhttpBody>>,
    permits: Arc<Semaphore>,
    closed: AtomicBool,
}

#[derive(Default)]
pub(crate) struct PoolState {
    connections: Vec<Arc<PooledConnection>>,
    creating_connections: usize,
}

impl PooledConnection {
    pub(crate) fn new(sender: http2::SendRequest<XhttpBody>, max_concurrent_streams: usize) -> Self {
        Self {
            sender: Mutex::new(sender),
            permits: Arc::new(Semaphore::new(max_concurrent_streams)),
            closed: AtomicBool::new(false),
        }
    }

    pub(crate) fn mark_closed(&self) {
        self.closed.store(true, Ordering::SeqCst);
        self.permits.close();
    }

    fn is_closed(&self) -> bool {
        self.closed.load(Ordering::SeqCst)
    }
}

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
            if state.connections.len() + state.creating_connections < inner.max_connections {
                state.creating_connections += 1;
                true
            } else {
                false
            }
        };

        if should_create {
            match connect::create_connection(&inner.mode, inner.max_concurrent_streams).await {
                Ok(connection) => {
                    let permit = connection
                        .permits
                        .clone()
                        .try_acquire_owned()
                        .map_err(|_| io::Error::other("xHTTP connection created without stream capacity"))?;
                    let mut state = inner.state.lock().await;
                    state.creating_connections = state.creating_connections.saturating_sub(1);
                    state.connections.push(connection.clone());
                    return Ok((connection, permit));
                }
                Err(error) => {
                    let mut state = inner.state.lock().await;
                    state.creating_connections = state.creating_connections.saturating_sub(1);
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

async fn try_acquire_existing(inner: &XhttpClientInner) -> Option<(Arc<PooledConnection>, OwnedSemaphorePermit)> {
    let state = inner.state.lock().await;
    state.connections.iter().find_map(|connection| {
        if connection.is_closed() {
            return None;
        }
        connection.permits.clone().try_acquire_owned().ok().map(|permit| (connection.clone(), permit))
    })
}
