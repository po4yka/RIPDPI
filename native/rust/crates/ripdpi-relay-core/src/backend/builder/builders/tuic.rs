use std::io;

use crate::backend::builder::BuildContext;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayBackendConfig, ResolvedRelayRuntimeConfig};
use crate::protocols::TuicSessionFactory;

pub(crate) fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let RelayBackendConfig::TuicV5(tuic) = &config.backend else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "expected TUIC config"));
    };
    Ok(RelayBackend::Tuic(PooledRelayBackend::new(
        TuicSessionFactory {
            config: ripdpi_tuic::Config {
                server: config.common.server.clone(),
                server_port: config.common.server_port,
                server_name: config.common.server_name.clone(),
                uuid: tuic.uuid.clone().unwrap_or_default(),
                password: tuic.password.clone().unwrap_or_default(),
                zero_rtt: tuic.zero_rtt,
                congestion_control: tuic.congestion_control.clone(),
                udp_enabled: config.common.udp_enabled,
                quic_bind_low_port: config.common.quic_bind_low_port,
                quic_migrate_after_handshake: config.common.quic_migrate_after_handshake,
                socket_protection: context.socket_protection,
                outbound_bind_ip: context.outbound_bind_ip,
                // Relay profiles currently expose TUIC zero-RTT and congestion
                // control, but not TUIC keepalive. Keep it disabled until the
                // Kotlin/native relay config contract adds an explicit field.
                keepalive_interval_ms: 0,
                root_certificate_pem: None,
            },
            migration: context.quic_migration.clone(),
        },
        context.pool_config,
        Some(context.quic_migration.clone()),
    )))
}
