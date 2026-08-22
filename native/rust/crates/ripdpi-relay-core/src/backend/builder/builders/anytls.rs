use std::io;

use crate::backend::builder::BuildContext;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayBackendConfig, ResolvedRelayRuntimeConfig};
use crate::protocols::AnyTlsSessionFactory;

pub(crate) fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let RelayBackendConfig::AnyTls(anytls) = &config.backend else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "expected AnyTLS config"));
    };
    let server_port = u16::try_from(config.common.server_port)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "AnyTLS server port must fit u16"))?;
    Ok(RelayBackend::AnyTls(PooledRelayBackend::new(
        AnyTlsSessionFactory {
            client_config: ripdpi_relay_tls_transports::AnyTlsClientConfig {
                server_host: config.common.server.clone(),
                server_port,
                server_name: config.common.server_name.clone(),
                password: anytls.password.clone().unwrap_or_default(),
                tls_fingerprint_profile: config.common.tls_fingerprint_profile.clone(),
                root_certificate_pem: anytls.root_certificate_pem.clone(),
                client_name: "ripdpi-anytls/0.1.0".to_string(),
                socket_protection: context.socket_protection,
                outbound_bind_ip: context.outbound_bind_ip,
            },
        },
        context.pool_config,
        None,
    )))
}
