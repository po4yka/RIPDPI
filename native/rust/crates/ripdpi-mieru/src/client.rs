use tokio::io::DuplexStream;

use crate::config::MieruConfig;
use crate::error::{MieruError, Result};

/// A Mieru outbound client.
///
/// In this foundation build the client carries the validated config but the
/// custom UDP/TCP session and replay-resistant handshake are stubbed: every
/// connect path returns [`MieruError::Unimplemented`].
#[derive(Debug)]
pub struct MieruClient {
    #[allow(dead_code)] // retained for the real handshake; see connect() TODO.
    config: MieruConfig,
}

/// Validate `config` and (eventually) establish a Mieru control connection.
///
/// The configuration is fully validated here (port range, non-empty
/// username/password, MTU bounds), so a malformed profile fails loudly. Once
/// validation passes the call returns [`MieruError::Unimplemented`] because the
/// custom UDP/TCP session and the replay-resistant handshake are intentionally
/// stubbed in this foundation.
pub async fn connect(config: &MieruConfig) -> Result<MieruClient> {
    config.validate()?;
    // TODO(outbound-mieru): real session handshake + replay protection (clock-synced via network-time source, NOT System time) — tracking: epic-extended-outbound-protocol-support
    Err(MieruError::Unimplemented)
}

impl MieruClient {
    /// Open a Mieru-tunnelled TCP stream to `target`.
    ///
    /// Stubbed pending the real custom UDP/TCP session and replay-resistant
    /// handshake; returns [`MieruError::Unimplemented`].
    pub async fn tcp_connect(&self, _target: &str) -> Result<DuplexStream> {
        // TODO(outbound-mieru): real session handshake + replay protection (clock-synced via network-time source, NOT System time) — tracking: epic-extended-outbound-protocol-support
        Err(MieruError::Unimplemented)
    }
}
