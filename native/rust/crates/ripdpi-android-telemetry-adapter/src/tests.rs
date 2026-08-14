use super::types::{
    NativeRuntimeEvent, NativeRuntimeSnapshot, ProxyForwardingEvidenceSnapshot, SNAPSHOT_SCHEMA_VERSION,
    TunnelStatsSnapshot,
};
use super::*;

use std::net::SocketAddr;
use std::sync::{Arc, Barrier};
use std::thread;

use android_support::{EventRingBuffers, EventRingLayer, RingConfig};
use golden_test_support::{assert_text_golden, canonicalize_json_with};
use ripdpi_failure_classifier::{ClassifiedFailure, FailureAction, FailureClass, FailureStage};
use ripdpi_runtime_api::RuntimeTelemetrySink;
use ripdpi_telemetry::LatencyDistributions;
use serde_json::Value;
use tracing_subscriber::prelude::*;

fn assert_proxy_snapshot_golden(name: &str, snapshot: &NativeRuntimeSnapshot) {
    let actual = canonicalize_json_with(
        &serde_json::to_string(snapshot).expect("serialize proxy snapshot"),
        scrub_runtime_timestamps,
    )
    .expect("canonicalize proxy telemetry");
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

fn with_proxy_event_capture<R>(f: impl FnOnce(EventRingBuffers) -> R) -> R {
    let buffers = EventRingBuffers::new(RingConfig::default());
    let subscriber = tracing_subscriber::registry().with(EventRingLayer::new(buffers.clone()));
    tracing::subscriber::with_default(subscriber, || f(buffers))
}

fn snapshot_with_captured_events(state: &ProxyTelemetryState, buffers: &EventRingBuffers) -> NativeRuntimeSnapshot {
    let mut snapshot = state.snapshot();
    snapshot.native_events = buffers.drain_proxy().into_iter().map(NativeRuntimeEvent::from).collect();
    snapshot
}

#[test]
fn proxy_telemetry_observer_updates_snapshot_and_drains_events() {
    with_proxy_event_capture(|buffers| {
        let state = Arc::new(ProxyTelemetryState::new(None));
        let observer = ProxyTelemetryObserver { state: state.clone() };
        let listener = SocketAddr::from(([127, 0, 0, 1], 1080));
        let target = SocketAddr::from(([203, 0, 113, 10], 443));

        observer.on_listener_started(listener, 256, 3);
        observer.on_client_accepted();
        observer.on_route_selected(target, 1, Some("example.org"), "connect");
        observer.on_upstream_connected(target, Some(87));
        observer.on_route_advanced(target, 1, 2, 7, Some("example.org"));
        observer.on_host_autolearn_state(true, 4, 1, 2, Some("tcp_reset"), Some("rkn"));
        observer.on_host_autolearn_event("host_promoted", Some("example.org"), Some(2));
        observer.on_client_error(&std::io::Error::other("boom"));
        observer.on_client_finished();

        let first = snapshot_with_captured_events(&state, &buffers);
        assert_eq!(first.state, "running");
        assert_eq!(first.health, "degraded");
        assert_eq!(first.active_sessions, 0);
        assert_eq!(first.total_sessions, 1);
        assert_eq!(first.total_errors, 1);
        assert_eq!(first.route_changes, 1);
        assert_eq!(first.last_route_group, Some(2));
        assert_eq!(first.listener_address.as_deref(), Some("127.0.0.1:1080"));
        assert_eq!(first.upstream_address.as_deref(), Some("203.0.113.10:443"));
        assert_eq!(first.upstream_rtt_ms, Some(87));
        assert_eq!(first.last_target.as_deref(), Some("203.0.113.10:443"));
        assert_eq!(first.last_host.as_deref(), Some("example.org"));
        assert_eq!(first.last_error.as_deref(), Some("boom"));
        assert!(first.autolearn_enabled);
        assert_eq!(first.learned_host_count, 4);
        assert_eq!(first.penalized_host_count, 1);
        assert_eq!(first.blocked_host_count, 2);
        assert_eq!(first.last_block_signal.as_deref(), Some("tcp_reset"));
        assert_eq!(first.last_block_provider.as_deref(), Some("rkn"));
        assert_eq!(first.last_autolearn_host.as_deref(), Some("example.org"));
        assert_eq!(first.last_autolearn_group, Some(2));
        assert_eq!(first.last_autolearn_action.as_deref(), Some("host_promoted"));
        assert_eq!(first.native_events.len(), 5);
        assert_eq!(first.native_events[0].kind.as_deref(), Some("runtime_ready"));

        let second = snapshot_with_captured_events(&state, &buffers);
        assert!(second.native_events.is_empty());
        assert_eq!(second.total_sessions, 1);

        observer.on_listener_stopped();
        let stopped = snapshot_with_captured_events(&state, &buffers);
        assert_eq!(stopped.state, "idle");
        assert_eq!(stopped.active_sessions, 0);
        assert_eq!(stopped.native_events.len(), 1);
        assert_eq!(stopped.native_events[0].kind.as_deref(), Some("runtime_stopped"));
    });
}

#[test]
fn proxy_forwarding_evidence_counts_protect_outcomes_and_bytes_without_sensitive_fields() {
    let state = Arc::new(ProxyTelemetryState::new(None));
    let observer = ProxyTelemetryObserver { state: state.clone() };
    let listener = SocketAddr::from(([127, 0, 0, 1], 1080));
    let target = SocketAddr::from(([203, 0, 113, 10], 443));

    observer.on_listener_started(listener, 256, 1);
    observer.on_client_accepted();
    observer.on_upstream_socket_created();
    observer.on_upstream_protect_attempted();
    observer.on_upstream_protect_succeeded();
    observer.on_upstream_connected(target, Some(42));
    observer.on_upstream_socket_created();
    observer.on_upstream_protect_attempted();
    observer.on_upstream_protect_rejected();
    observer.on_upstream_connect_failed(target, 7, std::io::ErrorKind::PermissionDenied);
    observer.on_upstream_socket_created();
    observer.on_upstream_protect_attempted();
    observer.on_upstream_protect_error();
    observer.on_upstream_connect_failed(target, 9, std::io::ErrorKind::ConnectionRefused);
    observer.on_upstream_application_bytes_forwarded(128, 1_000);
    observer.on_upstream_application_bytes_forwarded(0, 1_100);
    observer.on_upstream_application_bytes_forwarded(384, 1_200);

    let evidence = state.forwarding_evidence_snapshot();
    assert_eq!(evidence.proxy_client_sockets_accepted, 1);
    assert_eq!(evidence.upstream_socket_created, 3);
    assert_eq!(evidence.upstream_opened, 1);
    assert_eq!(evidence.upstream_open_failures, 2);
    assert_eq!(evidence.protect_attempted, 3);
    assert_eq!(evidence.protect_succeeded, 1);
    assert_eq!(evidence.protect_rejected, 1);
    assert_eq!(evidence.protect_errors, 1);
    assert_eq!(evidence.upstream_application_bytes, 512);
    assert_eq!(evidence.first_upstream_application_forwarded_at, Some(1_000));
    assert_eq!(evidence.last_upstream_application_forwarded_at, Some(1_200));

    let payload = serde_json::to_string(&evidence).expect("serialize evidence");
    assert!(!payload.contains("203.0.113.10"));
    assert!(!payload.contains("fd"));
    assert!(!payload.contains("PermissionDenied"));
}

#[test]
fn empty_proxy_forwarding_evidence_omits_absent_timestamps() {
    let state = ProxyTelemetryState::new(None);
    let evidence = state.forwarding_evidence_snapshot();
    let expected = ProxyForwardingEvidenceSnapshot {
        proxy_client_sockets_accepted: 0,
        upstream_socket_created: 0,
        upstream_opened: 0,
        upstream_open_failures: 0,
        protect_attempted: 0,
        protect_succeeded: 0,
        protect_rejected: 0,
        protect_errors: 0,
        upstream_application_bytes: 0,
        first_upstream_application_forwarded_at: None,
        last_upstream_application_forwarded_at: None,
    };

    assert_eq!(
        serde_json::to_value(&evidence).expect("serialize"),
        serde_json::to_value(&expected).expect("serialize")
    );
    let payload = serde_json::to_string(&evidence).expect("serialize evidence");
    assert!(!payload.contains("firstUpstreamApplicationForwardedAt"));
    assert!(!payload.contains("lastUpstreamApplicationForwardedAt"));
}

#[test]
fn proxy_forwarding_evidence_concurrent_byte_publication_preserves_timestamp_bounds() {
    let state = Arc::new(ProxyTelemetryState::new(None));
    let start = Arc::new(Barrier::new(5));
    let writer_timestamps = [1_400, 1_000, 1_600, 1_200];
    let mut writers = Vec::new();

    for timestamp in writer_timestamps {
        let state = state.clone();
        let start = start.clone();
        writers.push(thread::spawn(move || {
            let observer = ProxyTelemetryObserver { state };
            start.wait();
            observer.on_upstream_application_bytes_forwarded(100, timestamp);
        }));
    }

    let reader_state = state.clone();
    let reader_start = start.clone();
    let reader = thread::spawn(move || {
        reader_start.wait();
        loop {
            let evidence = reader_state.forwarding_evidence_snapshot();
            if evidence.upstream_application_bytes > 0 {
                assert!(evidence.first_upstream_application_forwarded_at.is_some());
                assert!(evidence.last_upstream_application_forwarded_at.is_some());
                break;
            }
            thread::yield_now();
        }
    });

    for writer in writers {
        writer.join().expect("writer joins");
    }
    reader.join().expect("reader joins");

    let evidence = state.forwarding_evidence_snapshot();
    assert_eq!(evidence.upstream_application_bytes, 400);
    assert_eq!(evidence.first_upstream_application_forwarded_at, Some(1_000));
    assert_eq!(evidence.last_upstream_application_forwarded_at, Some(1_600));
}

#[test]
fn proxy_retry_pacing_telemetry_tracks_backoff_and_diversification_separately() {
    with_proxy_event_capture(|buffers| {
        let state = Arc::new(ProxyTelemetryState::new(None));
        let observer = ProxyTelemetryObserver { state: state.clone() };
        let target = SocketAddr::from(([203, 0, 113, 10], 443));

        observer.on_retry_paced(target, 1, "same_signature_retry", 700);
        let paced = snapshot_with_captured_events(&state, &buffers);
        assert_eq!(paced.retry_paced_count, 1);
        assert_eq!(paced.last_retry_backoff_ms, Some(700));
        assert_eq!(paced.last_retry_reason.as_deref(), Some("same_signature_retry"));
        assert_eq!(paced.candidate_diversification_count, 0);
        assert_eq!(paced.native_events.len(), 1);

        observer.on_retry_paced(target, 2, "candidate_order_diversified", 0);
        let diversified = snapshot_with_captured_events(&state, &buffers);
        assert_eq!(diversified.retry_paced_count, 1);
        assert_eq!(diversified.last_retry_backoff_ms, None);
        assert_eq!(diversified.last_retry_reason.as_deref(), Some("candidate_order_diversified"));
        assert_eq!(diversified.candidate_diversification_count, 1);
        assert_eq!(diversified.native_events.len(), 1);
    });
}

#[test]
fn proxy_ws_tunnel_events_preserve_proxy_snapshot_contract() {
    with_proxy_event_capture(|buffers| {
        let state = Arc::new(ProxyTelemetryState::new(None));
        let observer = ProxyTelemetryObserver { state: state.clone() };
        let target = SocketAddr::from(([149, 154, 167, 51], 443));

        observer.on_telegram_dc_detected(target, 2);
        observer.on_ws_tunnel_escalation(target, 2, false);

        let snapshot = snapshot_with_captured_events(&state, &buffers);
        assert_eq!(snapshot.last_target.as_deref(), Some("149.154.167.51:443"));
        assert_eq!(snapshot.native_events.len(), 2);
        assert!(snapshot.native_events[0].message.contains("telegram dc detected"));
        assert!(snapshot.native_events[1].message.contains("ws tunnel escalation"));
        assert_eq!(snapshot.native_events[0].level, "info");
        assert_eq!(snapshot.native_events[1].level, "warn");
    });
}

#[test]
fn proxy_ws_tunnel_fake_sni_counter_increments_and_surfaces_in_snapshot() {
    with_proxy_event_capture(|buffers| {
        let state = Arc::new(ProxyTelemetryState::new(None));
        let observer = ProxyTelemetryObserver { state: state.clone() };
        let target = SocketAddr::from(([149, 154, 167, 51], 443));

        assert_eq!(snapshot_with_captured_events(&state, &buffers).ws_tunnel_fake_sni_active, 0);

        observer.on_ws_tunnel_fake_sni_active(target, 2);
        observer.on_ws_tunnel_fake_sni_active(target, 2);

        let snapshot = snapshot_with_captured_events(&state, &buffers);
        assert_eq!(snapshot.ws_tunnel_fake_sni_active, 2);
        assert_eq!(snapshot.last_target.as_deref(), Some("149.154.167.51:443"));
        assert!(snapshot.native_events.iter().any(|event| event.message.contains("ws tunnel fake-sni handshake")));
    });
}

#[test]
fn failure_classification_telemetry_records_strategy_execution_context() {
    with_proxy_event_capture(|buffers| {
        let state = Arc::new(ProxyTelemetryState::new(None));
        let observer = ProxyTelemetryObserver { state: state.clone() };
        let target = SocketAddr::from(([203, 0, 113, 10], 443));
        let failure = ClassifiedFailure::new(
            FailureClass::StrategyExecutionFailure,
            FailureStage::FirstWrite,
            FailureAction::RetryWithMatchingGroup,
            "desync action=set_ttl: Invalid argument (os error 22)",
        )
        .with_tag("action", "set_ttl")
        .with_tag("errno", "22");

        observer.on_failure_classified(target, &failure, Some("example.org"));

        let snapshot = snapshot_with_captured_events(&state, &buffers);
        assert_eq!(snapshot.last_failure_class.as_deref(), Some("strategy_execution_failure"));
        assert_eq!(snapshot.last_fallback_action.as_deref(), Some("retry_with_matching_group"));
        assert_eq!(snapshot.last_error.as_deref(), Some("desync action=set_ttl: Invalid argument (os error 22)"));
        assert_eq!(snapshot.network_errors, 1);
        assert!(snapshot.native_events.iter().any(|event| {
            event.message.contains("class=strategy_execution_failure") && event.message.contains("action=set_ttl")
        }));
    });
}

#[test]
fn proxy_telemetry_snapshots_match_goldens() {
    with_proxy_event_capture(|buffers| {
        let idle = snapshot_with_captured_events(&ProxyTelemetryState::new(None), &buffers);
        assert_proxy_snapshot_golden("proxy_idle", &idle);

        let state = Arc::new(ProxyTelemetryState::new(None));
        let observer = ProxyTelemetryObserver { state: state.clone() };
        let listener = SocketAddr::from(([127, 0, 0, 1], 1080));
        let target = SocketAddr::from(([203, 0, 113, 10], 443));

        observer.on_listener_started(listener, 256, 3);
        observer.on_client_accepted();
        observer.on_route_selected(target, 1, Some("example.org"), "connect");
        observer.on_upstream_connected(target, Some(87));
        observer.on_route_advanced(target, 1, 2, 7, Some("example.org"));
        observer.on_host_autolearn_state(true, 4, 1, 2, Some("tcp_reset"), Some("rkn"));
        observer.on_host_autolearn_event("host_promoted", Some("example.org"), Some(2));
        observer.on_client_error(&std::io::Error::other("boom"));
        observer.on_client_finished();

        let running = snapshot_with_captured_events(&state, &buffers);
        assert_proxy_snapshot_golden("proxy_running_degraded_first_poll", &running);

        let drained = snapshot_with_captured_events(&state, &buffers);
        assert_proxy_snapshot_golden("proxy_running_degraded_second_poll", &drained);

        observer.on_listener_stopped();
        let stopped = snapshot_with_captured_events(&state, &buffers);
        assert_proxy_snapshot_golden("proxy_stopped", &stopped);
    });
}

#[test]
fn clear_last_error_resets_field_via_arc_swap() {
    let state = ProxyTelemetryState::new(None);
    state.on_client_error("first failure".to_string());
    assert_eq!(state.snapshot().last_error.as_deref(), Some("first failure"));

    state.clear_last_error();
    assert!(state.snapshot().last_error.is_none());
}

#[test]
fn events_and_strings_are_independent_after_split() {
    with_proxy_event_capture(|buffers| {
        let state = ProxyTelemetryState::new(None);

        // Push an event without updating any string field
        state.push_event("test", "info", "standalone event".to_string());
        let snap = snapshot_with_captured_events(&state, &buffers);
        assert!(snap.listener_address.is_none());
        assert!(snap.last_error.is_none());

        // Update a string field without pushing an event
        state.on_upstream_connected("10.0.0.1:443".to_string(), Some(42));
        let snap2 = snapshot_with_captured_events(&state, &buffers);
        assert!(snap2.native_events.is_empty(), "events were drained in previous snapshot");
        assert_eq!(snap2.upstream_address.as_deref(), Some("10.0.0.1:443"));
        assert_eq!(snap2.upstream_rtt_ms, Some(42));
    });
}

#[test]
fn concurrent_writers_do_not_lose_string_updates() {
    use std::sync::Barrier;

    let state = Arc::new(ProxyTelemetryState::new(None));
    let barrier = Arc::new(Barrier::new(2));
    let iterations = 500;

    // Thread A: repeatedly sets last_error
    let state_a = state.clone();
    let barrier_a = barrier.clone();
    let thread_a = std::thread::spawn(move || {
        barrier_a.wait();
        for i in 0..iterations {
            state_a.on_client_error(format!("error-{i}"));
        }
    });

    // Thread B: repeatedly sets route/target
    let state_b = state.clone();
    let barrier_b = barrier.clone();
    let thread_b = std::thread::spawn(move || {
        barrier_b.wait();
        for i in 0..iterations {
            state_b.on_route_selected(format!("target-{i}"), i % 3, Some(format!("host-{i}")), "connect");
        }
    });

    thread_a.join().expect("thread_a panicked");
    thread_b.join().expect("thread_b panicked");

    // Final snapshot should have the last values from both threads
    let snap = state.snapshot();
    assert!(snap.last_error.is_some(), "last_error should be set");
    assert!(snap.last_target.is_some(), "last_target should be set");
    assert!(snap.last_host.is_some(), "last_host should be set");

    // Verify values are from the last iterations (rcu ensures no lost updates)
    assert!(
        snap.last_error.as_ref().unwrap().starts_with("error-"),
        "last_error should be from thread_a: {:?}",
        snap.last_error,
    );
    assert!(
        snap.last_target.as_ref().unwrap().starts_with("target-"),
        "last_target should be from thread_b: {:?}",
        snap.last_target,
    );
}

#[test]
fn snapshot_reads_do_not_block_under_concurrent_writes() {
    use std::sync::Barrier;

    let state = Arc::new(ProxyTelemetryState::new(None));
    let barrier = Arc::new(Barrier::new(2));
    let iterations = 500;

    let writer_state = state.clone();
    let writer_barrier = barrier.clone();
    let writer = std::thread::spawn(move || {
        writer_barrier.wait();
        for i in 0..iterations {
            writer_state.on_client_error(format!("err-{i}"));
        }
    });

    let reader_state = state.clone();
    let reader_barrier = barrier.clone();
    let reader = std::thread::spawn(move || {
        reader_barrier.wait();
        let mut snapshots = 0u32;
        for _ in 0..iterations {
            let snap = reader_state.snapshot();
            // String fields should never be partially updated
            if let Some(ref error) = snap.last_error {
                assert!(error.starts_with("err-"), "corrupted error field: {error}");
            }
            snapshots += 1;
        }
        snapshots
    });

    writer.join().expect("writer panicked");
    let count = reader.join().expect("reader panicked");
    assert_eq!(count, iterations as u32);
}

#[test]
fn proxy_snapshot_field_manifest_matches_contract_fixture() {
    use golden_test_support::{assert_contract_fixture, extract_field_paths};

    let snapshot = NativeRuntimeSnapshot {
        source: "proxy".to_string(),
        schema_version: SNAPSHOT_SCHEMA_VERSION,
        state: "running".to_string(),
        health: "healthy".to_string(),
        active_sessions: 1,
        total_sessions: 10,
        total_errors: 2,
        network_errors: 1,
        route_changes: 3,
        retry_paced_count: 1,
        last_retry_backoff_ms: Some(500),
        last_retry_reason: Some("backoff".to_string()),
        candidate_diversification_count: 1,
        last_route_group: Some(0),
        last_failure_class: Some("tcp_reset".to_string()),
        last_fallback_action: Some("retry_with_matching_group".to_string()),
        adaptive_override_active: true,
        adaptive_trigger_mask: Some(7),
        adaptive_last_trigger: Some("dns_timeout".to_string()),
        adaptive_override_reason: Some("forced_reorder".to_string()),
        strategy_pack_id: Some("baseline-stable".to_string()),
        strategy_pack_version: Some("2026.04.1".to_string()),
        tls_profile_id: Some("chrome_stable".to_string()),
        tls_profile_catalog_version: Some("v1".to_string()),
        morph_policy_id: Some("balanced".to_string()),
        morph_hint_family: Some("tcp:wide:entropy".to_string()),
        morph_rollback_reason: Some("direct_path_capability_downgrade".to_string()),
        quic_migration_status: Some("rebind_only".to_string()),
        quic_migration_reason: Some("udp_source_port_rebind_after_handshake".to_string()),
        pt_runtime_kind: None,
        pt_runtime_state: None,
        listener_address: Some("127.0.0.1:1080".to_string()),
        upstream_address: Some("203.0.113.10:443".to_string()),
        upstream_rtt_ms: Some(42),
        last_target: Some("203.0.113.10:443".to_string()),
        last_host: Some("example.org".to_string()),
        last_error: Some("connection reset".to_string()),
        autolearn_enabled: true,
        learned_host_count: 5,
        penalized_host_count: 1,
        blocked_host_count: 2,
        last_block_signal: Some("tcp_reset".to_string()),
        last_block_provider: Some("rkn".to_string()),
        last_autolearn_host: Some("example.org".to_string()),
        last_autolearn_group: Some(0),
        last_autolearn_action: Some("group_penalized".to_string()),
        slot_exhaustions: 1,
        ws_tunnel_fake_sni_active: 2,
        profile_id: Some("relay-profile".to_string()),
        protocol_kind: Some("hysteria2".to_string()),
        tcp_capable: Some(true),
        udp_capable: Some(true),
        fallback_mode: None,
        last_handshake_error: None,
        chain_entry_state: Some("connected".to_string()),
        chain_exit_state: Some("ready".to_string()),
        tunnel_stats: TunnelStatsSnapshot { tx_packets: 100, tx_bytes: 5000, rx_packets: 80, rx_bytes: 4000 },
        native_events: vec![NativeRuntimeEvent {
            source: "proxy".to_string(),
            level: "info".to_string(),
            message: "test".to_string(),
            created_at: 1000,
            kind: Some("runtime_ready".to_string()),
            runtime_id: Some("rt-1".to_string()),
            mode: Some("auto".to_string()),
            policy_signature: Some("sig".to_string()),
            fingerprint_hash: Some("hash".to_string()),
            subsystem: Some("proxy".to_string()),
        }],
        latency_distributions: Some(LatencyDistributions {
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
        direct_path_learning_signals: vec![],
        captured_at: 1000,
    };

    let json = serde_json::to_value(&snapshot).expect("serialize proxy snapshot");
    let paths = extract_field_paths(&json);
    let manifest = serde_json::to_string_pretty(&paths).expect("serialize field paths");
    assert_contract_fixture("proxy_snapshot_fields.json", &manifest);
}

#[test]
fn proxy_event_field_manifest_matches_contract_fixture() {
    use golden_test_support::{assert_contract_fixture, extract_field_paths};

    let event = NativeRuntimeEvent::from(android_support::NativeEventRecord {
        source: "proxy".to_string(),
        level: "info".to_string(),
        message: "test".to_string(),
        created_at: 1000,
        kind: Some("runtime_ready".to_string()),
        runtime_id: Some("rt-1".to_string()),
        mode: Some("auto".to_string()),
        policy_signature: Some("sig".to_string()),
        fingerprint_hash: Some("hash".to_string()),
        diagnostics_session_id: Some("diag-1".to_string()),
        subsystem: Some("proxy".to_string()),
    });

    let json = serde_json::to_value(&event).expect("serialize event");
    assert!(json.get("diagnosticsSessionId").is_none());
    let paths = extract_field_paths(&json);
    let manifest = serde_json::to_string_pretty(&paths).expect("serialize field paths");
    assert_contract_fixture("proxy_event_fields.json", &manifest);
}

#[test]
fn proxy_snapshot_json_carries_schema_version() {
    let snapshot = ProxyTelemetryState::new(None).snapshot();
    assert_eq!(snapshot.schema_version, SNAPSHOT_SCHEMA_VERSION);

    let json = serde_json::to_value(&snapshot).expect("serialize proxy snapshot");
    assert_eq!(json["schemaVersion"], serde_json::json!(SNAPSHOT_SCHEMA_VERSION));
}
