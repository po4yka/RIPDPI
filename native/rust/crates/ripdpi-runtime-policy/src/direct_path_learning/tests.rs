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

// Ranked-arm dispatcher

#[test]
fn ranked_arms_clean_tuple_prefers_quic() {
    let learner = DirectPathLearningState::default();
    let targets = vec!["203.0.113.10:443".parse().expect("target")];
    let arms = learner.ranked_arms_for(Some("example.org"), &targets);
    assert!(!arms.is_empty(), "must return at least one arm");
    assert_eq!(arms[0].label, "quic", "clean tuple: quic should rank first");
    assert!(arms[0].score > arms[1].score, "scores must be strictly descending");
    assert_eq!(arms[0].class, DirectPathBlockClass::Clean);
}

#[test]
fn ranked_arms_quic_blocked_prefers_tcp() {
    let targets = vec!["203.0.113.10:443".parse().expect("target")];
    let mut learner = DirectPathLearningState::default();
    learner.note_udp_failure(Some("example.org"), &targets);

    let arms = learner.ranked_arms_for(Some("example.org"), &targets);
    assert_eq!(arms[0].label, "tcp_plain", "quic_blocked: tcp_plain should rank first");
    assert_eq!(arms[0].class, DirectPathBlockClass::QuicBlocked);
}

#[test]
fn ranked_arms_tls_post_client_hello_prefers_split() {
    let targets = vec!["203.0.113.10:443".parse().expect("target")];
    let mut learner = DirectPathLearningState::default();
    learner.note_tls_post_client_hello_failure(Some("example.org"), &targets);

    let arms = learner.ranked_arms_for(Some("example.org"), &targets);
    assert_eq!(arms[0].label, "tcp_tls_split", "tls_post_client_hello: split should rank first");
    assert_eq!(arms[0].class, DirectPathBlockClass::TlsPostClientHello);
}

#[test]
fn ranked_arms_all_ips_failed_returns_relay_fallback_only() {
    let telemetry = RecordingTelemetry::default();
    let targets = vec!["203.0.113.10:443".parse().expect("target")];
    let mut learner = DirectPathLearningState::default();
    learner.note_all_ips_failed(Some(&telemetry), Some("example.org"), &targets);

    let arms = learner.ranked_arms_for(Some("example.org"), &targets);
    assert_eq!(arms.len(), 1, "all_ips_failed: exactly one relay arm");
    assert_eq!(arms[0].label, "relay_fallback");
    assert_eq!(arms[0].attempt_budget, 1, "relay fallback budget should be 1");
    assert_eq!(arms[0].class, DirectPathBlockClass::AllIpsFailed);
}

#[test]
fn ranked_arms_quic_confirmed_ranks_quic_with_score_one() {
    let telemetry = RecordingTelemetry::default();
    let targets = vec!["203.0.113.10:443".parse().expect("target")];
    let mut learner = DirectPathLearningState::default();
    learner.note_quic_success(Some(&telemetry), Some("example.org"), &targets);

    let arms = learner.ranked_arms_for(Some("example.org"), &targets);
    assert_eq!(arms[0].label, "quic");
    assert!((arms[0].score - 1.0_f32).abs() < f32::EPSILON, "quic_confirmed: score should be 1.0");
    assert_eq!(arms[0].class, DirectPathBlockClass::QuicConfirmed);
}

#[test]
fn ranked_arms_unknown_host_returns_clean_arms() {
    let learner = DirectPathLearningState::default();
    // Host not seen before — should return Clean arms without panic.
    let targets = vec!["203.0.113.99:443".parse().expect("target")];
    let arms = learner.ranked_arms_for(Some("never-seen.example"), &targets);
    assert_eq!(arms[0].class, DirectPathBlockClass::Clean);
}

#[test]
fn block_class_for_reflects_both_udp_and_tls_failures() {
    let targets = vec!["203.0.113.10:443".parse().expect("target")];
    let mut learner = DirectPathLearningState::default();
    learner.note_udp_failure(Some("example.org"), &targets);
    learner.note_tls_post_client_hello_failure(Some("example.org"), &targets);

    let class = learner.block_class_for(Some("example.org"), &targets);
    assert_eq!(class, DirectPathBlockClass::QuicBlockedAndTlsPostClientHello);

    let arms = learner.ranked_arms_for(Some("example.org"), &targets);
    assert_eq!(arms[0].label, "tcp_tls_split");
    assert_eq!(arms[0].class, DirectPathBlockClass::QuicBlockedAndTlsPostClientHello);
}

