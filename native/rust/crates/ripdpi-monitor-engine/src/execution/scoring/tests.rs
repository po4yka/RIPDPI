use ripdpi_monitor_adapter::proxy_config::ProxyUiConfig;

use crate::candidates::StrategyCandidateSpec;
use crate::types::{ProbeResult, StrategyEmitterTier, StrategyProbeCandidateSummary};

use super::*;

#[test]
fn winning_candidate_index_selects_highest_weighted_success_score() {
    let candidates = vec![
        summary_with("a", 2, 4, 3, false, None),
        summary_with("b", 4, 4, 6, false, None),
        summary_with("c", 3, 4, 5, false, None),
    ];

    assert_eq!(winning_candidate_index(&candidates), Some(1));
}

#[test]
fn winning_candidate_index_breaks_tie_with_quality_score() {
    let candidates = vec![summary_with("a", 3, 4, 5, false, None), summary_with("b", 3, 4, 8, false, None)];

    assert_eq!(winning_candidate_index(&candidates), Some(1));
}

#[test]
fn winning_candidate_index_skips_skipped_candidates() {
    let candidates = vec![
        summary_with("a", 2, 4, 3, false, None),
        summary_with("b", 10, 10, 20, true, None),
        summary_with("c", 3, 4, 5, false, None),
    ];

    assert_eq!(winning_candidate_index(&candidates), Some(2));
}

#[test]
fn winning_candidate_index_skips_not_applicable_candidates() {
    let mut candidates = vec![summary_with("a", 2, 4, 3, false, None), summary_with("b", 10, 10, 20, false, None)];
    candidates[1].outcome = "not_applicable".to_string();

    assert_eq!(winning_candidate_index(&candidates), Some(0));
}

#[test]
fn winning_candidate_index_returns_none_for_empty_list() {
    assert_eq!(winning_candidate_index(&[]), None);
}

#[test]
fn winning_candidate_index_prefers_lower_latency_on_tie() {
    let candidates = vec![summary_with("a", 3, 4, 5, false, Some(200)), summary_with("b", 3, 4, 5, false, Some(100))];

    assert_eq!(winning_candidate_index(&candidates), Some(1));
}

#[test]
fn candidate_score_add_accumulates_weighted_success() {
    let mut score = CandidateScore::default();
    score.add(ProbeSample {
        result: ProbeResult {
            probe_type: "test".to_string(),
            target: "t".to_string(),
            outcome: "ok".to_string(),
            details: vec![],
        },
        success: true,
        weight: 2,
        quality: 4,
        latency_ms: 50,
        started_at_ms: 100,
        retry_count: 0,
        protocol: "TEST".to_string(),
        reason: None,
        domain: None,
        is_control: false,
    });
    score.add(ProbeSample {
        result: ProbeResult {
            probe_type: "test".to_string(),
            target: "t".to_string(),
            outcome: "fail".to_string(),
            details: vec![],
        },
        success: false,
        weight: 1,
        quality: 0,
        latency_ms: 100,
        started_at_ms: 200,
        retry_count: 0,
        protocol: "TEST".to_string(),
        reason: Some("failed".to_string()),
        domain: None,
        is_control: false,
    });

    assert_eq!(score.succeeded_targets, 1);
    assert_eq!(score.total_targets, 2);
    assert_eq!(score.weighted_success_score, 2);
    assert_eq!(score.total_weight, 3);
    assert_eq!(score.quality_score, 8); // 4*2 + 0*1
    assert_eq!(score.average_latency_ms(), Some(50));
    assert!(!score.is_full_success());
}

#[test]
fn candidate_score_full_success_when_all_targets_succeed() {
    let mut score = CandidateScore::default();
    score.add(ProbeSample {
        result: ProbeResult {
            probe_type: "test".to_string(),
            target: "t".to_string(),
            outcome: "ok".to_string(),
            details: vec![],
        },
        success: true,
        weight: 1,
        quality: 3,
        latency_ms: 100,
        started_at_ms: 100,
        retry_count: 0,
        protocol: "TEST".to_string(),
        reason: None,
        domain: None,
        is_control: false,
    });

    assert!(score.is_full_success());
}

