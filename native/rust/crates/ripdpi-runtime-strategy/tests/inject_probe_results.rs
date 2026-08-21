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

#[test]
fn load_local_priors_decays_history_by_saved_at_stamp() {
    // H2 regression: `last_attempt_ms` is evolver-epoch-relative, so a value
    // persisted by a previous process is meaningless after restart. The
    // document must carry a wall-clock stamp and loading must bake the
    // stored age into the counters; otherwise stale priors stay pinned at
    // full strength until uptime exceeds the previous session's clock.
    let record = r#"{"strategy_id":"fake","attempts":40,"successes":40,"total_latency_ms":4000,"total_latency_square_ms":400000,"last_attempt_ms":999999}"#;
    let six_hours_ms: u64 = 6 * 60 * 60 * 1000;
    let now_unix_ms = unix_millis_now();
    let fake = probe_combo_for_strategy_id("fake").expect("fake combo");

    let stale_path = priors_fixture_path("stale");
    std::fs::write(
        &stale_path,
        format!(r#"{{"local_priors":[{record}],"local_priors_saved_at_unix_ms":{}}}"#, now_unix_ms - six_hours_ms),
    )
    .expect("write stale fixture");
    let mut stale = tls_evolver();
    stale.load_local_priors(&stale_path).expect("load stale priors");
    let _ = std::fs::remove_file(&stale_path);
    let stale_stats = stale.combo_stats_for(&fake).expect("stale fake stats");
    assert!(
        stale_stats.attempts < 40 && stale_stats.successes < 40,
        "six-hour-old history must decay (attempts={}, successes={})",
        stale_stats.attempts,
        stale_stats.successes
    );
    assert!(
        (4..=6).contains(&stale_stats.attempts),
        "6h idle ~= three win half-lives: attempts should sit near 5, got {}",
        stale_stats.attempts
    );
    assert_eq!(stale_stats.last_attempt_ms, 0, "loaded records anchor at the new epoch");

    let fresh_path = priors_fixture_path("fresh");
    std::fs::write(
        &fresh_path,
        format!(r#"{{"local_priors":[{record}],"local_priors_saved_at_unix_ms":{now_unix_ms}}}"#),
    )
    .expect("write fresh fixture");
    let mut fresh = tls_evolver();
    fresh.load_local_priors(&fresh_path).expect("load fresh priors");
    let _ = std::fs::remove_file(&fresh_path);
    assert_eq!(
        fresh.combo_stats_for(&fake).expect("fresh fake stats").attempts,
        40,
        "just-saved history must survive a round trip undecayed"
    );

    let legacy_path = priors_fixture_path("legacy");
    std::fs::write(&legacy_path, format!(r#"{{"local_priors":[{record}]}}"#)).expect("write legacy fixture");
    let mut legacy = tls_evolver();
    legacy.load_local_priors(&legacy_path).expect("load legacy priors");
    let _ = std::fs::remove_file(&legacy_path);
    assert_eq!(
        legacy.combo_stats_for(&fake).expect("legacy fake stats").attempts,
        40,
        "documents without a stamp keep the pre-stamp behaviour"
    );
}

fn priors_fixture_path(label: &str) -> std::path::PathBuf {
    std::env::temp_dir().join(format!("ripdpi-local-priors-{label}-{}-{}.json", std::process::id(), monotonic_suffix()))
}

fn unix_millis_now() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).expect("clock after epoch").as_millis() as u64
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
