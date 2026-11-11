use super::*;

fn predictable_batch() -> FitBatch {
    // Independent outcomes: success-history predicts success; low latency predicts failure.
    let samples = (0..64)
        .map(|index| {
            if index % 2 == 0 {
                Sample { features: [1.0, 0.0, 0.5], utility: 0.75 }
            } else {
                Sample { features: [0.0, 1.0, 0.5], utility: 0.0 }
            }
        })
        .collect();
    FitBatch { samples, previous: Weights::default(), generation: 64, incarnation: 1 }
}

#[test]
fn differential_evolution_is_deterministic_and_improves_later_observations() {
    let batch = predictable_batch();
    let weights = batch.fit().expect("predictive data improves the default model");
    assert_eq!(Some(weights), batch.fit());
    assert!(weights.0[0] > 0.55);
    assert!(weights.0[1] < 0.25);
    assert!(weights.0[2] >= 0.20 - f64::EPSILON);
    assert!((weights.0.iter().sum::<f64>() - 1.0).abs() < f64::EPSILON);
    assert!(loss(weights, &batch.samples[48..]) < loss(Weights::default(), &batch.samples[48..]));
}

#[test]
fn training_improvement_that_reverses_on_validation_is_rejected() {
    let mut batch = predictable_batch();
    for sample in &mut batch.samples[48..] {
        sample.utility = if sample.features[0] == 1.0 { 0.0 } else { 1.0 };
    }
    assert!(batch.fit().is_none());
    batch.previous = Weights::bounded(0.7, 0.1);
    assert_eq!(batch.fit(), Some(Weights::default()));
}

#[test]
fn sparse_single_outcome_and_stale_history_keep_defaults() {
    let now = Instant::now();
    let mut calibration = Calibration::new(now, 1);
    for _ in 0..32 {
        assert!(calibration.record([0.5, 0.9, 0.5], true, 20, now).is_none());
    }
    let batch = (0..32).find_map(|_| calibration.record([0.5, 0.9, 0.5], true, 20, now));
    assert!(batch.expect("64 samples schedule a fit").fit().is_none());
    calibration.weights = Weights::bounded(0.7, 0.1);
    assert_eq!(calibration.weights(now + MAX_IDLE + Duration::from_secs(1)), Weights::default());
    assert!(calibration.record([0.5; 3], false, 20, now + MAX_IDLE + Duration::from_secs(1)).is_none());
    assert_eq!(calibration.samples.len(), 1);
}

#[test]
fn stale_fit_cannot_overwrite_newer_observations_and_memory_is_bounded() {
    let now = Instant::now();
    let mut calibration = Calibration::new(now, 1);
    let batch = predictable_batch();
    for _ in 0..256 {
        calibration.record([0.5; 3], false, 20, now);
    }
    calibration.apply(&batch, Weights::bounded(0.7, 0.1));
    assert_eq!(calibration.weights(now), Weights::default());
    assert_eq!(calibration.samples.len(), MAX_SAMPLES);
}

#[test]
fn a_fit_from_an_evicted_scope_cannot_update_a_recreated_scope() {
    let mut replacement = Calibration::new(Instant::now(), 2);
    replacement.generation = 64;
    replacement.apply(&predictable_batch(), Weights::bounded(0.7, 0.1));
    assert_eq!(replacement.weights, Weights::default());
}

#[test]
fn continued_sparse_traffic_cannot_keep_an_expired_model_alive() {
    let now = Instant::now();
    let mut calibration = Calibration::new(now, 1);
    for _ in 0..64 {
        calibration.record([0.5; 3], false, 20, now);
    }
    calibration.weights = Weights::bounded(0.7, 0.1);
    calibration.fitted_at = Some(now);
    for step in 1..=32 {
        calibration.record([0.5; 3], false, 20, now + Duration::from_secs(step * 20));
    }
    assert!(calibration.samples.len() < MIN_SAMPLES);
    assert_eq!(calibration.weights(now + Duration::from_secs(640)), Weights::default());
}
