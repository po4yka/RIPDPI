use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Mutex;

use ripdpi_telemetry::{LatencyDistributions, LatencyHistogram};
use ripdpi_tunnel_core::DnsStatsSnapshot;
use serde::Serialize;

pub(crate) const MAX_TUNNEL_EVENTS: usize = 128;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct NativeRuntimeEvent {
    pub(crate) source: String,
    pub(crate) level: String,
    pub(crate) message: String,
    pub(crate) created_at: u64,
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
    running: AtomicBool,
    total_sessions: AtomicU64,
    total_errors: AtomicU64,
    upstream_address: Mutex<Option<String>>,
    last_error: Mutex<Option<String>>,
    events: Mutex<VecDeque<NativeRuntimeEvent>>,
    pub(crate) dns_histogram: LatencyHistogram,
}

impl TunnelTelemetryState {
    pub(crate) fn new() -> Self {
        Self {
            running: AtomicBool::new(false),
            total_sessions: AtomicU64::new(0),
            total_errors: AtomicU64::new(0),
            upstream_address: Mutex::new(None),
            last_error: Mutex::new(None),
            events: Mutex::new(VecDeque::with_capacity(MAX_TUNNEL_EVENTS)),
            dns_histogram: LatencyHistogram::new(),
        }
    }

    pub(crate) fn mark_started(&self, upstream: String) {
        self.running.store(true, Ordering::Relaxed);
        self.total_sessions.fetch_add(1, Ordering::Relaxed);
        if let Ok(mut guard) = self.upstream_address.lock() {
            *guard = Some(upstream.clone());
        }
        log::info!("tunnel started upstream={upstream}");
        self.push_event("tunnel", "info", format!("tunnel started upstream={upstream}"));
    }

    pub(crate) fn mark_stop_requested(&self) {
        log::info!("tunnel stop requested");
        self.push_event("tunnel", "info", "tunnel stop requested".to_string());
    }

    pub(crate) fn mark_stopped(&self) {
        self.running.store(false, Ordering::Relaxed);
        log::info!("tunnel stopped");
        self.push_event("tunnel", "info", "tunnel stopped".to_string());
    }

    pub(crate) fn record_error(&self, error: String) {
        self.total_errors.fetch_add(1, Ordering::Relaxed);
        if let Ok(mut guard) = self.last_error.lock() {
            *guard = Some(error.clone());
        }
        log::warn!("tunnel error: {error}");
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
            upstream_address: self.upstream_address.lock().ok().and_then(|guard| guard.clone()),
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
            last_error: self.last_error.lock().ok().and_then(|guard| guard.clone()),
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
            native_events: self.drain_events(),
            latency_distributions: LatencyDistributions {
                dns_resolution: self.dns_histogram.snapshot(),
                ..Default::default()
            }
            .into_option(),
            captured_at: now_ms(),
        }
    }

    fn drain_events(&self) -> Vec<NativeRuntimeEvent> {
        if let Ok(mut guard) = self.events.lock() {
            guard.drain(..).collect()
        } else {
            Vec::new()
        }
    }

    fn push_event(&self, source: &str, level: &str, message: String) {
        if let Ok(mut guard) = self.events.lock() {
            if guard.len() >= MAX_TUNNEL_EVENTS {
                guard.pop_front();
            }
            guard.push_back(NativeRuntimeEvent {
                source: source.to_string(),
                level: level.to_string(),
                message,
                created_at: now_ms(),
            });
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
        let state = TunnelTelemetryState::new();

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
        let ready = TunnelTelemetryState::new().snapshot((0, 0, 0, 0), DnsStatsSnapshot::default(), None, None);
        assert_tunnel_snapshot_golden("tunnel_ready", &ready);
        assert_tunnel_stats_golden("tunnel_ready_stats", (0, 0, 0, 0));

        let state = TunnelTelemetryState::new();
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
}
