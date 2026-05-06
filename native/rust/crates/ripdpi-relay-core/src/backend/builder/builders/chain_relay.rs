use std::io;

use crate::backend::builder::builders::common::vless_reality_config;
use crate::backend::builder::builders::BackendBuilder;
use crate::backend::builder::BuildContext;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayBackendConfig, RelayKind, ResolvedRelayRuntimeConfig};
use crate::protocols::ChainRelaySessionFactory;

pub(crate) const BUILDER: BackendBuilder = BackendBuilder::new(supports, build);

fn supports(config: &ResolvedRelayRuntimeConfig) -> bool {
    matches!(RelayKind::from_config(config), RelayKind::ChainRelay)
}

fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let RelayBackendConfig::ChainRelay(chain) = &config.backend else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "expected chain relay config"));
    };
    let entry = vless_reality_config(
        &chain.entry_server,
        chain.entry_port,
        chain.entry_uuid.as_deref().unwrap_or_default(),
        &chain.entry_server_name,
        &chain.entry_public_key,
        &chain.entry_short_id,
        &config.common.tls_fingerprint_profile,
    )
    .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("chain entry: {error}")))?;
    let exit = vless_reality_config(
        &chain.exit_server,
        chain.exit_port,
        chain.exit_uuid.as_deref().unwrap_or_default(),
        &chain.exit_server_name,
        &chain.exit_public_key,
        &chain.exit_short_id,
        &config.common.tls_fingerprint_profile,
    )
    .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("chain exit: {error}")))?;

    Ok(RelayBackend::ChainRelay(PooledRelayBackend::new(
        ChainRelaySessionFactory { entry, exit, outbound_bind_ip: context.outbound_bind_ip },
        context.pool_config,
        None,
    )))
}
