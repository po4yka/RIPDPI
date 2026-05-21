use android_support::NativeEventRecord;
use ripdpi_telemetry::LatencyDistributions;
use serde::Serialize;

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
pub(crate) struct NativeRuntimeSnapshot {
    pub(crate) source: String,
    pub(crate) schema_version: u32,
    pub(crate) state: String,
    pub(crate) health: String,
    pub(crate) active_sessions: u64,
    pub(crate) total_sessions: u64,
    pub(crate) total_errors: u64,
    pub(crate) route_changes: u64,
    pub(crate) last_route_group: Option<i32>,
    pub(crate) listener_address: Option<String>,
    pub(crate) upstream_address: Option<String>,
    pub(crate) resolver_id: Option<String>,
    pub(crate) resolver_protocol: Option<String>,
    pub(crate) resolver_endpoint: Option<String>,
    pub(crate) resolver_latency_ms: Option<u64>,
    pub(crate) resolver_latency_avg_ms: Option<u64>,
    pub(crate) resolver_fallback_active: bool,
    pub(crate) resolver_fallback_reason: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) dht_trigger_observations: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) last_dht_trigger_endpoint: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) last_dht_trigger_at: Option<u64>,
    pub(crate) network_handover_class: Option<String>,
    pub(crate) strategy_pack_id: Option<String>,
    pub(crate) strategy_pack_version: Option<String>,
    pub(crate) tls_profile_id: Option<String>,
    pub(crate) tls_profile_catalog_version: Option<String>,
    pub(crate) morph_policy_id: Option<String>,
    pub(crate) quic_migration_status: Option<String>,
    pub(crate) quic_migration_reason: Option<String>,
    pub(crate) pt_runtime_kind: Option<String>,
    pub(crate) pt_runtime_state: Option<String>,
    pub(crate) last_target: Option<String>,
    pub(crate) last_host: Option<String>,
    pub(crate) last_error: Option<String>,
    pub(crate) dns_queries_total: u64,
    pub(crate) dns_cache_hits: u64,
    pub(crate) dns_cache_misses: u64,
    pub(crate) dns_failures_total: u64,
    pub(crate) last_dns_host: Option<String>,
    pub(crate) last_dns_error: Option<String>,
    pub(crate) tunnel_stats: TunnelStatsSnapshot,
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
