use std::io;
use std::pin::Pin;
use std::task::{Context, Poll};

use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::net::TcpStream;

/// Preserve raw TCP EOF as an error; BoringSSL handles authenticated close_notify separately.
#[derive(Debug)]
pub(crate) struct TlsTransport(TcpStream);

impl TlsTransport {
    pub(crate) fn new(stream: TcpStream) -> Self {
        Self(stream)
    }
}

impl AsyncRead for TlsTransport {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        if buf.remaining() == 0 {
            return Poll::Ready(Ok(()));
        }
        let filled = buf.filled().len();
        match Pin::new(&mut self.0).poll_read(cx, buf) {
            Poll::Ready(Ok(())) if buf.filled().len() == filled => Poll::Ready(Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "TLS transport closed without close_notify",
            ))),
            result => result,
        }
    }
}

impl AsyncWrite for TlsTransport {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        Pin::new(&mut self.0).poll_write(cx, buf)
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.0).poll_flush(cx)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.0).poll_shutdown(cx)
    }
}
