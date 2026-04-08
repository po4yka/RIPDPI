use ripdpi_failure_classifier::FailureClass;
use ripdpi_proxy_config::{parse_proxy_config_json, ProxyConfigPayload, ProxyUiConfig};

use super::FamilyFailureTracker;

#[test]
fn family_tracker_blocks_after_threshold_2() {
    let mut tracker = FamilyFailureTracker::new(2);
    tracker.record("hostfake", true);
    assert!(tracker.blocked_family() != Some("hostfake"));
    tracker.record("hostfake", true);
    assert!(tracker.blocked_family() == Some("hostfake"));
}

#[test]
fn family_tracker_blocks_after_threshold_4() {
    let mut tracker = FamilyFailureTracker::new(4);
    tracker.record("hostfake", true);
    tracker.record("hostfake", true);
    assert!(tracker.blocked_family() != Some("hostfake"));
    tracker.record("hostfake", true);
    assert!(tracker.blocked_family() != Some("hostfake"));
    tracker.record("hostfake", true);
    assert!(tracker.blocked_family() == Some("hostfake"));
}

#[test]
fn family_tracker_resets_on_success() {
    let mut tracker = FamilyFailureTracker::new(2);
    tracker.record("hostfake", true);
    tracker.record("hostfake", false); // success resets consecutive count and blocked
    tracker.record("hostfake", true);
    assert!(tracker.blocked_family() != Some("hostfake")); // only 1 consecutive failure after reset
}

#[test]
fn family_tracker_resets_on_different_family() {
    let mut tracker = FamilyFailureTracker::new(2);
    tracker.record("hostfake", true);
    tracker.record("split", true); // different family resets hostfake consecutive counter
    tracker.record("hostfake", true);
    assert!(tracker.blocked_family() != Some("hostfake")); // only 1 consecutive for hostfake
}

use super::{
    baseline_has_tls_ech_only, baseline_supports_ech_candidates, ordered_follow_up_tcp_candidates,
    resolve_recommended_proxy_config_json, resolve_strategy_probe_audit_assessment,
};
use crate::candidates::{build_tcp_candidates, CandidateEligibility};
use crate::classification::{interleave_candidate_families, reorder_tcp_candidates_for_failure};
use crate::types::{
    ProbeDetail, ProbeResult, StrategyProbeAuditAssessment, StrategyProbeAuditConfidenceLevel,
    StrategyProbeCandidateSummary, StrategyProbeRecommendation,
};
use crate::util::STRATEGY_PROBE_SUITE_FULL_MATRIX_V1;

fn s(v: &str) -> String {
    v.to_string()
}

fn quic_candidate_summary(proxy_config_json: Option<String>) -> StrategyProbeCandidateSummary {
    StrategyProbeCandidateSummary {
        id: s("quic_realistic_burst"),
        label: s("QUIC realistic burst"),
        family: s("quic_burst"),
        outcome: s("success"),
        rationale: s("Recovered QUIC"),
        succeeded_targets: 1,
        total_targets: 1,
        weighted_success_score: 2,
        total_weight: 2,
        quality_score: 4,
        proxy_config_json,
        notes: Vec::new(),
        average_latency_ms: Some(220),
        skipped: false,
    }
}

fn strategy_candidate_summary(
    id: &str,
    family: &str,
    weighted_success_score: usize,
    total_weight: usize,
    succeeded_targets: usize,
    total_targets: usize,
    skipped: bool,
    outcome: &str,
) -> StrategyProbeCandidateSummary {
    StrategyProbeCandidateSummary {
        id: s(id),
        label: id.replace('_', " "),
        family: s(family),
        outcome: s(outcome),
        rationale: s("candidate result"),
        succeeded_targets,
        total_targets,
        weighted_success_score,
        total_weight,
        quality_score: weighted_success_score.saturating_mul(2),
        proxy_config_json: None,
        notes: Vec::new(),
        average_latency_ms: Some(200),
        skipped,
    }
}

fn recommendation() -> StrategyProbeRecommendation {
    StrategyProbeRecommendation {
        tcp_candidate_id: s("tcp_winner"),
        tcp_candidate_label: s("tcp winner"),
        quic_candidate_id: s("quic_winner"),
        quic_candidate_label: s("quic winner"),
        rationale: s("best"),
        recommended_proxy_config_json: s("{}"),
    }
}

