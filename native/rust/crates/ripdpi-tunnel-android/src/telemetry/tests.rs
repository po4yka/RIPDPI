use std::sync::Arc;

use android_support::{EventRingBuffers, EventRingLayer, RingConfig};
use golden_test_support::{assert_text_golden, canonicalize_json_with};
use ripdpi_tunnel_core::DnsStatsSnapshot;
use serde_json::{Value, json};
use tracing_subscriber::prelude::*;

use super::state::TunnelTelemetryState;
use super::types::{NativeRuntimeEvent, NativeRuntimeSnapshot, SNAPSHOT_SCHEMA_VERSION, TunnelStatsSnapshot};

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

fn with_tunnel_event_capture<R>(f: impl FnOnce(EventRingBuffers) -> R) -> R {
    let buffers = EventRingBuffers::new(RingConfig::default());
    let subscriber = tracing_subscriber::registry().with(EventRingLayer::new(buffers.clone()));
    tracing::subscriber::with_default(subscriber, || f(buffers))
}

fn snapshot_with_captured_events(
    state: &TunnelTelemetryState,
    traffic_stats: (u64, u64, u64, u64),
    buffers: &EventRingBuffers,
) -> NativeRuntimeSnapshot {
    let mut snapshot = state.snapshot(traffic_stats, DnsStatsSnapshot::default(), None, None);
    let _ = buffers.drain_tunnel().into_iter().map(NativeRuntimeEvent::from).collect::<Vec<_>>();
    snapshot.native_events.clear();
    snapshot
}

#[test]
fn tunnel_telemetry_snapshot_reports_stats_and_drains_events() {
    with_tunnel_event_capture(|buffers| {
        let state = TunnelTelemetryState::new(None);

        state.mark_started("127.0.0.1:1080".to_string());
        state.record_error("boom".to_string());
        state.mark_stop_requested();

        let first = snapshot_with_captured_events(&state, (7, 70, 8, 80), &buffers);
        assert_eq!(first.state, "running");
        assert_eq!(first.health, "degraded");
        assert_eq!(first.active_sessions, 1);
        assert_eq!(first.total_sessions, 1);
        assert_eq!(first.total_errors, 1);
        assert_eq!(first.upstream_address.as_deref(), Some("127.0.0.1:1080"));
        assert_eq!(first.last_error.as_deref(), Some("boom"));
        assert_eq!(first.tunnel_stats.tx_packets, 7);
        assert_eq!(first.tunnel_stats.rx_bytes, 80);

        let second = snapshot_with_captured_events(&state, (9, 90, 10, 100), &buffers);
        assert!(second.native_events.is_empty());
        assert_eq!(second.total_errors, 1);
        assert_eq!(second.tunnel_stats.tx_packets, 9);

        state.mark_stopped();
        let stopped = snapshot_with_captured_events(&state, (0, 0, 0, 0), &buffers);
        assert_eq!(stopped.state, "idle");
        assert_eq!(stopped.active_sessions, 0);
        assert!(stopped.native_events.is_empty());
    });
}

