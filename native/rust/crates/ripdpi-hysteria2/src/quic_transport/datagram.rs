//! Composable QUIC datagram surface (CONNECT-UDP / unreliable datagrams).
//!
//! [`QuicDatagramTransport`] wraps a `quinn::Connection` and exposes its
//! unreliable-datagram channel: the substrate MASQUE's CONNECT-UDP and
//! Hysteria2's UDP relay both ride on. This is the "QUIC datagram surface"
//! the epic asks for, factored out so any UDP-carrying outbound shares one
//! datagram path instead of re-deriving it.

use bytes::Bytes;

use crate::error::{HysteriaError, Result};

/// A composable QUIC unreliable-datagram transport over an established
/// `quinn::Connection`.
///
/// Like [`super::stream::QuicTransport`] this is a cheap `Clone` handle: the
/// datagram channel is connection-wide, so several UDP flows can share it
/// (demultiplexing by their own session id, which is the caller's concern).
#[derive(Clone)]
pub struct QuicDatagramTransport {
    connection: quinn::Connection,
}

impl QuicDatagramTransport {
    /// Adopt an already-established `quinn::Connection`.
    pub fn new(connection: quinn::Connection) -> Self {
        Self { connection }
    }

    /// True when the peer advertised QUIC datagram support during the
    /// handshake. Callers must check this before [`send`](Self::send) --
    /// a peer that did not negotiate datagrams will reject every send.
    pub fn datagrams_supported(&self) -> bool {
        self.connection.max_datagram_size().is_some()
    }

    /// The largest datagram payload the peer will accept, or `None` when the
    /// peer did not advertise datagram support.
    pub fn max_datagram_size(&self) -> Option<usize> {
        self.connection.max_datagram_size()
    }

    /// Send one unreliable datagram. Fails with
    /// [`HysteriaError::QuicDatagram`] when datagrams are unsupported or the
    /// payload exceeds [`max_datagram_size`](Self::max_datagram_size).
    pub fn send(&self, payload: Bytes) -> Result<()> {
        self.connection.send_datagram(payload)?;
        Ok(())
    }

    /// Await the next inbound unreliable datagram from the peer.
    pub async fn recv(&self) -> Result<Bytes> {
        self.connection.read_datagram().await.map_err(HysteriaError::from)
    }

    /// Borrow the underlying `quinn::Connection`.
    pub fn connection(&self) -> &quinn::Connection {
        &self.connection
    }

    /// Consume the transport and return the underlying `quinn::Connection`.
    pub fn into_connection(self) -> quinn::Connection {
        self.connection
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Compile-time guard: [`QuicDatagramTransport`] is a cheap `Clone`
    /// handle so several UDP flows can share the connection's datagram
    /// channel.
    #[test]
    fn quic_datagram_transport_is_clone() {
        fn _assert_clone<T: Clone>() {}
        _assert_clone::<QuicDatagramTransport>();
    }
}
