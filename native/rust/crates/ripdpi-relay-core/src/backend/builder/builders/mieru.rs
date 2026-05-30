use std::io;

use crate::backend::builder::builders::common::to_io_error;
use crate::backend::builder::BuildContext;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayBackendConfig, ResolvedRelayRuntimeConfig};
use crate::protocols::MieruSessionFactory;

/// Build the Mieru relay backend.
///
/// Mieru is TCP-only in this foundation and non-reusable. The custom UDP/TCP
/// wire engine and replay-resistant handshake in `ripdpi-mieru` are stubbed, so
/// the built backend validates config and fails session creation with
/// `Unimplemented` rather than carrying traffic.
pub(crate) fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let RelayBackendConfig::Mieru(mieru) = &config.backend else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "expected Mieru config"));
    };
    let port = u16::try_from(config.common.server_port)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "Mieru server port must fit u16"))?;
    let username = mieru
        .username
        .clone()
        .filter(|value| !value.trim().is_empty())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing Mieru username"))?;
    let password = mieru
        .password
        .clone()
        .filter(|value| !value.trim().is_empty())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing Mieru password"))?;
    let protocol = ripdpi_relay_tls_transports::MieruProtocol::parse(&mieru.protocol).map_err(to_io_error)?;
    let multiplexing = ripdpi_relay_tls_transports::MieruMux::parse(&mieru.multiplexing).map_err(to_io_error)?;
    let mtu =
        u16::try_from(mieru.mtu).map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "Mieru MTU must fit u16"))?;

    let client_config = ripdpi_relay_tls_transports::MieruConfig {
        server: config.common.server.clone(),
        port,
        username,
        password,
        protocol,
        multiplexing,
        mtu,
    };
    client_config.validate().map_err(to_io_error)?;

    Ok(RelayBackend::Mieru(PooledRelayBackend::new(
        MieruSessionFactory { config: client_config },
        context.pool_config,
        None,
    )))
}
