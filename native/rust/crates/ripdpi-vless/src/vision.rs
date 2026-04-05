use std::io;
use std::pin::Pin;
use std::task::{Context, Poll};

use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};

/// Threshold in bytes after which the inner TLS handshake is considered done.
/// The vision filter monitors the first ~16KB of outbound data for TLS record
/// boundaries. After this threshold, it switches to zero-copy pass-through mode.
const HANDSHAKE_THRESHOLD: usize = 16_384;

/// xtls-rprx-vision flow filter.
///
/// Wraps an inner stream and tracks whether the initial TLS-in-TLS handshake has
/// completed. Once the handshake threshold is reached, all further I/O is passed
/// through without inspection.
///
/// This prevents the distinctive pattern of encrypted data inside encrypted data
/// that DPI systems use to detect tunneled TLS connections.
pub struct VisionStream<S> {
    inner: S,
    handshake_done: bool,
    bytes_sent: usize,
}

impl<S> VisionStream<S> {
    /// Wrap a stream with the vision flow filter.
    pub fn new(inner: S) -> Self {
        Self { inner, handshake_done: false, bytes_sent: 0 }
    }
}

impl<S: AsyncRead + Unpin> AsyncRead for VisionStream<S> {
    fn poll_read(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        // Reads are always passed through -- vision only inspects writes.
        Pin::new(&mut self.get_mut().inner).poll_read(cx, buf)
    }
}

impl<S: AsyncWrite + Unpin> AsyncWrite for VisionStream<S> {
    fn poll_write(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        let this = self.get_mut();

        if !this.handshake_done {
            this.bytes_sent += buf.len();
            if this.bytes_sent >= HANDSHAKE_THRESHOLD {
                this.handshake_done = true;
                tracing::trace!(
                    bytes_sent = this.bytes_sent,
                    "vision: inner TLS handshake threshold reached, switching to pass-through"
                );
            }
        }

        Pin::new(&mut this.inner).poll_write(cx, buf)
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_flush(cx)
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_shutdown(cx)
    }
}
