use std::io;

use thiserror::Error;

/// Errors surfaced by the Trojan-Go outbound foundation.
///
/// The SMUX / WebSocket wire engine is intentionally stubbed in this foundation
/// build, so any attempt to drive a live connection through a *valid* config
/// terminates in [`TrojanGoError::Unimplemented`] rather than fabricating a
/// placeholder handshake or password hash.
#[derive(Debug, Error)]
pub enum TrojanGoError {
    /// The server port is outside the valid `1..=65535` range.
    #[error("Trojan-Go server port must be in 1..=65535")]
    InvalidPort,
    /// The password is empty. Trojan-Go authenticates with a non-empty
    /// SHA-224(password) hash, so a blank password is rejected before any I/O.
    #[error("Trojan-Go password must not be empty")]
    EmptyPassword,
    /// An unrecognised multiplexing mode token was supplied. The inner string is
    /// the rejected token.
    #[error("unsupported Trojan-Go mux mode `{0}`; only off and smux_v1 are supported")]
    UnsupportedMux(String),
    /// An unrecognised inner-protocol cipher token was supplied. The inner string
    /// is the rejected token.
    #[error("unsupported Trojan-Go inner cipher `{0}`")]
    UnsupportedInnerCipher(String),
    /// The Trojan-Go SMUX / WebSocket wire engine is not implemented in this
    /// foundation build.
    #[error("Trojan-Go SMUX/WebSocket wire engine is not implemented in this foundation build")]
    Unimplemented,
    /// Underlying I/O failure.
    #[error("I/O error: {0}")]
    Io(#[from] io::Error),
}

pub type Result<T> = std::result::Result<T, TrojanGoError>;
