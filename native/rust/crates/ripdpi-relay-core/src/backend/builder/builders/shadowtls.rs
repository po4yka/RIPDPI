use std::io;

use crate::backend::builder::BuildContext;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayBackendConfig, ResolvedRelayRuntimeConfig};
use crate::protocols::ShadowTlsSessionFactory;

pub(crate) fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let RelayBackendConfig::ShadowTlsV3(shadowtls) = &config.backend else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "expected ShadowTLS config"));
    };
    let inner = shadowtls
        .inner
        .as_ref()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing ShadowTLS inner relay config"))?;
    if inner.kind != "vless_reality" || inner.vless_transport != "reality_tcp" {
        return Err(io::Error::new(
            io::ErrorKind::Unsupported,
            "ShadowTLS supports only a VLESS Reality TCP inner relay",
        ));
    }
    Ok(RelayBackend::ShadowTls(PooledRelayBackend::new(
        ShadowTlsSessionFactory {
            client_config: ripdpi_relay_tls_transports::ShadowTlsClientConfig {
                password: shadowtls.password.clone().unwrap_or_default(),
                server_name: config.common.server_name.clone(),
                inner_profile_id: shadowtls.inner_profile_id.clone(),
                socket_protection: context.socket_protection,
                outbound_bind_ip: context.outbound_bind_ip,
            },
            outer_server: config.common.server.clone(),
            outer_server_port: config.common.server_port,
            inner: ripdpi_relay_tls_transports::ShadowTlsInnerConfig {
                kind: inner.kind.clone(),
                server: inner.server.clone(),
                server_port: inner.server_port,
                server_name: inner.server_name.clone(),
                reality_public_key: inner.reality_public_key.clone(),
                reality_short_id: inner.reality_short_id.clone(),
                vless_transport: inner.vless_transport.clone(),
                vless_flow: inner.vless_flow.clone(),
                xhttp_mode: inner.xhttp_mode.clone(),
                vless_uuid: inner.vless_uuid.clone(),
                tls_fingerprint_profile: inner.tls_fingerprint_profile.clone(),
            },
        },
        context.pool_config,
        None,
    )))
}
