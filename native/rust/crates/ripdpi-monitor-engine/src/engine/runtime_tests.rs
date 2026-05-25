use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex};

use super::{publish_cancelled_run, ExecutionPlan, ExecutionRuntime, ExecutionStageId, StrategyExecutionPlan};
use crate::candidates::build_strategy_probe_suite;
use crate::transport::direct_transport;
use crate::types::{
    DiagnosticProfileFamily, ProbeResult, ScanKind, ScanPathMode, ScanRequest, SharedState,
    StrategyEmitterTier, StrategyProbeCandidateSummary, StrategyProbeCompletionKind, StrategyProbeProgressLane,
    StrategyProbeRequest,
};
use ripdpi_monitor_adapter::proxy_config::ProxyUiConfig;

#[test]
fn parallel_group_contains_expected_stages() {
    // These are the stages run concurrently for CONNECTIVITY scans.
    // If this list changes, update ExecutionCoordinator::run() accordingly.
    let parallel_group: &[ExecutionStageId] = &[ExecutionStageId::Dns, ExecutionStageId::Tcp, ExecutionStageId::Quic];
    assert!(parallel_group.contains(&ExecutionStageId::Dns));
    assert!(parallel_group.contains(&ExecutionStageId::Tcp));
    assert!(parallel_group.contains(&ExecutionStageId::Quic));
    assert!(!parallel_group.contains(&ExecutionStageId::Web));
    assert!(!parallel_group.contains(&ExecutionStageId::Service));
    assert!(!parallel_group.contains(&ExecutionStageId::StrategyTcpCandidates));
}

fn test_plan() -> ExecutionPlan {
    ExecutionPlan {
        session_id: "session-1".to_string(),
        request: ScanRequest {
            profile_id: "automatic-probing".to_string(),
            display_name: "Automatic probing".to_string(),
            path_mode: ScanPathMode::RawPath,
            kind: ScanKind::StrategyProbe,
            family: DiagnosticProfileFamily::AutomaticProbing,
            region_tag: None,
            manual_only: false,
            pack_refs: Vec::new(),
            proxy_host: None,
            proxy_port: None,
            probe_tasks: Vec::new(),
            domain_targets: Vec::new(),
            dns_targets: Vec::new(),
            tcp_targets: Vec::new(),
            quic_targets: Vec::new(),
            service_targets: Vec::new(),
            circumvention_targets: Vec::new(),
            throughput_targets: Vec::new(),
            whitelist_sni: Vec::new(),
            telegram_target: None,
            strategy_probe: None,
            network_snapshot: None,
            route_probe: None,
            scan_deadline_ms: None,
                diagnostic_tls_keylog_path: None,
        },
        started_at: 0,
        total_steps: 8,
        transport: direct_transport(),
        probe_context: crate::connectivity::ProbeExecutionContext::new(direct_transport()),
        stage_order: Vec::new(),
        strategy: None,
    }
}

fn strategy_test_plan() -> ExecutionPlan {
    let base = ProxyUiConfig::default();
    ExecutionPlan {
        session_id: "session-1".to_string(),
        request: ScanRequest {
            profile_id: "automatic-probing".to_string(),
            display_name: "Automatic probing".to_string(),
            path_mode: ScanPathMode::RawPath,
            kind: ScanKind::StrategyProbe,
            family: DiagnosticProfileFamily::AutomaticProbing,
            region_tag: None,
            manual_only: false,
            pack_refs: Vec::new(),
            proxy_host: None,
            proxy_port: None,
            probe_tasks: Vec::new(),
            domain_targets: Vec::new(),
            dns_targets: Vec::new(),
            tcp_targets: Vec::new(),
            quic_targets: Vec::new(),
            service_targets: Vec::new(),
            circumvention_targets: Vec::new(),
            throughput_targets: Vec::new(),
            whitelist_sni: Vec::new(),
            telegram_target: None,
            strategy_probe: Some(StrategyProbeRequest {
                suite_id: "quick_v1".to_string(),
                base_proxy_config_json: None,
                target_selection: None,
                max_candidates: None,
            }),
            network_snapshot: None,
            route_probe: None,
            scan_deadline_ms: None,
            diagnostic_tls_keylog_path: None,
        },
        started_at: 0,
        total_steps: 8,
        transport: direct_transport(),
        probe_context: crate::connectivity::ProbeExecutionContext::new(direct_transport()),
        stage_order: Vec::new(),
        strategy: Some(StrategyExecutionPlan {
            suite_id: "quick_v1".to_string(),
            runtime_context: None,
            suite: build_strategy_probe_suite("quick_v1", &base).expect("quick strategy suite"),
            probe_seed: 0,
            max_candidates: None,
        }),
    }
}

