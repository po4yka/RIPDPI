use std::io;

use crate::backend::builder::builders::common::vless_reality_config;
use crate::backend::builder::builders::BackendBuilder;
use crate::backend::builder::BuildContext;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayKind, ResolvedRelayRuntimeConfig};
use crate::protocols::ChainRelaySessionFactory;

pub(crate) const BUILDER: BackendBuilder = BackendBuilder::new(supports, build);

fn supports(config: &ResolvedRelayRuntimeConfig) -> bool {
    matches!(RelayKind::from_config(config), RelayKind::ChainRelay)
}

fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let entry = vless_reality_config(
        &config.chain_entry_server,
        config.chain_entry_port,
        config.chain_entry_uuid.as_deref().unwrap_or_default(),
        &config.chain_entry_server_name,
        &config.chain_entry_public_key,
        &config.chain_entry_short_id,
        &config.tls_fingerprint_profile,
    )
    .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("chain entry: {error}")))?;
    let exit = vless_reality_config(
        &config.chain_exit_server,
        config.chain_exit_port,
        config.chain_exit_uuid.as_deref().unwrap_or_default(),
        &config.chain_exit_server_name,
        &config.chain_exit_public_key,
        &config.chain_exit_short_id,
        &config.tls_fingerprint_profile,
    )
    .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("chain exit: {error}")))?;

    Ok(RelayBackend::ChainRelay(PooledRelayBackend::new(
        ChainRelaySessionFactory { entry, exit, outbound_bind_ip: context.outbound_bind_ip },
        context.pool_config,
        None,
    )))
}
