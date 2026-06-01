use std::sync::Arc;

use super::RelayRuntime;
use crate::config::{RelayKind, ResolvedRelayRuntimeConfig};
use crate::runtime_validation::{
    describe_runtime_health, describe_upstream, planned_backend_capabilities, planned_backend_fallback_mode,
};
use crate::telemetry::{ChainHopTelemetrySnapshot, RelayTelemetry, now_ms};

pub(super) fn build_telemetry(runtime: &RelayRuntime) -> RelayTelemetry {
    let backend = runtime.state.backend();
    let capabilities =
        backend.map_or_else(|| planned_backend_capabilities(&runtime.config), |backend| backend.capabilities());
    let (quic_migration_status, quic_migration_reason) =
        backend.map_or((None, None), |backend| backend.quic_migration_snapshot());
    let is_running = runtime.state.is_running();
    let state = if is_running { "running" } else { "idle" };
    let chain_hops = backend.and_then(|backend| backend.chain_hop_snapshot());

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
        chain_entry_state: chain_hops
            .as_ref()
            .and_then(ChainHopTelemetrySnapshot::entry_state)
            .or_else(|| chain_state(&runtime.config, is_running)),
        chain_entry_latency_ms: chain_hops.as_ref().and_then(ChainHopTelemetrySnapshot::entry_latency_ms),
        chain_exit_state: chain_hops
            .as_ref()
            .and_then(ChainHopTelemetrySnapshot::exit_state)
            .or_else(|| chain_state(&runtime.config, is_running)),
        chain_exit_latency_ms: chain_hops.as_ref().and_then(ChainHopTelemetrySnapshot::exit_latency_ms),
        chain_intermediate_hops: chain_hops
            .as_ref()
            .map(ChainHopTelemetrySnapshot::intermediate_hops)
            .unwrap_or_default(),
        strategy_pack_id: None,
        strategy_pack_version: None,
        tls_profile_id: Some(runtime.config.common.tls_fingerprint_profile.clone()),
        tls_profile_catalog_version: Some(ripdpi_xhttp::tls_profile_catalog_version().to_string()),
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

#[cfg(test)]
mod tests {
    use crate::config::{
        ChainRelayConfig, CommonRelayConfig, RelayBackendConfig, ResolvedRelayFinalmaskConfig,
        ResolvedRelayRuntimeConfig,
    };
    use crate::runtime::RelayRuntime;

    #[test]
    fn chain_telemetry_exposes_per_hop_latency_fields() {
        let runtime = RelayRuntime::new(chain_config());

        let telemetry = runtime.telemetry();

        assert_eq!(Some("idle"), telemetry.chain_entry_state.as_deref());
        assert_eq!(Some("idle"), telemetry.chain_exit_state.as_deref());
        assert_eq!(None, telemetry.chain_entry_latency_ms);
        assert_eq!(None, telemetry.chain_exit_latency_ms);
    }

    fn chain_config() -> ResolvedRelayRuntimeConfig {
        ResolvedRelayRuntimeConfig {
            common: CommonRelayConfig {
                enabled: true,
                profile_id: "chain".to_string(),
                outbound_bind_ip: String::new(),
                server: "relay.example".to_string(),
                server_port: 443,
                server_name: "relay.example".to_string(),
                local_socks_host: "127.0.0.1".to_string(),
                local_socks_port: 10_80,
                udp_enabled: false,
                tcp_fallback_enabled: true,
                quic_bind_low_port: false,
                quic_migrate_after_handshake: false,
                tls_fingerprint_profile: "chrome_stable".to_string(),
                finalmask: ResolvedRelayFinalmaskConfig::default(),
            },
            backend: RelayBackendConfig::ChainRelay(ChainRelayConfig::default()),
        }
    }
}
