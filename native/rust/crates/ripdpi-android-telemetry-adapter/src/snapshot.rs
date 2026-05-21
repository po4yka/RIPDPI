use std::sync::atomic::Ordering;

use android_support::drain_proxy_events;
use ripdpi_telemetry::LatencyDistributions;

use super::state::ProxyTelemetryState;
use super::types::{NativeRuntimeEvent, NativeRuntimeSnapshot, TunnelStatsSnapshot, SNAPSHOT_SCHEMA_VERSION};
use super::util::now_ms;

impl ProxyTelemetryState {
    pub fn snapshot(&self) -> NativeRuntimeSnapshot {
        let strings = self.strings.load();
        let listener_address = strings.listener_address.clone();
        let upstream_address = strings.upstream_address.clone();
        let upstream_rtt_ms = strings.upstream_rtt_ms;
        let last_target = strings.last_target.clone();
        let last_host = strings.last_host.clone();
        let last_error = strings.last_error.clone();
        let last_failure_class = strings.last_failure_class.clone();
        let last_fallback_action = strings.last_fallback_action.clone();
        let adaptive_trigger_mask = strings.adaptive_trigger_mask;
        let adaptive_last_trigger = strings.adaptive_last_trigger.clone();
        let adaptive_override_reason = strings.adaptive_override_reason.clone();
        let morph_hint_family = strings.morph_hint_family.clone();
        let morph_rollback_reason = strings.morph_rollback_reason.clone();
        let quic_migration_status = strings.quic_migration_status.clone();
        let quic_migration_reason = strings.quic_migration_reason.clone();
        let last_retry_reason = strings.last_retry_reason.clone();
        let last_autolearn_host = strings.last_autolearn_host.clone();
        let last_autolearn_action = strings.last_autolearn_action.clone();
        let last_block_signal = strings.last_block_signal.clone();
        let last_block_provider = strings.last_block_provider.clone();
        let direct_path_learning_signals = self
            .direct_path_learning_signals
            .lock()
            .map(|mut signals| std::mem::take(&mut *signals))
            .unwrap_or_default();
        NativeRuntimeSnapshot {
            source: "proxy".to_string(),
            schema_version: SNAPSHOT_SCHEMA_VERSION,
            // Ordering: Acquire -- pairs with Release stores in mark_running/mark_stopped.
            state: if self.running.load(Ordering::Acquire) { "running".to_string() } else { "idle".to_string() },
            // Ordering: Acquire -- pairs with Release stores in mark_running/mark_stopped.
            health: if self.running.load(Ordering::Acquire) {
                // Ordering: Relaxed -- counter read for display only, no happens-before needed.
                if self.total_errors.load(Ordering::Relaxed) == 0 {
                    "healthy".to_string()
                } else {
                    "degraded".to_string()
                }
            } else {
                "idle".to_string()
            },
            // Ordering: Acquire -- active_sessions gates display logic; pairs with AcqRel stores.
            active_sessions: self.active_sessions.load(Ordering::Acquire),
            // Ordering: Relaxed -- counter read for display only, no happens-before needed.
            total_sessions: self.total_sessions.load(Ordering::Relaxed),
            // Ordering: Relaxed -- counter read for display only, no happens-before needed.
            total_errors: self.total_errors.load(Ordering::Relaxed),
            // Ordering: Relaxed -- counter read for display only, no happens-before needed.
            network_errors: self.network_errors.load(Ordering::Relaxed),
            // Ordering: Relaxed -- counter read for display only, no happens-before needed.
            route_changes: self.route_changes.load(Ordering::Relaxed),
            // Ordering: Relaxed -- counter read for display only, no happens-before needed.
            retry_paced_count: self.retry_paced_count.load(Ordering::Relaxed),
            // Ordering: Relaxed -- display-only field; staleness of a few ms is acceptable.
            last_retry_backoff_ms: match self.last_retry_backoff_ms.load(Ordering::Relaxed) {
                0 => None,
                value => Some(value),
            },
            last_retry_reason,
            // Ordering: Relaxed -- counter read for display only, no happens-before needed.
            candidate_diversification_count: self.candidate_diversification_count.load(Ordering::Relaxed),
            // Ordering: Relaxed -- display-only field; readers tolerate stale group index.
            last_route_group: match self.last_route_group.load(Ordering::Relaxed) {
                value if value >= 0 => i32::try_from(value).ok(),
                _ => None,
            },
            last_failure_class,
            last_fallback_action,
            // Ordering: Acquire -- pairs with Release stores in mark_running/mark_stopped/on_adaptive_override.
            adaptive_override_active: self.adaptive_override_active.load(Ordering::Acquire),
            adaptive_trigger_mask,
            adaptive_last_trigger,
            adaptive_override_reason,
            strategy_pack_id: None,
            strategy_pack_version: None,
            tls_profile_id: None,
            tls_profile_catalog_version: None,
            morph_policy_id: None,
            morph_hint_family,
            morph_rollback_reason,
            quic_migration_status,
            quic_migration_reason,
            pt_runtime_kind: None,
            pt_runtime_state: None,
            listener_address,
            upstream_address,
            upstream_rtt_ms,
            last_target,
            last_host,
            last_error,
            // Ordering: Acquire -- pairs with Release store in set_autolearn_state.
            autolearn_enabled: self.autolearn_enabled.load(Ordering::Acquire),
            // Ordering: Relaxed -- counter read for display only, no happens-before needed.
            learned_host_count: i32::try_from(self.learned_host_count.load(Ordering::Relaxed)).unwrap_or(i32::MAX),
            // Ordering: Relaxed -- counter read for display only, no happens-before needed.
            penalized_host_count: i32::try_from(self.penalized_host_count.load(Ordering::Relaxed)).unwrap_or(i32::MAX),
            // Ordering: Relaxed -- counter read for display only, no happens-before needed.
            blocked_host_count: i32::try_from(self.blocked_host_count.load(Ordering::Relaxed)).unwrap_or(i32::MAX),
            last_block_signal,
            last_block_provider,
            last_autolearn_host,
            // Ordering: Relaxed -- display-only field; readers tolerate stale group index.
            last_autolearn_group: match self.last_autolearn_group.load(Ordering::Relaxed) {
                value if value >= 0 => i32::try_from(value).ok(),
                _ => None,
            },
            last_autolearn_action,
            // Ordering: Relaxed -- counter read for display only, no happens-before needed.
            slot_exhaustions: self.slot_exhaustions.load(Ordering::Relaxed),
            profile_id: None,
            protocol_kind: None,
            tcp_capable: None,
            udp_capable: None,
            fallback_mode: None,
            last_handshake_error: None,
            chain_entry_state: None,
            chain_exit_state: None,
            tunnel_stats: TunnelStatsSnapshot { tx_packets: 0, tx_bytes: 0, rx_packets: 0, rx_bytes: 0 },
            direct_path_learning_signals,
            native_events: drain_proxy_events().into_iter().map(NativeRuntimeEvent::from).collect(),
            latency_distributions: LatencyDistributions {
                tcp_connect: self.tcp_connect_histogram.snapshot(),
                tls_handshake: self.tls_handshake_histogram.snapshot(),
                ..Default::default()
            }
            .into_option(),
            captured_at: now_ms(),
        }
    }
}
