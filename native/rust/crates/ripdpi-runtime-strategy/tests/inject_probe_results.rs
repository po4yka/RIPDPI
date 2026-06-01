use ripdpi_config::OffsetBase;
use ripdpi_runtime_strategy::strategy_evolver::{
    LearningContext, LearningTargetBucket, LearningTransportKind, PROBE_OBSERVATION_WEIGHT, ProbeResult,
    StrategyEvolver, probe_combo_for_strategy_id,
};

#[test]
fn inject_probe_results_prefers_probe_winner() {
    let mut evolver = tls_evolver();
    let results = (0..10)
        .map(|_| ProbeResult::success("fake", "youtube.com", 80))
        .chain((0..10).map(|_| ProbeResult::failure("split", "youtube.com", 500)))
        .collect::<Vec<_>>();

    evolver.inject_probe_results(&results);

    let fake = probe_combo_for_strategy_id("fake").expect("fake combo");
    assert_eq!(evolver.best_combo().expect("best combo").0, &fake);
}

#[test]
fn probe_observation_weight_expands_single_result_into_weighted_attempts() {
    let mut evolver = tls_evolver();
    let fake = probe_combo_for_strategy_id("fake").expect("fake combo");

    evolver.inject_probe_results(&[ProbeResult::success("fake", "youtube.com", 10)]);

    let fake_stats = evolver.combo_stats_for(&fake).expect("fake stats");
    assert_eq!(fake_stats.attempts, PROBE_OBSERVATION_WEIGHT);
    assert_eq!(fake_stats.successes, PROBE_OBSERVATION_WEIGHT);
}

#[test]
fn local_probe_priors_persist_and_reload() {
    let path =
        std::env::temp_dir().join(format!("ripdpi-local-priors-{}-{}.json", std::process::id(), monotonic_suffix(),));
    std::fs::write(&path, r#"{"shared_priors":{"version":1}}"#).expect("seed shared-priors section");
    let mut original = tls_evolver();
    original.inject_probe_results(&[ProbeResult::success("fake", "youtube.com", 50)]);
    original.save_local_priors(&path).expect("save local priors");
    let saved = std::fs::read_to_string(&path).expect("read local priors");
    let saved: serde_json::Value = serde_json::from_str(&saved).expect("parse saved priors");
    assert_eq!(saved["shared_priors"]["version"], 1);
    assert!(saved["local_priors"].is_array());

    let mut loaded = tls_evolver();
    loaded.load_local_priors(&path).expect("load local priors");
    let _ = std::fs::remove_file(&path);

    assert_eq!(loaded.best_combo().expect("best combo").0, original.best_combo().expect("best combo").0);
}

#[test]
fn probe_injection_recomputes_selection_immediately() {
    let mut evolver = tls_evolver();
    evolver.inject_probe_results(&[ProbeResult::success("tls_rec_split", "youtube.com", 40)]);

    let hints = evolver.suggest_hints().expect("hints");
    assert_eq!(hints.tls_record_offset_base, Some(OffsetBase::AutoHost));
}

fn tls_evolver() -> StrategyEvolver {
    let mut evolver = StrategyEvolver::new(true, 0.0);
    evolver.set_learning_context(LearningContext {
        target_bucket: LearningTargetBucket::Tls,
        transport: LearningTransportKind::Tcp,
        ..LearningContext::default()
    });
    evolver
}

fn monotonic_suffix() -> u128 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).expect("system clock after epoch").as_nanos()
}
