use super::*;
use ripdpi_runtime_adaptive::adaptive_port::EvolutionHintContext;

fn resolver() -> StrategyEvolutionResolver {
    let mut config = RuntimeConfig::default();
    config.adaptive.strategy_evolution = true;
    config.adaptive.evolution_epsilon_permil = 0;
    config.adaptive.evolution_experiment_ttl_ms = 0;
    StrategyEvolutionResolver::from_config(&config)
}

fn start(resolver: &mut StrategyEvolutionResolver) -> EvolutionAttempt {
    resolver.hints_for_attempt(LearningContext::default(), None, None).unwrap().1
}

#[test]
fn feedback_is_at_most_once_and_foreign_instances_do_not_match() {
    let mut first = resolver();
    let mut second = resolver();
    let first_attempt = start(&mut first);
    let second_attempt = start(&mut second);
    assert_ne!(first_attempt, second_attempt);
    second.record_success(first_attempt, 1);
    assert_eq!(second.evolver.combos_tested(), 0);
    assert!(second.pending.contains_key(&second_attempt));
    first.record_failure(first_attempt, FailureClass::TcpReset);
    first.record_success(first_attempt, 1);
    assert_eq!(first.evolver.best_combo().unwrap().1.attempts, 1);
    assert_eq!(first.evolver.best_combo().unwrap().1.successes, 0);
    second.record_success(second_attempt, 1);
    assert_eq!(second.evolver.best_combo().unwrap().1.attempts, 1);
    assert_eq!(second.evolver.best_combo().unwrap().1.successes, 1);
}

#[test]
fn reset_and_neutral_discard_invalidate_exact_attempts() {
    let mut resolver = resolver();
    let discarded = start(&mut resolver);
    let live = start(&mut resolver);
    resolver.discard(discarded);
    resolver.record_success(discarded, 1);
    assert_eq!(resolver.evolver.combos_tested(), 0);
    assert!(resolver.pending.contains_key(&live));
    resolver.reset();
    let after_reset = start(&mut resolver);
    assert_ne!(live, after_reset);
    resolver.record_failure(live, FailureClass::TcpReset);
    assert_eq!(resolver.evolver.combos_tested(), 0);
    assert!(resolver.pending.contains_key(&after_reset));
}

#[test]
fn held_expired_or_wrong_context_token_never_starts_a_replacement() {
    let mut resolver = resolver();
    let held = start(&mut resolver);
    let changed =
        LearningContext { network_identity: Some("another-network".to_owned()), ..LearningContext::default() };
    assert!(resolver.hints_for_attempt(changed, Some(held), None).is_none());
    assert!(resolver.pending.is_empty());
    resolver.record_success(held, 1);
    assert_eq!(resolver.evolver.combos_tested(), 0);
    let held = start(&mut resolver);
    resolver.evolver.experiment_ttl_ms = 1;
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(1);
    while !resolver.evolver.experiment_expired(&resolver.pending[&held]) {
        assert!(std::time::Instant::now() < deadline, "monotonic TTL did not elapse");
        std::thread::yield_now();
    }
    assert!(resolver.hints_for_attempt(LearningContext::default(), Some(held), None).is_none());
    assert!(resolver.pending.is_empty());
    resolver.record_success(held, 1);
    assert_eq!(resolver.evolver.combos_tested(), 0);
    assert_ne!(held, start(&mut resolver));
}

#[test]
fn pending_attempts_are_bounded_and_eviction_is_neutral() {
    let mut resolver = resolver();
    let oldest = start(&mut resolver);
    for _ in 0..MAX_PENDING_EVOLUTION_ATTEMPTS {
        start(&mut resolver);
    }
    assert_eq!(resolver.pending.len(), MAX_PENDING_EVOLUTION_ATTEMPTS);
    resolver.record_failure(oldest, FailureClass::TcpReset);
    assert_eq!(resolver.evolver.combos_tested(), 0);
    assert_eq!(resolver.pending.len(), MAX_PENDING_EVOLUTION_ATTEMPTS);
}

#[test]
fn exhausted_identity_space_does_not_wrap_or_create_unowned_attempt() {
    let mut resolver = resolver();
    resolver.next_attempt = u64::MAX;
    assert!(resolver.hints_for_attempt(LearningContext::default(), None, None).is_none());
    assert!(resolver.hints_for_attempt(LearningContext::default(), None, None).is_none());
    assert!(resolver.pending.is_empty());
    resolver.generation = None;
    resolver.next_attempt = 1;
    assert!(resolver.hints_for_attempt(LearningContext::default(), None, None).is_none());
}

#[test]
fn public_hint_fallback_keeps_held_dead_identity() {
    use ripdpi_runtime_adaptive::adaptive_port::{AdaptiveFeedbackPort, AdaptiveHintPort};
    let mut config = RuntimeConfig::default();
    config.adaptive.strategy_evolution = true;
    let services = crate::ServicesStateHandle::new(crate::ServicesState::new(config.clone(), None, None));
    let target = "127.0.0.1:443".parse().unwrap();
    let group = &config.groups[0];
    let first = services
        .resolve_tcp_hints_with_evolver(
            &config,
            EvolutionHintContext { runtime_context: None, existing_attempt: None },
            0,
            target,
            None,
            group,
            b"hello",
        )
        .unwrap();
    let held = first.evolution_attempt.unwrap();
    services.discard_evolver_attempt(held);
    let resumed = services
        .resolve_tcp_hints_with_evolver(
            &config,
            EvolutionHintContext { runtime_context: None, existing_attempt: Some(held) },
            0,
            target,
            None,
            group,
            b"hello",
        )
        .unwrap();
    assert_eq!(resumed.evolution_attempt, Some(held));
    assert!(services.0.adaptive.strategy_evolver.read().unwrap().pending.is_empty());
    services.note_evolver_success(held);
    assert_eq!(services.0.adaptive.strategy_evolver.read().unwrap().evolver.combos_tested(), 0);
    let fresh = services
        .resolve_tcp_hints_with_evolver(
            &config,
            EvolutionHintContext { runtime_context: None, existing_attempt: None },
            0,
            target,
            None,
            group,
            b"hello",
        )
        .unwrap();
    assert_ne!(fresh.evolution_attempt, Some(held));
}

