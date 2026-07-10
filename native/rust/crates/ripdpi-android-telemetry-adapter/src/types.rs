use android_support::NativeEventRecord;
use ripdpi_telemetry::LatencyDistributions;
use serde::Serialize;

#[allow(clippy::trivially_copy_pass_by_ref)]
fn is_false(value: &bool) -> bool {
    !*value
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct NativeRuntimeEvent {
    pub(crate) source: String,
    pub(crate) level: String,
    pub(crate) message: String,
    pub(crate) created_at: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) kind: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) runtime_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) mode: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) policy_signature: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) fingerprint_hash: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) subsystem: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct DirectPathLearningSignal {
    pub(crate) authority: String,
    pub(crate) ip_set_digest: String,
    pub(crate) event: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) strategy_family: Option<String>,
    pub(crate) captured_at: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct TunnelStatsSnapshot {
    pub(crate) tx_packets: u64,
    pub(crate) tx_bytes: u64,
    pub(crate) rx_packets: u64,
    pub(crate) rx_bytes: u64,
}

/// Runtime-telemetry payload schema version emitted on every snapshot.
/// Additive forward marker — consumers do not branch on it yet. See
/// `docs/architecture/TELEMETRY_CONTRACT.md`.
pub(crate) const SNAPSHOT_SCHEMA_VERSION: u32 = 1;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NativeRuntimeSnapshot {
    pub(crate) source: String,
    pub(crate) schema_version: u32,
    pub state: String,
    pub(crate) health: String,
    pub(crate) active_sessions: u64,
    pub(crate) total_sessions: u64,
    pub(crate) total_errors: u64,
    pub(crate) network_errors: u64,
    pub(crate) route_changes: u64,
    pub(crate) retry_paced_count: u64,
    pub(crate) last_retry_backoff_ms: Option<u64>,
    pub(crate) last_retry_reason: Option<String>,
    pub(crate) candidate_diversification_count: u64,
    pub(crate) last_route_group: Option<i32>,
    pub(crate) last_failure_class: Option<String>,
    pub(crate) last_fallback_action: Option<String>,
    #[serde(skip_serializing_if = "is_false")]
    pub(crate) adaptive_override_active: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) adaptive_trigger_mask: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) adaptive_last_trigger: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) adaptive_override_reason: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) strategy_pack_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) strategy_pack_version: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) tls_profile_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) tls_profile_catalog_version: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) morph_policy_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) morph_hint_family: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) morph_rollback_reason: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) quic_migration_status: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) quic_migration_reason: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) pt_runtime_kind: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) pt_runtime_state: Option<String>,
    pub(crate) listener_address: Option<String>,
    pub(crate) upstream_address: Option<String>,
    pub(crate) upstream_rtt_ms: Option<u64>,
    pub(crate) last_target: Option<String>,
    pub(crate) last_host: Option<String>,
    pub(crate) last_error: Option<String>,
    pub(crate) autolearn_enabled: bool,
    pub(crate) learned_host_count: i32,
    pub(crate) penalized_host_count: i32,
    pub(crate) blocked_host_count: i32,
    pub(crate) last_block_signal: Option<String>,
    pub(crate) last_block_provider: Option<String>,
    pub(crate) last_autolearn_host: Option<String>,
    pub(crate) last_autolearn_group: Option<i32>,
    pub(crate) last_autolearn_action: Option<String>,
    pub(crate) slot_exhaustions: u64,
    /// Cumulative count of successful WS-tunnel handshakes established with the
    /// fake-SNI cover active (TLS cert verification disabled). Defaults to 0 on
    /// older payloads.
    #[serde(default)]
    pub(crate) ws_tunnel_fake_sni_active: u64,
    pub(crate) profile_id: Option<String>,
    pub(crate) protocol_kind: Option<String>,
    pub(crate) tcp_capable: Option<bool>,
    pub(crate) udp_capable: Option<bool>,
    pub(crate) fallback_mode: Option<String>,
    pub(crate) last_handshake_error: Option<String>,
    pub(crate) chain_entry_state: Option<String>,
    pub(crate) chain_exit_state: Option<String>,
    pub(crate) tunnel_stats: TunnelStatsSnapshot,
    pub(crate) direct_path_learning_signals: Vec<DirectPathLearningSignal>,
    pub(crate) native_events: Vec<NativeRuntimeEvent>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) latency_distributions: Option<LatencyDistributions>,
    pub(crate) captured_at: u64,
}

impl From<NativeEventRecord> for NativeRuntimeEvent {
    fn from(value: NativeEventRecord) -> Self {
        Self {
            source: value.source,
            level: value.level,
            message: value.message,
            created_at: value.created_at,
            kind: value.kind,
            runtime_id: value.runtime_id,
            mode: value.mode,
            policy_signature: value.policy_signature,
            fingerprint_hash: value.fingerprint_hash,
            subsystem: value.subsystem,
        }
    }
}
