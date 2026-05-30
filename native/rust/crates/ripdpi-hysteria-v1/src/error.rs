use std::io;

use thiserror::Error;

/// Errors surfaced by the legacy Hysteria v1 outbound foundation.
///
/// The custom QUIC-framing v1 handshake, authentication, and the
/// bandwidth-based congestion controller are intentionally stubbed in this
/// foundation build, so any attempt to drive a live connection through a
/// *valid* config terminates in [`HysteriaV1Error::Unimplemented`] rather than
/// fabricating a placeholder handshake.
#[derive(Debug, Error)]
pub enum HysteriaV1Error {
    /// The server port is outside the valid `1..=65535` range.
    #[error("Hysteria v1 server port must be in 1..=65535")]
    InvalidPort,
    /// The auth payload is empty. Hysteria v1 authenticates with an auth
    /// string/base64 payload, so a blank payload is rejected before any I/O.
    #[error("Hysteria v1 auth payload must not be empty")]
    EmptyAuth,
    /// The up/down bandwidth is zero. Unlike Hysteria2, the v1 brutal
    /// congestion controller REQUIRES non-zero up and down bandwidth.
    #[error("Hysteria v1 requires non-zero up_mbps and down_mbps")]
    InvalidBandwidth,
    /// An unrecognised auth-type token was supplied. The inner string is the
    /// rejected token.
    #[error("unsupported Hysteria v1 auth type `{0}`; only string and base64 are supported")]
    UnsupportedAuthType(String),
    /// An unrecognised transport-protocol token was supplied. The inner string
    /// is the rejected token.
    #[error("unsupported Hysteria v1 protocol `{0}`; only udp, wechat-video and faketcp are supported")]
    UnsupportedProtocol(String),
    /// The Hysteria v1 custom QUIC-framing wire engine is not implemented in
    /// this foundation build.
    #[error("Hysteria v1 custom QUIC-framing wire engine is not implemented in this foundation build")]
    Unimplemented,
    /// Underlying I/O failure.
    #[error("I/O error: {0}")]
    Io(#[from] io::Error),
}

pub type Result<T> = std::result::Result<T, HysteriaV1Error>;
