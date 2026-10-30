//! One shared HTTP/2 connection to the NaiveProxy server for many CONNECT streams.
//!
//! Upstream NaiveProxy multiplexes tunnels on Chromium's HTTP/2 sessions; a new
//! TCP + TLS + HTTP/2 handshake per tunnel was the main traffic difference.
//!
//! hyper's own connection task owns the socket. Dropping the pool's sender
//! retires a connection: it takes no new streams and closes gracefully once
//! its open tunnels end. A dead connection shows as `is_closed` through the
//! HTTP/2 keepalive configured in `connect_tunnel`, which pings while any
//! stream (an open tunnel or an unanswered CONNECT) is open.

use std::future::Future;
use std::io;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Mutex, MutexGuard, PoisonError};
use std::time::Duration;

use bytes::Bytes;
use http_body_util::Empty;
use hyper::client::conn::http2::SendRequest;
use tokio::time::Instant;

/// Upstream Android defaults: a session takes no new streams after 10 minutes,
/// or after 5 minutes without a new stream. Its open streams run on.
const SESSION_MAX_AGE: Duration = Duration::from_secs(600);
const SESSION_MAX_IDLE: Duration = Duration::from_secs(300);

pub(crate) type Sender = SendRequest<Empty<Bytes>>;

/// A handle for opening one CONNECT stream on a pooled connection.
pub(crate) struct Session {
    pub(crate) id: u64,
    pub(crate) sender: Sender,
    verified: Arc<AtomicBool>,
}

impl Session {
    /// The server answered a CONNECT on this connection, so it works.
    pub(crate) fn mark_verified(&self) {
        // Relaxed: the flag publishes no other data.
        self.verified.store(true, Ordering::Relaxed);
    }

    pub(crate) fn is_verified(&self) -> bool {
        self.verified.load(Ordering::Relaxed)
    }
}

struct Entry {
    id: u64,
    sender: Sender,
    verified: Arc<AtomicBool>,
    created: Instant,
    last_used: Instant,
}

/// The helper's single upstream session group: one process serves one server
/// configuration, so one current connection is enough.
#[derive(Default)]
pub(crate) struct SessionPool {
    current: Mutex<Option<Entry>>,
    creating: tokio::sync::Mutex<()>,
    next_id: AtomicU64,
}

impl SessionPool {
    /// Returns the current live session, or connects a new one with `connect`.
    /// Only one connect runs at a time; concurrent opens wait and share it.
    ///
    /// # Cancel safety
    ///
    /// cancel-safe: the pool changes only after `connect` completes, with no
    /// await between; a cancelled connect drops its partial connection.
    pub(crate) async fn session<F, Fut>(&self, connect: F) -> io::Result<Session>
    where
        F: FnOnce() -> Fut,
        Fut: Future<Output = io::Result<Sender>>,
    {
        if let Some(session) = self.live() {
            return Ok(session);
        }
        let _creating = self.creating.lock().await;
        if let Some(session) = self.live() {
            return Ok(session);
        }
        let sender = connect().await?;
        let now = Instant::now();
        // Relaxed: the id only has to be unique, it orders nothing.
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let verified = Arc::new(AtomicBool::new(false));
        let session = Session { id, sender: sender.clone(), verified: Arc::clone(&verified) };
        *lock(&self.current) = Some(Entry { id, sender, verified, created: now, last_used: now });
        Ok(session)
    }

    /// Session `id` is unusable: give it to no new stream. The connection
    /// closes once its open tunnels end.
    pub(crate) fn discard(&self, id: u64) {
        let mut current = lock(&self.current);
        if current.as_ref().is_some_and(|entry| entry.id == id) {
            *current = None;
        }
    }

    fn live(&self) -> Option<Session> {
        let mut current = lock(&self.current);
        let now = Instant::now();
        let entry = current.as_mut()?;
        if entry.sender.is_closed()
            || now.duration_since(entry.created) >= SESSION_MAX_AGE
            || now.duration_since(entry.last_used) >= SESSION_MAX_IDLE
        {
            // shortcut: retirement is checked when a stream opens, so an idle
            // connection lingers until then or until the server closes it.
            *current = None;
            return None;
        }
        entry.last_used = now;
        Some(Session { id: entry.id, sender: entry.sender.clone(), verified: Arc::clone(&entry.verified) })
    }
}

