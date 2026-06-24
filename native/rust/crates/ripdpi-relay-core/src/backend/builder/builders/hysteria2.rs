use std::io;

use crate::backend::builder::BuildContext;
use crate::backend::builder::builders::common::to_io_error;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayBackendConfig, ResolvedRelayRuntimeConfig};
use crate::protocols::Hysteria2SessionFactory;

pub(crate) fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let RelayBackendConfig::Hysteria2(hysteria) = &config.backend else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "expected Hysteria2 config"));
    };
    let password = hysteria
        .password
        .as_ref()
        .filter(|value| !value.trim().is_empty())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing Hysteria2 password"))?;
    let mut client_config = ripdpi_hysteria2::Config::from_url(&format!(
        "hysteria2://{password}@{}:{}/?sni={}",
        config.common.server, config.common.server_port, config.common.server_name,
    ))
    .map_err(to_io_error)?;
    client_config.salamander_key = hysteria.salamander_key.as_ref().filter(|value| !value.trim().is_empty()).cloned();
    client_config.insecure = hysteria.insecure;
    client_config.quic_bind_low_port = config.common.quic_bind_low_port;
    client_config.quic_migrate_after_handshake = config.common.quic_migrate_after_handshake;

    Ok(RelayBackend::Hysteria2(PooledRelayBackend::new(
        Hysteria2SessionFactory { config: client_config, migration: context.quic_migration.clone() },
        context.pool_config,
        Some(context.quic_migration.clone()),
    )))
}
