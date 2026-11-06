use crate::telemetry::RelayTelemetryState;
use std::io;
use std::pin::Pin;
use std::task::{Context, Poll};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};

pub(super) struct CountedIo<'a, S> {
    pub(super) stream: S,
    pub(super) telemetry: &'a RelayTelemetryState,
}
impl<S: AsyncRead + Unpin> AsyncRead for CountedIo<'_, S> {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.stream).poll_read(cx, buf)
    }
}
impl<S: AsyncWrite + Unpin> AsyncWrite for CountedIo<'_, S> {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        let result = Pin::new(&mut self.stream).poll_write(cx, buf);
        if let Poll::Ready(Ok(bytes)) = result {
            self.telemetry.record_output_bytes(bytes);
        }
        result
    }
    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.stream).poll_flush(cx)
    }
    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.stream).poll_shutdown(cx)
    }
}
