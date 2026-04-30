use std::io;

use crate::backend::builder::builders::BackendBuilder;
use crate::backend::builder::BuildContext;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayKind, ResolvedRelayRuntimeConfig};
use crate::protocols::MasqueSessionFactory;

pub(crate) const BUILDER: BackendBuilder = BackendBuilder::new(supports, build);

fn supports(config: &ResolvedRelayRuntimeConfig) -> bool {
    matches!(RelayKind::from_config(config), RelayKind::Masque)
}

fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    Ok(RelayBackend::Masque(PooledRelayBackend::new(
        MasqueSessionFactory {
            config: ripdpi_masque::config::MasqueConfig {
                url: config.masque_url.clone(),
                use_http2_fallback: config.masque_use_http2_fallback,
                auth_mode: config.masque_auth_mode.clone(),
                auth_token: config.masque_auth_token.clone(),
                client_certificate_chain_pem: config.masque_client_certificate_chain_pem.clone(),
                client_private_key_pem: config.masque_client_private_key_pem.clone(),
                cloudflare_geohash_header: config.masque_cloudflare_geohash_header.clone(),
                privacy_pass_provider_url: config.masque_privacy_pass_provider_url.clone(),
                privacy_pass_provider_auth_token: config.masque_privacy_pass_provider_auth_token.clone(),
                tls_fingerprint_profile: config.tls_fingerprint_profile.clone(),
                quic_bind_low_port: config.quic_bind_low_port,
                quic_migrate_after_handshake: config.quic_migrate_after_handshake,
            },
            migration: context.quic_migration.clone(),
        },
        context.pool_config,
        Some(context.quic_migration.clone()),
    )))
}
