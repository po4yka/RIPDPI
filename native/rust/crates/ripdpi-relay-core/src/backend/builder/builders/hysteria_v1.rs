use std::io;

use crate::backend::builder::builders::common::to_io_error;
use crate::backend::builder::BuildContext;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayBackendConfig, ResolvedRelayRuntimeConfig};
use crate::protocols::HysteriaV1SessionFactory;

/// Build the legacy Hysteria v1 relay backend.
///
/// Hysteria v1 is TCP-only in this foundation and non-reusable. The custom
/// QUIC-framing v1 wire engine, authentication, and bandwidth-based congestion
/// controller in `ripdpi-hysteria-v1` are stubbed, so the built backend
/// validates config and fails session creation with `Unimplemented` rather than
/// carrying traffic.
pub(crate) fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let RelayBackendConfig::HysteriaV1(hysteria) = &config.backend else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "expected Hysteria v1 config"));
    };
    let port = u16::try_from(config.common.server_port)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "Hysteria v1 server port must fit u16"))?;
    let auth_payload = hysteria
        .auth_payload
        .clone()
        .filter(|value| !value.trim().is_empty())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing Hysteria v1 auth payload"))?;
    let auth_type = ripdpi_relay_tls_transports::HysteriaV1AuthType::parse(&hysteria.auth_type).map_err(to_io_error)?;
    let protocol = ripdpi_relay_tls_transports::HysteriaV1Protocol::parse(&hysteria.protocol).map_err(to_io_error)?;
    let up_mbps = u32::try_from(hysteria.up_mbps)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "Hysteria v1 up_mbps must be a non-negative u32"))?;
    let down_mbps = u32::try_from(hysteria.down_mbps)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "Hysteria v1 down_mbps must be a non-negative u32"))?;

    let client_config = ripdpi_relay_tls_transports::HysteriaV1Config {
        server: config.common.server.clone(),
        port,
        auth_type,
        auth_payload,
        obfuscation: hysteria.obfuscation.clone().filter(|value| !value.is_empty()),
        protocol,
        up_mbps,
        down_mbps,
        sni: hysteria.sni.clone().filter(|value| !value.is_empty()),
        alpn: hysteria.alpn.clone().filter(|value| !value.is_empty()),
    };
    client_config.validate().map_err(to_io_error)?;

    Ok(RelayBackend::HysteriaV1(PooledRelayBackend::new(
        HysteriaV1SessionFactory { config: client_config },
        context.pool_config,
        None,
    )))
}
