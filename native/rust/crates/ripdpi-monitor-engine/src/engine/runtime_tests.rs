use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex};
use std::time::Duration;

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
            in_path_route: None,
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
            in_path_route: None,
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
        observation_role: crate::types::StrategyProbeObservationRole::EphemeralCandidateRawPath,
        active_snapshot_faithful: true,
        desync_execution_required: true,
        runtime_terminal_status: crate::types::StrategyProbeRuntimeTerminalStatus::Unavailable,
        execution_evidence_complete: false,
        execution_attempts: Vec::new(),
        route_features: Vec::new(),
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

/// A probe runner which consumes all currently assigned I/O time when its
/// configured work cannot fit. This mirrors a slow synchronous network probe:
/// it must respect the runtime's active deadline rather than inventing its own
/// timeout.
struct DeadlineAwareStageRunner {
    stage: ExecutionStageId,
    work: Duration,
}

struct StageBudgetCancellingRunner {
    stage: ExecutionStageId,
    work: Duration,
}

impl ExecutionStageRunner for StageBudgetCancellingRunner {
    fn id(&self) -> ExecutionStageId {
        self.stage.clone()
    }

    fn phase(&self) -> &'static str {
        "stage_budget_fake"
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
        let permitted = ripdpi_diagnostics_contracts::util::bounded_scan_io_timeout(self.work)
            .expect("stage begins with an assigned slice");
        std::thread::sleep(permitted);
        CollectedStageOutcome::Cancelled(Vec::new())
    }
}

impl ExecutionStageRunner for DeadlineAwareStageRunner {
    fn id(&self) -> ExecutionStageId {
        self.stage.clone()
    }

    fn phase(&self) -> &'static str {
        "deadline_aware_fake"
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
        let permitted = ripdpi_diagnostics_contracts::util::bounded_scan_io_timeout(self.work)
            .expect("coordinator must not start a stage after its assigned budget expires");
        std::thread::sleep(permitted);
        let probe = ProbeResult {
            probe_type: self.stage.as_str().to_string(),
            target: format!("{} target", self.stage.as_str()),
            outcome: if permitted < self.work { "skipped_by_stage_budget" } else { "ok" }.to_string(),
            details: Vec::new(),
        };
        CollectedStageOutcome::Completed(vec![CollectedStep {
            phase: "deadline_aware_fake",
            message: format!("{} completed", self.stage.as_str()),
            latest_probe_target: Some(probe.target.clone()),
            latest_probe_outcome: Some(probe.outcome.clone()),
            artifacts: RunnerArtifacts::from_results(
                vec![probe],
                "deadline_aware_fake",
                "info",
                format!("{} completed", self.stage.as_str()),
            ),
        }])
    }
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
fn slow_early_stages_leave_budget_for_all_configured_late_probe_families() {
    let shared = Arc::new(Mutex::new(SharedState::default()));
    let cancel = Arc::new(AtomicBool::new(false));
    let mut runtime = ExecutionRuntime::new(shared, cancel);
    runtime.set_scan_deadline(std::time::Instant::now() + Duration::from_millis(250));
    let mut plan = connectivity_parallel_plan();
    plan.stage_order = vec![
        ExecutionStageId::Dns,
        ExecutionStageId::Web,
        ExecutionStageId::Service,
        ExecutionStageId::Telegram,
        ExecutionStageId::Throughput,
    ];

    let coordinator = ExecutionCoordinator::new(vec![
        Box::new(DeadlineAwareStageRunner { stage: ExecutionStageId::Dns, work: Duration::from_millis(140) }),
        Box::new(DeadlineAwareStageRunner { stage: ExecutionStageId::Web, work: Duration::from_millis(140) }),
        Box::new(DeadlineAwareStageRunner { stage: ExecutionStageId::Service, work: Duration::from_millis(1) }),
        Box::new(DeadlineAwareStageRunner { stage: ExecutionStageId::Telegram, work: Duration::from_millis(1) }),
        Box::new(DeadlineAwareStageRunner { stage: ExecutionStageId::Throughput, work: Duration::from_millis(1) }),
    ]);

    assert!(matches!(coordinator.run(&plan, &mut runtime, None), RunnerOutcome::Completed));
    let executed: Vec<&str> = runtime.results.iter().map(|result| result.probe_type.as_str()).collect();
    assert!(executed.contains(&"service"), "service must retain an executable budget: {executed:?}");
    assert!(executed.contains(&"telegram"), "telegram must retain an executable budget: {executed:?}");
    assert!(executed.contains(&"throughput"), "throughput must retain an executable budget: {executed:?}");
}

