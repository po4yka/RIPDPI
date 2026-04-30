use std::io;

use crate::backend::builder::builders::common::to_io_error;
use crate::backend::builder::builders::BackendBuilder;
use crate::backend::builder::BuildContext;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayKind, ResolvedRelayRuntimeConfig};
use crate::protocols::Hysteria2SessionFactory;

pub(crate) const BUILDER: BackendBuilder = BackendBuilder::new(supports, build);

fn supports(config: &ResolvedRelayRuntimeConfig) -> bool {
    matches!(RelayKind::from_config(config), RelayKind::Hysteria2)
}

fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let password = config
        .hysteria_password
        .as_ref()
        .filter(|value| !value.trim().is_empty())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing Hysteria2 password"))?;
    let mut client_config = ripdpi_hysteria2::Config::from_url(&format!(
        "hysteria2://{password}@{}:{}/?sni={}",
        config.server, config.server_port, config.server_name,
    ))
    .map_err(to_io_error)?;
    client_config.salamander_key =
        config.hysteria_salamander_key.as_ref().filter(|value| !value.trim().is_empty()).cloned();
    client_config.quic_bind_low_port = config.quic_bind_low_port;
    client_config.quic_migrate_after_handshake = config.quic_migrate_after_handshake;

    Ok(RelayBackend::Hysteria2(PooledRelayBackend::new(
        Hysteria2SessionFactory { config: client_config, migration: context.quic_migration.clone() },
        context.pool_config,
        Some(context.quic_migration.clone()),
    )))
}
