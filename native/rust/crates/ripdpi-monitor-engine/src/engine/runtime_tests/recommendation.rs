use super::*;
use crate::candidates::strategy_probe_config_json;
use crate::engine::runners::prepare_strategy_probe_report;
use ripdpi_proxy_config::{ProxyConfigPayload, parse_proxy_config_json};

fn measured_candidate(spec: &crate::candidates::StrategyCandidateSpec) -> StrategyProbeCandidateSummary {
    assert!(spec.config.chains.tcp_steps.is_empty());
    assert!(spec.config.chains.udp_steps.is_empty());
    let mut summary = candidate_summary(spec.id, spec.family, 1);
    summary.desync_execution_required = false;
    summary.execution_evidence_complete = true;
    summary.proxy_config_json = Some(strategy_probe_config_json(&spec.config));
    summary
}

fn prepared_recommendation(tcp: bool, quic: bool) -> crate::types::StrategyProbeRecommendation {
    let plan = strategy_test_plan();
    let suite = &plan.strategy.as_ref().unwrap().suite;
    let tcp_spec = suite.tcp_candidates.iter().find(|spec| spec.id == "parser_only").unwrap();
    let quic_spec = suite.quic_candidates.iter().find(|spec| spec.id == "quic_disabled").unwrap();
    let mut runtime = ExecutionRuntime::new(
        Arc::new(Mutex::new(SharedState::default())),
        Arc::new(AtomicBool::new(false)),
    );
    if tcp {
        runtime.strategy.tcp_candidates.push(measured_candidate(tcp_spec));
    }
    if quic {
        runtime.strategy.quic_candidates.push(measured_candidate(quic_spec));
    }
    assert!(prepare_strategy_probe_report(&plan, &mut runtime));
    runtime.strategy.strategy_probe_report.unwrap().recommendation.expect("a measured lane has a recommendation")
}

#[test]
fn recommendation_keeps_measured_tcp_settings_when_quic_is_composed() {
    let recommendation = prepared_recommendation(true, true);
    let ProxyConfigPayload::Ui { config, .. } =
        parse_proxy_config_json(&recommendation.recommended_proxy_config_json).unwrap()
    else {
        panic!("UI config required");
    };
    assert!(config.protocols.desync_http);
    assert!(config.protocols.desync_https);
    assert!(config.parser_evasions.host_mixed_case);
    assert!(config.parser_evasions.domain_mixed_case);
    assert!(!config.protocols.desync_udp);
}

#[test]
fn recommendation_keeps_measured_tcp_when_quic_has_no_winner() {
    prepared_recommendation(true, false);
}

#[test]
fn recommendation_keeps_measured_quic_when_tcp_has_no_winner() {
    prepared_recommendation(false, true);
}

#[test]
fn audit_without_a_winner_still_reports_coverage_and_low_confidence() {
    let mut plan = strategy_test_plan();
    plan.strategy.as_mut().unwrap().suite_id = crate::util::STRATEGY_PROBE_SUITE_FULL_MATRIX_V1.to_string();
    let mut runtime = ExecutionRuntime::new(
        Arc::new(Mutex::new(SharedState::default())),
        Arc::new(AtomicBool::new(false)),
    );
    let mut failed = candidate_summary("baseline_plain_direct", "baseline", 0);
    failed.outcome = "failed".to_string();
    failed.succeeded_targets = 0;
    runtime.strategy.tcp_candidates.push(failed);
    assert!(prepare_strategy_probe_report(&plan, &mut runtime));

    let report = runtime.strategy.strategy_probe_report.unwrap();
    assert!(report.recommendation.is_none());
    let assessment = report.audit_assessment.expect("failed audits still report their measurement coverage");
    assert_eq!(assessment.coverage.tcp_candidates_executed, 1);
    assert_eq!(assessment.coverage.winner_coverage_percent, 0);
    assert_eq!(assessment.confidence.level, crate::types::StrategyProbeAuditConfidenceLevel::Low);
}
