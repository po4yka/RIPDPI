use std::io;

use crate::backend::builder::builders::BackendBuilder;
use crate::backend::builder::BuildContext;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayBackendConfig, RelayKind, ResolvedRelayRuntimeConfig};
use crate::protocols::ShadowTlsSessionFactory;

pub(crate) const BUILDER: BackendBuilder = BackendBuilder::new(supports, build);

fn supports(config: &ResolvedRelayRuntimeConfig) -> bool {
    matches!(RelayKind::from_config(config), RelayKind::ShadowTlsV3)
}

fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let RelayBackendConfig::ShadowTlsV3(shadowtls) = &config.backend else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "expected ShadowTLS config"));
    };
    Ok(RelayBackend::ShadowTls(PooledRelayBackend::new(
        ShadowTlsSessionFactory {
            client_config: ripdpi_shadowtls::Config {
                password: shadowtls.password.clone().unwrap_or_default(),
                server_name: config.common.server_name.clone(),
                inner_profile_id: shadowtls.inner_profile_id.clone(),
            },
            outer_server: config.common.server.clone(),
            outer_server_port: config.common.server_port,
            inner: shadowtls
                .inner
                .clone()
                .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing ShadowTLS inner relay config"))?,
        },
        context.pool_config,
        None,
    )))
}
