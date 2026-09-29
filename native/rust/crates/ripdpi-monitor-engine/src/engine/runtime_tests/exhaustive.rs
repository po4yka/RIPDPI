use super::*;
use crate::test_fixtures::HttpTextServer;
use crate::tests::{DirectCandidateRuntimeLauncher, lock_network_probes};
use crate::types::{ConfirmGoodDpiEvidence, ConfirmGoodDpiEvidenceSource, DomainTarget};
use crate::util::STRATEGY_PROBE_SUITE_FULL_MATRIX_V1;

fn bounded_plan(suite_id: &str, max_candidates: Option<usize>) -> ExecutionPlan {
    let mut plan = strategy_test_plan();
    let strategy = plan.strategy.as_mut().expect("strategy plan");
    strategy.suite_id = suite_id.to_string();
    strategy.suite = build_strategy_probe_suite(suite_id, &ProxyUiConfig::default()).expect("strategy suite");
    strategy.max_candidates = max_candidates;
    // Two real, universally applicable candidates keep these coordinator tests small.
    strategy.suite.tcp_candidates.retain(|spec| matches!(spec.id, "baseline_plain_direct" | "parser_only"));
    assert_eq!(strategy.suite.tcp_candidates.len(), 2);
    strategy.suite.quic_candidates.retain(|spec| spec.id == "quic_disabled");
    let request = plan.request.strategy_probe.as_mut().expect("strategy request");
    request.suite_id = suite_id.to_string();
    request.max_candidates = max_candidates;
    plan.stage_order = vec![ExecutionStageId::StrategyTcpCandidates, ExecutionStageId::StrategyRecommendation];
    plan.total_steps = 3;
    plan
}

fn target(port: u16) -> DomainTarget {
    DomainTarget {
        host: "127.0.0.1".to_string(),
        connect_ip: Some("127.0.0.1".to_string()),
        connect_ips: Vec::new(),
        https_port: Some(9),
        http_port: Some(port),
        http_path: "/".to_string(),
        is_control: false,
        concurrency_probe: None,
    }
}

fn runtime() -> ExecutionRuntime {
    ExecutionRuntime::new(Arc::new(Mutex::new(SharedState::default())), Arc::new(AtomicBool::new(false)))
}

#[test]
fn exhaustive_audit_runs_tcp_after_confirmed_quic_but_quick_and_capped_keep_pivot() {
    let _serial = lock_network_probes();
    let server = HttpTextServer::start_text("HTTP/1.1 200 OK", "probe");
    for (suite_id, cap, exhaustive) in [
        (STRATEGY_PROBE_SUITE_FULL_MATRIX_V1, None, true),
        ("quick_v1", None, false),
        (STRATEGY_PROBE_SUITE_FULL_MATRIX_V1, Some(2), false),
    ] {
        let mut plan = bounded_plan(suite_id, cap);
        plan.request.domain_targets = vec![target(server.port())];
        plan.request.confirm_good_dpi_evidence = Some(ConfirmGoodDpiEvidence {
            source: ConfirmGoodDpiEvidenceSource::Active,
            stalled_flow_count: 3,
            distinct_target_count: 3,
            catalog_profile_validated: true,
            reality_handshake_confirmed: true,
            application_response_bytes: 0,
            quic_control_succeeded: true,
        });
        let mut runtime = runtime();
        let quic = plan.strategy.as_ref().expect("strategy plan").suite.quic_candidates.first().expect("QUIC spec");
        // Existing successful unmodified QUIC observation is input to the TCP decision.
        assert!(quic.config.chains.udp_steps.is_empty());
        let mut summary = candidate_summary(quic.id, quic.family, 1);
        summary.desync_execution_required = false;
        runtime.strategy.quic_candidates.push(summary);
        let (coordinator, _) = execution_coordinator(Arc::new(DirectCandidateRuntimeLauncher));
        assert!(matches!(coordinator.run(&plan, &mut runtime, None), RunnerOutcome::Completed));
        assert_eq!(runtime.strategy.tcp_candidates.len(), 2);
        assert!(runtime.strategy.tcp_candidates.iter().all(|candidate| candidate.skipped != exhaustive));
        assert_eq!(runtime.results.iter().any(|result| result.probe_type == "strategy_http"), exhaustive);
    }
}

