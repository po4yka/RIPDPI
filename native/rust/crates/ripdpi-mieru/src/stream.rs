use std::io;
use std::pin::Pin;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::task::{Context, Poll};

use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt, DuplexStream, ReadBuf};

use crate::Result;
use crate::mux::SessionRegistration;
use crate::owned_tasks::OwnedTasks;

/// A logical Mieru stream that owns its I/O workers. Dropping it aborts them;
/// call [`Self::close`] to also observe their completion before releasing a
/// surrounding runtime. A multiplexed parent retains join ownership as well.
pub struct MieruStream {
    io: DuplexStream,
    tasks: Arc<OwnedTasks>,
    registration: Option<SessionRegistration>,
    /// Set by the inbound pump when the stream ended without the server
    /// closing the session (see [`end_inbound`]).
    inbound_failed: Arc<AtomicBool>,
}

impl MieruStream {
    pub(crate) fn new(
        io: DuplexStream,
        tasks: Arc<OwnedTasks>,
        registration: Option<SessionRegistration>,
        inbound_failed: Arc<AtomicBool>,
    ) -> Self {
        Self { io, tasks, registration, inbound_failed }
    }

    /// # Cancel safety
    /// Cancel-safe: the task group retains every unjoined handle for a retry.
    /// This is a full close, unlike AsyncWrite::shutdown's write half-close.
    pub async fn close(&mut self) -> Result<()> {
        self.registration.take();
        self.tasks.close().await
    }
}

impl std::fmt::Debug for MieruStream {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.debug_struct("MieruStream").finish_non_exhaustive()
    }
}

impl Drop for MieruStream {
    fn drop(&mut self) {
        self.registration.take();
        self.tasks.abort();
    }
}

impl AsyncRead for MieruStream {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        let filled = buf.filled().len();
        let poll = Pin::new(&mut self.io).poll_read(cx, buf);
        // Ordering: Acquire pairs with the Release store in `end_inbound`,
        // which comes before the duplex shutdown this EOF reports.
        let eof = matches!(poll, Poll::Ready(Ok(()))) && buf.filled().len() == filled && buf.remaining() > 0;
        if eof && self.inbound_failed.load(Ordering::Acquire) {
            return Poll::Ready(Err(io::Error::new(
                io::ErrorKind::ConnectionReset,
                "Mieru stream ended without a close from the server",
            )));
        }
        poll
    }
}

/// Ends the caller's read direction. After the server closed the session the
/// caller reads a clean EOF; otherwise (carrier error or EOF, AEAD failure, an
/// overflowed mailbox) it reads the data already delivered, then a
/// `ConnectionReset` error, so a truncated transfer is not taken as complete.
///
/// # Cancel safety
///
/// cancel-safe: the flag is set before the only await, and a cancelled
/// shutdown leaves the duplex for the stream owner to drop.
pub(crate) async fn end_inbound<W>(writer: &mut W, closed_by_server: bool, failed: &AtomicBool)
where
    W: AsyncWrite + Unpin,
{
    if !closed_by_server {
        // Ordering: see `MieruStream::poll_read`.
        failed.store(true, Ordering::Release);
    }
    let _ = writer.shutdown().await;
}

impl AsyncWrite for MieruStream {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, bytes: &[u8]) -> Poll<io::Result<usize>> {
        Pin::new(&mut self.io).poll_write(cx, bytes)
    }
    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.io).poll_flush(cx)
    }
    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.io).poll_shutdown(cx)
    }
}
