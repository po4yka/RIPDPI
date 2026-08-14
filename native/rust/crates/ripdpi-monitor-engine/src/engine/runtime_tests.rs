use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex};

use super::coordinator::ExecutionCoordinator;
use super::recording::{CollectedStageOutcome, CollectedStep};
use super::stage::ExecutionStageRunner;
use super::{
    publish_cancelled_run, ExecutionPlan, ExecutionRuntime, ExecutionStageId, RunnerArtifacts, RunnerOutcome,
    StrategyExecutionPlan,
};
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
            confirm_good_dpi_evidence: None,
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
            confirm_good_dpi_evidence: None,
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

/// A fake stage runner used to drive `ExecutionCoordinator::run` in tests.
///
/// `run_collecting` either records a single healthy step or panics, depending
/// on `panics`, so we can exercise the panic-recovery path in the parallel
/// connectivity group without booting any real network probe.
struct FakeStageRunner {
    stage: ExecutionStageId,
    panics: bool,
}

impl ExecutionStageRunner for FakeStageRunner {
    fn id(&self) -> ExecutionStageId {
        self.stage.clone()
    }

    fn phase(&self) -> &'static str {
        "fake"
    }

    fn total_steps(&self, _plan: &ExecutionPlan) -> usize {
        1
    }

    fn run_collecting(
        &self,
        _plan: &ExecutionPlan,
        _cancel: &AtomicBool,
        _tls_verifier: Option<&Arc<dyn rustls::client::danger::ServerCertVerifier>>,
    ) -> CollectedStageOutcome {
        assert!(!self.panics, "fake {:?} runner deliberate panic", self.stage);
        let probe = ProbeResult {
            probe_type: format!("{:?}_fake", self.stage),
            target: format!("{:?} target", self.stage),
            outcome: "ok".to_string(),
            details: Vec::new(),
        };
        let artifacts =
            RunnerArtifacts::from_results(vec![probe], "fake", "info", format!("{:?} ok", self.stage));
        CollectedStageOutcome::Completed(vec![CollectedStep {
            phase: "fake",
            message: format!("{:?} ok", self.stage),
            latest_probe_target: Some(format!("{:?} target", self.stage)),
            latest_probe_outcome: Some("ok".to_string()),
            artifacts,
        }])
    }
}

fn connectivity_parallel_plan() -> ExecutionPlan {
    let mut plan = test_plan();
    plan.request.kind = ScanKind::Connectivity;
    plan.stage_order = vec![ExecutionStageId::Dns, ExecutionStageId::Tcp, ExecutionStageId::Quic];
    plan
}

#[test]
fn parallel_runner_panic_terminates_scan_after_recording_sibling_results() {
    // One of the three parallel connectivity runners panics. Surviving results
    // are retained for support, but normal completion would falsely imply that
    // all requested probes ran successfully.
    let shared = Arc::new(Mutex::new(SharedState::default()));
    let cancel = Arc::new(AtomicBool::new(false));
    let mut runtime = ExecutionRuntime::new(shared, cancel);
    let plan = connectivity_parallel_plan();

    let coordinator = ExecutionCoordinator::new(vec![
        Box::new(FakeStageRunner { stage: ExecutionStageId::Dns, panics: false }),
        Box::new(FakeStageRunner { stage: ExecutionStageId::Tcp, panics: true }),
        Box::new(FakeStageRunner { stage: ExecutionStageId::Quic, panics: false }),
    ]);

    // Silence the deliberate panic backtrace noise from the runner thread.
    let prev_hook = std::panic::take_hook();
    std::panic::set_hook(Box::new(|_| {}));
    let outcome = coordinator.run(&plan, &mut runtime, None);
    std::panic::set_hook(prev_hook);

    assert!(matches!(outcome, RunnerOutcome::Failed(message) if message.contains("runner panicked")));

    // Both healthy runners' results are present.
    let outcomes: Vec<&str> = runtime.results.iter().map(|r| r.outcome.as_str()).collect();
    assert_eq!(outcomes.iter().filter(|o| **o == "ok").count(), 2, "both healthy runners recorded");

    // The panicked runner is surfaced as a failed step keyed to its stage.
    let panicked: Vec<&ProbeResult> =
        runtime.results.iter().filter(|r| r.outcome == "runner_panicked").collect();
    assert_eq!(panicked.len(), 1, "exactly one runner marked panicked");
    assert!(panicked[0].probe_type.starts_with("Tcp"), "panicked stage is Tcp: {:?}", panicked[0].probe_type);
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
    assert_eq!(report.completion_kind, crate::types::ScanCompletionKind::PartialResults);
    assert_eq!(report.termination_reason, Some(crate::types::ScanTerminationReason::UserCancelled));
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
