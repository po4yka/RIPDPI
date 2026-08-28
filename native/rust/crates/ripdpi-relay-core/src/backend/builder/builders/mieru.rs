use std::io;

use crate::backend::builder::BuildContext;
use crate::backend::builder::builders::common::to_io_error;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayBackendConfig, ResolvedRelayRuntimeConfig};
use crate::protocols::MieruSessionFactory;

/// Build the Mieru relay backend.
///
/// Mieru is TCP-only (the UDP/KCP carrier is out of scope). With multiplexing
/// `off` it is non-reusable (one relayed stream per session); `low`/`middle`/
/// `high` make it a reusable carrier that multiplexes many sub-sessions. The
/// `ripdpi-mieru` engine implements the real XChaCha20-Poly1305 time-rotated-key
/// wire protocol, open-session handshake, and in-tunnel SOCKS5 connect; the
/// session factory dials the carrier and runs it. The TCP carrier is covered by the pinned upstream loopback oracle.
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
        MieruSessionFactory::new(client_config, context.socket_protection),
        context.pool_config,
        None,
    )))
}
