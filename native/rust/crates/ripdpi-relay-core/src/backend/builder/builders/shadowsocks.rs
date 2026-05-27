use std::io;

use crate::backend::builder::BuildContext;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayBackendConfig, ResolvedRelayRuntimeConfig};
use crate::protocols::ShadowsocksSessionFactory;

pub(crate) fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let RelayBackendConfig::Shadowsocks(shadowsocks) = &config.backend else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "expected Shadowsocks config"));
    };
    let server_port = u16::try_from(config.common.server_port)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "Shadowsocks server port must fit u16"))?;
    let factory = ShadowsocksSessionFactory::new(
        config.common.server.clone(),
        server_port,
        shadowsocks.method.clone(),
        shadowsocks.password.clone().unwrap_or_default(),
        context.outbound_bind_ip,
    )?;
    Ok(RelayBackend::Shadowsocks(PooledRelayBackend::new(factory, context.pool_config, None)))
}