/// No code panics while it holds the lock, but a poisoned slot is still valid.
fn lock(slot: &Mutex<Option<Entry>>) -> MutexGuard<'_, Option<Entry>> {
    slot.lock().unwrap_or_else(PoisonError::into_inner)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicUsize;

    use hyper_util::rt::{TokioExecutor, TokioIo};

    /// An HTTP/2 connection over an in-memory pipe, so paused time cannot stall real I/O.
    ///
    /// # Cancel safety:
    /// cancel-safe: dropping it drops both pipe ends; the spawned tasks end with them.
    async fn connect(connects: &AtomicUsize) -> io::Result<Sender> {
        connects.fetch_add(1, Ordering::Relaxed);
        let (client, server) = tokio::io::duplex(64 * 1024);
        tokio::spawn(async move {
            let service = hyper::service::service_fn(|_request| async {
                Ok::<_, std::convert::Infallible>(hyper::Response::new(Empty::<Bytes>::new()))
            });
            let _ = hyper::server::conn::http2::Builder::new(TokioExecutor::new())
                .serve_connection(TokioIo::new(server), service)
                .await;
        });
        let (sender, connection) = hyper::client::conn::http2::handshake(TokioExecutor::new(), TokioIo::new(client))
            .await
            .map_err(io::Error::other)?;
        tokio::spawn(async move { drop(connection.await) });
        Ok(sender)
    }

    /// # Cancel safety:
    /// cancel-safe: the test owns every connection and the paused runtime drops them.
    #[tokio::test(start_paused = true)]
    async fn sessions_are_reused_until_idle_or_age_retires_them() {
        let pool = SessionPool::default();
        let connects = AtomicUsize::new(0);
        let first = pool.session(|| connect(&connects)).await.expect("first session");

        tokio::time::advance(SESSION_MAX_IDLE - Duration::from_secs(1)).await;
        let reused = pool.session(|| connect(&connects)).await.expect("reused session");
        assert_eq!(reused.id, first.id, "a session inside the idle limit is reused");

        tokio::time::advance(SESSION_MAX_IDLE).await;
        let after_idle = pool.session(|| connect(&connects)).await.expect("session after idle");
        assert_ne!(after_idle.id, first.id, "an idle session takes no new streams");

        for _ in 0..3 {
            tokio::time::advance(SESSION_MAX_IDLE - Duration::from_secs(1)).await;
            pool.session(|| connect(&connects)).await.expect("busy session");
        }
        let after_age = pool.session(|| connect(&connects)).await.expect("session after age");
        assert_ne!(after_age.id, after_idle.id, "a session older than the age limit takes no new streams");
        assert_eq!(connects.load(Ordering::Relaxed), 3);
    }

    /// # Cancel safety:
    /// cancel-safe: the test owns every connection and the runtime drops them.
    #[tokio::test]
    async fn concurrent_opens_share_one_new_connection_and_discard_replaces_it() {
        let pool = SessionPool::default();
        let connects = AtomicUsize::new(0);
        let (first, second) = tokio::join!(pool.session(|| connect(&connects)), pool.session(|| connect(&connects)));
        let (first, second) = (first.expect("first"), second.expect("second"));
        assert_eq!(first.id, second.id, "concurrent opens must share one connection");
        assert_eq!(connects.load(Ordering::Relaxed), 1);

        pool.discard(first.id);
        let replacement = pool.session(|| connect(&connects)).await.expect("replacement");
        assert_ne!(replacement.id, first.id, "a discarded session takes no new streams");
        pool.discard(first.id);
        let kept = pool.session(|| connect(&connects)).await.expect("kept");
        assert_eq!(kept.id, replacement.id, "a late discard of an old id keeps the newer session");
    }
}
