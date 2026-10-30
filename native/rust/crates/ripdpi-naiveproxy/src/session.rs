//! Shared HTTP/2 connections to the NaiveProxy server for many CONNECT streams.
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
use std::pin::Pin;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Mutex, MutexGuard, PoisonError};
use std::task::{Context, Poll};
use std::time::Duration;

use bytes::Bytes;
use http_body_util::Empty;
use hyper::client::conn::http2::SendRequest;
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::time::Instant;

/// Upstream Android defaults: a session takes no new streams after 10 minutes,
/// or after 5 minutes without a new stream. Its open streams run on.
const SESSION_MAX_AGE: Duration = Duration::from_secs(600);
const SESSION_MAX_IDLE: Duration = Duration::from_secs(300);
/// Streams (open tunnels plus unanswered CONNECTs) a connection may carry
/// before the server's SETTINGS arrive: hyper's own default, and the minimum
/// RFC 9113 recommends a server allow. After that the connection's live
/// `SETTINGS_MAX_CONCURRENT_STREAMS` applies; a stream past it goes to another
/// connection instead of queueing behind the server's limit.
pub(crate) const INITIAL_MAX_STREAMS: usize = 100;

pub(crate) type Sender = SendRequest<Empty<Bytes>>;

/// A handle for opening one CONNECT stream on a pooled connection. It holds
/// one stream slot of that connection until it, or the tunnel it becomes, drops.
pub(crate) struct Session {
    pub(crate) id: u64,
    pub(crate) sender: Sender,
    verified: Arc<AtomicBool>,
    slot: StreamSlot,
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

    /// Keeps this session's stream slot taken for as long as `stream` lives.
    pub(crate) fn into_tunnel<S>(self, stream: S) -> PooledTunnel<S> {
        PooledTunnel { inner: stream, _slot: self.slot }
    }
}

/// One taken stream slot of a connection; dropping it frees the slot.
struct StreamSlot(Arc<AtomicUsize>);

impl StreamSlot {
    /// Takes a slot. Callers hold the pool lock, so the check-then-take is not racy.
    fn take(streams: &Arc<AtomicUsize>) -> Self {
        // Relaxed: increments happen under the pool lock, so no two choices
        // over-fill a connection; a decrement outside it can only make a later
        // choice see a higher count and spill early. It publishes no data.
        streams.fetch_add(1, Ordering::Relaxed);
        Self(Arc::clone(streams))
    }
}

impl Drop for StreamSlot {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::Relaxed);
    }
}

/// A tunnel stream that keeps its connection's stream slot while it lives.
pub(crate) struct PooledTunnel<S> {
    inner: S,
    _slot: StreamSlot,
}

impl<S: AsyncRead + Unpin> AsyncRead for PooledTunnel<S> {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.inner).poll_read(cx, buf)
    }
}

impl<S: AsyncWrite + Unpin> AsyncWrite for PooledTunnel<S> {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        Pin::new(&mut self.inner).poll_write(cx, buf)
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.inner).poll_flush(cx)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.inner).poll_shutdown(cx)
    }

    fn poll_write_vectored(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        bufs: &[io::IoSlice<'_>],
    ) -> Poll<io::Result<usize>> {
        Pin::new(&mut self.inner).poll_write_vectored(cx, bufs)
    }

    fn is_write_vectored(&self) -> bool {
        self.inner.is_write_vectored()
    }
}

struct Entry {
    id: u64,
    sender: Sender,
    verified: Arc<AtomicBool>,
    streams: Arc<AtomicUsize>,
    /// The server's current stream limit for this connection, see [`INITIAL_MAX_STREAMS`].
    max_streams: Arc<AtomicUsize>,
    created: Instant,
    last_used: Instant,
}

impl Entry {
    fn session(&mut self, now: Instant) -> Session {
        self.last_used = now;
        Session {
            id: self.id,
            sender: self.sender.clone(),
            verified: Arc::clone(&self.verified),
            slot: StreamSlot::take(&self.streams),
        }
    }
}

/// The helper's upstream session group: one process serves one server
/// configuration. Usually one connection; another opens only while every
/// current one is at its stream limit.
#[derive(Default)]
pub(crate) struct SessionPool {
    entries: Mutex<Vec<Entry>>,
    creating: tokio::sync::Mutex<()>,
    next_id: AtomicU64,
}

