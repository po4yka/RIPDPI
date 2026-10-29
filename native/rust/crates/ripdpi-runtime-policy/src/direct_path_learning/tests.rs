use super::*;

use std::sync::Mutex;

#[derive(Default)]
struct RecordingTelemetry {
    signals: Mutex<Vec<(String, String, &'static str, Option<String>)>>,
}

impl DirectPathLearningObserver for RecordingTelemetry {
    fn on_direct_path_learning_signal(
        &self,
        authority: &str,
        ip_set_digest: &str,
        event: &'static str,
        strategy_family: Option<&str>,
    ) {
        self.signals.lock().expect("signals").push((
            authority.to_string(),
            ip_set_digest.to_string(),
            event,
            strategy_family.map(ToOwned::to_owned),
        ));
    }
}

#[test]
fn udp_failure_then_tcp_success_emits_quic_blocked_signal() {
    let telemetry = RecordingTelemetry::default();
    let targets = vec!["203.0.113.10:443".parse().expect("target"), "203.0.113.11:443".parse().expect("target")];
    let mut learner = DirectPathLearningState::default();

    learner.note_udp_failure(Some("example.org"), &targets);
    learner.note_tcp_success(Some(&telemetry), Some("example.org"), &targets, Some("tlsrec_split"));

    let signals = telemetry.signals.lock().expect("signals");
    assert_eq!(signals.len(), 1);
    assert_eq!(signals[0].2, "QUIC_BLOCKED_TCP_OK");
}

#[test]
fn tls_failure_then_tcp_success_emits_post_client_hello_signal_with_family() {
    let telemetry = RecordingTelemetry::default();
    let targets = vec!["203.0.113.10:443".parse().expect("target")];
    let mut learner = DirectPathLearningState::default();

    learner.note_tls_post_client_hello_failure(Some("example.org"), &targets);
    learner.note_tcp_success(Some(&telemetry), Some("example.org"), &targets, Some("tlsrec_split"));

    let signals = telemetry.signals.lock().expect("signals");
    assert_eq!(signals.len(), 1);
    assert_eq!(signals[0].2, "TCP_POST_CLIENT_HELLO_FAILURE_TCP_OK");
    assert_eq!(signals[0].3.as_deref(), Some("tlsrec_split"));
}

#[test]
fn learner_emits_all_ips_failed_once_per_transition() {
    let telemetry = RecordingTelemetry::default();
    let targets = vec!["203.0.113.10:443".parse().expect("target")];
    let mut learner = DirectPathLearningState::default();

    learner.note_all_ips_failed(Some(&telemetry), Some("example.org"), &targets);
    learner.note_all_ips_failed(Some(&telemetry), Some("example.org"), &targets);

    let signals = telemetry.signals.lock().expect("signals");
    assert_eq!(signals.len(), 1);
    assert_eq!(signals[0].2, "ALL_IPS_FAILED");
}

#[test]
fn owned_stack_required_signal_emits_once_per_tuple() {
    let telemetry = RecordingTelemetry::default();
    let targets = vec!["203.0.113.10:443".parse().expect("target")];
    let mut learner = DirectPathLearningState::default();

    learner.note_owned_stack_required(Some(&telemetry), Some("example.org"), &targets);
    learner.note_owned_stack_required(Some(&telemetry), Some("example.org"), &targets);

    let signals = telemetry.signals.lock().expect("signals");
    assert_eq!(signals.len(), 1);
    assert_eq!(signals[0].2, "OWNED_STACK_REQUIRED");
}

#[test]
fn no_tcp_fallback_timeout_emits_signal_and_tcp_attempt_clears_pending_state() {
    let telemetry = RecordingTelemetry::default();
    let targets = vec!["203.0.113.10:443".parse().expect("target")];
    let mut learner = DirectPathLearningState::default();

    learner.note_udp_suppressed(Some("example.org"), &targets, 10);
    learner.note_transport_attempt(Some("example.org"), &targets, TransportProtocol::Tcp);
    learner.emit_due_timeouts(Some(&telemetry), 3_100);
    assert!(telemetry.signals.lock().expect("signals").is_empty());

    learner.note_udp_suppressed(Some("example.org"), &targets, 10);
    learner.emit_due_timeouts(Some(&telemetry), 3_100);

    let signals = telemetry.signals.lock().expect("signals");
    assert_eq!(signals.len(), 1);
    assert_eq!(signals[0].2, "NO_TCP_FALLBACK_DETECTED");
}

#[test]
fn quic_success_clears_negative_state_and_allows_future_relearning() {
    let telemetry = RecordingTelemetry::default();
    let targets = vec!["203.0.113.10:443".parse().expect("target")];
    let mut learner = DirectPathLearningState::default();

    learner.note_udp_failure(Some("example.org"), &targets);
    learner.note_quic_success(Some(&telemetry), Some("example.org"), &targets);
    learner.note_udp_failure(Some("example.org"), &targets);
    learner.note_tcp_success(Some(&telemetry), Some("example.org"), &targets, Some("split"));

    let signals = telemetry.signals.lock().expect("signals");
    assert_eq!(signals.len(), 2);
    assert_eq!(signals[0].2, "QUIC_SUCCESS");
    assert_eq!(signals[1].2, "QUIC_BLOCKED_TCP_OK");
}

#[test]
fn attempts_and_successes_without_negative_state_do_not_create_tuples() {
    let telemetry = RecordingTelemetry::default();
    let mut learner = DirectPathLearningState::default();
    for port in 1..=64u16 {
        let targets = vec![SocketAddr::from(([203, 0, 113, 10], port))];
        learner.note_transport_attempt(None, &targets, TransportProtocol::Tcp);
        learner.note_transport_attempt(None, &targets, TransportProtocol::Udp);
        learner.note_tcp_success(Some(&telemetry), None, &targets, None);
    }
    assert!(learner.tuples.is_empty());
    assert!(telemetry.signals.lock().expect("signals").is_empty());
}

#[test]
fn tuple_map_is_bounded() {
    let mut learner = DirectPathLearningState::default();
    for index in 0..(MAX_DIRECT_PATH_TUPLES as u32 + 16) {
        let targets = vec![SocketAddr::from((std::net::Ipv4Addr::from(0x0A00_0000 + index), 443))];
        learner.note_udp_failure(None, &targets);
    }
    assert_eq!(learner.tuples.len(), MAX_DIRECT_PATH_TUPLES);
}

#[test]
fn tuple_cap_evicts_the_least_recently_written_tuple() {
    let mut learner = DirectPathLearningState::default();
    let target = |index: u32| vec![SocketAddr::from((std::net::Ipv4Addr::from(0x0A00_0000 + index), 443))];
    for index in 0..MAX_DIRECT_PATH_TUPLES as u32 {
        learner.note_udp_failure(None, &target(index));
    }
    learner.note_tls_post_client_hello_failure(None, &target(0));
    learner.note_udp_failure(None, &target(MAX_DIRECT_PATH_TUPLES as u32));

    let survivor = tuple_key_for_targets(None, &target(0)).expect("key");
    let evicted = tuple_key_for_targets(None, &target(1)).expect("key");
    assert!(learner.tuples[&survivor].tls_post_client_hello_failed, "recently written tuple survives");
    assert!(!learner.tuples.contains_key(&evicted), "oldest write is evicted");
    assert_eq!(learner.tuples.len(), MAX_DIRECT_PATH_TUPLES);
}

#[test]
fn due_timeout_check_tracks_the_earliest_pending_suppression() {
    let telemetry = RecordingTelemetry::default();
    let first = vec!["203.0.113.10:443".parse().expect("target")];
    let second = vec!["203.0.113.20:443".parse().expect("target")];
    let mut learner = DirectPathLearningState::default();
    assert!(!learner.has_due_timeouts(u64::MAX));

    learner.note_udp_suppressed(Some("a.example"), &first, 1_000);
    learner.note_udp_suppressed(Some("b.example"), &second, 2_000);
    assert!(!learner.has_due_timeouts(3_999));
    assert!(learner.has_due_timeouts(4_000));

    learner.emit_due_timeouts(Some(&telemetry), 4_000);
    assert_eq!(telemetry.signals.lock().expect("signals").len(), 1);
    assert!(!learner.has_due_timeouts(4_999), "the bound moves to the remaining pending tuple");
    assert!(learner.has_due_timeouts(5_000));
}