fn candidate_summary(
    id: &str,
    family: &str,
    weighted_success_score: usize,
) -> StrategyProbeCandidateSummary {
    StrategyProbeCandidateSummary {
        id: id.to_string(),
        label: id.replace('_', " "),
        family: family.to_string(),
        emitter_tier: StrategyEmitterTier::NonRootProduction,
        exact_emitter_requires_root: false,
        emitter_downgraded: false,
        quic_layout_family: None,
        outcome: "success".to_string(),
        rationale: "candidate result".to_string(),
        succeeded_targets: 1,
        total_targets: 1,
        weighted_success_score,
        total_weight: 1,
        quality_score: weighted_success_score,
        proxy_config_json: None,
        notes: Vec::new(),
        average_latency_ms: Some(100),
        skipped: false,
        domain_outcomes: Vec::new(),
    }
}

#[test]
fn cancelled_strategy_probe_preserves_partial_strategy_report() {
    let shared = Arc::new(Mutex::new(SharedState::default()));
    let cancel = Arc::new(AtomicBool::new(true));
    let mut runtime = ExecutionRuntime::new(shared.clone(), cancel);
    let plan = strategy_test_plan();
    runtime.results.push(ProbeResult {
        probe_type: "strategy_https".to_string(),
        target: "baseline_current · example.com".to_string(),
        outcome: "tls_ok".to_string(),
        details: Vec::new(),
    });
    runtime.strategy.tcp_candidates.push(candidate_summary("baseline_current", "baseline", 80));
    runtime.strategy.quic_candidates.push(candidate_summary("quic_disabled", "quic_disabled", 70));

    publish_cancelled_run(&plan, &shared, runtime);

    let report = shared.lock().expect("shared").report.clone().expect("cancelled report");
    let strategy_probe = report.strategy_probe_report.expect("partial strategy report");
    assert_eq!(report.summary, "Scan completed with partial results");
    assert_eq!(strategy_probe.completion_kind, StrategyProbeCompletionKind::PartialResults);
    assert_eq!(strategy_probe.recommendation.tcp_candidate_id, "baseline_current");
    assert_eq!(strategy_probe.recommendation.quic_candidate_id, "quic_disabled");
}

#[test]
fn skipped_strategy_probe_candidate_publishes_live_progress_and_increments_step() {
    let shared = Arc::new(Mutex::new(SharedState::default()));
    let cancel = Arc::new(AtomicBool::new(false));
    let mut runtime = ExecutionRuntime::new(shared.clone(), cancel);
    let plan = test_plan();

    runtime.record_skipped_strategy_probe_candidate(
        &plan,
        "tcp",
        StrategyProbeProgressLane::Tcp,
        3,
        14,
        "tcp_fake_tls",
        "TCP fake TLS",
        Some("skipped".to_string()),
        "Skipped TCP fake TLS".to_string(),
    );

    let progress = shared.lock().expect("shared").progress.clone().expect("progress");
    let live_progress = progress.strategy_probe_progress.expect("strategy probe progress");

    assert_eq!(progress.completed_steps, 1);
    assert_eq!(progress.phase, "tcp");
    assert_eq!(progress.message, "Skipped TCP fake TLS");
    assert_eq!(progress.latest_probe_target.as_deref(), Some("TCP fake TLS"));
    assert_eq!(progress.latest_probe_outcome.as_deref(), Some("skipped"));
    assert_eq!(live_progress.lane, StrategyProbeProgressLane::Tcp);
    assert_eq!(live_progress.candidate_index, 3);
    assert_eq!(live_progress.candidate_total, 14);
    assert_eq!(live_progress.candidate_id, "tcp_fake_tls");
    assert_eq!(live_progress.candidate_label, "TCP fake TLS");
}
