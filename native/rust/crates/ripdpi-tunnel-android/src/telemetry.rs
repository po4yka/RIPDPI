use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;

use arc_swap::ArcSwapOption;

use ripdpi_telemetry::{LatencyDistributions, LatencyHistogram};
use ripdpi_tunnel_core::DnsStatsSnapshot;
use serde::Serialize;

use android_support::{clear_tunnel_events, drain_tunnel_events, NativeEventRecord};

use crate::config::TunnelLogContext;

static NEXT_TUNNEL_SESSION_ID: AtomicU64 = AtomicU64::new(1);

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct NativeRuntimeEvent {
    pub(crate) source: String,
    pub(crate) level: String,
    pub(crate) message: String,
    pub(crate) created_at: u64,
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

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct NativeRuntimeSnapshot {
    pub(crate) source: String,
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
    pub(crate) network_handover_class: Option<String>,
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

pub(crate) struct TunnelTelemetryState {
    session_id: String,
    log_scope: String,
    log_context: Option<TunnelLogContext>,
    running: AtomicBool,
    total_sessions: AtomicU64,
    total_errors: AtomicU64,
    upstream_address: ArcSwapOption<String>,
    last_error: ArcSwapOption<String>,
    pub(crate) dns_histogram: LatencyHistogram,
}

impl TunnelTelemetryState {
    pub(crate) fn new(log_context: Option<TunnelLogContext>) -> Self {
        let ordinal = NEXT_TUNNEL_SESSION_ID.fetch_add(1, Ordering::Relaxed);
        let session_id = format!("tunnel-{ordinal}");
        clear_tunnel_events();
        Self {
            log_scope: format!("tunnel:{session_id}"),
            session_id,
            log_context,
            running: AtomicBool::new(false),
            total_sessions: AtomicU64::new(0),
            total_errors: AtomicU64::new(0),
            upstream_address: ArcSwapOption::empty(),
            last_error: ArcSwapOption::empty(),
            dns_histogram: LatencyHistogram::new(),
        }
    }

    pub(crate) fn log_scope(&self) -> &str {
        &self.log_scope
    }

    pub(crate) fn log_line(&self, source: &str, level: &str, message: &str) {
        let log_context = self.log_context.as_ref();
        let runtime_id = log_context.and_then(|context| context.runtime_id.as_deref()).unwrap_or("");
        let mode = log_context.and_then(|context| context.mode.as_deref()).unwrap_or("");
        let policy_signature =
            log_context.and_then(|context| context.policy_signature.as_deref()).unwrap_or("");
        let fingerprint_hash = log_context.and_then(|context| context.fingerprint_hash.as_deref()).unwrap_or("");
        let diagnostics_session_id =
            log_context.and_then(|context| context.diagnostics_session_id.as_deref()).unwrap_or("");
        match level.trim().to_ascii_lowercase().as_str() {
            "trace" => tracing::trace!(
                ring = "tunnel",
                subsystem = "tunnel",
                session = self.session_id.as_str(),
                source,
                runtime_id,
                mode,
                policy_signature,
                fingerprint_hash,
                diagnostics_session_id,
                "{message}"
            ),
            "debug" => tracing::debug!(
                ring = "tunnel",
                subsystem = "tunnel",
                session = self.session_id.as_str(),
                source,
                runtime_id,
                mode,
                policy_signature,
                fingerprint_hash,
                diagnostics_session_id,
                "{message}"
            ),
            "warn" | "warning" => tracing::warn!(
                ring = "tunnel",
                subsystem = "tunnel",
                session = self.session_id.as_str(),
                source,
                runtime_id,
                mode,
                policy_signature,
                fingerprint_hash,
                diagnostics_session_id,
                "{message}"
            ),
            "error" => tracing::error!(
                ring = "tunnel",
                subsystem = "tunnel",
                session = self.session_id.as_str(),
                source,
                runtime_id,
                mode,
                policy_signature,
                fingerprint_hash,
                diagnostics_session_id,
                "{message}"
            ),
            _ => tracing::info!(
                ring = "tunnel",
                subsystem = "tunnel",
                session = self.session_id.as_str(),
                source,
                runtime_id,
                mode,
                policy_signature,
                fingerprint_hash,
                diagnostics_session_id,
                "{message}"
            ),
        }
    }

    pub(crate) fn mark_started(&self, upstream: String) {
        self.running.store(true, Ordering::Relaxed);
        self.total_sessions.fetch_add(1, Ordering::Relaxed);
        self.upstream_address.store(Some(Arc::new(upstream.clone())));
        self.push_event("tunnel", "info", format!("tunnel started upstream={upstream}"));
    }

    pub(crate) fn mark_stop_requested(&self) {
        self.push_event("tunnel", "info", "tunnel stop requested".to_string());
    }

    pub(crate) fn mark_stopped(&self) {
        self.running.store(false, Ordering::Relaxed);
        self.push_event("tunnel", "info", "tunnel stopped".to_string());
    }

    pub(crate) fn record_error(&self, error: String) {
        self.total_errors.fetch_add(1, Ordering::Relaxed);
        self.last_error.store(Some(Arc::new(error.clone())));
        self.push_event("tunnel", "warn", format!("tunnel error: {error}"));
    }

    pub(crate) fn snapshot(
        &self,
        traffic_stats: (u64, u64, u64, u64),
        dns_stats: DnsStatsSnapshot,
        resolver_id: Option<String>,
        resolver_protocol: Option<String>,
    ) -> NativeRuntimeSnapshot {
        NativeRuntimeSnapshot {
            source: "tunnel".to_string(),
            state: if self.running.load(Ordering::Relaxed) { "running".to_string() } else { "idle".to_string() },
            health: if self.running.load(Ordering::Relaxed) {
                if self.total_errors.load(Ordering::Relaxed) == 0 {
                    "healthy".to_string()
                } else {
                    "degraded".to_string()
                }
            } else {
                "idle".to_string()
            },
            active_sessions: u64::from(self.running.load(Ordering::Relaxed)),
            total_sessions: self.total_sessions.load(Ordering::Relaxed),
            total_errors: self.total_errors.load(Ordering::Relaxed),
            route_changes: 0,
            last_route_group: None,
            listener_address: None,
            upstream_address: self.upstream_address.load().as_ref().map(|a| (**a).clone()),
            resolver_id,
            resolver_protocol,
            resolver_endpoint: dns_stats.resolver_endpoint,
            resolver_latency_ms: dns_stats.resolver_latency_ms,
            resolver_latency_avg_ms: dns_stats.resolver_latency_avg_ms,
            resolver_fallback_active: dns_stats.resolver_fallback_active,
            resolver_fallback_reason: dns_stats.resolver_fallback_reason,
            network_handover_class: None,
            last_target: None,
            last_host: dns_stats.last_host,
            last_error: self.last_error.load().as_ref().map(|a| (**a).clone()),
            dns_queries_total: dns_stats.dns_queries_total,
            dns_cache_hits: dns_stats.dns_cache_hits,
            dns_cache_misses: dns_stats.dns_cache_misses,
            dns_failures_total: dns_stats.dns_failures_total,
            last_dns_host: dns_stats.last_dns_host,
            last_dns_error: dns_stats.last_dns_error,
            tunnel_stats: TunnelStatsSnapshot {
                tx_packets: traffic_stats.0,
                tx_bytes: traffic_stats.1,
                rx_packets: traffic_stats.2,
                rx_bytes: traffic_stats.3,
            },
            native_events: drain_tunnel_events().into_iter().map(NativeRuntimeEvent::from).collect(),
            latency_distributions: LatencyDistributions {
                dns_resolution: self.dns_histogram.snapshot(),
                ..Default::default()
            }
            .into_option(),
            captured_at: now_ms(),
        }
    }

    fn push_event(&self, source: &str, level: &str, message: String) {
        self.log_line(source, level, &message);
    }
}

impl From<NativeEventRecord> for NativeRuntimeEvent {
    fn from(value: NativeEventRecord) -> Self {
        Self {
            source: value.source,
            level: value.level,
            message: value.message,
            created_at: value.created_at,
            runtime_id: value.runtime_id,
            mode: value.mode,
            policy_signature: value.policy_signature,
            fingerprint_hash: value.fingerprint_hash,
            subsystem: value.subsystem,
        }
    }
}

pub(crate) fn now_ms() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}

#[cfg(test)]
mod tests {
    use super::*;
    use golden_test_support::{assert_text_golden, canonicalize_json_with};
    use serde_json::{json, Value};

    fn assert_tunnel_snapshot_golden(name: &str, snapshot: &NativeRuntimeSnapshot) {
        let actual = canonicalize_json_with(
            &serde_json::to_string(snapshot).expect("serialize tunnel snapshot"),
            scrub_runtime_timestamps,
        )
        .expect("canonicalize tunnel telemetry");
        assert_text_golden(env!("CARGO_MANIFEST_DIR"), &format!("tests/golden/{name}.json"), &actual);
    }

    fn assert_tunnel_stats_golden(name: &str, stats: (u64, u64, u64, u64)) {
        let actual = canonicalize_json_with(
            &json!({
                "txPackets": stats.0,
                "txBytes": stats.1,
                "rxPackets": stats.2,
                "rxBytes": stats.3,
            })
            .to_string(),
            |_| {},
        )
        .expect("canonicalize tunnel stats");
        assert_text_golden(env!("CARGO_MANIFEST_DIR"), &format!("tests/golden/{name}.json"), &actual);
    }

    fn scrub_runtime_timestamps(value: &mut Value) {
        match value {
            Value::Array(items) => {
                for item in items {
                    scrub_runtime_timestamps(item);
                }
            }
            Value::Object(map) => {
                for (key, item) in map.iter_mut() {
                    if matches!(key.as_str(), "createdAt" | "capturedAt") {
                        *item = Value::from(0);
                    } else {
                        scrub_runtime_timestamps(item);
                    }
                }
            }
            _ => {}
        }
    }

    #[test]
    fn tunnel_telemetry_snapshot_reports_stats_and_drains_events() {
        let state = TunnelTelemetryState::new(None);

        state.mark_started("127.0.0.1:1080".to_string());
        state.record_error("boom".to_string());
        state.mark_stop_requested();

        let first = state.snapshot((7, 70, 8, 80), DnsStatsSnapshot::default(), None, None);
        assert_eq!(first.state, "running");
        assert_eq!(first.health, "degraded");
        assert_eq!(first.active_sessions, 1);
        assert_eq!(first.total_sessions, 1);
        assert_eq!(first.total_errors, 1);
        assert_eq!(first.upstream_address.as_deref(), Some("127.0.0.1:1080"));
        assert_eq!(first.last_error.as_deref(), Some("boom"));
        assert_eq!(first.tunnel_stats.tx_packets, 7);
        assert_eq!(first.tunnel_stats.rx_bytes, 80);
        assert_eq!(first.native_events.len(), 3);

        let second = state.snapshot((9, 90, 10, 100), DnsStatsSnapshot::default(), None, None);
        assert!(second.native_events.is_empty());
        assert_eq!(second.total_errors, 1);
        assert_eq!(second.tunnel_stats.tx_packets, 9);

        state.mark_stopped();
        let stopped = state.snapshot((0, 0, 0, 0), DnsStatsSnapshot::default(), None, None);
        assert_eq!(stopped.state, "idle");
        assert_eq!(stopped.active_sessions, 0);
        assert_eq!(stopped.native_events.len(), 1);
    }

    #[test]
    fn tunnel_telemetry_and_stats_match_goldens() {
        let ready = TunnelTelemetryState::new(None).snapshot((0, 0, 0, 0), DnsStatsSnapshot::default(), None, None);
        assert_tunnel_snapshot_golden("tunnel_ready", &ready);
        assert_tunnel_stats_golden("tunnel_ready_stats", (0, 0, 0, 0));

        let state = TunnelTelemetryState::new(None);
        state.mark_started("127.0.0.1:1080".to_string());
        state.record_error("boom".to_string());
        state.mark_stop_requested();

        let running = state.snapshot((7, 70, 8, 80), DnsStatsSnapshot::default(), None, None);
        assert_tunnel_snapshot_golden("tunnel_running_degraded_first_poll", &running);
        assert_tunnel_stats_golden("tunnel_running_stats", (7, 70, 8, 80));

        let drained = state.snapshot((9, 90, 10, 100), DnsStatsSnapshot::default(), None, None);
        assert_tunnel_snapshot_golden("tunnel_running_degraded_second_poll", &drained);

        state.mark_stopped();
        let stopped = state.snapshot((0, 0, 0, 0), DnsStatsSnapshot::default(), None, None);
        assert_tunnel_snapshot_golden("tunnel_stopped", &stopped);
    }

    #[test]
    fn tunnel_error_overwrite_keeps_latest_value() {
        let state = TunnelTelemetryState::new(None);
        state.record_error("first error".to_string());
        state.record_error("second error".to_string());

        let snap = state.snapshot((0, 0, 0, 0), DnsStatsSnapshot::default(), None, None);
        assert_eq!(snap.last_error.as_deref(), Some("second error"));
        assert_eq!(snap.total_errors, 2);
    }

    #[test]
    fn tunnel_upstream_address_updated_on_restart() {
        let state = TunnelTelemetryState::new(None);
        state.mark_started("10.0.0.1:1080".to_string());

        let snap = state.snapshot((0, 0, 0, 0), DnsStatsSnapshot::default(), None, None);
        assert_eq!(snap.upstream_address.as_deref(), Some("10.0.0.1:1080"));

        state.mark_stopped();
        state.mark_started("10.0.0.2:1080".to_string());

        let snap2 = state.snapshot((0, 0, 0, 0), DnsStatsSnapshot::default(), None, None);
        assert_eq!(snap2.upstream_address.as_deref(), Some("10.0.0.2:1080"));
    }

    #[test]
    fn tunnel_snapshot_reads_do_not_block_under_concurrent_writes() {
        use std::sync::{Arc, Barrier};

        let state = Arc::new(TunnelTelemetryState::new(None));
        state.mark_started("127.0.0.1:1080".to_string());
        let barrier = Arc::new(Barrier::new(2));
        let iterations = 500;

        let writer_state = state.clone();
        let writer_barrier = barrier.clone();
        let writer = std::thread::spawn(move || {
            writer_barrier.wait();
            for i in 0..iterations {
                writer_state.record_error(format!("err-{i}"));
            }
        });

        let reader_state = state.clone();
        let reader_barrier = barrier.clone();
        let reader = std::thread::spawn(move || {
            reader_barrier.wait();
            let mut snapshots = 0u32;
            for _ in 0..iterations {
                let snap = reader_state.snapshot((0, 0, 0, 0), DnsStatsSnapshot::default(), None, None);
                if let Some(ref error) = snap.last_error {
                    assert!(error.starts_with("err-"), "corrupted error: {error}");
                }
                assert_eq!(snap.upstream_address.as_deref(), Some("127.0.0.1:1080"));
                snapshots += 1;
            }
            snapshots
        });

        writer.join().expect("writer panicked");
        let count = reader.join().expect("reader panicked");
        assert_eq!(count, iterations as u32);
    }
}
