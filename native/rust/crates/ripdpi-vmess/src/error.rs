use std::io;

use thiserror::Error;

/// Errors surfaced by the VMess outbound foundation.
///
/// The AEAD wire engine is intentionally stubbed in this foundation build, so
/// any attempt to drive a live connection through a *valid* config terminates
/// in [`VmessError::Unimplemented`] rather than fabricating placeholder crypto.
#[derive(Debug, Error)]
pub enum VmessError {
    /// A legacy / insecure VMess cipher was requested. VMess foundation here is
    /// AEAD-only; `auto`, `none`, `md5`, and any `aes-128-cfb`-class cipher are
    /// rejected by name. The inner string is the rejected security token.
    #[error("legacy VMess security `{0}` is rejected; only aes-128-gcm and chacha20-poly1305 are supported")]
    LegacySecurityRejected(String),
    /// A non-zero `alterId` was supplied. AlterId multiplexing is the removed
    /// pre-AEAD VMess mode and is unsupported.
    #[error("VMess alterId must be 0; legacy alterId multiplexing is unsupported")]
    AlterIdUnsupported,
    /// The UUID is not a well-formed RFC 4122 (8-4-4-4-12 hex) identifier.
    #[error("VMess uuid is not a valid RFC 4122 identifier")]
    InvalidUuid,
    /// The server port is outside the valid `1..=65535` range.
    #[error("VMess server port must be in 1..=65535")]
    InvalidPort,
    /// An unrecognised transport token was supplied.
    #[error("unsupported VMess transport `{0}`")]
    UnsupportedTransport(String),
    /// The VMess AEAD wire engine is not implemented in this foundation build.
    #[error("VMess AEAD wire engine is not implemented in this foundation build")]
    Unimplemented,
    /// Underlying I/O failure.
    #[error("I/O error: {0}")]
    Io(#[from] io::Error),
}

pub type Result<T> = std::result::Result<T, VmessError>;
