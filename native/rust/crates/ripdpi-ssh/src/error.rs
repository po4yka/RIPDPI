use std::io;

use thiserror::Error;

/// Errors surfaced by the SSH outbound engine.
#[derive(Debug, Error)]
pub enum SshError {
    /// The host is empty. SSH always dials a named host, so a blank host is
    /// rejected before any I/O.
    #[error("SSH host must not be empty")]
    EmptyHost,
    /// The server port is outside the valid `1..=65535` range.
    #[error("SSH server port must be in 1..=65535")]
    InvalidPort,
    /// The username is empty. SSH always authenticates against a named account,
    /// so a blank username is rejected before any I/O.
    #[error("SSH username must not be empty")]
    EmptyUsername,
    /// No usable authentication material was supplied (empty password, empty
    /// private-key PEM).
    #[error("SSH authentication material is missing")]
    MissingAuth,
    /// A strict host-key policy's pinned fingerprint did not match the
    /// fingerprint the server presented. The connection is aborted rather than
    /// trusted: `expected` is the pinned value, `got` is what the server sent.
    #[error("SSH host-key mismatch: expected `{expected}`, got `{got}`")]
    HostKeyMismatch {
        /// The fingerprint the policy pinned.
        expected: String,
        /// The fingerprint the server actually presented.
        got: String,
    },
    /// A TOFU policy with no pinned fingerprint observed the server's host key
    /// for the first time. The inner string is the presented fingerprint, which
    /// the UI surfaces so the user can explicitly accept or reject it — the
    /// engine never silently trusts a first-use key.
    #[error("SSH host key is untrusted on first use: {0}")]
    HostKeyUntrusted(String),
    /// The server rejected the supplied credentials.
    #[error("SSH authentication failed")]
    AuthFailed,
    #[error("SSH worker cleanup failed")]
    CleanupFailed,
    /// Underlying I/O failure.
    #[error("I/O error: {0}")]
    Io(#[from] io::Error),
    /// A lower-level SSH protocol failure. The inner string is the rendered
    /// cause; secrets never reach this path.
    #[error("SSH protocol error: {0}")]
    Ssh(String),
}

pub type Result<T> = std::result::Result<T, SshError>;