#[test]
fn stage_budget_exhaustion_skips_only_its_stage_and_advances_progress() {
    let shared = Arc::new(Mutex::new(SharedState::default()));
    let cancel = Arc::new(AtomicBool::new(false));
    let mut runtime = ExecutionRuntime::new(shared, cancel);
    runtime.set_scan_deadline(std::time::Instant::now() + Duration::from_millis(250));
    let mut plan = connectivity_parallel_plan();
    plan.total_steps = 2;
    plan.stage_order = vec![ExecutionStageId::Dns, ExecutionStageId::Service];

    let coordinator = ExecutionCoordinator::new(vec![
        Box::new(StageBudgetCancellingRunner { stage: ExecutionStageId::Dns, work: Duration::from_millis(400) }),
        Box::new(DeadlineAwareStageRunner { stage: ExecutionStageId::Service, work: Duration::from_millis(1) }),
    ]);

    assert!(matches!(coordinator.run(&plan, &mut runtime, None), RunnerOutcome::Completed));
    assert_eq!(runtime.completed_steps, plan.total_steps, "skipped stage work must consume planned progress");
    assert!(runtime.results.iter().any(|result| result.probe_type == "service"));
    assert!(runtime.results.iter().any(|result| result.outcome == "skipped_by_stage_budget"));
    assert_eq!(runtime.stage_executions()[0].executed_steps, 0);
    assert_eq!(runtime.stage_executions()[0].skipped_by_stage_budget_steps, 1);
    assert_eq!(runtime.stage_executions()[1].executed_steps, 1);
}

#[test]
fn global_deadline_records_every_unstarted_stage_as_skipped() {
    let shared = Arc::new(Mutex::new(SharedState::default()));
    let cancel = Arc::new(AtomicBool::new(false));
    let mut runtime = ExecutionRuntime::new(shared, cancel);
    runtime.set_scan_deadline(std::time::Instant::now() - Duration::from_millis(1));
    let mut plan = connectivity_parallel_plan();
    plan.stage_order = vec![ExecutionStageId::Dns, ExecutionStageId::Service];
    let coordinator = ExecutionCoordinator::new(vec![
        Box::new(DeadlineAwareStageRunner { stage: ExecutionStageId::Dns, work: Duration::from_millis(1) }),
        Box::new(DeadlineAwareStageRunner { stage: ExecutionStageId::Service, work: Duration::from_millis(1) }),
    ]);

    let outcome = coordinator.run(&plan, &mut runtime, None);
    let snapshots = runtime.stage_executions();

    assert!(matches!(outcome, RunnerOutcome::Cancelled));
    assert_eq!(
        snapshots
            .iter()
            .map(|stage| (stage.stage_id.as_str(), stage.skipped_by_global_deadline_steps))
            .collect::<Vec<_>>(),
        vec![("dns", 1), ("service", 1)]
    );
}