fn baseline_https_result(outcome: &str, tls_ech_resolution_detail: &str) -> ProbeResult {
    ProbeResult {
        probe_type: s("strategy_https"),
        target: s("baseline_current · example.com"),
        outcome: s(outcome),
        details: vec![
            ProbeDetail { key: s("candidateId"), value: s("baseline_current") },
            ProbeDetail { key: s("tlsEchResolutionDetail"), value: s(tls_ech_resolution_detail) },
        ],
    }
}

#[test]
fn resolve_recommended_proxy_config_json_prefers_winning_quic_summary_config() {
    let mut composed_config = ProxyUiConfig::default();
    composed_config.chains.tcp_steps = vec![crate::candidates::tcp_step("tlsrec", "extlen")];
    composed_config.quic.fake_profile = "realistic_initial".to_string();

    let mut fallback_config = ProxyUiConfig::default();
    fallback_config.quic.fake_profile = "compat_default".to_string();
    let fallback_quic_spec = crate::candidates::candidate_spec(
        "quic_realistic_burst",
        "QUIC realistic burst",
        "quic_burst",
        fallback_config,
    );

    let winning_quic_candidate =
        quic_candidate_summary(Some(crate::candidates::strategy_probe_config_json(&composed_config)));

    let recommended_proxy_config_json =
        resolve_recommended_proxy_config_json(&winning_quic_candidate, &fallback_quic_spec);

    match parse_proxy_config_json(&recommended_proxy_config_json).expect("parse ui config") {
        ProxyConfigPayload::Ui { config, .. } => {
            assert_eq!(config.chains.tcp_steps.len(), 1);
            assert_eq!(config.chains.tcp_steps[0].kind, "tlsrec");
            assert_eq!(config.quic.fake_profile, "realistic_initial");
        }
        ProxyConfigPayload::CommandLine { .. } => panic!("expected UI proxy config"),
    }
}

#[test]
fn resolve_recommended_proxy_config_json_falls_back_to_quic_winner_spec_config() {
    let mut fallback_config = ProxyUiConfig::default();
    fallback_config.quic.fake_profile = "compat_default".to_string();
    let fallback_quic_spec =
        crate::candidates::candidate_spec("quic_compat_burst", "QUIC compat burst", "quic_burst", fallback_config);

    let winning_quic_candidate = quic_candidate_summary(None);

    let recommended_proxy_config_json =
        resolve_recommended_proxy_config_json(&winning_quic_candidate, &fallback_quic_spec);

    match parse_proxy_config_json(&recommended_proxy_config_json).expect("parse ui config") {
        ProxyConfigPayload::Ui { config, .. } => {
            assert_eq!(config.chains.tcp_steps, fallback_quic_spec.config.chains.tcp_steps);
            assert_eq!(config.quic.fake_profile, "compat_default");
        }
        ProxyConfigPayload::CommandLine { .. } => panic!("expected UI proxy config"),
    }
}

#[test]
fn baseline_ech_detection_recognizes_resolution_detail_and_tls_ech_only() {
    assert!(baseline_supports_ech_candidates(&[baseline_https_result("tls_ok", "ech_config_available")]));
    assert!(baseline_supports_ech_candidates(&[baseline_https_result("tls_ech_only", "none")]));
    assert!(baseline_has_tls_ech_only(&[baseline_https_result("tls_ech_only", "none")]));
    assert!(!baseline_supports_ech_candidates(&[baseline_https_result("tls_ok", "none")]));
    assert!(!baseline_has_tls_ech_only(&[baseline_https_result("tls_ok", "ech_config_available")]));
}

#[test]
fn ordered_follow_up_tcp_candidates_prioritize_ech_candidates_after_tls_ech_only_baseline() {
    let candidates = build_tcp_candidates(&ProxyUiConfig::default());

    let ordered = ordered_follow_up_tcp_candidates(
        &candidates,
        Some(FailureClass::TlsAlert),
        &[baseline_https_result("tls_ech_only", "ech_config_available")],
        7,
        true,
    );
    let ids = ordered.iter().take(2).map(|candidate| candidate.id).collect::<Vec<_>>();

    assert_eq!(ids, vec!["ech_split", "ech_tlsrec"]);
    assert!(ordered
        .iter()
        .take(2)
        .all(|candidate| candidate.eligibility == CandidateEligibility::RequiresEchCapability));
}

