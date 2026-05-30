use tokio::io::DuplexStream;

use crate::config::TrojanGoConfig;
use crate::error::{Result, TrojanGoError};

/// A Trojan-Go outbound client.
///
/// In this foundation build the client carries the validated config but the
/// SMUX / WebSocket wire engine is stubbed: every connect path returns
/// [`TrojanGoError::Unimplemented`].
#[derive(Debug)]
pub struct TrojanGoClient {
    #[allow(dead_code)] // retained for the real handshake; see connect() TODO.
    config: TrojanGoConfig,
}

/// Validate `config` and (eventually) establish a Trojan-Go control connection.
///
/// The configuration is fully validated here (port range, non-empty password),
/// so a malformed profile fails loudly. Once validation passes the call returns
/// [`TrojanGoError::Unimplemented`] because the SMUX / WebSocket wire engine and
/// the password-hash handshake are intentionally stubbed in this foundation.
pub async fn connect(config: &TrojanGoConfig) -> Result<TrojanGoClient> {
    config.validate()?;
    // TODO(outbound-trojan-go): real SMUX/WS handshake — tracking: epic-extended-outbound-protocol-support
    Err(TrojanGoError::Unimplemented)
}

impl TrojanGoClient {
    /// Open a Trojan-Go-tunnelled TCP stream to `target`.
    ///
    /// Stubbed pending the real SMUX / WebSocket engine; returns
    /// [`TrojanGoError::Unimplemented`].
    pub async fn tcp_connect(&self, _target: &str) -> Result<DuplexStream> {
        // TODO(outbound-trojan-go): real SMUX/WS handshake — tracking: epic-extended-outbound-protocol-support
        Err(TrojanGoError::Unimplemented)
    }
}