#[test]
fn small_executable_budget_is_fair_to_every_configured_late_stage() {
    let shared = Arc::new(Mutex::new(SharedState::default()));
    let cancel = Arc::new(AtomicBool::new(false));
    let mut runtime = ExecutionRuntime::new(shared, cancel);
    runtime.set_scan_deadline(std::time::Instant::now() + Duration::from_millis(360));
    let mut plan = connectivity_parallel_plan();
    plan.stage_order = vec![
        ExecutionStageId::Dns, ExecutionStageId::Web, ExecutionStageId::Service,
        ExecutionStageId::Circumvention, ExecutionStageId::Telegram, ExecutionStageId::Throughput,
    ];
    let coordinator = ExecutionCoordinator::new(vec![
        Box::new(DeadlineAwareStageRunner { stage: ExecutionStageId::Dns, work: Duration::from_millis(120) }),
        Box::new(DeadlineAwareStageRunner { stage: ExecutionStageId::Web, work: Duration::from_millis(120) }),
        Box::new(DeadlineAwareStageRunner { stage: ExecutionStageId::Service, work: Duration::from_millis(1) }),
        Box::new(DeadlineAwareStageRunner { stage: ExecutionStageId::Circumvention, work: Duration::from_millis(1) }),
        Box::new(DeadlineAwareStageRunner { stage: ExecutionStageId::Telegram, work: Duration::from_millis(1) }),
        Box::new(DeadlineAwareStageRunner { stage: ExecutionStageId::Throughput, work: Duration::from_millis(1) }),
    ]);
    assert!(matches!(coordinator.run(&plan, &mut runtime, None), RunnerOutcome::Completed));
    for stage in ["service", "circumvention", "telegram", "throughput"] {
        assert!(runtime.results.iter().any(|result| result.probe_type == stage), "missing {stage}");
    }
}

#[test]
fn parallel_connectivity_group_reserves_budget_and_provenance_for_late_stage() {
    let shared = Arc::new(Mutex::new(SharedState::default()));
    let cancel = Arc::new(AtomicBool::new(false));
    let mut runtime = ExecutionRuntime::new(shared, cancel);
    runtime.set_scan_deadline(std::time::Instant::now() + Duration::from_millis(300));
    let mut plan = connectivity_parallel_plan();
    plan.total_steps = 4;
    plan.stage_order = vec![
        ExecutionStageId::Dns,
        ExecutionStageId::Tcp,
        ExecutionStageId::Quic,
        ExecutionStageId::Service,
    ];
    let coordinator = ExecutionCoordinator::new(vec![
        Box::new(StageBudgetCancellingRunner { stage: ExecutionStageId::Dns, work: Duration::from_millis(400) }),
        Box::new(StageBudgetCancellingRunner { stage: ExecutionStageId::Tcp, work: Duration::from_millis(400) }),
        Box::new(StageBudgetCancellingRunner { stage: ExecutionStageId::Quic, work: Duration::from_millis(400) }),
        Box::new(DeadlineAwareStageRunner { stage: ExecutionStageId::Service, work: Duration::from_millis(1) }),
    ]);

    let outcome = coordinator.run(&plan, &mut runtime, None);
    let snapshots = runtime.stage_executions();

    assert!(
        matches!(outcome, RunnerOutcome::Completed)
            && runtime.results.iter().any(|result| result.probe_type == "service")
            && snapshots.iter().map(|snapshot| snapshot.stage_id.as_str()).collect::<Vec<_>>()
                == vec!["dns", "tcp", "quic", "service"]
            && snapshots.iter().take(3).all(|snapshot| snapshot.skipped_by_stage_budget_steps == 1),
        "parallel group must leave an accounted slice for service: snapshots={snapshots:?}",
    );
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

    let cleanup_receipt = crate::types::CandidateRuntimeCleanupReceipt {
        started: 2,
        stopped: 2,
        joined: 2,
        forced_abort: 1,
        ..Default::default()
    };
    publish_cancelled_run(&plan, &shared, runtime, None, Some(cleanup_receipt.clone()));

    let report = shared.lock().expect("shared").report.clone().expect("cancelled report");
    assert_eq!(report.completion_kind, crate::types::ScanCompletionKind::PartialResults);
    assert_eq!(report.termination_reason, Some(crate::types::ScanTerminationReason::UserCancelled));
    assert_eq!(report.candidate_runtime_cleanup, Some(cleanup_receipt));
    let strategy_probe = report.strategy_probe_report.expect("partial strategy report");
    assert_eq!(report.summary, "Scan completed with partial results");
    assert_eq!(strategy_probe.completion_kind, StrategyProbeCompletionKind::PartialResults);
    assert!(strategy_probe.recommendation.is_none(), "partial unverified evidence is not promotable");
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