#[test]
fn candidate_score_preserves_control_classification_in_domain_outcome() {
    let mut score = CandidateScore::default();
    score.add(ProbeSample {
        result: ProbeResult {
            probe_type: "test".to_string(),
            target: "control.example".to_string(),
            outcome: "ok".to_string(),
            details: vec![],
        },
        success: true,
        weight: 1,
        quality: 3,
        latency_ms: 25,
        started_at_ms: 100,
        retry_count: 0,
        protocol: "TEST".to_string(),
        reason: None,
        domain: Some("control.example".to_string()),
        is_control: true,
    });

    let outcomes = score.domain_outcomes();
    assert_eq!(outcomes.len(), 1);
    assert_eq!(outcomes[0].domain, "control.example");
    assert!(outcomes[0].succeeded);
    assert!(outcomes[0].is_control);
}

#[test]
fn candidate_score_average_latency_none_when_no_success() {
    let score = CandidateScore::default();

    assert_eq!(score.average_latency_ms(), None);
}

#[test]
fn not_applicable_candidate_execution_keeps_ech_notes_and_rationale() {
    let spec = crate::candidates::build_tcp_candidates(&test_ui_config())
        .into_iter()
        .find(|candidate| candidate.id == "ech_split")
        .expect("ech_split candidate");

    let execution = not_applicable_candidate_execution(&spec, 4, 3, "No baseline HTTPS target exposed ECH capability");

    assert_eq!(execution.summary.outcome, "not_applicable");
    assert_eq!(execution.summary.rationale, "No baseline HTTPS target exposed ECH capability");
    assert_eq!(execution.summary.total_targets, 4);
    assert_eq!(execution.summary.total_weight, 12);
    assert!(
        execution
            .summary
            .notes
            .iter()
            .any(|note| note.contains("Runs only when the baseline proves an ECH-capable HTTPS path"))
    );
    assert!(execution.summary.notes.iter().any(|note| note == "No baseline HTTPS target exposed ECH capability"));
}

#[test]
fn failed_execution_sets_outcome_and_rationale() {
    let exec = failed_candidate_execution(&test_spec(), 4, 3, "proxy startup failed".to_string());

    assert_eq!(exec.summary.outcome, "failed");
    assert_eq!(exec.summary.rationale, "proxy startup failed");
    assert_eq!(exec.summary.succeeded_targets, 0);
    assert_eq!(exec.summary.total_targets, 4);
    assert_eq!(exec.summary.total_weight, 12);
    assert!(!exec.cancelled);
    assert!(exec.results.is_empty());
}

#[test]
fn cancelled_execution_marks_cancelled_flag() {
    let score = CandidateScore { total_targets: 2, total_weight: 6, ..Default::default() };
    let exec = cancelled_candidate_execution(&test_spec(), score, 0);

    assert!(exec.cancelled);
    assert_eq!(exec.summary.outcome, "failed"); // no succeeded targets
}

#[test]
fn skipped_summary_sets_skipped_flag_and_rationale() {
    let summary = skipped_candidate_summary(&test_spec(), 4, 3, "prerequisite not met");

    assert!(summary.skipped);
    assert_eq!(summary.outcome, "skipped");
    assert_eq!(summary.rationale, "prerequisite not met");
    assert_eq!(summary.total_weight, 12);
    assert!(summary.notes.iter().any(|n| n == "prerequisite not met"));
}

#[test]
fn candidate_proxy_config_json_serializes() {
    let json = candidate_proxy_config_json(&test_spec());

    assert!(json.is_some(), "should produce valid JSON");
    let json_str = json.unwrap();
    let _: serde_json::Value = serde_json::from_str(&json_str).expect("should be valid JSON");
}

#[test]
fn candidate_notes_collects_extra_notes() {
    let notes = candidate_notes(&test_spec(), &["extra note 1", "extra note 2"]);

    assert!(notes.iter().any(|n| n == "extra note 1"));
    assert!(notes.iter().any(|n| n == "extra note 2"));
}

