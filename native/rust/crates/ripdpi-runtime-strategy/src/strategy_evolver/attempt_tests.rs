use super::*;
use ripdpi_failure_classifier::FailureClass;

#[test]
fn delayed_feedback_stays_with_its_learning_context() {
    let mut evolver = StrategyEvolver::new(true, 0.0);
    let tls = LearningContext { target_bucket: LearningTargetBucket::Tls, ..LearningContext::default() };
    let quic = LearningContext { target_bucket: LearningTargetBucket::Quic, ..LearningContext::default() };
    let tls_attempt = evolver.start_experiment(tls.clone()).expect("TLS attempt");
    let _quic_attempt = evolver.start_experiment(quic.clone()).expect("QUIC attempt");
    evolver.record_experiment_failure(tls_attempt, FailureClass::TcpReset);

    let tls_attempts: u32 =
        evolver.contexts.get(&tls).map_or(0, |state| state.combos.values().map(|s| s.attempts).sum());
    let quic_attempts: u32 =
        evolver.contexts.get(&quic).map_or(0, |state| state.combos.values().map(|s| s.attempts).sum());
    assert_eq!(tls_attempts, 1, "the delayed TLS failure must train the TLS attempt");
    assert_eq!(quic_attempts, 0, "the QUIC attempt has no result yet");
}

#[test]
fn same_context_attempts_keep_independent_outcomes() {
    let mut evolver = StrategyEvolver::new(true, 0.0);
    let context = LearningContext::default();
    let first = evolver.start_experiment(context.clone()).unwrap();
    let second = evolver.start_experiment(context.clone()).unwrap();
    evolver.record_experiment_success(second, 30);
    evolver.record_experiment_failure(first, FailureClass::TcpReset);
    let state = evolver.contexts.get(&context).unwrap();
    assert_eq!(state.combos.values().map(|stats| stats.attempts).sum::<u32>(), 2);
    assert_eq!(state.combos.values().map(|stats| stats.successes).sum::<u32>(), 1);
}

#[test]
fn owned_attempt_expires_at_exact_ttl_and_late_feedback_is_neutral() {
    let mut evolver = StrategyEvolver::new(true, 0.0).with_time_knobs(10, 0, 0, 0);
    evolver.set_test_clock_ms(100);
    let attempt = evolver.start_experiment(LearningContext::default()).unwrap();
    evolver.set_test_clock_ms(109);
    assert!(!evolver.experiment_expired(&attempt));
    evolver.set_test_clock_ms(110);
    assert!(evolver.experiment_expired(&attempt));
    evolver.record_experiment_success(attempt, 1);
    assert_eq!(evolver.combos_tested(), 0);
}

#[test]
fn zero_ttl_retains_owned_attempt_and_neutral_failures_do_not_train() {
    for class in [FailureClass::CapabilitySkipped, FailureClass::StrategyExecutionFailure] {
        let mut evolver = StrategyEvolver::new(true, 0.0).with_time_knobs(0, 0, 0, 0);
        evolver.set_test_clock_ms(0);
        let attempt = evolver.start_experiment(LearningContext::default()).unwrap();
        evolver.set_test_clock_ms(u64::MAX);
        assert!(!evolver.experiment_expired(&attempt));
        evolver.record_experiment_failure(attempt, class);
        assert_eq!(evolver.combos_tested(), 0);
    }
}

#[test]
fn success_after_generic_continuation_trains_only_the_initial_protocol_context() {
    for (bucket, transport, alpn) in [
        (LearningTargetBucket::Tls, LearningTransportKind::Tcp, LearningAlpnClass::H2Http11),
        (LearningTargetBucket::Quic, LearningTransportKind::UdpQuic, LearningAlpnClass::H3),
    ] {
        let mut evolver = StrategyEvolver::new(true, 0.0);
        let original =
            LearningContext { target_bucket: bucket, transport, alpn_class: alpn, ..LearningContext::default() };
        let attempt = evolver.start_experiment(original.clone()).unwrap();
        let generic = LearningContext::default();
        evolver.set_learning_context(generic.clone());
        evolver.record_experiment_success(attempt, 10);
        let attempts = |context: &LearningContext| {
            evolver
                .contexts
                .get(context)
                .map_or(0, |state| state.combos.values().map(|stats| stats.attempts).sum::<u32>())
        };
        assert_eq!(attempts(&original), 1);
        assert_eq!(attempts(&generic), 0);
    }
}
