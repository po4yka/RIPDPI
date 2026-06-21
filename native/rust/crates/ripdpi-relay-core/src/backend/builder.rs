pub(crate) mod builders;

use std::io;
use std::net::IpAddr;

use ripdpi_relay_mux::RelayPoolConfig;
use ripdpi_xhttp::XhttpSocketProtector;

use crate::backend::RelayBackend;
use crate::config::ResolvedRelayRuntimeConfig;
use crate::runtime_validation::{parse_outbound_bind_ip, pool_config_for_backend};
use crate::telemetry::QuicMigrationTelemetryState;
use crate::transport_descriptor::relay_transport_registration;

/// Build the in-process [`RelayBackend`] for `config`.
///
/// Dispatch is by `relay_kind`: the [`relay_transport_registration`] for the
/// config's kind supplies the backend builder. A kind with no registration
/// (`"off"`, an unknown kind) or no builder (the NaiveProxy subprocess-fallback
/// kind) yields `RelayBackend::Unsupported` — the relay then runs out-of-process
/// or fails fast, exactly as before this dispatch moved onto the registry.
pub(crate) async fn build_backend(config: &ResolvedRelayRuntimeConfig) -> io::Result<RelayBackend> {
    build_backend_with_socket_protector(config, None).await
}

pub(crate) async fn build_backend_with_socket_protector(
    config: &ResolvedRelayRuntimeConfig,
    socket_protector: Option<XhttpSocketProtector>,
) -> io::Result<RelayBackend> {
    let context = BuildContext {
        outbound_bind_ip: parse_outbound_bind_ip(&config.common.outbound_bind_ip)?,
        socket_protector,
        pool_config: pool_config_for_backend(config),
        quic_migration: QuicMigrationTelemetryState::default(),
    };

    match relay_transport_registration(config.kind_id()).and_then(|registration| registration.builder) {
        Some(build) => build(config, &context),
        None => Ok(RelayBackend::Unsupported { kind: config.kind_id().to_string() }),
    }
}

pub(crate) struct BuildContext {
    pub(crate) outbound_bind_ip: Option<IpAddr>,
    pub(crate) socket_protector: Option<XhttpSocketProtector>,
    pub(crate) pool_config: RelayPoolConfig,
    pub(crate) quic_migration: QuicMigrationTelemetryState,
}