#[test]
fn simultaneous_flow_feedback_consumes_only_its_own_attempt() {
    use ripdpi_runtime_adaptive::adaptive_port::{AdaptiveFeedbackPort, AdaptiveHintPort};
    let mut config = RuntimeConfig::default();
    config.adaptive.strategy_evolution = true;
    config.adaptive.evolution_epsilon_permil = 0;
    let services = crate::ServicesStateHandle::new(crate::ServicesState::new(config.clone(), None, None));
    let barrier = std::sync::Barrier::new(2);
    let attempts = std::thread::scope(|scope| {
        let success = scope.spawn(|| {
            let attempt = services
                .resolve_tcp_hints_with_evolver(
                    &config,
                    EvolutionHintContext { runtime_context: None, existing_attempt: None },
                    0,
                    "127.0.0.1:443".parse().unwrap(),
                    None,
                    &config.groups[0],
                    b"hello",
                )
                .unwrap()
                .evolution_attempt
                .unwrap();
            barrier.wait();
            services.note_evolver_success(attempt);
            attempt
        });
        let failure = scope.spawn(|| {
            let attempt = services
                .resolve_tcp_hints_with_evolver(
                    &config,
                    EvolutionHintContext { runtime_context: None, existing_attempt: None },
                    0,
                    "127.0.0.2:443".parse().unwrap(),
                    None,
                    &config.groups[0],
                    b"hello",
                )
                .unwrap()
                .evolution_attempt
                .unwrap();
            barrier.wait();
            services.note_evolver_failure(attempt, FailureClass::TcpReset);
            attempt
        });
        (success.join().unwrap(), failure.join().unwrap())
    });
    assert_ne!(attempts.0, attempts.1);
    let resolver = services.0.adaptive.strategy_evolver.read().unwrap();
    assert!(resolver.pending.is_empty());
    let stats = resolver.evolver.best_combo().unwrap().1;
    assert_eq!((stats.attempts, stats.successes), (2, 1));
}

#[test]
fn generation_exhaustion_fails_closed() {
    let counter = AtomicU64::new(u64::MAX - 1);
    assert_eq!(allocate_generation(&counter), Some(u64::MAX - 1));
    assert_eq!(allocate_generation(&counter), None);
    assert_eq!(allocate_generation(&counter), None);
    assert_eq!(counter.load(Ordering::Relaxed), u64::MAX);
}

#[test]
fn tls_and_quic_continuations_resume_the_original_observation() {
    for (bucket, transport, alpn) in [
        (LearningTargetBucket::Tls, LearningTransportKind::Tcp, LearningAlpnClass::H2Http11),
        (LearningTargetBucket::Quic, LearningTransportKind::UdpQuic, LearningAlpnClass::H3),
    ] {
        let mut resolver = resolver();
        let original = LearningContext {
            target_bucket: bucket,
            transport,
            alpn_class: alpn,
            ech_capable: true,
            ..LearningContext::default()
        };
        let (hints, attempt) = resolver.hints_for_attempt(original.clone(), None, None).unwrap();
        let continuation = LearningContext {
            transport: if transport == LearningTransportKind::Tcp {
                LearningTransportKind::Tcp
            } else {
                LearningTransportKind::Unknown
            },
            ..LearningContext::default()
        };
        let resumed = resolver.hints_for_attempt(continuation, Some(attempt), None).unwrap();
        assert_eq!(resumed, (hints, attempt));
        assert_eq!(resolver.pending.len(), 1);
        assert_eq!(resolver.pending[&attempt].context(), &original);
        resolver.record_success(attempt, 10);
        assert!(resolver.pending.is_empty());
        assert_eq!(resolver.evolver.best_combo().unwrap().1.attempts, 1);
        assert_eq!(resolver.evolver.best_combo().unwrap().1.successes, 1);
    }
}

#[test]
fn changed_network_capability_or_transport_discards_without_replacement() {
    for changed in [
        LearningContext { network_identity: Some("other-network".to_owned()), ..LearningContext::default() },
        LearningContext { capability_context: CapabilityContext::Degraded, ..LearningContext::default() },
        LearningContext { transport: LearningTransportKind::Tcp, ..LearningContext::default() },
    ] {
        let mut resolver = resolver();
        let held = start(&mut resolver);
        assert!(resolver.hints_for_attempt(changed, Some(held), None).is_none());
        assert!(resolver.pending.is_empty());
        assert!(resolver.hints_for_attempt(LearningContext::default(), Some(held), None).is_none());
        resolver.record_success(held, 1);
        assert_eq!(resolver.evolver.combos_tested(), 0);
    }
}

#[test]
fn neutral_attempt_does_not_steal_same_context_success_or_accept_duplicates() {
    let mut resolver = resolver();
    let canceled = start(&mut resolver);
    let live = start(&mut resolver);
    resolver.discard(canceled);
    resolver.record_success(canceled, 1);
    assert!(resolver.pending.contains_key(&live));
    resolver.record_success(live, 10);
    resolver.record_failure(live, FailureClass::TcpReset);
    resolver.record_success(canceled, 1);
    let stats = resolver.evolver.best_combo().unwrap().1;
    assert_eq!((stats.attempts, stats.successes), (1, 1));
    assert!(resolver.pending.is_empty());
}
