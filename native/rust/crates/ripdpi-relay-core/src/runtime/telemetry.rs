use std::sync::Arc;

use ripdpi_tls_profiles::profile_catalog_version;

use super::RelayRuntime;
use crate::config::{RelayKind, ResolvedRelayRuntimeConfig};
use crate::runtime_validation::{
    describe_runtime_health, describe_upstream, planned_backend_capabilities, planned_backend_fallback_mode,
};
use crate::telemetry::{now_ms, RelayTelemetry};

pub(super) fn build_telemetry(runtime: &RelayRuntime) -> RelayTelemetry {
    let backend = runtime.state.backend();
    let capabilities =
        backend.map_or_else(|| planned_backend_capabilities(&runtime.config), |backend| backend.capabilities());
    let (quic_migration_status, quic_migration_reason) =
        backend.map_or((None, None), |backend| backend.quic_migration_snapshot());
    let is_running = runtime.state.is_running();
    let state = if is_running { "running" } else { "idle" };

    RelayTelemetry {
        source: "relay",
        state: state.to_string(),
        health: describe_runtime_health(state, backend.map(Arc::as_ref)),
        active_sessions: runtime.state.active_sessions(),
        total_sessions: runtime.state.total_sessions(),
        listener_address: runtime.state.listener_address(),
        upstream_address: Some(describe_upstream(&runtime.config)),
        last_target: runtime.state.last_target(),
        last_error: runtime.state.last_error(),
        profile_id: Some(runtime.config.common.profile_id.clone()),
        protocol_kind: Some(runtime.config.kind_id().to_string()),
        tcp_capable: Some(capabilities.tcp),
        udp_capable: Some(capabilities.udp),
        fallback_mode: planned_backend_fallback_mode(&runtime.config),
        last_handshake_error: runtime.state.last_handshake_error(),
        chain_entry_state: chain_state(&runtime.config, is_running),
        chain_exit_state: chain_state(&runtime.config, is_running),
        strategy_pack_id: None,
        strategy_pack_version: None,
        tls_profile_id: Some(runtime.config.common.tls_fingerprint_profile.clone()),
        tls_profile_catalog_version: Some(profile_catalog_version().to_string()),
        morph_policy_id: None,
        quic_migration_status,
        quic_migration_reason,
        pt_runtime_kind: None,
        pt_runtime_state: None,
        captured_at: now_ms(),
    }
}

fn chain_state(config: &ResolvedRelayRuntimeConfig, is_running: bool) -> Option<String> {
    matches!(RelayKind::from_config(config), RelayKind::ChainRelay)
        .then(|| if is_running { "connected" } else { "idle" }.to_string())
}
