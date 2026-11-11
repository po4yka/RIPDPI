use super::*;

#[test]
fn learned_weights_reach_endpoint_ranking_only_in_the_trained_scope() {
    let registry = HealthRegistry::new(Duration::from_secs(60));
    let scope = ResolverNetworkScope::new("trained");
    for index in 0..96 {
        let success = index % 2 == 0;
        registry.record_endpoint_outcome_in_scope(
            &scope,
            if success { "reliable" } else { "unreliable" },
            success,
            if success { 2000 } else { 0 },
        );
    }
    let mut inner = registry.inner.lock().unwrap();
    let state = inner.scopes.get_mut(&scope).unwrap();
    let weights = state.calibration.weights(Instant::now());
    assert!(weights.0[0] > 0.55, "learned weights must favor predictive reliability: {weights:?}");
    // Default scores favor the fast path (0.57 vs 0.485); learned reliability reverses that choice.
    let reliable = state.endpoint_scores.get_mut("reliable").unwrap();
    reliable.ewma_success_rate = 0.70;
    reliable.ewma_latency_ms = 2000.0;
    let unreliable = state.endpoint_scores.get_mut("unreliable").unwrap();
    unreliable.ewma_success_rate = 0.40;
    unreliable.ewma_latency_ms = 0.0;
    drop(inner);
    assert_eq!(registry.rank_indices_in_scope(&scope, &["unreliable", "reliable"]), vec![1, 0]);
    let other = ResolverNetworkScope::new("other");
    assert_eq!(registry.rank_indices_in_scope(&other, &["unreliable", "reliable"]), vec![0, 1]);
    registry.record_oracle_observation_in_scope(&scope, "reliable", ResolverOracleObservation::Poisoned);
    assert_eq!(registry.rank_indices_in_scope(&scope, &["unreliable", "reliable"]), vec![0, 1]);
}
