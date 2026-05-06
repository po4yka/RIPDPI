use std::io;

use crate::backend::builder::builders::BackendBuilder;
use crate::backend::builder::BuildContext;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayBackendConfig, RelayKind, ResolvedRelayRuntimeConfig};
use crate::protocols::MasqueSessionFactory;

pub(crate) const BUILDER: BackendBuilder = BackendBuilder::new(supports, build);

fn supports(config: &ResolvedRelayRuntimeConfig) -> bool {
    matches!(RelayKind::from_config(config), RelayKind::Masque)
}

fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let RelayBackendConfig::Masque(masque) = &config.backend else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "expected MASQUE config"));
    };
    Ok(RelayBackend::Masque(PooledRelayBackend::new(
        MasqueSessionFactory {
            config: ripdpi_masque::config::MasqueConfig {
                url: masque.url.clone(),
                use_http2_fallback: masque.use_http2_fallback,
                auth_mode: masque.auth_mode.clone(),
                auth_token: masque.auth_token.clone(),
                client_certificate_chain_pem: masque.client_certificate_chain_pem.clone(),
                client_private_key_pem: masque.client_private_key_pem.clone(),
                cloudflare_geohash_header: masque.cloudflare_geohash_header.clone(),
                privacy_pass_provider_url: masque.privacy_pass_provider_url.clone(),
                privacy_pass_provider_auth_token: masque.privacy_pass_provider_auth_token.clone(),
                tls_fingerprint_profile: config.common.tls_fingerprint_profile.clone(),
                quic_bind_low_port: config.common.quic_bind_low_port,
                quic_migrate_after_handshake: config.common.quic_migrate_after_handshake,
            },
            migration: context.quic_migration.clone(),
        },
        context.pool_config,
        Some(context.quic_migration.clone()),
    )))
}
