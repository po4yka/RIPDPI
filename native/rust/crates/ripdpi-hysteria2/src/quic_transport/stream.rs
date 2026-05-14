//! Composable bi-directional QUIC stream surface.
//!
//! [`QuicTransport`] wraps a `quinn::Connection` and hands out
//! `AsyncRead + AsyncWrite` bi-directional streams via
//! [`QuicTransport::open_bi`]. This is the "QUIC bi-directional stream
//! surface" the epic asks for: an outbound protocol (VLESS-QUIC, VMess-QUIC,
//! Hysteria2's own TCP-over-QUIC) opens a logical stream and layers its
//! framing on top, without re-implementing the `quinn` connection setup.

use std::io;
use std::pin::Pin;
use std::task::{Context, Poll};

use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};

/// A composable QUIC transport over an established `quinn::Connection`.
///
/// The connection itself is built once (via the shared
/// [`super::endpoint::build_quic_endpoint`] factory and a
/// `quinn::Endpoint::connect`); this type then multiplexes logical
/// bi-directional streams over it. It is `Clone` because `quinn::Connection`
/// is a cheap handle -- several outbound flows can share one QUIC connection.
#[derive(Clone)]
pub struct QuicTransport {
    connection: quinn::Connection,
}

impl QuicTransport {
    /// Adopt an already-established `quinn::Connection`.
    pub fn new(connection: quinn::Connection) -> Self {
        Self { connection }
    }

    /// Open a fresh bi-directional QUIC stream, returning an
    /// `AsyncRead + AsyncWrite` byte surface ([`QuicBiStream`]).
    pub async fn open_bi(&self) -> Result<QuicBiStream, quinn::ConnectionError> {
        let (send, recv) = self.connection.open_bi().await?;
        Ok(QuicBiStream { send, recv })
    }

    /// Accept the next inbound bi-directional QUIC stream the peer opens.
    pub async fn accept_bi(&self) -> Result<QuicBiStream, quinn::ConnectionError> {
        let (send, recv) = self.connection.accept_bi().await?;
        Ok(QuicBiStream { send, recv })
    }

    /// The largest QUIC datagram this connection's peer will accept, or
    /// `None` when the peer did not advertise datagram support.
    pub fn max_datagram_size(&self) -> Option<usize> {
        self.connection.max_datagram_size()
    }

    /// Borrow the underlying `quinn::Connection` (for datagram transports or
    /// connection-level operations like migration).
    pub fn connection(&self) -> &quinn::Connection {
        &self.connection
    }

    /// Consume the transport and return the underlying `quinn::Connection`.
    pub fn into_connection(self) -> quinn::Connection {
        self.connection
    }
}

/// One bi-directional QUIC stream as an `AsyncRead + AsyncWrite` byte surface.
///
/// Reads drain the `quinn::RecvStream`; writes feed the `quinn::SendStream`.
/// `poll_shutdown` finishes the send side cleanly. This mirrors the existing
/// `crate::tcp::DuplexStream`, but it is `pub` and protocol-agnostic so any
/// outbound can layer its own framing on it.
pub struct QuicBiStream {
    send: quinn::SendStream,
    recv: quinn::RecvStream,
}

impl QuicBiStream {
    /// Wrap a raw `quinn` send/recv stream pair.
    pub fn from_streams(send: quinn::SendStream, recv: quinn::RecvStream) -> Self {
        Self { send, recv }
    }

    /// Split the bi-stream into its independent send and receive halves.
    pub fn into_halves(self) -> (quinn::SendStream, quinn::RecvStream) {
        (self.send, self.recv)
    }
}

impl AsyncRead for QuicBiStream {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.recv).poll_read(cx, buf)
    }
}

impl AsyncWrite for QuicBiStream {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        Pin::new(&mut self.send).poll_write(cx, buf).map_err(io::Error::other)
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.send).poll_flush(cx)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.send).poll_shutdown(cx)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Compile-time guard: [`QuicBiStream`] satisfies the async-I/O bounds an
    /// outbound crate composes its framing over, exactly like
    /// `crate::tcp::DuplexStream` does today.
    #[test]
    fn quic_bi_stream_is_async_read_write() {
        fn _assert_bounds<S: AsyncRead + AsyncWrite + Unpin>() {}
        _assert_bounds::<QuicBiStream>();
    }

    /// Compile-time guard: [`QuicTransport`] is a cheap `Clone` handle, so
    /// several outbound flows can share one QUIC connection.
    #[test]
    fn quic_transport_is_clone() {
        fn _assert_clone<T: Clone>() {}
        _assert_clone::<QuicTransport>();
    }
}