#[test]
fn ranked_arms_are_strictly_score_descending() {
    // For every block class, verify the returned slice is sorted descending.
    let all_classes = [
        DirectPathBlockClass::Clean,
        DirectPathBlockClass::QuicBlocked,
        DirectPathBlockClass::TlsPostClientHello,
        DirectPathBlockClass::QuicBlockedAndTlsPostClientHello,
        DirectPathBlockClass::NoTcpFallback,
        DirectPathBlockClass::AllIpsFailed,
        DirectPathBlockClass::QuicConfirmed,
    ];
    for class in all_classes {
        let arms = ranked_arms_for_class(class);
        assert!(!arms.is_empty(), "{class:?}: arm list must be non-empty");
        for window in arms.windows(2) {
            assert!(
                window[0].score >= window[1].score,
                "{class:?}: arms must be score-descending, got {} then {}",
                window[0].score,
                window[1].score,
            );
        }
    }
}

// Per-class attempt-budget enforcement

#[test]
fn note_arm_attempt_decrements_remaining_budget() {
    let targets = vec!["203.0.113.10:443".parse().expect("target")];
    let mut learner = DirectPathLearningState::default();

    // Fresh tuple — full default budget.
    let initial = learner.ranked_arms_for(Some("example.org"), &targets);
    assert_eq!(initial[0].attempt_budget, DEFAULT_ATTEMPT_BUDGET);

    learner.note_arm_attempt(Some("example.org"), &targets, "quic");
    let after_one = learner.ranked_arms_for(Some("example.org"), &targets);
    assert_eq!(after_one[0].label, "quic");
    assert_eq!(
        after_one[0].attempt_budget,
        DEFAULT_ATTEMPT_BUDGET - 1,
        "remaining budget must reflect recorded attempts",
    );
    // Sibling arm is unaffected.
    assert_eq!(after_one[1].label, "tcp_plain");
    assert_eq!(after_one[1].attempt_budget, DEFAULT_ATTEMPT_BUDGET);
}

#[test]
fn ranked_arms_drops_exhausted_arm_and_keeps_remaining() {
    let targets = vec!["203.0.113.10:443".parse().expect("target")];
    let mut learner = DirectPathLearningState::default();

    for _ in 0..DEFAULT_ATTEMPT_BUDGET {
        learner.note_arm_attempt(Some("example.org"), &targets, "quic");
    }

    let arms = learner.ranked_arms_for(Some("example.org"), &targets);
    assert_eq!(arms.len(), 1, "exhausted quic should drop, leaving tcp_plain");
    assert_eq!(arms[0].label, "tcp_plain");
    assert_eq!(arms[0].attempt_budget, DEFAULT_ATTEMPT_BUDGET);
}

#[test]
fn ranked_arms_collapses_to_relay_fallback_when_all_arms_exhausted() {
    let targets = vec!["203.0.113.10:443".parse().expect("target")];
    let mut learner = DirectPathLearningState::default();

    for _ in 0..DEFAULT_ATTEMPT_BUDGET {
        learner.note_arm_attempt(Some("example.org"), &targets, "quic");
    }
    for _ in 0..DEFAULT_ATTEMPT_BUDGET {
        learner.note_arm_attempt(Some("example.org"), &targets, "tcp_plain");
    }

    let arms = learner.ranked_arms_for(Some("example.org"), &targets);
    assert_eq!(arms.len(), 1, "must escalate to relay fallback");
    assert_eq!(arms[0].label, "relay_fallback");
    assert_eq!(arms[0].class, DirectPathBlockClass::AllIpsFailed);
    assert_eq!(arms[0].attempt_budget, 1);
}

