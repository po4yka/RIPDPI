use std::collections::BTreeMap;

use crate::types::{
    STRATEGY_PROBE_METHODOLOGY_VERSION, ScanPathMode, StrategyProbeAuditAssessment, StrategyProbeAuditConfidence,
    StrategyProbeAuditConfidenceLevel, StrategyProbeAuditCoverage, StrategyProbeCandidateSummary,
    StrategyProbeCompletionKind, StrategyProbeRecommendation, StrategyProbeReport, StrategyProbeTargetSelection,
};

use super::{
    DIAGNOSTICS_ENGINE_SCHEMA_VERSION, EngineProbeResultWire, EngineProgressWire, EngineScanReportWire,
    EngineScanRequestWire, ResolverRecommendationWire,
};

#[test]
fn diagnostics_schema_version_matches_contract_fixture() {
    use golden_test_support::assert_contract_fixture;
    use serde_json::json;

    let fixture = json!({
        "schemaVersion": DIAGNOSTICS_ENGINE_SCHEMA_VERSION,
    });
    let actual = serde_json::to_string_pretty(&fixture).expect("serialize");
    assert_contract_fixture("diagnostics_schema_version.json", &actual);
}

#[test]
fn diagnostics_wire_payloads_require_schema_version() {
    let request = serde_json::from_value::<EngineScanRequestWire>(serde_json::json!({}));
    let report = serde_json::from_value::<EngineScanReportWire>(serde_json::json!({}));
    let progress = serde_json::from_value::<EngineProgressWire>(serde_json::json!({}));

    for error in [request.unwrap_err(), report.unwrap_err(), progress.unwrap_err()] {
        assert!(error.to_string().contains("schemaVersion"), "error should name schemaVersion: {error}");
    }
}

#[test]
fn diagnostics_progress_field_manifest_matches_contract_fixture() {
    use golden_test_support::{assert_contract_fixture, extract_field_paths};

    let progress = EngineProgressWire {
        schema_version: DIAGNOSTICS_ENGINE_SCHEMA_VERSION,
        session_id: "test-session".to_string(),
        phase: "probing".to_string(),
        completed_steps: 5,
        total_steps: 10,
        message: "Running probes".to_string(),
        is_finished: false,
        latest_probe_target: Some("example.org".to_string()),
        latest_probe_outcome: Some("reachable".to_string()),
        strategy_probe_progress: None,
    };

    let json = serde_json::to_value(&progress).expect("serialize progress");
    let paths = extract_field_paths(&json);
    let manifest = serde_json::to_string_pretty(&paths).expect("serialize field paths");
    assert_contract_fixture("diagnostics_progress_fields.json", &manifest);
}

