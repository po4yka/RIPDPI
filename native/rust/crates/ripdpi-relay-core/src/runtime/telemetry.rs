use std::sync::Arc;

use super::RelayRuntime;
use crate::config::{RelayKind, ResolvedRelayRuntimeConfig};
use crate::runtime_validation::{
    describe_runtime_health, describe_upstream, planned_backend_capabilities, planned_backend_fallback_mode,
};
use crate::telemetry::{ChainHopTelemetrySnapshot, RelayTelemetry, now_ms};

pub(super) fn build_telemetry(runtime: &RelayRuntime) -> RelayTelemetry {
    let backend = runtime.state.backend();
    let mut capabilities =
        backend.map_or_else(|| planned_backend_capabilities(&runtime.config), |backend| backend.capabilities());
    capabilities.udp &= runtime.config.common.udp_enabled;
    let (quic_migration_status, quic_migration_reason) =
        backend.map_or((None, None), |backend| backend.quic_migration_snapshot());
    let is_running = runtime.state.is_running();
    let state = if is_running { "running" } else { "idle" };
    let health = match runtime.state.session_error_streak() {
        _ if !is_running => "idle",
        streak if streak >= FAILED_SESSION_ERROR_STREAK => "failed",
        streak if streak > 0 => "degraded",
        _ => "running",
    };
    let chain_hops = backend.and_then(|backend| backend.chain_hop_snapshot());

    RelayTelemetry {
        source: "relay",
        state: state.to_string(),
        health: describe_runtime_health(health, backend.map(Arc::as_ref)),
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
        xudp_telemetry: runtime.state.xudp_telemetry(),
        fallback_mode: planned_backend_fallback_mode(&runtime.config),
        last_handshake_error: runtime.state.last_handshake_error(),
        chain_entry_state: chain_hops
            .as_ref()
            .map_or_else(|| idle_chain_state(&runtime.config), ChainHopTelemetrySnapshot::entry_state),
        chain_entry_latency_ms: chain_hops.as_ref().and_then(ChainHopTelemetrySnapshot::entry_latency_ms),
        chain_exit_state: chain_hops
            .as_ref()
            .map_or_else(|| idle_chain_state(&runtime.config), ChainHopTelemetrySnapshot::exit_state),
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
        confirm_good_dpi_eligible: runtime.confirm_good_dpi_eligible(),
        confirm_good_dpi_evidence: runtime.state.confirm_good_dpi_evidence(),
        captured_at: now_ms(),
    }
}

const FAILED_SESSION_ERROR_STREAK: u64 = 3;

fn idle_chain_state(config: &ResolvedRelayRuntimeConfig) -> Option<String> {
    matches!(RelayKind::from_config(config), RelayKind::ChainRelay).then(|| "idle".to_string())
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

    /// # Cancel safety:
    /// NOT cancel-safe: the test must stop and join its runtime before return.
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn chain_runtime_reports_only_attempted_hops_after_entry_failure() {
        use std::net::{Ipv4Addr, SocketAddr};
        use std::sync::Arc;
        use std::time::Duration;

        use crate::config::ResolvedChainRelayHopConfig;
        use crate::socks::RelayTargetAddr;

        let probe = std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("reserve closed entry port");
        let closed_entry_port = probe.local_addr().expect("entry address").port();
        drop(probe);
        let mut config = chain_config();
        config.common.local_socks_port = 0;
        let RelayBackendConfig::ChainRelay(chain) = &mut config.backend else { unreachable!() };
        chain.hops = (0..4)
            .map(|index| ResolvedChainRelayHopConfig {
                kind: "shadowsocks".to_string(),
                profile_id: format!("hop-{index}"),
                server: "127.0.0.1".to_string(),
                server_port: i32::from(closed_entry_port),
                shadowsocks_method: Some("aes-256-gcm".to_string()),
                shadowsocks_password: Some("fixture-password".to_string()),
                ..ResolvedChainRelayHopConfig::default()
            })
            .collect();
        let runtime = RelayRuntime::new(config);
        let run = tokio::spawn(Arc::clone(&runtime).run());
        tokio::time::timeout(Duration::from_secs(3), async {
            while runtime.telemetry().listener_address.is_none() {
                assert!(!run.is_finished(), "runtime must reach readiness");
                tokio::time::sleep(Duration::from_millis(5)).await;
            }
        })
        .await
        .expect("runtime readiness deadline");
        let before = runtime.telemetry();
        let target = RelayTargetAddr::Ip(SocketAddr::from((Ipv4Addr::LOCALHOST, 80)));
        let result = runtime.state.backend().expect("real chain backend").connect_tcp(&target).await;
        let after = runtime.telemetry();
        runtime.stop();
        tokio::time::timeout(Duration::from_secs(3), run)
            .await
            .expect("runtime stop deadline")
            .expect("runtime join")
            .expect("runtime stops cleanly");

        assert_eq!("running", before.state);
        assert_eq!(None, before.chain_entry_state);
        assert_eq!(None, before.chain_exit_state);
        assert!(result.is_err(), "entry connection must fail");
        assert_eq!(Some("failed"), after.chain_entry_state.as_deref());
        assert_eq!(None, after.chain_exit_state);
        assert_eq!(None, after.chain_exit_latency_ms);
        assert!(after.chain_intermediate_hops.is_empty());
    }

    #[test]
    fn health_tracks_consecutive_session_failures_and_recovery() {
        let runtime = RelayRuntime::new(chain_config());
        runtime.state.set_running(true);

        assert_eq!("running", runtime.telemetry().health);

        runtime.state.record_error("first failure".to_string());
        assert_eq!("degraded", runtime.telemetry().health);

        runtime.state.record_error("second failure".to_string());
        runtime.state.record_error("third failure".to_string());
        assert_eq!("failed", runtime.telemetry().health);

        runtime.state.record_session_success();
        assert_eq!("running", runtime.telemetry().health);
    }

    fn chain_config() -> ResolvedRelayRuntimeConfig {
        ResolvedRelayRuntimeConfig {
            common: CommonRelayConfig {
                enabled: true,
                profile_id: "chain".to_string(),
                outbound_bind_ip: String::new(),
                socket_protection: crate::config::SocketProtection::Inactive,
                server: "relay.example".to_string(),
                server_port: 443,
                server_name: "relay.example".to_string(),
                local_socks_host: "127.0.0.1".to_string(),
                local_socks_port: 10_80,
                local_socks_auth: None,
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