#[test]
fn positive_signal_resets_arm_attempts() {
    let telemetry = RecordingTelemetry::default();
    let targets = vec!["203.0.113.10:443".parse().expect("target")];
    let mut learner = DirectPathLearningState::default();

    // Burn the quic budget…
    for _ in 0..DEFAULT_ATTEMPT_BUDGET {
        learner.note_arm_attempt(Some("example.org"), &targets, "quic");
    }
    // …then a successful QUIC observation should clear counters.
    learner.note_quic_success(Some(&telemetry), Some("example.org"), &targets);

    let arms = learner.ranked_arms_for(Some("example.org"), &targets);
    assert_eq!(arms[0].class, DirectPathBlockClass::QuicConfirmed);
    assert_eq!(arms[0].attempt_budget, DEFAULT_ATTEMPT_BUDGET, "budget must reset on positive signal");
}

// Deterministic class-to-arm execution ladder

#[test]
fn class_to_arm_ladder_walks_clean_quic_blocked_exhausted_relay_and_back() {
    // Drive a single tuple through the full life cycle and assert the
    // ranked-arm response at every step. This pins the contract that
    // negative signals advance the class, attempt budgets shrink, the
    // exhausted arm drops, and a positive signal restores the original
    // ranking.
    let telemetry = RecordingTelemetry::default();
    let targets = vec!["203.0.113.10:443".parse().expect("target")];
    let mut learner = DirectPathLearningState::default();
    let host = Some("example.org");

    // Step 1: clean tuple → quic ranks first.
    let step1 = learner.ranked_arms_for(host, &targets);
    assert_eq!(step1[0].label, "quic");
    assert_eq!(step1[0].class, DirectPathBlockClass::Clean);
    assert_eq!(step1[1].label, "tcp_plain");

    // Step 2: UDP failure flips us to QuicBlocked → tcp_plain ranks first.
    learner.note_udp_failure(host, &targets);
    let step2 = learner.ranked_arms_for(host, &targets);
    assert_eq!(step2[0].label, "tcp_plain");
    assert_eq!(step2[0].class, DirectPathBlockClass::QuicBlocked);
    assert_eq!(step2[1].label, "tcp_tls_split");
    assert_eq!(step2[0].attempt_budget, DEFAULT_ATTEMPT_BUDGET);

    // Step 3: record three tcp_plain attempts → arm drops, tcp_tls_split is left.
    for _ in 0..DEFAULT_ATTEMPT_BUDGET {
        learner.note_arm_attempt(host, &targets, "tcp_plain");
    }
    let step3 = learner.ranked_arms_for(host, &targets);
    assert_eq!(step3.len(), 1);
    assert_eq!(step3[0].label, "tcp_tls_split");
    assert_eq!(step3[0].class, DirectPathBlockClass::QuicBlocked);

    // Step 4: exhaust tcp_tls_split too → escalate to relay_fallback.
    for _ in 0..DEFAULT_ATTEMPT_BUDGET {
        learner.note_arm_attempt(host, &targets, "tcp_tls_split");
    }
    let step4 = learner.ranked_arms_for(host, &targets);
    assert_eq!(step4.len(), 1);
    assert_eq!(step4[0].label, "relay_fallback");
    assert_eq!(step4[0].class, DirectPathBlockClass::AllIpsFailed);
    assert_eq!(step4[0].attempt_budget, 1);

    // Step 5: a successful TCP observation while UDP-failed clears the
    // negative state → fresh ranking, fresh budgets.
    learner.note_tcp_success(Some(&telemetry), host, &targets, Some("split"));
    let step5 = learner.ranked_arms_for(host, &targets);
    assert_eq!(step5[0].label, "quic", "after positive TCP, class is back to Clean");
    assert_eq!(step5[0].class, DirectPathBlockClass::Clean);
    assert_eq!(step5[0].attempt_budget, DEFAULT_ATTEMPT_BUDGET);
    assert_eq!(step5[1].label, "tcp_plain");
    assert_eq!(step5[1].attempt_budget, DEFAULT_ATTEMPT_BUDGET);

    // Confirm telemetry observed the QUIC-blocked / TCP-OK transition,
    // matching the existing `quic_success_clears_negative_state` test
    // expectations for the same state machine path.
    let signals = telemetry.signals.lock().expect("signals");
    assert!(signals.iter().any(|s| s.2 == "QUIC_BLOCKED_TCP_OK"), "expected QUIC_BLOCKED_TCP_OK in: {:?}", *signals,);
}
