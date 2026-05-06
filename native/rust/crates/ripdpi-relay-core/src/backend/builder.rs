mod builders;

use std::io;
use std::net::IpAddr;

use ripdpi_relay_mux::RelayPoolConfig;

use crate::backend::RelayBackend;
use crate::config::ResolvedRelayRuntimeConfig;
use crate::runtime_validation::{parse_outbound_bind_ip, pool_config_for_backend};
use crate::telemetry::QuicMigrationTelemetryState;

pub(crate) async fn build_backend(config: &ResolvedRelayRuntimeConfig) -> io::Result<RelayBackend> {
    let outbound_bind_ip = parse_outbound_bind_ip(&config.common.outbound_bind_ip)?;
    let pool_config = pool_config_for_backend(config);
    let quic_migration = QuicMigrationTelemetryState::default();

    builders::build_backend(config, &BuildContext { outbound_bind_ip, pool_config, quic_migration })
}

pub(crate) struct BuildContext {
    pub(crate) outbound_bind_ip: Option<IpAddr>,
    pub(crate) pool_config: RelayPoolConfig,
    pub(crate) quic_migration: QuicMigrationTelemetryState,
}
