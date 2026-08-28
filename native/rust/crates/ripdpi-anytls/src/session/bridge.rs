use super::{AnyTlsError, AnyTlsStream, Owner};
use std::io;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, DuplexStream, ReadBuf};
use tokio::task::AbortHandle;

/// Application endpoint; the client's task group retains and joins its pump.
/// Dropping one endpoint aborts only that stream, preserving multiplexed peers.
pub struct AnyTlsIo {
    io: DuplexStream,
    abort: AbortHandle,
    _owner: Arc<Owner>,
}
impl AnyTlsStream {
    /// Registers the pump synchronously before returning the application endpoint.
    ///
    /// # Cancel safety
    /// Conversion has no await. Pump cancellation ends only this logical stream;
    /// partially forwarded writes are not resumed. The client retains its join.
    /// The select reads are cancel-safe; full writes run after branch selection.
    pub fn into_io(mut self) -> Result<AnyTlsIo, AnyTlsError> {
        let owner = self.owner.take().ok_or(AnyTlsError::SessionClosed)?;
        let tasks = Arc::clone(&self.tasks);
        let (io, relay) = tokio::io::duplex(65_536);
        let abort = tasks.spawn(async move {
            let (mut app_read, mut app_write) = tokio::io::split(relay);
            let mut buffer = [0; 16 * 1024];
            let mut sending = true;
            loop {
                tokio::select! {
                    read = app_read.read(&mut buffer), if sending => {
                        match read {
                            Ok(0) => {
                                if self.close().await.is_err() { return; }
                                sending = false;
                            }
                            Ok(read) => if self.write_all(&buffer[..read]).await.is_err() { return; },
                            Err(_) => return,
                        }
                    }
                    chunk = self.read_chunk() => {
                        let Ok(chunk) = chunk else { return; };
                        if app_write.write_all(&chunk).await.is_err() { return; }
                    }
                }
            }
        })?;
        Ok(AnyTlsIo { io, abort, _owner: owner })
    }
}
impl Drop for AnyTlsIo {
    fn drop(&mut self) {
        self.abort.abort();
    }
}
impl AsyncRead for AnyTlsIo {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.io).poll_read(cx, buf)
    }
}
impl AsyncWrite for AnyTlsIo {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        Pin::new(&mut self.io).poll_write(cx, buf)
    }
    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.io).poll_flush(cx)
    }
    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.io).poll_shutdown(cx)
    }
}