impl SessionPool {
    /// Returns a live session with a free stream slot, or connects a new one
    /// with `connect`. Only one connect runs at a time; concurrent opens wait
    /// and share it.
    ///
    /// # Cancel safety
    ///
    /// cancel-safe: the pool changes only after `connect` completes, with no
    /// await between; a cancelled connect drops its partial connection.
    pub(crate) async fn session<F, Fut>(&self, connect: F) -> io::Result<Session>
    where
        F: FnOnce() -> Fut,
        Fut: Future<Output = io::Result<(Sender, Arc<AtomicUsize>)>>,
    {
        if let Some(session) = self.live() {
            return Ok(session);
        }
        let _creating = self.creating.lock().await;
        if let Some(session) = self.live() {
            return Ok(session);
        }
        let (sender, max_streams) = connect().await?;
        let now = Instant::now();
        // Relaxed: the id only has to be unique, it orders nothing.
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let mut entry = Entry {
            id,
            sender,
            verified: Arc::new(AtomicBool::new(false)),
            streams: Arc::new(AtomicUsize::new(0)),
            max_streams,
            created: now,
            last_used: now,
        };
        let session = entry.session(now);
        lock(&self.entries).push(entry);
        Ok(session)
    }

    /// Session `id` is unusable: give it to no new stream. The connection
    /// closes once its open tunnels end.
    pub(crate) fn discard(&self, id: u64) {
        lock(&self.entries).retain(|entry| entry.id != id);
    }

    fn live(&self) -> Option<Session> {
        let mut entries = lock(&self.entries);
        let now = Instant::now();
        // shortcut: retirement is checked when a stream opens, so an idle
        // connection lingers until then or until the server closes it; a spill
        // connection, used only at peaks, is the most likely to linger.
        entries.retain(|entry| {
            !entry.sender.is_closed()
                && now.duration_since(entry.created) < SESSION_MAX_AGE
                && now.duration_since(entry.last_used) < SESSION_MAX_IDLE
        });
        entries
            .iter_mut()
            .find(|entry| entry.streams.load(Ordering::Relaxed) < entry.max_streams.load(Ordering::Relaxed))
            .map(|entry| entry.session(now))
    }
}

/// No code panics while it holds the lock, but a poisoned list is still valid.
fn lock(entries: &Mutex<Vec<Entry>>) -> MutexGuard<'_, Vec<Entry>> {
    entries.lock().unwrap_or_else(PoisonError::into_inner)
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
    async fn connect(connects: &AtomicUsize) -> io::Result<(Sender, Arc<AtomicUsize>)> {
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
        Ok((sender, Arc::new(AtomicUsize::new(INITIAL_MAX_STREAMS))))
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

    /// # Cancel safety:
    /// cancel-safe: the test owns every connection and the runtime drops them.
    #[tokio::test]
    async fn a_full_connection_spills_new_streams_to_another() {
        let pool = SessionPool::default();
        let connects = AtomicUsize::new(0);
        let mut held = Vec::new();
        for _ in 0..INITIAL_MAX_STREAMS {
            held.push(pool.session(|| connect(&connects)).await.expect("slot"));
        }
        let first = held[0].id;
        assert!(held.iter().all(|session| session.id == first), "streams fill one connection first");

        let spilled = pool.session(|| connect(&connects)).await.expect("spilled");
        assert_ne!(spilled.id, first, "a full connection takes no new stream");
        assert_eq!(connects.load(Ordering::Relaxed), 2);

        held.pop();
        let refilled = pool.session(|| connect(&connects)).await.expect("refilled");
        assert_eq!(refilled.id, first, "a freed slot on the first connection is used first");
        let tunnel = refilled.into_tunnel(tokio::io::empty());
        let next = pool.session(|| connect(&connects)).await.expect("next");
        assert_eq!(next.id, spilled.id, "an open tunnel keeps its slot taken");

        drop(tunnel);
        drop(next);
        let reused = pool.session(|| connect(&connects)).await.expect("reused");
        assert_eq!(reused.id, first, "a closed tunnel frees its slot");
        assert_eq!(connects.load(Ordering::Relaxed), 2);
    }
}
