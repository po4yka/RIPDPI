use super::*;
use ripdpi_proxy_runtime_adapter::desync_platform::EvolutionHintContext;

#[test]
fn first_outbound_attempt_survives_duplex_handoff() {
    let attempt = FakeTtlAttempt { ttl: 8, generation: 12, revision: 3 };
    let mut first = FirstOutboundSession::new();
    first.fake_ttl_attempt = Some(attempt);
    let shared = first.into_relay_session().into_shared();
    assert_eq!(shared.snapshot().expect("session snapshot").fake_ttl_attempt, Some(attempt));
}

#[test]
fn neutral_response_cannot_reward_a_delayed_send_or_become_accepted() {
    let shared = FirstOutboundSession::new().into_relay_session().into_shared();
    shared.observe_inbound_payload(b"received closure");
    shared.begin_fake_ttl_send().expect("begin overlapping send");
    shared.finish_first_response(FirstResponseOutcome::Neutral).expect("neutral first response");
    let attempt = FakeTtlAttempt { ttl: 7, generation: 12, revision: 3 };
    assert!(shared.finish_fake_ttl_send(Some(attempt)).expect("publish delayed send").is_none());
    assert!(shared.accept_fake_ttl_response(Some(56)).expect("late response cannot reward").is_none());
    shared.finish_first_response(FirstResponseOutcome::Accepted).expect("late completion cannot replace neutrality");
    assert!(!shared.rotation_eligible());
    assert!(!shared.snapshot().expect("final state").permits_success());
}

#[test]
fn server_first_session_keeps_the_first_outbound_attempt() {
    let first = FakeTtlAttempt { ttl: 8, generation: 12, revision: 3 };
    let later = FakeTtlAttempt { ttl: 7, generation: 12, revision: 4 };
    let shared = FirstOutboundSession::new().into_relay_session().into_shared();
    let inbound = shared.clone();
    for attempt in [None, Some(first), Some(later)] {
        shared.begin_fake_ttl_send().expect("begin send");
        assert!(shared.finish_fake_ttl_send(attempt).expect("finish send").is_none());
    }
    assert_eq!(inbound.snapshot().expect("session snapshot").fake_ttl_attempt, Some(first));
}

#[test]
fn a_response_without_an_overlapping_send_cannot_credit_a_future_attempt() {
    let shared = FirstOutboundSession::new().into_relay_session().into_shared();
    assert!(shared.accept_fake_ttl_response(Some(56)).expect("accept response").is_none());
    assert_eq!(shared.begin_fake_ttl_send().expect("begin future send"), None);
    let attempt = FakeTtlAttempt { ttl: 7, generation: 12, revision: 3 };
    assert!(shared.finish_fake_ttl_send(Some(attempt)).expect("finish future send").is_none());
    assert!(shared.accept_fake_ttl_response(Some(56)).expect("response already closed").is_none());
    assert!(shared.snapshot().expect("snapshot").fake_ttl_attempt.is_none());
}

#[test]
fn an_overlapping_plain_send_cannot_credit_a_future_fake_attempt() {
    let shared = FirstOutboundSession::new().into_relay_session().into_shared();
    shared.begin_fake_ttl_send().expect("begin plain send");
    assert!(shared.accept_fake_ttl_response(None).expect("accept response").is_none());
    assert!(shared.finish_fake_ttl_send(None).expect("finish plain send").is_none());
    shared.begin_fake_ttl_send().expect("begin later fake send");
    let attempt = FakeTtlAttempt { ttl: 7, generation: 12, revision: 3 };
    assert!(shared.finish_fake_ttl_send(Some(attempt)).expect("finish later send").is_none());
}

#[test]
fn an_overlapping_attempt_claims_fake_feedback_once_with_the_observed_ttl() {
    let shared = FirstOutboundSession::new().into_relay_session().into_shared();
    let attempt = FakeTtlAttempt { ttl: 7, generation: 12, revision: 3 };
    shared.begin_fake_ttl_send().expect("begin send");
    assert!(shared.accept_fake_ttl_response(Some(56)).expect("accept response").is_none());
    assert_eq!(
        shared.finish_fake_ttl_send(Some(attempt)).expect("finish send"),
        Some(FakeTtlFeedback { attempt, server_ttl: Some(56) })
    );
    assert!(shared.accept_fake_ttl_response(Some(55)).expect("feedback claimed").is_none());
    assert_eq!(shared.begin_fake_ttl_send().expect("begin later send"), Some(attempt));
    assert!(shared.finish_fake_ttl_send(Some(attempt)).expect("finish later send").is_none());
    let terminal = shared.snapshot().expect("terminal receipt");
    assert_eq!(terminal.fake_ttl_attempt, Some(attempt));
    assert_eq!(terminal.server_ttl, Some(56));
}

#[test]
fn neutral_evolution_response_closes_only_its_delayed_receipt() {
    use crate::runtime::config::RuntimeConfig;
    use ripdpi_proxy_runtime_adapter::model::ports::AdaptiveHintPort;
    let mut config = RuntimeConfig::default();
    config.adaptive.strategy_evolution = true;
    config.adaptive.evolution_epsilon_permil = 0;
    let state = RuntimeState::test(config.clone());
    let target = "127.0.0.1:443".parse().unwrap();
    let payload = crate::runtime::payload::RUNTIME_DEFAULT_FAKE_TLS;
    let resolve = |attempt| {
        state
            .test_services()
            .resolve_tcp_hints_with_evolver(
                &config,
                EvolutionHintContext { runtime_context: None, existing_attempt: attempt },
                0,
                target,
                None,
                &config.groups[0],
                payload,
            )
            .unwrap()
    };
    let selected = resolve(None);
    let expected_hint = selected.hints.split_offset_base;
    assert!(expected_hint.is_some(), "TLS attempt differs from group fallback");
    let canceled = selected.evolution_attempt.unwrap();
    let other = resolve(None).evolution_attempt.unwrap();
    let shared = FirstOutboundSession::new().into_relay_session().into_shared();
    shared.begin_evolution_send().unwrap();
    shared.finish_first_response(FirstResponseOutcome::Neutral).unwrap();
    shared.finish_evolution_response(&state, true).unwrap();
    shared.finish_evolution_send(&state, Some(canceled)).unwrap();
    shared.finish_first_response(FirstResponseOutcome::Accepted).unwrap();
    shared.finish_evolution_response(&state, true).unwrap();
    assert!(!shared.snapshot().unwrap().permits_success());
    assert_eq!(resolve(Some(canceled)).hints.split_offset_base, None);
    assert_eq!(resolve(Some(other)).hints.split_offset_base, expected_hint);
    state.note_evolver_success(Some(canceled));
    state.note_evolver_success(Some(other));
    assert_eq!(resolve(Some(other)).hints.split_offset_base, None);
}
