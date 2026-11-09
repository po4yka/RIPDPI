//! One DoT connection shared by concurrent queries (RFC 7858 3.3 pipelining).
//!
//! Each query keeps its own message ID unless another in-flight query on the
//! connection uses it; then it gets a free wire ID. Replies that arrive
//! out of order are matched by ID (RFC 7766 6.2.1.1, 7). The task that holds the
//! reader lock reads frames for every waiter until its own reply arrives; the
//! frame buffer lives with the connection, so no byte is lost between reads.

use std::collections::hash_map::Entry;
use std::collections::{HashMap, HashSet};
use std::sync::atomic::{AtomicBool, AtomicU16, Ordering};
use std::sync::{Mutex, MutexGuard, PoisonError};
use std::time::Duration;

use bytes::BytesMut;
use futures::{SinkExt, StreamExt};
use tokio::io::{ReadHalf, WriteHalf};
use tokio::sync::oneshot;
use tokio::sync::{Mutex as AsyncMutex, MutexGuard as AsyncMutexGuard};
use tokio::time::{Instant, timeout};
use tokio_util::codec::{FramedRead, FramedWrite, LengthDelimitedCodec};

use super::connection::DotTlsStream;
use crate::transport::{STREAM_CLOSED_BEFORE_FRAME, dns_length_codec, ensure_response_matches_query};
use crate::types::EncryptedDnsError;

type Reader = FramedRead<ReadHalf<DotTlsStream>, LengthDelimitedCodec>;

pub(super) struct DotConnection {
    writer: AsyncMutex<FramedWrite<WriteHalf<DotTlsStream>, LengthDelimitedCodec>>,
    reader: AsyncMutex<Reader>,
    waiters: Mutex<Waiters>,
    next_id: AtomicU16,
    /// Set once the stream is unusable; checked under `waiters` so no waiter registers after it.
    broken: AtomicBool,
    activity: Mutex<Activity>,
    /// The socket is registered with this runtime's reactor and makes progress only on it.
    runtime: tokio::runtime::Id,
}

#[derive(Default)]
struct Waiters {
    pending: HashMap<u16, oneshot::Sender<BytesMut>>,
    /// IDs of cancelled or timed-out queries whose late reply may still arrive.
    abandoned: HashSet<u16>,
}

#[derive(Clone, Copy)]
struct Activity {
    last_read: Instant,
    last_use: Instant,
}

impl DotConnection {
    pub(super) fn new(stream: DotTlsStream) -> Self {
        let (read, write) = tokio::io::split(stream);
        let now = Instant::now();
        Self {
            writer: AsyncMutex::new(FramedWrite::new(write, dns_length_codec())),
            reader: AsyncMutex::new(FramedRead::new(read, dns_length_codec())),
            waiters: Mutex::default(),
            next_id: AtomicU16::new(0),
            broken: AtomicBool::new(false),
            activity: Mutex::new(Activity { last_read: now, last_use: now }),
            runtime: tokio::runtime::Handle::current().id(),
        }
    }

    /// Whether a new query on the current runtime may use this connection.
    pub(super) fn usable(&self, max_idle: Duration) -> bool {
        !self.is_broken()
            && self.idle_for() <= max_idle
            && tokio::runtime::Handle::try_current().is_ok_and(|handle| handle.id() == self.runtime)
    }

    pub(super) fn is_broken(&self) -> bool {
        self.broken.load(Ordering::Acquire)
    }

    /// Time since the last query was sent or reply was read.
    pub(super) fn idle_for(&self) -> Duration {
        self.activity().last_use.elapsed()
    }

    /// Sends `query` and waits up to `limit` for the matching reply, returned with the original ID.
    ///
    /// # Cancel safety
    ///
    /// cancel-safe: the waiter entry is removed on drop; a partly written frame stays in the
    /// shared `FramedWrite` buffer and a partly read frame in the shared `FramedRead` buffer, so
    /// the next lock holder continues the stream where this call stopped. A reply to a
    /// cancelled query has no waiter and is dropped.
    pub(super) async fn exchange(&self, query: &[u8], limit: Duration) -> Result<Vec<u8>, EncryptedDnsError> {
        if query.len() < 2 {
            return Err(EncryptedDnsError::DnsParse("DNS query is shorter than its message ID".to_string()));
        }
        // Checked here so an oversized query fails alone instead of breaking the shared stream.
        if u16::try_from(query.len()).is_err() {
            return Err(EncryptedDnsError::Request("DNS payload is too large for TCP framing".to_string()));
        }
        let (id, receiver) = self.register(u16::from_be_bytes([query[0], query[1]]))?;
        let _waiter = WaiterGuard { connection: self, id };
        let sent_at = std::sync::OnceLock::new();
        match timeout(limit, self.send_and_wait(query, id, receiver, &sent_at)).await {
            Ok(Ok(response)) => {
                let mut response = response.to_vec();
                response[..2].copy_from_slice(&query[..2]);
                // A reply with our wire ID for another question means the stream is out of step.
                ensure_response_matches_query(query, &response).inspect_err(|_| self.mark_broken())?;
                Ok(response)
            }
            Ok(Err(error)) => {
                self.mark_broken();
                Err(error)
            }
            Err(_) => {
                // Nothing read since the query left: the connection is dead, not just this query lost.
                if sent_at.get().is_none_or(|sent_at| self.activity().last_read <= *sent_at) {
                    self.mark_broken();
                }
                Err(EncryptedDnsError::Request("DNS-over-TLS exchange timed out".to_string()))
            }
        }
    }

