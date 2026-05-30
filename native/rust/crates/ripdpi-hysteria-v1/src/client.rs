use tokio::io::DuplexStream;

use crate::config::HysteriaV1Config;
use crate::error::{HysteriaV1Error, Result};

/// A legacy Hysteria v1 outbound client.
///
/// In this foundation build the client carries the validated config but the
/// custom QUIC-framing v1 session, authentication, and bandwidth-based
/// congestion controller are stubbed: every connect path returns
/// [`HysteriaV1Error::Unimplemented`].
#[derive(Debug)]
pub struct HysteriaV1Client {
    #[allow(dead_code)] // retained for the real handshake; see connect() TODO.
    config: HysteriaV1Config,
}

/// Validate `config` and (eventually) establish a Hysteria v1 control
/// connection.
///
/// The configuration is fully validated here (port range, non-empty auth
/// payload, mandatory non-zero up/down bandwidth), so a malformed profile fails
/// loudly. Once validation passes the call returns
/// [`HysteriaV1Error::Unimplemented`] because the custom QUIC-framing v1
/// session, authentication, and bandwidth-based congestion controller are
/// intentionally stubbed in this foundation.
pub async fn connect(config: &HysteriaV1Config) -> Result<HysteriaV1Client> {
    config.validate()?;
    // TODO(outbound-hysteria-v1): real v1 QUIC framing + auth + bandwidth-based congestion — tracking: epic-extended-outbound-protocol-support
    Err(HysteriaV1Error::Unimplemented)
}

impl HysteriaV1Client {
    /// Open a Hysteria v1-tunnelled TCP stream to `target`.
    ///
    /// Stubbed pending the real custom QUIC-framing v1 session and
    /// bandwidth-based congestion controller; returns
    /// [`HysteriaV1Error::Unimplemented`].
    pub async fn tcp_connect(&self, _target: &str) -> Result<DuplexStream> {
        // TODO(outbound-hysteria-v1): real v1 QUIC framing + auth + bandwidth-based congestion — tracking: epic-extended-outbound-protocol-support
        Err(HysteriaV1Error::Unimplemented)
    }
}
