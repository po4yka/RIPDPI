use std::io;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll};

use tokio::io::{AsyncRead, AsyncWrite, DuplexStream, ReadBuf};

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
}

impl MieruStream {
    pub(crate) fn new(io: DuplexStream, tasks: Arc<OwnedTasks>, registration: Option<SessionRegistration>) -> Self {
        Self { io, tasks, registration }
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
        Pin::new(&mut self.io).poll_read(cx, buf)
    }
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
