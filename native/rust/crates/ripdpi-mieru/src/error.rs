use std::io;

use thiserror::Error;

/// Errors surfaced by the Mieru outbound foundation.
///
/// The custom UDP/TCP session and the replay-resistant handshake are
/// intentionally stubbed in this foundation build, so any attempt to drive a
/// live connection through a *valid* config terminates in
/// [`MieruError::Unimplemented`] rather than fabricating a placeholder
/// handshake.
#[derive(Debug, Error)]
pub enum MieruError {
    /// The server port is outside the valid `1..=65535` range.
    #[error("Mieru server port must be in 1..=65535")]
    InvalidPort,
    /// The username is empty. Mieru authenticates with a username/password
    /// pair, so a blank username is rejected before any I/O.
    #[error("Mieru username must not be empty")]
    EmptyUsername,
    /// The password is empty. Mieru authenticates with a username/password
    /// pair, so a blank password is rejected before any I/O.
    #[error("Mieru password must not be empty")]
    EmptyPassword,
    /// The MTU is outside the supported `1280..=1500` range.
    #[error("Mieru MTU must be in 1280..=1500")]
    InvalidMtu,
    /// An unrecognised transport-protocol token was supplied. The inner string
    /// is the rejected token.
    #[error("unsupported Mieru protocol `{0}`; only tcp and udp are supported")]
    UnsupportedProtocol(String),
    /// An unrecognised multiplexing-level token was supplied. The inner string
    /// is the rejected token.
    #[error("unsupported Mieru multiplexing `{0}`; only off, low, middle and high are supported")]
    UnsupportedMultiplexing(String),
    /// The Mieru custom UDP/TCP wire engine is not implemented in this
    /// foundation build.
    #[error("Mieru custom UDP/TCP wire engine is not implemented in this foundation build")]
    Unimplemented,
    /// Underlying I/O failure.
    #[error("I/O error: {0}")]
    Io(#[from] io::Error),
}

pub type Result<T> = std::result::Result<T, MieruError>;
