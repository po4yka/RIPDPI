use std::io;

use crate::backend::builder::BuildContext;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayBackendConfig, ResolvedRelayRuntimeConfig};
use crate::protocols::TrojanSessionFactory;

pub(crate) fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let RelayBackendConfig::Trojan(trojan) = &config.backend else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "expected Trojan config"));
    };
    let server_port = u16::try_from(config.common.server_port)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "Trojan server port must fit u16"))?;
    Ok(RelayBackend::Trojan(PooledRelayBackend::new(
        TrojanSessionFactory {
            client_config: ripdpi_relay_tls_transports::TrojanClientConfig {
                server_host: config.common.server.clone(),
                server_port,
                server_name: config.common.server_name.clone(),
                password: trojan.password.clone().unwrap_or_default(),
                tls_fingerprint_profile: config.common.tls_fingerprint_profile.clone(),
                root_certificate_pem: trojan.root_certificate_pem.clone(),
                socket_protection: context.socket_protection,
                outbound_bind_ip: context.outbound_bind_ip,
            },
        },
        context.pool_config,
        None,
    )))
}
