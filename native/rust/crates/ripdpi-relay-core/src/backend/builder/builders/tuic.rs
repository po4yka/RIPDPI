use std::io;

use crate::backend::builder::builders::BackendBuilder;
use crate::backend::builder::BuildContext;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayKind, ResolvedRelayRuntimeConfig};
use crate::protocols::TuicSessionFactory;

pub(crate) const BUILDER: BackendBuilder = BackendBuilder::new(supports, build);

fn supports(config: &ResolvedRelayRuntimeConfig) -> bool {
    matches!(RelayKind::from_config(config), RelayKind::TuicV5)
}

fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    Ok(RelayBackend::Tuic(PooledRelayBackend::new(
        TuicSessionFactory {
            config: ripdpi_tuic::Config {
                server: config.server.clone(),
                server_port: config.server_port,
                server_name: config.server_name.clone(),
                uuid: config.tuic_uuid.clone().unwrap_or_default(),
                password: config.tuic_password.clone().unwrap_or_default(),
                zero_rtt: config.tuic_zero_rtt,
                congestion_control: config.tuic_congestion_control.clone(),
                udp_enabled: config.udp_enabled,
                quic_bind_low_port: config.quic_bind_low_port,
                quic_migrate_after_handshake: config.quic_migrate_after_handshake,
            },
            migration: context.quic_migration.clone(),
        },
        context.pool_config,
        Some(context.quic_migration.clone()),
    )))
}
