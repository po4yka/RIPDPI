// SPDX-License-Identifier: MIT
//
//! WireGuard-over-WebSocket carrier framing for RIPDPI.
//!
//! # Why this crate exists
//!
//! WireGuard's UDP fingerprint is one of the easiest DPI signatures in the
//! wild: a fixed-size handshake initiation whose first byte is the message
//! type (`0x01`). Russian TSPU and similar middleboxes drop these inline,
//! and the well-known WG UDP port range is itself a coarse selector.
//!
//! This crate wraps each WireGuard UDP datagram inside a *binary* WebSocket
//! message carried over a single WSS/TCP stream to port 443. To a DPI box the
//! flow then looks like an ordinary HTTP/1.1 `Upgrade` to a long-lived
//! WebSocket. Combined with the AmneziaWG junk-packet prefix (see
//! [`JunkPrefix`]) the residual "first packet shape" heuristic is defeated as
//! well.
//!
//! # Scope of this crate
//!
//! This is the **carrier-framing** layer only. It deliberately does *not*:
//!
//! * implement the WireGuard Noise handshake or transport crypto — that lives
//!   in `ripdpi-warp-core` (`boringtun` + `amneziawg.rs`);
//! * implement an exit server — the production client path opens a protected
//!   `wss://` endpoint and the peer terminator remains operator-provided.
//!
//! The public surface is intentionally tiny: [`frame_datagram`] /
//! [`unframe_message`] are the pure byte-level codec, [`WsCarrier`] drives them
//! over an established WebSocket, [`WssEndpoint`] validates production endpoint
//! semantics, and [`connect_wss_carrier`] creates the protected WSS connection.
//!
//! # `VpnService.protect` invariant
//!
//! Any **production** carrier socket — the `TcpStream` underneath the WSS stream
//! that this crate frames over — targets a non-loopback exit and MUST
//! be `protect_socket(fd)`-ed *before* `connect()` returns, exactly as for any
//! other RIPDPI outbound socket (see `.claude/rules/vpnservice-protect-invariant.md`).
//!
//! [`connect_protected_carrier`] is the seam that honours this: it takes an
//! injected [`CarrierSocketProtector`] (the JNI-backed `VpnService.protect`
//! shim in production, a fake in tests), runs it on the socket fd *before*
//! `connect()`, and fails closed — dropping the socket and propagating the
//! error — if the protector rejects the fd. The crate never reaches for a
//! process-global registry or JNI itself; the protector is always supplied by
//! the caller, mirroring `ripdpi-warp-core::platform::WarpSocketProtector`. The
//! `127.0.0.1`/`[::1]` loopbacks used by the tests are protect-exempt in
//! production, but the seam still invokes the injected protector unconditionally
//! so the fail-closed path is exercised.

use std::fmt;

use tokio_tungstenite::tungstenite::Message;

mod connect;
mod endpoint;

pub use connect::{CarrierSocketProtector, connect_protected_carrier};
pub use endpoint::{WssCarrier, WssCarrierStream, WssEndpoint, WssEndpointError, connect_wss_carrier};

