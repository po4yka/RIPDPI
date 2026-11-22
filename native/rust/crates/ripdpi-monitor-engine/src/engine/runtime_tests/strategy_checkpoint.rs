use super::*;
use crate::test_fixtures::HttpTextServer;
use crate::tests::{DirectCandidateRuntimeLauncher, lock_network_probes};
use crate::types::{DomainTarget, ScanCompletionKind, ScanReportDisposition, StrategyProbeRuntimeTerminalStatus};

#[test]
fn checkpoint_keeps_completed_candidate_evidence_without_a_recommendation() {
    let _serial = lock_network_probes();
    let http = HttpTextServer::start_text("HTTP/1.1 200 OK", "candidate response");
    let mut plan = strategy_test_plan();
    plan.request.domain_targets = vec![DomainTarget {
        host: "127.0.0.1".to_string(),
        connect_ip: Some("127.0.0.1".to_string()),
        connect_ips: Vec::new(),
        https_port: Some(9),
        http_port: Some(http.port()),
        http_path: "/".to_string(),
        is_control: false,
        control_tier: None,
        concurrency_probe: None,
    }];
    plan.request.strategy_probe.as_mut().expect("strategy request").max_candidates = Some(2);
    let strategy = plan.strategy.as_mut().expect("strategy plan");
    strategy.max_candidates = Some(2);
    strategy.suite.tcp_candidates.retain(|spec| matches!(spec.id, "baseline_plain_direct" | "parser_only"));
    plan.stage_order = vec![ExecutionStageId::StrategyTcpCandidates];
    plan.total_steps = 2;
    let mut runtime = ExecutionRuntime::new(Arc::new(Mutex::new(SharedState::default())), Arc::new(AtomicBool::new(false)));
    let (coordinator, _) = execution_coordinator(Arc::new(DirectCandidateRuntimeLauncher));

    assert!(matches!(coordinator.run(&plan, &mut runtime, None), RunnerOutcome::Completed));
    let shared = runtime.shared.lock().expect("checkpoint state");
    let report = shared.checkpoint_report.as_ref().expect("checkpoint");
    assert_eq!(report.report_disposition, ScanReportDisposition::Checkpoint);
    assert_eq!(report.completion_kind, ScanCompletionKind::PartialResults);
    assert!(report.candidate_runtime_cleanup.is_none());
    assert!(report.results.iter().any(|result| result.probe_type == "strategy_http" && result.outcome == "http_ok"));
    let strategy_report = report.strategy_probe_report.as_ref().expect("candidate checkpoint");
    assert_eq!(strategy_report.completion_kind, StrategyProbeCompletionKind::PartialResults);
    assert!(strategy_report.recommendation.is_none());
    assert!(strategy_report.domain_strategy_seeds.is_empty());
    let candidates: Vec<_> = strategy_report.tcp_candidates.iter().map(|summary| summary.id.as_str()).collect();
    assert_eq!(candidates, ["baseline_plain_direct", "parser_only"]);
    assert!(strategy_report.tcp_candidates.iter().all(|summary| {
        summary.succeeded_targets > 0
            && summary.execution_evidence_complete
            && summary.runtime_terminal_status == StrategyProbeRuntimeTerminalStatus::CleanShutdown
    }));
}
