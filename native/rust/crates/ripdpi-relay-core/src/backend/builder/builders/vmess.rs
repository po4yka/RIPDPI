use std::io;

use crate::backend::builder::BuildContext;
use crate::backend::builder::builders::common::to_io_error;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayBackendConfig, ResolvedRelayRuntimeConfig};
use crate::protocols::VmessSessionFactory;

/// Build the VMess relay backend.
///
/// VMess is TCP-only and non-reusable. The AEAD wire engine in `ripdpi-vmess`
/// is stubbed in this foundation, so the built backend validates config and
/// fails session creation with `Unimplemented` rather than carrying traffic.
pub(crate) fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let RelayBackendConfig::Vmess(vmess) = &config.backend else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "expected VMess config"));
    };
    let port = u16::try_from(config.common.server_port)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "VMess server port must fit u16"))?;
    let uuid = vmess
        .uuid
        .clone()
        .filter(|value| !value.trim().is_empty())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing VMess uuid"))?;
    let security = ripdpi_relay_tls_transports::VmessSecurity::parse(&vmess.security).map_err(to_io_error)?;
    let transport = ripdpi_relay_tls_transports::VmessTransport::parse(&vmess.transport).map_err(to_io_error)?;

    let client_config = ripdpi_relay_tls_transports::VmessConfig {
        server: config.common.server.clone(),
        port,
        uuid,
        alter_id: 0,
        security,
        transport,
        ws_path: optional(&vmess.ws_path),
        ws_host: optional(&vmess.ws_host),
        grpc_service: optional(&vmess.grpc_service),
        h2_path: optional(&vmess.h2_path),
        h2_host: optional(&vmess.h2_host),
    };
    client_config.validate().map_err(to_io_error)?;

    Ok(RelayBackend::Vmess(PooledRelayBackend::new(
        VmessSessionFactory { config: client_config },
        context.pool_config,
        None,
    )))
}

fn optional(value: &Option<String>) -> Option<String> {
    value.as_ref().map(|inner| inner.trim().to_owned()).filter(|inner| !inner.is_empty())
}
