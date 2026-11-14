use super::*;

#[test]
fn first_outbound_attempt_survives_duplex_handoff() {
    let attempt = FakeTtlAttempt { ttl: 8, generation: 12, revision: 3 };
    let mut first = FirstOutboundSession::new();
    first.fake_ttl_attempt = Some(attempt);
    let shared = first.into_relay_session().into_shared();
    assert_eq!(shared.snapshot().expect("session snapshot").fake_ttl_attempt, Some(attempt));
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
    assert_eq!(shared.fake_ttl_attempt().expect("bound attempt"), Some(attempt));
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
    shared.complete_fake_ttl_feedback().expect("complete feedback");
    assert_eq!(shared.begin_fake_ttl_send().expect("begin later send"), Some(attempt));
    assert!(shared.finish_fake_ttl_send(Some(attempt)).expect("finish later send").is_none());
}