#[test]
fn tunnel_telemetry_and_stats_match_goldens() {
    with_tunnel_event_capture(|buffers| {
        let ready = snapshot_with_captured_events(&TunnelTelemetryState::new(None), (0, 0, 0, 0), &buffers);
        assert_tunnel_snapshot_golden("tunnel_ready", &ready);
        assert_tunnel_stats_golden("tunnel_ready_stats", (0, 0, 0, 0));

        let state = TunnelTelemetryState::new(None);
        state.mark_started("127.0.0.1:1080".to_string());
        state.record_error("boom".to_string());
        state.mark_stop_requested();

        let running = snapshot_with_captured_events(&state, (7, 70, 8, 80), &buffers);
        assert_tunnel_snapshot_golden("tunnel_running_degraded_first_poll", &running);
        assert_tunnel_stats_golden("tunnel_running_stats", (7, 70, 8, 80));

        let drained = snapshot_with_captured_events(&state, (9, 90, 10, 100), &buffers);
        assert_tunnel_snapshot_golden("tunnel_running_degraded_second_poll", &drained);

        state.mark_stopped();
        let stopped = snapshot_with_captured_events(&state, (0, 0, 0, 0), &buffers);
        assert_tunnel_snapshot_golden("tunnel_stopped", &stopped);
    });
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
fn tunnel_snapshot_exposes_dht_trigger_observations() {
    let state = TunnelTelemetryState::new(None);
    let dns_stats = DnsStatsSnapshot {
        dht_trigger_observations: 2,
        last_dht_trigger_endpoint: Some("134.195.198.23:6881".to_string()),
        last_dht_trigger_at_ms: Some(4242),
        ..DnsStatsSnapshot::default()
    };

    let snap = state.snapshot((0, 0, 0, 0), dns_stats, None, None);

    assert_eq!(snap.dht_trigger_observations, Some(2));
    assert_eq!(snap.last_dht_trigger_endpoint.as_deref(), Some("134.195.198.23:6881"));
    assert_eq!(snap.last_dht_trigger_at, Some(4242));
}

#[test]
fn tunnel_snapshot_marks_relay_dns_route_as_fail_closed() {
    with_tunnel_event_capture(|buffers| {
        let state = TunnelTelemetryState::new(None);

        state.mark_relay_dns_route("socks5", true);
        let snapshot = state.snapshot((0, 0, 0, 0), DnsStatsSnapshot::default(), None, None);
        let native_events = buffers.drain_tunnel().into_iter().map(NativeRuntimeEvent::from).collect::<Vec<_>>();

        assert_eq!(snapshot.relay_dns_route.as_deref(), Some("socks5"));
        assert!(snapshot.relay_dns_fail_closed);
        assert!(
            native_events.iter().any(|event| event.kind.as_deref() == Some("relay_dns_route")),
            "relay DNS route event must be visible to Android telemetry",
        );
    });
}

#[test]
fn tunnel_snapshot_reads_do_not_block_under_concurrent_writes() {
    use std::sync::Barrier;

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

#[test]
fn tunnel_snapshot_field_manifest_matches_contract_fixture() {
    use golden_test_support::{assert_contract_fixture, extract_field_paths};

    let dns_stats = DnsStatsSnapshot {
        dns_queries_total: 100,
        dns_cache_hits: 50,
        dns_cache_misses: 30,
        dns_failures_total: 5,
        split_dns_proxy_decisions: 7,
        split_dns_direct_fallback_decisions: 2,
        split_dns_block_decisions: 3,
        direct_dns_successes: 9,
        direct_dns_stale_responses: 1,
        last_split_dns_coverage_reason: Some("route_policy_unavailable".to_string()),
        dht_trigger_observations: 2,
        last_dns_host: Some("example.org".to_string()),
        last_dns_error: Some("SERVFAIL".to_string()),
        last_dht_trigger_endpoint: Some("134.195.198.23:6881".to_string()),
        last_dht_trigger_at_ms: Some(4242),
        last_host: Some("example.org".to_string()),
        resolver_endpoint: Some("1.1.1.1:443".to_string()),
        resolver_latency_ms: Some(15),
        resolver_latency_avg_ms: Some(12),
        resolver_fallback_active: true,
        resolver_fallback_reason: Some("timeout".to_string()),
    };

    let snapshot = NativeRuntimeSnapshot {
        source: "tunnel".to_string(),
        schema_version: SNAPSHOT_SCHEMA_VERSION,
        state: "running".to_string(),
        health: "healthy".to_string(),
        active_sessions: 1,
        total_sessions: 10,
        total_errors: 2,
        route_changes: 0,
        last_route_group: Some(0),
        listener_address: Some("tun0".to_string()),
        upstream_address: Some("127.0.0.1:1080".to_string()),
        resolver_id: Some("cloudflare".to_string()),
        resolver_protocol: Some("doh".to_string()),
        resolver_endpoint: dns_stats.resolver_endpoint,
        resolver_latency_ms: dns_stats.resolver_latency_ms,
        resolver_latency_avg_ms: dns_stats.resolver_latency_avg_ms,
        resolver_fallback_active: dns_stats.resolver_fallback_active,
        resolver_fallback_reason: dns_stats.resolver_fallback_reason,
        relay_dns_route: Some("socks5".to_string()),
        relay_dns_fail_closed: true,
        dht_trigger_observations: Some(2),
        last_dht_trigger_endpoint: Some("134.195.198.23:6881".to_string()),
        last_dht_trigger_at: Some(4242),
        network_handover_class: Some("wifi_to_cellular".to_string()),
        strategy_pack_id: Some("baseline-stable".to_string()),
        strategy_pack_version: Some("2026.04.1".to_string()),
        tls_profile_id: Some("chrome_stable".to_string()),
        tls_profile_catalog_version: Some("v1".to_string()),
        morph_policy_id: Some("balanced".to_string()),
        quic_migration_status: Some("not_attempted".to_string()),
        quic_migration_reason: None,
        pt_runtime_kind: None,
        pt_runtime_state: None,
        confirm_good_dpi_eligible: false,
        confirm_good_dpi_evidence: None,
        last_target: Some("203.0.113.10:443".to_string()),
        last_host: dns_stats.last_host,
        last_error: Some("connection reset".to_string()),
        dns_queries_total: dns_stats.dns_queries_total,
        dns_cache_hits: dns_stats.dns_cache_hits,
        dns_cache_misses: dns_stats.dns_cache_misses,
        dns_failures_total: dns_stats.dns_failures_total,
        split_dns_proxy_decisions: dns_stats.split_dns_proxy_decisions,
        split_dns_direct_fallback_decisions: dns_stats.split_dns_direct_fallback_decisions,
        split_dns_block_decisions: dns_stats.split_dns_block_decisions,
        direct_dns_successes: dns_stats.direct_dns_successes,
        direct_dns_stale_responses: dns_stats.direct_dns_stale_responses,
        last_split_dns_coverage_reason: dns_stats.last_split_dns_coverage_reason,
        last_dns_host: dns_stats.last_dns_host,
        last_dns_error: dns_stats.last_dns_error,
        tunnel_stats: TunnelStatsSnapshot { tx_packets: 100, tx_bytes: 5000, rx_packets: 80, rx_bytes: 4000 },
        native_events: vec![NativeRuntimeEvent {
            source: "tunnel".to_string(),
            level: "info".to_string(),
            message: "test".to_string(),
            created_at: 1000,
            kind: Some("runtime_stopped".to_string()),
            runtime_id: Some("rt-1".to_string()),
            mode: Some("auto".to_string()),
            policy_signature: Some("sig".to_string()),
            fingerprint_hash: Some("hash".to_string()),
            subsystem: Some("tunnel".to_string()),
        }],
        latency_distributions: Some(ripdpi_telemetry::LatencyDistributions {
            dns_resolution: Some(ripdpi_telemetry::LatencyPercentiles {
                p50: 10,
                p95: 20,
                p99: 30,
                min: 1,
                max: 50,
                count: 100,
            }),
            tcp_connect: Some(ripdpi_telemetry::LatencyPercentiles {
                p50: 15,
                p95: 25,
                p99: 35,
                min: 2,
                max: 60,
                count: 200,
            }),
            tls_handshake: Some(ripdpi_telemetry::LatencyPercentiles {
                p50: 20,
                p95: 30,
                p99: 40,
                min: 5,
                max: 80,
                count: 150,
            }),
        }),
        connection_quality: Some(ripdpi_quality::ConnectionQualitySnapshot {
            loss_pct: 0.0,
            rtt_p50_ms: 12,
            rtt_p95_ms: 24,
            jitter_ms: 3,
            sample_count: 10,
            window_start_at_ms: 1000,
            transport_kind: ripdpi_quality::TransportKind::TcpTunnel,
        }),
        captured_at: 1000,
    };

    let json = serde_json::to_value(&snapshot).expect("serialize tunnel snapshot");
    let paths = extract_field_paths(&json);
    let manifest = serde_json::to_string_pretty(&paths).expect("serialize field paths");
    assert_contract_fixture("tunnel_snapshot_fields.json", &manifest);
}

#[test]
fn tunnel_event_field_manifest_matches_contract_fixture() {
    use golden_test_support::{assert_contract_fixture, extract_field_paths};

    let event = NativeRuntimeEvent::from(android_support::NativeEventRecord {
        source: "tunnel".to_string(),
        level: "info".to_string(),
        message: "test".to_string(),
        created_at: 1000,
        kind: Some("runtime_stopped".to_string()),
        runtime_id: Some("rt-1".to_string()),
        mode: Some("auto".to_string()),
        policy_signature: Some("sig".to_string()),
        fingerprint_hash: Some("hash".to_string()),
        diagnostics_session_id: Some("diag-1".to_string()),
        subsystem: Some("tunnel".to_string()),
    });

    let json = serde_json::to_value(&event).expect("serialize event");
    assert!(json.get("diagnosticsSessionId").is_none());
    let paths = extract_field_paths(&json);
    let manifest = serde_json::to_string_pretty(&paths).expect("serialize field paths");
    assert_contract_fixture("tunnel_event_fields.json", &manifest);
}

#[test]
fn tunnel_snapshot_json_carries_schema_version() {
    let state = TunnelTelemetryState::new(None);
    let snapshot = state.snapshot((0, 0, 0, 0), DnsStatsSnapshot::default(), None, None);
    assert_eq!(snapshot.schema_version, SNAPSHOT_SCHEMA_VERSION);

    let value = serde_json::to_value(&snapshot).expect("serialize tunnel snapshot");
    assert_eq!(value["schemaVersion"], json!(3));
}

#[test]
fn tunnel_snapshot_omits_connection_quality_until_sample_recorded() {
    let state = TunnelTelemetryState::new(None);
    let snapshot = state.snapshot((0, 0, 0, 0), DnsStatsSnapshot::default(), None, None);
    assert!(snapshot.connection_quality.is_none());

    let value = serde_json::to_value(&snapshot).expect("serialize tunnel snapshot");
    assert!(
        value.get("connectionQuality").is_none(),
        "connectionQuality must be skipped from JSON when no samples recorded, got: {value}",
    );
}

#[test]
fn tunnel_snapshot_includes_connection_quality_after_sample_recorded() {
    use ripdpi_quality::QualitySample;

    let state = TunnelTelemetryState::new(None);
    state.quality_window.record(QualitySample { rtt_ms: 42, succeeded: true, loss_pct: 0.0 });
    state.quality_window.record(QualitySample { rtt_ms: 0, succeeded: false, loss_pct: 0.0 });

    let snapshot = state.snapshot((0, 0, 0, 0), DnsStatsSnapshot::default(), None, None);
    let quality = snapshot.connection_quality.as_ref().expect("connection_quality populated");
    assert_eq!(quality.sample_count, 1);
    assert_eq!(quality.rtt_p50_ms, 42);
    assert!((quality.loss_pct - 50.0).abs() < 0.01, "loss_pct={}", quality.loss_pct);

    let value = serde_json::to_value(&snapshot).expect("serialize tunnel snapshot");
    let wire = value.get("connectionQuality").expect("connectionQuality present in JSON");
    assert_eq!(wire["sampleCount"], json!(1));
    assert_eq!(wire["rttP50Ms"], json!(42));
    assert_eq!(wire["transportKind"], json!("tcp_tunnel"));
}