#[test]
fn candidate_notes_empty_when_no_notes() {
    let spec = crate::candidates::candidate_spec("bare", "Bare", "bare", test_ui_config());
    let notes = candidate_notes(&spec, &[]);

    assert!(notes.iter().all(|n| n != "extra"), "should not contain extras");
}

#[test]
fn build_execution_computes_outcome_success() {
    let mut score = CandidateScore { total_targets: 2, total_weight: 6, ..Default::default() };
    score.succeeded_targets = 2;
    score.weighted_success_score = 6;
    score.quality_score = 10;
    let exec = build_candidate_execution(&test_spec(), score, 0);

    assert_eq!(exec.summary.outcome, "success");
}

#[test]
fn build_execution_preserves_target_attempt_timing_and_retry_evidence() {
    let mut score = CandidateScore::default();
    score.add(ProbeSample {
        result: ProbeResult {
            probe_type: "strategy_https".to_string(),
            target: "Test Label · blocked.example".to_string(),
            outcome: "tls_handshake_failed".to_string(),
            details: vec![],
        },
        success: false,
        weight: 2,
        quality: 0,
        latency_ms: 250,
        started_at_ms: 1_000,
        retry_count: 1,
        protocol: "HTTPS".to_string(),
        reason: Some("operation timed out".to_string()),
        domain: Some("blocked.example".to_string()),
        is_control: true,
    });

    let execution = build_candidate_execution(&test_spec(), score, 0);
    assert_eq!(execution.attempts.len(), 1);
    let attempt = &execution.attempts[0];

    assert_eq!(attempt.target, "blocked.example");
    assert_eq!(attempt.started_at_ms, Some(1_000));
    assert_eq!(attempt.duration_ms, Some(250));
    assert_eq!(attempt.retry_count, 1);
    assert_eq!(attempt.status, crate::types::StrategyProbeAttemptStatus::TimedOut);
    assert!(attempt.is_control);
}

#[test]
fn build_execution_computes_outcome_partial() {
    let mut score = CandidateScore { total_targets: 4, total_weight: 12, ..Default::default() };
    score.succeeded_targets = 2;
    score.weighted_success_score = 6;
    score.quality_score = 5;
    let exec = build_candidate_execution(&test_spec(), score, 3);

    assert_eq!(exec.summary.outcome, "partial");
}

#[test]
fn build_execution_computes_outcome_failed() {
    let score = CandidateScore { total_targets: 4, total_weight: 12, ..Default::default() };
    let exec = build_candidate_execution(&test_spec(), score, 0);

    assert_eq!(exec.summary.outcome, "failed");
}

fn summary_with(
    id: &str,
    weighted_success_score: usize,
    total_weight: usize,
    quality_score: usize,
    skipped: bool,
    average_latency_ms: Option<u64>,
) -> StrategyProbeCandidateSummary {
    StrategyProbeCandidateSummary {
        id: id.to_string(),
        label: id.to_string(),
        family: "test".to_string(),
        emitter_tier: StrategyEmitterTier::NonRootProduction,
        exact_emitter_requires_root: false,
        emitter_downgraded: false,
        quic_layout_family: None,
        outcome: if skipped { "skipped" } else { "success" }.to_string(),
        rationale: String::new(),
        succeeded_targets: weighted_success_score,
        total_targets: total_weight,
        weighted_success_score,
        total_weight,
        quality_score,
        proxy_config_json: None,
        notes: vec![],
        average_latency_ms,
        skipped,
        domain_outcomes: vec![],
    }
}

fn test_ui_config() -> ProxyUiConfig {
    let mut config = ProxyUiConfig::default();
    config.protocols.desync_udp = true;
    config.chains.tcp_steps = vec![];
    config.fake_packets.fake_sni = "www.wikipedia.org".to_string();
    config
}

fn test_spec() -> StrategyCandidateSpec {
    crate::candidates::candidate_spec("test_id", "Test Label", "test_family", test_ui_config())
}