/// Errors produced by the WireGuard-over-WebSocket carrier.
#[derive(Debug, thiserror::Error)]
pub enum WsCarrierError {
    /// The peer closed the WebSocket before a datagram was received.
    #[error("websocket stream closed by peer")]
    Closed,
    /// A non-binary WebSocket frame arrived where a WireGuard datagram was
    /// expected. WireGuard frames are always carried as binary messages; a
    /// text/ping/pong frame here is a protocol violation by the peer.
    #[error("expected a binary websocket frame, got a non-binary frame")]
    UnexpectedFrameType,
    /// The underlying WebSocket transport failed.
    #[error("websocket transport error: {0}")]
    Transport(#[from] tokio_tungstenite::tungstenite::Error),
}

/// Frame a single WireGuard UDP datagram as a binary WebSocket message.
///
/// This is the forward direction of the carrier codec. WireGuard datagrams are
/// self-delimiting (the receiver knows the message type and length from the
/// payload), so the datagram bytes are carried verbatim as the binary
/// message payload — no extra length prefix is needed because WebSocket frames
/// preserve message boundaries.
#[must_use]
pub fn frame_datagram(datagram: &[u8]) -> Message {
    Message::binary(datagram.to_vec())
}

/// Recover the WireGuard UDP datagram from an inbound WebSocket message.
///
/// This is the inverse of [`frame_datagram`]. `Close` maps to
/// [`WsCarrierError::Closed`]; control frames (`Ping`/`Pong`) and text frames
/// are rejected with [`WsCarrierError::UnexpectedFrameType`] because the
/// carrier only ever transports WireGuard datagrams as binary frames. The
/// caller is responsible for answering pings at the transport layer before
/// handing the message here if it chooses to surface them.
pub fn unframe_message(message: Message) -> Result<Vec<u8>, WsCarrierError> {
    match message {
        Message::Binary(payload) => Ok(payload.into()),
        Message::Close(_) => Err(WsCarrierError::Closed),
        Message::Text(_) | Message::Ping(_) | Message::Pong(_) | Message::Frame(_) => {
            Err(WsCarrierError::UnexpectedFrameType)
        }
    }
}

/// An AmneziaWG-style junk-packet prefix.
///
/// AmneziaWG defeats the "first packet shape" heuristic by emitting `Jc` junk
/// packets, each of a random size in `[Jmin, Jmax]`, *before* the real
/// handshake initiation. Over the WebSocket carrier each junk packet is a
/// binary frame of random bytes, indistinguishable on the wire from a framed
/// datagram.
///
/// This type is the *generation seam* for that behaviour: the full
/// `Jc`/`Jmin`/`Jmax`/`H1..H4`/`S1..S4` parameterisation and its validation
/// already live in `ripdpi-warp-core::amneziawg`; this carrier only needs the
/// junk-frame *count and size envelope* to interleave random binary frames
/// ahead of the first real datagram. Keeping the envelope here avoids a
/// dependency edge from the carrier crate to the crypto crate for the minimal
/// slice.
#[derive(Clone, Copy, PartialEq, Eq)]
pub struct JunkPrefix {
    count: u32,
    min_len: u16,
    max_len: u16,
}

impl JunkPrefix {
    /// Build a junk-prefix envelope.
    ///
    /// Returns `None` if `min_len > max_len` (the size range would be empty),
    /// mirroring the `JunkRangeInverted` validation in
    /// `ripdpi-warp-core::amneziawg`.
    #[must_use]
    pub fn new(count: u32, min_len: u16, max_len: u16) -> Option<Self> {
        if min_len > max_len {
            return None;
        }
        Some(Self { count, min_len, max_len })
    }

    /// Number of junk packets to emit before the first real datagram (`Jc`).
    #[must_use]
    pub fn count(&self) -> u32 {
        self.count
    }

    /// Generate the junk frames to send ahead of the real handshake.
    ///
    /// Each frame is a binary WebSocket message of a uniformly random length in
    /// `[min_len, max_len]` filled with CSPRNG bytes. The same `getrandom`
    /// source is used as the AmneziaWG path in `ripdpi-warp-core`.
    #[must_use]
    pub fn generate(&self) -> Vec<Message> {
        let mut frames = Vec::with_capacity(self.count as usize);
        for _ in 0..self.count {
            let len = self.random_len();
            let mut buf = vec![0u8; len];
            // getrandom never partially fills; an error here means the OS
            // CSPRNG is unavailable, which is unrecoverable for an obfuscation
            // layer that must not emit a predictable prefix.
            getrandom::fill(&mut buf).expect("getrandom failed");
            frames.push(Message::binary(buf));
        }
        frames
    }

    fn random_len(self) -> usize {
        let span = u32::from(self.max_len - self.min_len);
        let offset = if span == 0 { 0 } else { random_u32() % (span + 1) };
        usize::from(self.min_len) + offset as usize
    }
}

impl fmt::Debug for JunkPrefix {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        // Render the envelope, never the generated bytes.
        f.debug_struct("JunkPrefix")
            .field("count", &self.count)
            .field("min_len", &self.min_len)
            .field("max_len", &self.max_len)
            .finish()
    }
}

fn random_u32() -> u32 {
    let mut buf = [0u8; 4];
    getrandom::fill(&mut buf).expect("getrandom failed");
    u32::from_le_bytes(buf)
}

/// Drives WireGuard datagrams over an established WebSocket carrier.
///
/// `S` is any WebSocket stream — in production a TLS stream returned by
/// `tokio_tungstenite::client_async` over a *protected* `TcpStream`; in tests a
/// plain `127.0.0.1` TCP stream. The carrier owns no socket and opens no
/// connection, so the protect invariant is satisfied by construction at the
/// caller's seam.
pub struct WsCarrier<S> {
    stream: S,
}