#[test]
fn ordered_follow_up_tcp_candidates_keep_normal_order_without_tls_ech_only_baseline() {
    let candidates = build_tcp_candidates(&ProxyUiConfig::default());
    let expected = interleave_candidate_families(
        reorder_tcp_candidates_for_failure(&candidates, Some(FailureClass::TlsAlert), true)
            .into_iter()
            .skip(1)
            .collect(),
        7,
    );

    let ordered = ordered_follow_up_tcp_candidates(
        &candidates,
        Some(FailureClass::TlsAlert),
        &[baseline_https_result("tls_ok", "ech_config_available")],
        7,
        true,
    );

    assert_eq!(
        ordered.iter().map(|candidate| candidate.id).collect::<Vec<_>>(),
        expected.iter().map(|candidate| candidate.id).collect::<Vec<_>>()
    );
}

fn audit(
    tcp: &[StrategyProbeCandidateSummary],
    quic: &[StrategyProbeCandidateSummary],
    tcp_planned: usize,
    quic_planned: usize,
    dns_sc: bool,
) -> Option<StrategyProbeAuditAssessment> {
    resolve_strategy_probe_audit_assessment(
        STRATEGY_PROBE_SUITE_FULL_MATRIX_V1,
        tcp,
        quic,
        &recommendation(),
        tcp_planned,
        quic_planned,
        dns_sc,
    )
}

#[test]
fn resolve_strategy_probe_audit_assessment_high_when_matrix_is_consistent() {
    let tcp = vec![
        strategy_candidate_summary("tcp_runner_up", "split", 40, 100, 2, 5, false, "partial"),
        strategy_candidate_summary("tcp_winner", "hostfake", 100, 100, 5, 5, false, "success"),
    ];
    let quic = vec![
        strategy_candidate_summary("quic_runner_up", "quic_disabled", 45, 100, 1, 2, false, "partial"),
        strategy_candidate_summary("quic_winner", "quic_burst", 100, 100, 2, 2, false, "success"),
    ];
    let a = audit(&tcp, &quic, 2, 2, false).expect("audit assessment");
    assert_eq!(a.confidence.level, StrategyProbeAuditConfidenceLevel::High);
    assert_eq!(a.confidence.score, 100);
    assert_eq!(a.coverage.matrix_coverage_percent, 100);
    assert_eq!(a.coverage.winner_coverage_percent, 100);
    assert!(a.confidence.warnings.is_empty());
}

#[test]
fn resolve_strategy_probe_audit_assessment_low_when_dns_short_circuited() {
    let tcp = vec![strategy_candidate_summary("tcp_winner", "baseline", 0, 0, 0, 5, true, "skipped")];
    let quic = vec![strategy_candidate_summary("quic_winner", "quic_disabled", 0, 0, 0, 2, true, "skipped")];
    let a = audit(&tcp, &quic, 3, 2, true).expect("audit assessment");
    assert_eq!(a.confidence.level, StrategyProbeAuditConfidenceLevel::Low);
    assert!(a.dns_short_circuited);
    assert_eq!(
        a.confidence.rationale,
        "Baseline DNS tampering short-circuited the audit before fallback candidates ran"
    );
    assert!(a
        .confidence
        .warnings
        .contains(&s("Baseline DNS tampering short-circuited the audit before fallback candidates ran.")));
}

#[test]
fn resolve_strategy_probe_audit_assessment_penalizes_incomplete_lane_execution() {
    let tcp = vec![
        strategy_candidate_summary("tcp_runner_up", "split", 50, 100, 2, 5, false, "partial"),
        strategy_candidate_summary("tcp_winner", "hostfake", 100, 100, 5, 5, false, "success"),
    ];
    let quic = vec![
        strategy_candidate_summary("quic_runner_up", "quic_disabled", 55, 100, 1, 2, false, "partial"),
        strategy_candidate_summary("quic_winner", "quic_burst", 100, 100, 2, 2, false, "success"),
    ];
    let a = audit(&tcp, &quic, 4, 4, false).expect("audit assessment");
    assert_eq!(a.confidence.level, StrategyProbeAuditConfidenceLevel::Medium);
    assert_eq!(a.confidence.score, 70);
    assert!(a.confidence.warnings.contains(&s("TCP matrix coverage stayed below 75% of applicable candidates.")));
    assert!(a.confidence.warnings.contains(&s("QUIC matrix coverage stayed below 75% of applicable candidates.")));
}