#[test]
fn diagnostics_scan_report_field_manifest_matches_contract_fixture() {
    use golden_test_support::{assert_contract_fixture, extract_field_paths};

    let report = EngineScanReportWire {
        schema_version: DIAGNOSTICS_ENGINE_SCHEMA_VERSION,
        session_id: "test-session".to_string(),
        profile_id: "connectivity-basic".to_string(),
        path_mode: ScanPathMode::RawPath,
        started_at: 1000,
        finished_at: 2000,
        summary: "All probes passed".to_string(),
        results: vec![EngineProbeResultWire {
            probe_type: "dns".to_string(),
            target: "example.org".to_string(),
            outcome: "reachable".to_string(),
            details: vec![],
            probe_retry_count: Some(0),
        }],
        resolver_recommendation: Some(ResolverRecommendationWire {
            trigger_outcome: "dns_tampering".to_string(),
            selected_resolver_id: "cloudflare".to_string(),
            selected_protocol: "doh".to_string(),
            selected_endpoint: "1.1.1.1:443".to_string(),
            selected_bootstrap_ips: vec!["1.1.1.1".to_string()],
            selected_host: "cloudflare-dns.com".to_string(),
            selected_port: 443,
            selected_tls_server_name: "cloudflare-dns.com".to_string(),
            selected_doh_url: "https://cloudflare-dns.com/dns-query".to_string(),
            selected_dnscrypt_provider_name: String::new(),
            selected_dnscrypt_public_key: String::new(),
            rationale: "DNS tampering detected".to_string(),
            applied_temporarily: false,
            persistable: true,
        }),
        strategy_probe_report: Some(StrategyProbeReport {
            suite_id: "full_matrix_v1".to_string(),
            methodology_version: STRATEGY_PROBE_METHODOLOGY_VERSION.to_string(),
            tcp_candidates: vec![StrategyProbeCandidateSummary {
                id: "baseline_current".to_string(),
                label: "Current strategy".to_string(),
                family: "baseline_current".to_string(),
                emitter_tier: crate::types::StrategyEmitterTier::NonRootProduction,
                exact_emitter_requires_root: false,
                emitter_downgraded: false,
                quic_layout_family: None,
                outcome: "skipped".to_string(),
                rationale: "DNS tampering detected before fallback; TCP strategy escalation skipped".to_string(),
                succeeded_targets: 0,
                total_targets: 6,
                weighted_success_score: 0,
                total_weight: 18,
                quality_score: 0,
                proxy_config_json: None,
                notes: vec![],
                average_latency_ms: None,
                skipped: true,
                domain_outcomes: vec![],
            }],
            quic_candidates: vec![StrategyProbeCandidateSummary {
                id: "quic_disabled".to_string(),
                label: "Current QUIC strategy".to_string(),
                family: "quic_disabled".to_string(),
                emitter_tier: crate::types::StrategyEmitterTier::NonRootProduction,
                exact_emitter_requires_root: false,
                emitter_downgraded: false,
                quic_layout_family: None,
                outcome: "skipped".to_string(),
                rationale: "DNS tampering detected before fallback; QUIC strategy escalation skipped".to_string(),
                succeeded_targets: 0,
                total_targets: 2,
                weighted_success_score: 0,
                total_weight: 4,
                quality_score: 0,
                proxy_config_json: None,
                notes: vec![],
                average_latency_ms: None,
                skipped: true,
                domain_outcomes: vec![],
            }],
            recommendation: StrategyProbeRecommendation {
                tcp_candidate_id: "baseline_current".to_string(),
                tcp_candidate_label: "Current strategy".to_string(),
                quic_candidate_id: "quic_disabled".to_string(),
                quic_candidate_label: "Current QUIC strategy".to_string(),
                quic_candidate_layout_family: None,
                rationale:
                    "dns_tampering classified before fallback; keep current strategy and prefer resolver override"
                        .to_string(),
                recommended_proxy_config_json: "{}".to_string(),
            },
            completion_kind: StrategyProbeCompletionKind::DnsShortCircuited,
            audit_assessment: Some(StrategyProbeAuditAssessment {
                dns_short_circuited: true,
                coverage: StrategyProbeAuditCoverage {
                    tcp_candidates_planned: 11,
                    tcp_candidates_executed: 0,
                    tcp_candidates_skipped: 1,
                    tcp_candidates_not_applicable: 0,
                    quic_candidates_planned: 2,
                    quic_candidates_executed: 0,
                    quic_candidates_skipped: 1,
                    quic_candidates_not_applicable: 0,
                    tcp_winner_succeeded_targets: 0,
                    tcp_winner_total_targets: 6,
                    quic_winner_succeeded_targets: 0,
                    quic_winner_total_targets: 2,
                    matrix_coverage_percent: 0,
                    winner_coverage_percent: 0,
                    tcp_winner_coverage_percent: 0,
                    quic_winner_coverage_percent: 0,
                },
                confidence: StrategyProbeAuditConfidence {
                    level: StrategyProbeAuditConfidenceLevel::Low,
                    score: 35,
                    rationale: "Baseline DNS tampering short-circuited the audit before fallback candidates ran"
                        .to_string(),
                    warnings: vec![
                        "Baseline DNS tampering short-circuited the audit before fallback candidates ran.".to_string(),
                    ],
                },
            }),
            target_selection: Some(StrategyProbeTargetSelection {
                cohort_id: "global-core".to_string(),
                cohort_label: "Global core".to_string(),
                domain_hosts: vec!["www.youtube.com".to_string(), "discord.com".to_string()],
                quic_hosts: vec!["www.youtube.com".to_string()],
            }),
            pilot_bucket_labels: vec!["foreign:google:ech=yes".to_string(), "foreign:direct:ech=no".to_string()],
            domain_strategy_seeds: vec![],
        }),
        observations: vec![],
        engine_analysis_version: Some("1.0".to_string()),
        diagnoses: vec![],
        classifier_version: Some("1.0".to_string()),
        pack_versions: BTreeMap::from([("core".to_string(), 1)]),
    };

    let json = serde_json::to_value(&report).expect("serialize report");
    let paths = extract_field_paths(&json);
    let manifest = serde_json::to_string_pretty(&paths).expect("serialize field paths");
    assert_contract_fixture("diagnostics_scan_report_fields.json", &manifest);
}