impl<S> WsCarrier<S>
where
    S: futures::Sink<Message, Error = tokio_tungstenite::tungstenite::Error>
        + futures::Stream<Item = Result<Message, tokio_tungstenite::tungstenite::Error>>
        + Unpin,
{
    /// Wrap an established WebSocket stream as a WireGuard carrier.
    pub fn new(stream: S) -> Self {
        Self { stream }
    }

    /// Send one WireGuard UDP datagram across the carrier as a binary frame.
    ///
    // cancel-safe: a cancelled `send` may leave a partially-buffered frame in
    // the sink; the WebSocket message-boundary guarantee means a half-sent
    // binary frame is never delivered as a truncated datagram — the peer
    // either sees the whole frame or the stream errors/closes. The caller
    // must not assume the datagram was delivered if the future is dropped.
    pub async fn send_datagram(&mut self, datagram: &[u8]) -> Result<(), WsCarrierError> {
        use futures::SinkExt;
        self.stream.send(frame_datagram(datagram)).await?;
        Ok(())
    }

    /// Emit the AmneziaWG junk-packet prefix ahead of the real handshake.
    ///
    // cancel-safe: each junk frame is sent independently; cancellation drops at
    // most one in-flight junk frame, which is harmless (junk is discardable by
    // definition). No real datagram is sent here.
    pub async fn send_junk_prefix(&mut self, prefix: &JunkPrefix) -> Result<(), WsCarrierError> {
        use futures::SinkExt;
        for frame in prefix.generate() {
            self.stream.send(frame).await?;
        }
        Ok(())
    }

    /// Receive the next WireGuard UDP datagram from the carrier.
    ///
    /// Returns [`WsCarrierError::Closed`] when the peer closes the stream.
    ///
    // cancel-safe: a cancelled `recv` drops the in-progress read of the next
    // frame; because WebSocket frames are message-delimited, the next call
    // resumes at the following frame boundary with no datagram split across
    // the cancellation. No datagram is lost that the caller had already
    // observed.
    pub async fn recv_datagram(&mut self) -> Result<Vec<u8>, WsCarrierError> {
        use futures::StreamExt;
        match self.stream.next().await {
            Some(message) => unframe_message(message?),
            None => Err(WsCarrierError::Closed),
        }
    }

    /// Consume the carrier and return the underlying WebSocket stream.
    pub fn into_inner(self) -> S {
        self.stream
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio_tungstenite::tungstenite::Message;

    #[test]
    fn frame_then_unframe_is_byte_identical() {
        // A synthetic 148-byte WireGuard handshake initiation (type byte 0x01).
        let mut datagram = vec![0u8; 148];
        datagram[0] = 0x01;
        for (i, b) in datagram.iter_mut().enumerate().skip(1) {
            *b = (i % 251) as u8;
        }

        let framed = frame_datagram(&datagram);
        assert!(matches!(framed, Message::Binary(_)), "WG frames must be binary");

        let recovered = unframe_message(framed).expect("unframe binary");
        assert_eq!(recovered, datagram, "round-trip must be byte-identical");
    }

    #[test]
    fn unframe_rejects_non_binary_frames() {
        let err = unframe_message(Message::text("not a datagram")).expect_err("text rejected");
        assert!(matches!(err, WsCarrierError::UnexpectedFrameType));

        let err = unframe_message(Message::Ping(Vec::new().into())).expect_err("ping rejected");
        assert!(matches!(err, WsCarrierError::UnexpectedFrameType));
    }

    #[test]
    fn unframe_close_maps_to_closed() {
        let err = unframe_message(Message::Close(None)).expect_err("close is an error");
        assert!(matches!(err, WsCarrierError::Closed));
    }

    #[test]
    fn junk_prefix_rejects_inverted_range() {
        assert!(JunkPrefix::new(3, 100, 40).is_none(), "min_len > max_len must be rejected");
        assert!(JunkPrefix::new(0, 40, 40).is_some(), "equal bounds are valid");
    }

    #[test]
    fn junk_prefix_generates_count_frames_within_envelope() {
        let prefix = JunkPrefix::new(8, 16, 64).expect("valid envelope");
        let frames = prefix.generate();
        assert_eq!(frames.len(), 8, "Jc frames generated");
        for frame in frames {
            let Message::Binary(payload) = frame else {
                panic!("junk frames must be binary");
            };
            assert!((16..=64).contains(&payload.len()), "junk frame length {} outside [16, 64]", payload.len());
        }
    }
}