#[test]
fn resolve_strategy_probe_audit_assessment_excludes_not_applicable_candidates_from_coverage() {
    let tcp = vec![
        strategy_candidate_summary("tcp_runner_up", "split", 90, 100, 4, 5, false, "success"),
        strategy_candidate_summary("tcp_winner", "hostfake", 100, 100, 5, 5, false, "success"),
        strategy_candidate_summary("tcp_not_applicable", "split", 0, 0, 0, 0, false, "not_applicable"),
    ];
    let quic = vec![
        strategy_candidate_summary("quic_runner_up", "quic_disabled", 90, 100, 1, 2, false, "success"),
        strategy_candidate_summary("quic_winner", "quic_burst", 100, 100, 2, 2, false, "success"),
        strategy_candidate_summary("quic_not_applicable", "quic_burst", 0, 0, 0, 0, false, "not_applicable"),
    ];
    let a = audit(&tcp, &quic, 3, 3, false).expect("audit assessment");
    assert_eq!(a.coverage.tcp_candidates_planned, 3);
    assert_eq!(a.coverage.tcp_candidates_not_applicable, 1);
    assert_eq!(a.coverage.quic_candidates_planned, 3);
    assert_eq!(a.coverage.quic_candidates_not_applicable, 1);
    assert_eq!(a.coverage.matrix_coverage_percent, 100);
    assert_eq!(a.confidence.level, StrategyProbeAuditConfidenceLevel::High);
    assert!(!a.confidence.warnings.iter().any(|w| w.contains("applicable candidates")));
}

#[test]
fn resolve_strategy_probe_audit_assessment_does_not_penalize_non_ech_baselines_for_ech_candidates() {
    let tcp = vec![
        strategy_candidate_summary("tcp_runner_up", "split", 90, 100, 4, 5, false, "success"),
        strategy_candidate_summary("tcp_winner", "hostfake", 100, 100, 5, 5, false, "success"),
        strategy_candidate_summary("ech_split", "ech_split", 0, 0, 0, 0, false, "not_applicable"),
        strategy_candidate_summary("ech_tlsrec", "ech_tlsrec", 0, 0, 0, 0, false, "not_applicable"),
    ];
    let quic = vec![
        strategy_candidate_summary("quic_runner_up", "quic_disabled", 90, 100, 1, 2, false, "success"),
        strategy_candidate_summary("quic_winner", "quic_burst", 100, 100, 2, 2, false, "success"),
    ];
    let a = audit(&tcp, &quic, 4, 2, false).expect("audit assessment");
    assert_eq!(a.coverage.tcp_candidates_not_applicable, 2);
    assert_eq!(a.coverage.matrix_coverage_percent, 100);
    assert_eq!(a.confidence.level, StrategyProbeAuditConfidenceLevel::High);
    assert_eq!(a.confidence.score, 100);
    assert_eq!(a.confidence.rationale, "Matrix coverage and winner strength are consistent");
}

#[test]
fn resolve_strategy_probe_audit_assessment_penalizes_narrow_winner_margin() {
    let tcp = vec![
        strategy_candidate_summary("tcp_runner_up", "split", 92, 100, 4, 5, false, "success"),
        strategy_candidate_summary("tcp_winner", "hostfake", 96, 100, 5, 5, false, "success"),
    ];
    let quic = vec![
        strategy_candidate_summary("quic_runner_up", "quic_disabled", 89, 100, 2, 2, false, "success"),
        strategy_candidate_summary("quic_winner", "quic_burst", 95, 100, 2, 2, false, "success"),
    ];
    let a = audit(&tcp, &quic, 2, 2, false).expect("audit assessment");
    assert_eq!(a.confidence.level, StrategyProbeAuditConfidenceLevel::High);
    assert_eq!(a.confidence.score, 80);
    assert!(a.confidence.warnings.contains(&s("TCP winner margin stayed below 10 points over the next candidate.")));
    assert!(a.confidence.warnings.contains(&s("QUIC winner margin stayed below 10 points over the next candidate.")));
}

