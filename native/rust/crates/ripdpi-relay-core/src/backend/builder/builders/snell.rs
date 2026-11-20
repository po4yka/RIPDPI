use std::io;

use crate::backend::builder::BuildContext;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayBackendConfig, ResolvedRelayRuntimeConfig};
use crate::protocols::SnellSessionFactory;

pub(crate) fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let RelayBackendConfig::Snell(snell) = &config.backend else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "expected Snell config"));
    };
    let server_port = u16::try_from(config.common.server_port)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "Snell server port must fit u16"))?;
    let psk = snell
        .psk
        .clone()
        .filter(|psk| !psk.is_empty())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "Snell pre-shared key is required"))?;
    let factory = SnellSessionFactory::new(
        config.common.server.clone(),
        server_port,
        psk,
        snell.user_key.clone().unwrap_or_default(),
        context.outbound_bind_ip,
        context.socket_protection,
    )?;
    Ok(RelayBackend::Snell(PooledRelayBackend::new(factory, context.pool_config, None)))
}
