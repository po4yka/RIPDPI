use super::{AnyTlsError, AnyTlsStream, Command, Frame, Outbound, Owner};
use std::io;
use std::pin::Pin;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::task::{Context, Poll};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, DuplexStream, ReadBuf};
use tokio::task::AbortHandle;

/// Application endpoint; the client's task group retains and joins its pump.
/// Dropping one endpoint aborts only that stream, preserving multiplexed peers.
pub struct AnyTlsIo {
    io: DuplexStream,
    abort: AbortHandle,
    /// Set by the pump before it ends without a peer FIN: the read side then
    /// ends with `ConnectionReset`, not a clean EOF.
    reset: Arc<AtomicBool>,
    _owner: Arc<Owner>,
}
impl AnyTlsStream {
    /// Registers the pump synchronously before returning the application endpoint.
    ///
    /// # Cancel safety
    /// Conversion has no await. Pump cancellation ends only this logical stream;
    /// partially forwarded writes are not resumed. The client retains its join.
    /// Uplink and downlink run concurrently, so a stall in one direction does
    /// not stop the other; the pump ends when either direction fails or the
    /// downlink ends.
    pub fn into_io(mut self) -> Result<AnyTlsIo, AnyTlsError> {
        let owner = self.owner.take().ok_or(AnyTlsError::SessionClosed)?;
        let tasks = Arc::clone(&self.session.tasks);
        let (io, relay) = tokio::io::duplex(65_536);
        let reset = Arc::new(AtomicBool::new(false));
        let pump_reset = Arc::clone(&reset);
        let abort = tasks.spawn(async move {
            let (mut app_read, mut app_write) = tokio::io::split(relay);
            let outbound = self.session.outbound.clone();
            let stream_id = self.stream_id;
            let leftover: Vec<u8> = self.read_buffer.drain(..).collect();
            let inbound = &mut self.inbound;
            let fin_queued = &mut self.fin_queued;
            let peer_fin = Arc::clone(&self.peer_fin);
            let uplink_reset = Arc::clone(&pump_reset);
            let uplink = async {
                let mut buffer = [0; 16 * 1024];
                loop {
                    match app_read.read(&mut buffer).await {
                        // Local EOF closes the stream, as the reference clients do;
                        // the downlink keeps delivering frames already in flight.
                        Ok(0) => {
                            let fin = Frame::control(Command::Fin, stream_id);
                            if outbound.send(Outbound::Batch(vec![fin])).await.is_err() {
                                uplink_reset.store(true, Ordering::Release);
                                return;
                            }
                            *fin_queued = true;
                            std::future::pending::<()>().await;
                        }
                        Ok(read) => {
                            let frame = Frame::with_data(Command::Psh, stream_id, buffer[..read].to_vec());
                            if outbound.send(Outbound::Batch(vec![frame])).await.is_err() {
                                uplink_reset.store(true, Ordering::Release);
                                return;
                            }
                        }
                        Err(_) => return,
                    }
                }
            };
            let downlink = async {
                if !leftover.is_empty() && app_write.write_all(&leftover).await.is_err() {
                    return;
                }
                while let Some(chunk) = inbound.recv().await {
                    if app_write.write_all(&chunk).await.is_err() {
                        return;
                    }
                }
                // The route closed: only a peer FIN is a clean end; a dead
                // carrier must not look like a complete response.
                if !peer_fin.load(Ordering::Acquire) {
                    pump_reset.store(true, Ordering::Release);
                }
            };
            tokio::select! {
                () = uplink => {}
                () = downlink => {}
            }
        })?;
        Ok(AnyTlsIo { io, abort, reset, _owner: owner })
    }
}
impl Drop for AnyTlsIo {
    fn drop(&mut self) {
        self.abort.abort();
    }
}
impl AsyncRead for AnyTlsIo {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        let before = buf.filled().len();
        let poll = Pin::new(&mut self.io).poll_read(cx, buf);
        if matches!(poll, Poll::Ready(Ok(()))) && buf.filled().len() == before && self.reset.load(Ordering::Acquire) {
            return Poll::Ready(Err(io::Error::new(io::ErrorKind::ConnectionReset, "AnyTLS session closed")));
        }
        poll
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