#[test]
fn test_audit_assessment_penalizes_all_tied_candidates() {
    let tcp: Vec<_> = (0..15)
        .map(|i| strategy_candidate_summary(&format!("tcp_{i}"), "split", 2, 9, 1, 6, false, "partial"))
        .collect();
    let quic: Vec<_> =
        (0..3).map(|i| strategy_candidate_summary(&format!("quic_{i}"), "quic", 0, 4, 0, 2, false, "failed")).collect();
    let rec = StrategyProbeRecommendation {
        tcp_candidate_id: s("tcp_0"),
        tcp_candidate_label: s("tcp 0"),
        quic_candidate_id: s("quic_0"),
        quic_candidate_label: s("quic 0"),
        rationale: s("all tied"),
        recommended_proxy_config_json: String::new(),
    };
    let a =
        resolve_strategy_probe_audit_assessment(STRATEGY_PROBE_SUITE_FULL_MATRIX_V1, &tcp, &quic, &rec, 15, 3, false)
            .expect("should produce assessment for full_matrix_v1");
    assert_eq!(a.confidence.level, StrategyProbeAuditConfidenceLevel::Low);
    assert!(a.confidence.warnings.iter().any(|w| w.contains("TCP candidates produced identical results")));
    assert!(a.confidence.warnings.iter().any(|w| w.contains("QUIC candidates produced identical results")));
}

#[test]
fn test_audit_assessment_high_when_baseline_tied_high_coverage() {
    let tcp = vec![
        strategy_candidate_summary("baseline_current", "baseline", 90, 100, 4, 5, false, "success"),
        strategy_candidate_summary("tcp_split", "split", 90, 100, 4, 5, false, "success"),
        strategy_candidate_summary("tcp_hostfake", "hostfake", 90, 100, 4, 5, false, "success"),
    ];
    let quic = vec![
        strategy_candidate_summary("baseline_current", "baseline", 80, 100, 2, 2, false, "success"),
        strategy_candidate_summary("quic_burst", "quic_burst", 80, 100, 2, 2, false, "success"),
    ];
    let rec = StrategyProbeRecommendation {
        tcp_candidate_id: s("baseline_current"),
        tcp_candidate_label: s("Current strategy"),
        quic_candidate_id: s("baseline_current"),
        quic_candidate_label: s("Current QUIC strategy"),
        rationale: s("all tied"),
        recommended_proxy_config_json: String::new(),
    };
    let a =
        resolve_strategy_probe_audit_assessment(STRATEGY_PROBE_SUITE_FULL_MATRIX_V1, &tcp, &quic, &rec, 3, 2, false)
            .expect("should produce assessment");
    assert_eq!(a.confidence.level, StrategyProbeAuditConfidenceLevel::High);
    assert_eq!(a.confidence.score, 100);
    assert!(a.confidence.rationale.contains("no evasion needed"));
    assert!(a.confidence.warnings.is_empty());
}

#[test]
fn test_audit_assessment_low_when_baseline_tied_low_coverage() {
    let tcp = vec![
        strategy_candidate_summary("baseline_current", "baseline", 20, 100, 1, 5, false, "partial"),
        strategy_candidate_summary("tcp_split", "split", 20, 100, 1, 5, false, "partial"),
    ];
    let quic = vec![
        strategy_candidate_summary("baseline_current", "baseline", 20, 100, 0, 2, false, "failed"),
        strategy_candidate_summary("quic_burst", "quic_burst", 20, 100, 0, 2, false, "failed"),
    ];
    let rec = StrategyProbeRecommendation {
        tcp_candidate_id: s("baseline_current"),
        tcp_candidate_label: s("Current strategy"),
        quic_candidate_id: s("baseline_current"),
        quic_candidate_label: s("Current QUIC strategy"),
        rationale: s("all tied"),
        recommended_proxy_config_json: String::new(),
    };
    let a =
        resolve_strategy_probe_audit_assessment(STRATEGY_PROBE_SUITE_FULL_MATRIX_V1, &tcp, &quic, &rec, 2, 2, false)
            .expect("should produce assessment");
    assert_eq!(a.confidence.level, StrategyProbeAuditConfidenceLevel::Low);
    assert!(a.confidence.warnings.iter().any(|w| w.contains("identical results")));
}
