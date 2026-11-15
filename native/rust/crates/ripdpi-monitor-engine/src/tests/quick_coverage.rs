use super::*;

#[test]
fn monitor_session_strategy_probe_without_quic_success_keeps_tcp_recommendation() {
    let _serial = lock_network_probes();
    let server = HttpTextServer::start_text("HTTP/1.1 200 OK", "probe");
    let mut request = strategy_probe_request(minimal_ui_config());
    request.domain_targets[0].http_port = Some(server.port());
    let session = monitor_session_with_direct_candidate_runtime();

    session.start_scan("session-strategy".to_string(), request.into()).expect("start strategy probe");
    let report = wait_for_report(&session);
    let execution_plan = report.execution_plan.as_ref().expect("execution plan");
    let strategy_plan = execution_plan.strategy.as_ref().expect("strategy execution plan");
    assert_eq!(
        (
            strategy_plan.suite_id.as_str(),
            strategy_plan.inventory_semantics.as_str(),
            strategy_plan.max_candidates,
            strategy_plan.tcp_candidates.is_empty(),
            strategy_plan.quic_candidates.is_empty(),
        ),
        ("quick_v1", "ordered_pre_runtime_filter_pool", Some(2), false, false),
    );
    let strategy_probe = report.strategy_probe_report.expect("strategy probe report");

    assert_eq!(report.profile_id, "automatic-probing");
    assert_eq!(
        strategy_probe.tcp_candidates.first().map(|candidate| candidate.id.as_str()),
        Some("baseline_plain_direct")
    );
    let recommendation = strategy_probe.recommendation.as_ref().expect("measured TCP recommendation");
    assert!(recommendation.tcp_candidate_id.is_some());
    assert!(recommendation.quic_candidate_id.is_none(), "a non-applicable QUIC candidate is not promotable");
    let assessment = strategy_probe.audit_assessment.expect("quick scans report measured coverage");
    assert_eq!(assessment.coverage.tcp_candidates_planned, 2);
    assert_eq!(assessment.coverage.quic_candidates_planned, 0);
    assert_eq!(strategy_probe.completion_kind, StrategyProbeCompletionKind::Normal);
}

#[test]
fn completed_quick_scan_reports_high_coverage_for_a_measured_control() {
    let _serial = lock_network_probes();
    let http = HttpTextServer::start_text("HTTP/1.1 200 OK", "probe");
    let https = TlsHttpServer::start(TlsMode::Single("localhost".to_string()), FatServerMode::AlwaysOk);
    let mut request = strategy_probe_request(minimal_ui_config());
    let target = &mut request.domain_targets[0];
    target.host = "localhost".into();
    target.connect_ip = Some("127.0.0.1".into());
    target.http_port = Some(http.port());
    target.https_port = Some(https.port());
    target.is_control = true;
    request.strategy_probe.as_mut().unwrap().max_candidates = Some(1);

    let shared = Arc::new(Mutex::new(crate::types::SharedState::default()));
    crate::engine::run_engine_scan(
        shared.clone(),
        Arc::new(std::sync::atomic::AtomicBool::new(false)),
        "quick-control".into(),
        request,
        std::time::Instant::now() + Duration::from_secs(8),
        Arc::new(Mutex::new(None)),
        Some(Arc::new(NoCertificateVerification)),
        Arc::new(DirectCandidateRuntimeLauncher),
    );

    let report = shared.lock().unwrap().report.take().expect("terminal report");
    assert_eq!(report.completion_kind, ScanCompletionKind::Normal);
    let strategy = report.strategy_probe_report.expect("measured strategy report");
    assert_eq!(strategy.completion_kind, StrategyProbeCompletionKind::Normal);
    let assessment = strategy.audit_assessment.expect("quick coverage assessment");
    assert_eq!(assessment.coverage.matrix_coverage_percent, 100);
    assert_eq!(assessment.coverage.winner_coverage_percent, 100);
    assert_eq!(assessment.confidence.level, crate::types::StrategyProbeAuditConfidenceLevel::High);
    let winner_id = strategy.recommendation.unwrap().tcp_candidate_id.unwrap();
    let winner = strategy.tcp_candidates.iter().find(|candidate| candidate.id == winner_id).unwrap();
    assert!(winner.domain_outcomes.iter().any(|row| row.domain == "localhost" && row.is_control && row.succeeded));
}
