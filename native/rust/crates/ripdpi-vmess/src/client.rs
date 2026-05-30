use tokio::io::DuplexStream;

use crate::config::VmessConfig;
use crate::error::{Result, VmessError};

/// A VMess outbound client.
///
/// In this foundation build the client carries the validated config but the
/// AEAD wire engine is stubbed: every connect path returns
/// [`VmessError::Unimplemented`].
#[derive(Debug)]
pub struct VmessClient {
    #[allow(dead_code)] // retained for the real handshake; see connect() TODO.
    config: VmessConfig,
}

/// Validate `config` and (eventually) establish a VMess control connection.
///
/// The configuration is fully validated here (port range, RFC 4122 UUID,
/// alterId-must-be-zero), so a malformed profile fails loudly. Once validation
/// passes the call returns [`VmessError::Unimplemented`] because the AEAD wire
/// engine is intentionally stubbed in this foundation.
pub async fn connect(config: &VmessConfig) -> Result<VmessClient> {
    config.validate()?;
    // TODO(outbound-vmess): real AEAD handshake — tracking: epic-extended-outbound-protocol-support
    Err(VmessError::Unimplemented)
}

impl VmessClient {
    /// Open a VMess-tunnelled TCP stream to `target`.
    ///
    /// Stubbed pending the real AEAD engine; returns [`VmessError::Unimplemented`].
    pub async fn tcp_connect(&self, _target: &str) -> Result<DuplexStream> {
        // TODO(outbound-vmess): real AEAD handshake — tracking: epic-extended-outbound-protocol-support
        Err(VmessError::Unimplemented)
    }
}