    /// # Cancel safety
    ///
    /// cancel-safe: see [`Self::exchange`].
    async fn send_and_wait(
        &self,
        query: &[u8],
        id: u16,
        mut receiver: oneshot::Receiver<BytesMut>,
        sent_at: &std::sync::OnceLock<Instant>,
    ) -> Result<BytesMut, EncryptedDnsError> {
        let mut wire = BytesMut::from(query);
        wire[..2].copy_from_slice(&id.to_be_bytes());
        self.writer.lock().await.send(wire.freeze()).await.map_err(request_error)?;
        self.touch(false);
        let _ = sent_at.set(Instant::now());
        tokio::select! {
            biased;
            reply = &mut receiver => reply.map_err(|_| connection_closed()),
            reader = self.reader.lock() => self.read_until_own_reply(reader, &mut receiver).await,
        }
    }

    /// Reads frames for every waiter until this caller's reply arrives.
    ///
    /// # Cancel safety
    ///
    /// cancel-safe: `FramedRead::next` keeps a partial frame in the shared buffer.
    async fn read_until_own_reply(
        &self,
        mut reader: AsyncMutexGuard<'_, Reader>,
        receiver: &mut oneshot::Receiver<BytesMut>,
    ) -> Result<BytesMut, EncryptedDnsError> {
        loop {
            let frame = tokio::select! {
                biased;
                // Another task may have dispatched this reply or broken the connection.
                reply = &mut *receiver => return reply.map_err(|_| connection_closed()),
                frame = reader.next() => frame,
            };
            let frame = frame
                .ok_or_else(|| EncryptedDnsError::Request(STREAM_CLOSED_BEFORE_FRAME.to_string()))?
                .map_err(request_error)?;
            self.touch(true);
            if frame.len() < 2 {
                return Err(EncryptedDnsError::DnsParse("DNS-over-TLS frame is shorter than a message ID".to_string()));
            }
            let id = u16::from_be_bytes([frame[0], frame[1]]);
            let mut waiters = self.waiters();
            if let Some(waiter) = waiters.pending.remove(&id) {
                let _ = waiter.send(frame);
            } else if !waiters.abandoned.remove(&id) {
                // Not a late reply either: the stream is out of step.
                return Err(EncryptedDnsError::DnsParse("DNS response does not match the query sent".to_string()));
            }
        }
    }

    /// Reserves `preferred` (the client's own ID) when it is free on this connection, else another ID.
    fn register(&self, preferred: u16) -> Result<(u16, oneshot::Receiver<BytesMut>), EncryptedDnsError> {
        let mut guard = self.waiters();
        let waiters = &mut *guard;
        if self.broken.load(Ordering::Acquire) {
            return Err(connection_closed());
        }
        let candidates =
            std::iter::once(preferred).chain((0..=u16::MAX).map(|_| self.next_id.fetch_add(1, Ordering::Relaxed)));
        for id in candidates {
            // An abandoned ID may still get its late reply, so it is not reused.
            if waiters.abandoned.contains(&id) {
                continue;
            }
            if let Entry::Vacant(slot) = waiters.pending.entry(id) {
                let (sender, receiver) = oneshot::channel();
                slot.insert(sender);
                return Ok((id, receiver));
            }
        }
        Err(EncryptedDnsError::Request("DNS-over-TLS connection has no free message ID".to_string()))
    }

    fn mark_broken(&self) {
        let mut waiters = self.waiters();
        self.broken.store(true, Ordering::Release);
        // Dropping the senders wakes every waiter with an error; their replies may still arrive.
        let ids: Vec<u16> = waiters.pending.drain().map(|(id, _)| id).collect();
        waiters.abandoned.extend(ids);
    }

    fn touch(&self, read: bool) {
        let now = Instant::now();
        let mut activity = self.activity.lock().unwrap_or_else(PoisonError::into_inner);
        activity.last_use = now;
        if read {
            activity.last_read = now;
        }
    }

    fn activity(&self) -> Activity {
        *self.activity.lock().unwrap_or_else(PoisonError::into_inner)
    }

    fn waiters(&self) -> MutexGuard<'_, Waiters> {
        self.waiters.lock().unwrap_or_else(PoisonError::into_inner)
    }
}

struct WaiterGuard<'a> {
    connection: &'a DotConnection,
    id: u16,
}

impl Drop for WaiterGuard<'_> {
    fn drop(&mut self) {
        let mut waiters = self.connection.waiters();
        if waiters.pending.remove(&self.id).is_some() {
            waiters.abandoned.insert(self.id);
        }
    }
}

impl std::fmt::Debug for DotConnection {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("DotConnection").field("broken", &self.broken.load(Ordering::Relaxed)).finish_non_exhaustive()
    }
}

fn connection_closed() -> EncryptedDnsError {
    EncryptedDnsError::Request("DNS-over-TLS connection closed before the reply".to_string())
}

fn request_error(error: std::io::Error) -> EncryptedDnsError {
    EncryptedDnsError::Request(error.to_string())
}