#[test]
fn exhaustive_audit_keeps_failed_pilot_candidates_and_probes_every_target() {
    let _serial = lock_network_probes();
    let server = HttpTextServer::start_text("HTTP/1.1 403 Forbidden", "Access denied by upstream filtering");
    for (suite_id, cap, retained) in [
        (STRATEGY_PROBE_SUITE_FULL_MATRIX_V1, None, true),
        ("quick_v1", None, false),
        (STRATEGY_PROBE_SUITE_FULL_MATRIX_V1, Some(2), true),
    ] {
        let mut plan = bounded_plan(suite_id, cap);
        plan.request.domain_targets = vec![target(server.port()), target(server.port())];
        let mut runtime = runtime();
        let (coordinator, _) = execution_coordinator(Arc::new(DirectCandidateRuntimeLauncher));
        assert!(matches!(coordinator.run(&plan, &mut runtime, None), RunnerOutcome::Completed));
        let parser = runtime
            .strategy
            .tcp_candidates
            .iter()
            .find(|candidate| candidate.id == "parser_only")
            .expect("parser candidate");
        assert_eq!(parser.succeeded_targets, 0);
        assert_eq!(parser.outcome != "eliminated", retained);
        let results = runtime.results.iter().filter(|result| result.target.starts_with("Parser-only ·")).count();
        assert_eq!(results, if retained { 4 } else { 0 });
    }
}

#[test]
fn exhaustive_audit_stage_budget_remains_active_and_partial_precedes_dns_fallback() {
    for (suite_id, cap, completion) in [
        (STRATEGY_PROBE_SUITE_FULL_MATRIX_V1, None, StrategyProbeCompletionKind::PartialResults),
        ("quick_v1", None, StrategyProbeCompletionKind::DnsTamperingWithFallback),
        (STRATEGY_PROBE_SUITE_FULL_MATRIX_V1, Some(2), StrategyProbeCompletionKind::DnsTamperingWithFallback),
    ] {
        let mut plan = bounded_plan(suite_id, cap);
        plan.request.domain_targets = vec![target(9)];
        let mut runtime = runtime();
        runtime.strategy.dns_override_domain_targets = Some(plan.request.domain_targets.clone());
        runtime.results.push(ProbeResult {
            probe_type: "dns_integrity".to_string(),
            target: "loopback".to_string(),
            outcome: "dns_nxdomain_mismatch".to_string(),
            details: Vec::new(),
        });
        let global_deadline = std::time::Instant::now() + Duration::from_millis(300);
        runtime.set_scan_deadline(global_deadline);
        let launcher = Arc::new(DeadlineObservingRuntimeLauncher {
            observed_deadlines: Mutex::new(Vec::new()),
            work: Duration::from_millis(220),
        });
        let (coordinator, _) = execution_coordinator(launcher.clone());
        assert!(matches!(coordinator.run(&plan, &mut runtime, None), RunnerOutcome::Completed));
        let observed = launcher.observed_deadlines.lock().expect("observed deadlines");
        assert!(observed.first().copied().flatten().is_some_and(|deadline| deadline < global_deadline));
        let stages = runtime.stage_executions();
        assert_eq!(stages[0].skipped_by_stage_budget_steps, 1);
        assert_eq!(stages[1].skipped_by_global_deadline_steps, 0);
        assert_eq!(runtime.strategy.tcp_candidates.len(), 1);
        assert_eq!(
            runtime.strategy.strategy_probe_report.as_ref().expect("partial report").completion_kind,
            completion
        );
    }
}

#[test]
fn capped_quick_and_full_suites_keep_the_candidate_limit() {
    let _serial = lock_network_probes();
    let server = HttpTextServer::start_text("HTTP/1.1 200 OK", "probe");
    for suite_id in ["quick_v1", STRATEGY_PROBE_SUITE_FULL_MATRIX_V1] {
        let mut plan = bounded_plan(suite_id, Some(1));
        plan.request.domain_targets = vec![target(server.port())];
        let mut runtime = runtime();
        let (coordinator, _) = execution_coordinator(Arc::new(DirectCandidateRuntimeLauncher));
        assert!(matches!(coordinator.run(&plan, &mut runtime, None), RunnerOutcome::Completed));
        assert_eq!(runtime.strategy.tcp_candidates.len(), 1);
        assert_eq!(runtime.strategy.tcp_candidates[0].id, "baseline_plain_direct");
        assert!(!runtime.results.iter().any(|result| result.target.starts_with("Parser-only ·")));
    }
}
