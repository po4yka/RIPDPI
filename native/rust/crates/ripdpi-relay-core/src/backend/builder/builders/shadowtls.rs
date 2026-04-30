use std::io;

use crate::backend::builder::builders::BackendBuilder;
use crate::backend::builder::BuildContext;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayKind, ResolvedRelayRuntimeConfig};
use crate::protocols::ShadowTlsSessionFactory;

pub(crate) const BUILDER: BackendBuilder = BackendBuilder::new(supports, build);

fn supports(config: &ResolvedRelayRuntimeConfig) -> bool {
    matches!(RelayKind::from_config(config), RelayKind::ShadowTlsV3)
}

fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    Ok(RelayBackend::ShadowTls(PooledRelayBackend::new(
        ShadowTlsSessionFactory {
            client_config: ripdpi_shadowtls::Config {
                password: config.shadow_tls_password.clone().unwrap_or_default(),
                server_name: config.server_name.clone(),
                inner_profile_id: config.shadow_tls_inner_profile_id.clone(),
            },
            outer_server: config.server.clone(),
            outer_server_port: config.server_port,
            inner: config
                .shadow_tls_inner
                .clone()
                .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing ShadowTLS inner relay config"))?,
        },
        context.pool_config,
        None,
    )))
}
