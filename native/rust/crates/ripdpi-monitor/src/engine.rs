use std::collections::BTreeMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use ripdpi_failure_classifier::{ClassifiedFailure, FailureClass};
use ripdpi_proxy_config::{parse_proxy_config_json, ProxyConfigPayload, ProxyRuntimeContext, ProxyUiConfig};
use rustls::client::danger::ServerCertVerifier;

use crate::candidates::{
    build_quic_candidates_for_suite, build_strategy_probe_suite, build_strategy_probe_summary, candidate_pause_ms,
    StrategyProbeSuite,
};
use crate::classification::{
    classified_failure_probe_result, classify_strategy_probe_baseline_observations, filter_quic_candidates_for_failure,
    interleave_candidate_families, next_candidate_index, pack_versions_from_refs, reorder_tcp_candidates_for_failure,
};
use crate::connectivity::{
    build_network_environment_probe, push_event, run_circumvention_probe, run_dns_probe, run_domain_probe,
    run_quic_probe, run_service_probe, run_tcp_probe, run_throughput_probe, set_progress, set_report,
    summarize_probe_event,
};
use crate::execution::{
    execute_quic_candidate, execute_tcp_candidate, skipped_candidate_summary, winning_candidate_index,
};
use crate::observations::{observation_for_probe, observations_for_results, ENGINE_ANALYSIS_VERSION};
use crate::strategy::detect_strategy_probe_dns_tampering;
use crate::telegram::run_telegram_probe;
use crate::transport::{describe_transport, transport_for_request, TransportConfig};
use crate::types::{
    NativeSessionEvent, ProbeObservation, ProbeResult, ProbeTaskFamily, ScanKind, ScanProgress, ScanReport,
    ScanRequest, SharedState, StrategyProbeCandidateSummary, StrategyProbeRecommendation, StrategyProbeReport,
};
use crate::util::{classify_probe_outcome, event_level_for_outcome, now_ms, probe_session_seed, stable_probe_hash};

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum ExecutionStageId {
    Environment,
    Dns,
    Web,
    Quic,
    Tcp,
    Service,
    Circumvention,
    Telegram,
    Throughput,
    StrategyDnsBaseline,
    StrategyTcpCandidates,
    StrategyQuicCandidates,
    StrategyRecommendation,
}

pub(crate) enum RunnerOutcome {
    Completed,
    Cancelled,
    Finished,
}

pub(crate) struct RunnerArtifacts {
    pub(crate) probe_results: Vec<ProbeResult>,
    pub(crate) observations: Vec<ProbeObservation>,
    pub(crate) events: Vec<NativeSessionEvent>,
}

impl RunnerArtifacts {
    fn from_probe(probe: ProbeResult, source: &str, path_mode: &crate::types::ScanPathMode) -> Self {
        let probe_type = probe.probe_type.clone();
        let outcome = probe.outcome.clone();
        let message = summarize_probe_event(&probe);
        Self {
            observations: observation_for_probe(&probe).into_iter().collect(),
            probe_results: vec![probe],
            events: vec![NativeSessionEvent {
                source: source.to_string(),
                level: event_level_for_outcome(&probe_type, path_mode, &outcome).to_string(),
                message,
                created_at: now_ms(),
            }],
        }
    }

    fn from_results(results: Vec<ProbeResult>, source: &str, level: &str, message: String) -> Self {
        Self {
            observations: observations_for_results(&results),
            probe_results: results,
            events: vec![NativeSessionEvent {
                source: source.to_string(),
                level: level.to_string(),
                message,
                created_at: now_ms(),
            }],
        }
    }
}

pub(crate) trait ExecutionStageRunner {
    fn id(&self) -> ExecutionStageId;

    fn phase(&self) -> &'static str;

    fn total_steps(&self, plan: &ExecutionPlan) -> usize;

    fn run(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome;
}

pub(crate) struct ExecutionPlan {
    pub(crate) session_id: String,
    pub(crate) request: ScanRequest,
    pub(crate) started_at: u64,
    pub(crate) total_steps: usize,
    pub(crate) transport: TransportConfig,
    pub(crate) stage_order: Vec<ExecutionStageId>,
    pub(crate) strategy: Option<StrategyExecutionPlan>,
}

pub(crate) struct StrategyExecutionPlan {
    pub(crate) suite_id: String,
    pub(crate) base_payload: ProxyUiConfig,
    pub(crate) runtime_context: Option<ProxyRuntimeContext>,
    pub(crate) suite: StrategyProbeSuite,
    pub(crate) probe_seed: u64,
}

#[derive(Default)]
struct StrategyExecutionState {
    baseline_failure: Option<ClassifiedFailure>,
    tcp_candidates: Vec<StrategyProbeCandidateSummary>,
    quic_candidates: Vec<StrategyProbeCandidateSummary>,
    summary: Option<String>,
    strategy_probe_report: Option<StrategyProbeReport>,
}

pub(crate) struct ExecutionRuntime {
    pub(crate) shared: Arc<Mutex<SharedState>>,
    cancel: Arc<AtomicBool>,
    pub(crate) completed_steps: usize,
    pub(crate) results: Vec<ProbeResult>,
    pub(crate) observations: Vec<ProbeObservation>,
    final_report: Option<ScanReport>,
    strategy: StrategyExecutionState,
}

impl ExecutionRuntime {
    fn new(shared: Arc<Mutex<SharedState>>, cancel: Arc<AtomicBool>) -> Self {
        Self {
            shared,
            cancel,
            completed_steps: 0,
            results: Vec::new(),
            observations: Vec::new(),
            final_report: None,
            strategy: StrategyExecutionState::default(),
        }
    }

    fn is_cancelled(&self) -> bool {
        self.cancel.load(Ordering::Acquire)
    }

    fn record_step(
        &mut self,
        plan: &ExecutionPlan,
        phase: &str,
        message: String,
        latest_probe_target: Option<String>,
        latest_probe_outcome: Option<String>,
        artifacts: RunnerArtifacts,
    ) {
        self.results.extend(artifacts.probe_results);
        self.observations.extend(artifacts.observations);
        for event in artifacts.events {
            push_event(
                &self.shared,
                &plan.session_id,
                &plan.request.profile_id,
                &plan.request.path_mode,
                &event.source,
                &event.level,
                event.message,
            );
        }
        self.completed_steps += 1;
        set_progress(
            &self.shared,
            ScanProgress {
                session_id: plan.session_id.clone(),
                phase: phase.to_string(),
                completed_steps: self.completed_steps,
                total_steps: plan.total_steps,
                message,
                is_finished: false,
                latest_probe_target,
                latest_probe_outcome,
            },
        );
    }

    fn finish_with_report(&mut self, report: ScanReport) {
        self.final_report = Some(report);
    }
}

pub(crate) struct ExecutionCoordinator {
    runners: BTreeMap<ExecutionStageId, Box<dyn ExecutionStageRunner + Send + Sync>>,
}

impl ExecutionCoordinator {
    fn new(runners: Vec<Box<dyn ExecutionStageRunner + Send + Sync>>) -> Self {
        let runners = runners.into_iter().map(|runner| (runner.id(), runner)).collect();
        Self { runners }
    }

    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        plan.stage_order
            .iter()
            .filter_map(|stage| self.runners.get(stage))
            .map(|runner| runner.total_steps(plan))
            .sum::<usize>()
            .max(1)
    }

    fn run(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome {
        for stage in &plan.stage_order {
            let Some(runner) = self.runners.get(stage) else {
                continue;
            };
            if runtime.is_cancelled() {
                return RunnerOutcome::Cancelled;
            }
            if runner.total_steps(plan) == 0 {
                continue;
            }
            match runner.run(plan, runtime, tls_verifier) {
                RunnerOutcome::Completed => {}
                RunnerOutcome::Cancelled => return RunnerOutcome::Cancelled,
                RunnerOutcome::Finished => return RunnerOutcome::Finished,
            }
        }
        RunnerOutcome::Completed
    }
}

pub(crate) fn run_engine_scan(
    shared: Arc<Mutex<SharedState>>,
    cancel: Arc<AtomicBool>,
    session_id: String,
    request: ScanRequest,
    tls_verifier: Option<Arc<dyn ServerCertVerifier>>,
) {
    let started_at = now_ms();
    let transport = transport_for_request(&request);
    let mut plan = match build_execution_plan(session_id.clone(), request.clone(), started_at, transport.clone()) {
        Ok(plan) => plan,
        Err(message) => {
            let report =
                build_report(session_id.clone(), request, started_at, message, Vec::new(), Vec::new(), None, None);
            set_report(&shared, report);
            set_progress(
                &shared,
                ScanProgress {
                    session_id,
                    phase: "finished".to_string(),
                    completed_steps: 1,
                    total_steps: 1,
                    message: "Diagnostics finished".to_string(),
                    is_finished: true,
                    latest_probe_target: None,
                    latest_probe_outcome: None,
                },
            );
            return;
        }
    };
    let coordinator = execution_coordinator();
    plan.total_steps = coordinator.total_steps(&plan);

    set_progress(
        &shared,
        ScanProgress {
            session_id: plan.session_id.clone(),
            phase: "starting".to_string(),
            completed_steps: 0,
            total_steps: plan.total_steps,
            message: format!("Preparing {}", plan.request.display_name),
            is_finished: false,
            latest_probe_target: None,
            latest_probe_outcome: None,
        },
    );
    push_event(
        &shared,
        &plan.session_id,
        &plan.request.profile_id,
        &plan.request.path_mode,
        "engine",
        "info",
        format!(
            "Starting {} in {:?} transport={}",
            plan.request.display_name,
            plan.request.path_mode,
            describe_transport(&plan.transport),
        ),
    );

    let mut runtime = ExecutionRuntime::new(shared.clone(), cancel);
    match coordinator.run(&plan, &mut runtime, tls_verifier.as_ref()) {
        RunnerOutcome::Cancelled => {
            let report = build_report(
                plan.session_id.clone(),
                plan.request.clone(),
                plan.started_at,
                "Scan cancelled".to_string(),
                runtime.results,
                runtime.observations,
                None,
                None,
            );
            set_report(&shared, report);
            push_event(
                &shared,
                &plan.session_id,
                &plan.request.profile_id,
                &plan.request.path_mode,
                "engine",
                "warn",
                "Diagnostics cancelled".to_string(),
            );
            set_progress(
                &shared,
                ScanProgress {
                    session_id: plan.session_id,
                    phase: "cancelled".to_string(),
                    completed_steps: runtime.completed_steps,
                    total_steps: plan.total_steps,
                    message: "Diagnostics cancelled".to_string(),
                    is_finished: true,
                    latest_probe_target: None,
                    latest_probe_outcome: None,
                },
            );
        }
        RunnerOutcome::Finished => {
            if let Some(report) = runtime.final_report {
                set_report(&shared, report);
            }
            set_progress(
                &shared,
                ScanProgress {
                    session_id: plan.session_id,
                    phase: "finished".to_string(),
                    completed_steps: plan.total_steps,
                    total_steps: plan.total_steps,
                    message: "Diagnostics finished".to_string(),
                    is_finished: true,
                    latest_probe_target: None,
                    latest_probe_outcome: None,
                },
            );
        }
        RunnerOutcome::Completed => {
            let summary = match plan.request.kind {
                ScanKind::Connectivity => {
                    connectivity_summary(&runtime.results, &plan.request.path_mode)
                }
                ScanKind::StrategyProbe => {
                    runtime.strategy.summary.clone().unwrap_or_else(|| "Automatic probing finished".to_string())
                }
            };
            let report = build_report(
                plan.session_id.clone(),
                plan.request.clone(),
                plan.started_at,
                summary,
                runtime.results,
                runtime.observations,
                runtime.strategy.strategy_probe_report,
                None,
            );
            set_report(&shared, report);
            push_event(
                &shared,
                &plan.session_id,
                &plan.request.profile_id,
                &plan.request.path_mode,
                "engine",
                "info",
                "Diagnostics finished".to_string(),
            );
            set_progress(
                &shared,
                ScanProgress {
                    session_id: plan.session_id,
                    phase: "finished".to_string(),
                    completed_steps: plan.total_steps,
                    total_steps: plan.total_steps,
                    message: "Diagnostics finished".to_string(),
                    is_finished: true,
                    latest_probe_target: None,
                    latest_probe_outcome: None,
                },
            );
        }
    }
}

fn build_execution_plan(
    session_id: String,
    request: ScanRequest,
    started_at: u64,
    transport: TransportConfig,
) -> Result<ExecutionPlan, String> {
    let strategy = if matches!(request.kind, ScanKind::StrategyProbe) {
        Some(build_strategy_execution_plan(&session_id, &request)?)
    } else {
        None
    };
    let stage_order = match request.kind {
        ScanKind::Connectivity => connectivity_stage_order(&request),
        ScanKind::StrategyProbe => vec![
            ExecutionStageId::Environment,
            ExecutionStageId::StrategyDnsBaseline,
            ExecutionStageId::StrategyTcpCandidates,
            ExecutionStageId::StrategyQuicCandidates,
            ExecutionStageId::StrategyRecommendation,
        ],
    };
    Ok(ExecutionPlan { session_id, request, started_at, total_steps: 0, transport, stage_order, strategy })
}

fn build_strategy_execution_plan(session_id: &str, request: &ScanRequest) -> Result<StrategyExecutionPlan, String> {
    let strategy_probe = request.strategy_probe.clone().ok_or_else(|| "missing strategyProbe settings".to_string())?;
    let base_proxy_config_json = strategy_probe
        .base_proxy_config_json
        .as_deref()
        .filter(|value| !value.trim().is_empty())
        .ok_or_else(|| "strategy_probe scan requires baseProxyConfigJson".to_string())?;
    let (base_payload, runtime_context) =
        match parse_proxy_config_json(base_proxy_config_json).map_err(|err| err.to_string())? {
            ProxyConfigPayload::Ui { config, runtime_context, .. } => (config, runtime_context),
            ProxyConfigPayload::CommandLine { .. } => {
                return Err("strategy_probe scans only support UI proxy config".to_string())
            }
        };
    let suite = build_strategy_probe_suite(&strategy_probe.suite_id, &base_payload)?;
    Ok(StrategyExecutionPlan {
        suite_id: strategy_probe.suite_id,
        probe_seed: probe_session_seed(base_payload.host_autolearn.network_scope_key.as_deref(), session_id),
        base_payload,
        runtime_context,
        suite,
    })
}

fn connectivity_stage_order(request: &ScanRequest) -> Vec<ExecutionStageId> {
    let mut ordered = vec![ExecutionStageId::Environment];
    if !request.probe_tasks.is_empty() {
        for task in &request.probe_tasks {
            let stage = match task.family {
                ProbeTaskFamily::Dns => ExecutionStageId::Dns,
                ProbeTaskFamily::Web => ExecutionStageId::Web,
                ProbeTaskFamily::Quic => ExecutionStageId::Quic,
                ProbeTaskFamily::Tcp => ExecutionStageId::Tcp,
                ProbeTaskFamily::Service => ExecutionStageId::Service,
                ProbeTaskFamily::Circumvention => ExecutionStageId::Circumvention,
                ProbeTaskFamily::Telegram => ExecutionStageId::Telegram,
                ProbeTaskFamily::Throughput => ExecutionStageId::Throughput,
            };
            if !ordered.contains(&stage) {
                ordered.push(stage);
            }
        }
        return ordered;
    }
    ordered.extend([
        ExecutionStageId::Dns,
        ExecutionStageId::Web,
        ExecutionStageId::Quic,
        ExecutionStageId::Tcp,
        ExecutionStageId::Service,
        ExecutionStageId::Circumvention,
        ExecutionStageId::Telegram,
        ExecutionStageId::Throughput,
    ]);
    ordered
}

fn build_report(
    session_id: String,
    request: ScanRequest,
    started_at: u64,
    summary: String,
    results: Vec<ProbeResult>,
    observations: Vec<ProbeObservation>,
    strategy_probe_report: Option<StrategyProbeReport>,
    classifier_version: Option<String>,
) -> ScanReport {
    ScanReport {
        session_id,
        profile_id: request.profile_id,
        path_mode: request.path_mode,
        started_at,
        finished_at: now_ms(),
        summary,
        results,
        observations,
        engine_analysis_version: Some(ENGINE_ANALYSIS_VERSION.to_string()),
        diagnoses: Vec::new(),
        classifier_version,
        pack_versions: pack_versions_from_refs(&request.pack_refs),
        strategy_probe_report,
    }
}

struct EnvironmentRunner;
struct DnsRunner;
struct WebRunner;
struct QuicRunner;
struct TcpRunner;
struct ServiceRunner;
struct CircumventionRunner;
struct TelegramRunner;
struct ThroughputRunner;
struct StrategyDnsBaselineRunner;
struct StrategyTcpRunner;
struct StrategyQuicRunner;
struct StrategyRecommendationRunner;

impl ExecutionStageRunner for EnvironmentRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::Environment
    }

    fn phase(&self) -> &'static str {
        "environment"
    }

    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        usize::from(plan.request.network_snapshot.is_some())
    }

    fn run(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome {
        let Some(snapshot) = plan.request.network_snapshot.as_ref() else {
            return RunnerOutcome::Completed;
        };
        let probe = build_network_environment_probe(Some(snapshot)).expect("snapshot probe");
        let artifacts = RunnerArtifacts::from_probe(probe.clone(), "network_environment", &plan.request.path_mode);
        runtime.record_step(
            plan,
            self.phase(),
            "Collected network environment".to_string(),
            Some(probe.target.clone()),
            Some(probe.outcome.clone()),
            artifacts,
        );
        if snapshot.transport == "none" {
            push_event(
                &runtime.shared,
                &plan.session_id,
                &plan.request.profile_id,
                &plan.request.path_mode,
                "engine",
                "warn",
                "OS reports no network; aborting scan".to_string(),
            );
            let report = build_report(
                plan.session_id.clone(),
                plan.request.clone(),
                plan.started_at,
                connectivity_summary(&runtime.results, &plan.request.path_mode),
                runtime.results.clone(),
                runtime.observations.clone(),
                None,
                None,
            );
            runtime.finish_with_report(report);
            return RunnerOutcome::Finished;
        }
        if !snapshot.validated && !snapshot.captive_portal {
            push_event(
                &runtime.shared,
                &plan.session_id,
                &plan.request.profile_id,
                &plan.request.path_mode,
                "engine",
                "warn",
                "OS reports unvalidated network; probe results may be unreliable".to_string(),
            );
        }
        RunnerOutcome::Completed
    }
}

macro_rules! connectivity_runner {
    ($runner:ident, $id:expr, $phase:expr, $len:expr, $iter:expr, $body:expr, $label:expr, $source:expr) => {
        impl ExecutionStageRunner for $runner {
            fn id(&self) -> ExecutionStageId {
                $id
            }

            fn phase(&self) -> &'static str {
                $phase
            }

            fn total_steps(&self, plan: &ExecutionPlan) -> usize {
                $len(plan)
            }

            fn run(
                &self,
                plan: &ExecutionPlan,
                runtime: &mut ExecutionRuntime,
                tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
            ) -> RunnerOutcome {
                for item in $iter(plan) {
                    if runtime.is_cancelled() {
                        return RunnerOutcome::Cancelled;
                    }
                    let label = $label(item.clone());
                    let probe = $body(item, plan, tls_verifier);
                    let outcome = probe.outcome.clone();
                    let artifacts = RunnerArtifacts::from_probe(probe, $source, &plan.request.path_mode);
                    runtime.record_step(plan, self.phase(), label.clone(), Some(label), Some(outcome), artifacts);
                }
                RunnerOutcome::Completed
            }
        }
    };
}

connectivity_runner!(
    DnsRunner,
    ExecutionStageId::Dns,
    "dns",
    |plan: &ExecutionPlan| plan.request.dns_targets.len(),
    |plan: &ExecutionPlan| plan.request.dns_targets.clone(),
    |target: crate::types::DnsTarget, plan: &ExecutionPlan, _tls| {
        run_dns_probe(&target, &plan.transport, &plan.request.path_mode)
    },
    |target: crate::types::DnsTarget| format!("DNS {}", target.domain),
    "dns_integrity"
);

connectivity_runner!(
    WebRunner,
    ExecutionStageId::Web,
    "reachability",
    |plan: &ExecutionPlan| plan.request.domain_targets.len(),
    |plan: &ExecutionPlan| plan.request.domain_targets.clone(),
    |target: crate::types::DomainTarget, plan: &ExecutionPlan, tls| run_domain_probe(&target, &plan.transport, tls),
    |target: crate::types::DomainTarget| format!("Reachability {}", target.host),
    "domain_reachability"
);

connectivity_runner!(
    QuicRunner,
    ExecutionStageId::Quic,
    "quic",
    |plan: &ExecutionPlan| plan.request.quic_targets.len(),
    |plan: &ExecutionPlan| plan.request.quic_targets.clone(),
    |target: crate::types::QuicTarget, plan: &ExecutionPlan, _tls| run_quic_probe(&target, &plan.transport),
    |target: crate::types::QuicTarget| format!("QUIC {}", target.host),
    "quic_reachability"
);

connectivity_runner!(
    TcpRunner,
    ExecutionStageId::Tcp,
    "tcp",
    |plan: &ExecutionPlan| plan.request.tcp_targets.len(),
    |plan: &ExecutionPlan| plan.request.tcp_targets.clone(),
    |target: crate::types::TcpTarget, plan: &ExecutionPlan, _tls| {
        run_tcp_probe(&target, &plan.request.whitelist_sni, &plan.transport)
    },
    |target: crate::types::TcpTarget| format!("TCP {}", target.provider),
    "tcp_fat_header"
);

connectivity_runner!(
    ServiceRunner,
    ExecutionStageId::Service,
    "service",
    |plan: &ExecutionPlan| plan.request.service_targets.len(),
    |plan: &ExecutionPlan| plan.request.service_targets.clone(),
    |target: crate::types::ServiceTarget, plan: &ExecutionPlan, tls| run_service_probe(&target, &plan.transport, tls),
    |target: crate::types::ServiceTarget| format!("Service {}", target.service),
    "service_reachability"
);

connectivity_runner!(
    CircumventionRunner,
    ExecutionStageId::Circumvention,
    "circumvention",
    |plan: &ExecutionPlan| plan.request.circumvention_targets.len(),
    |plan: &ExecutionPlan| plan.request.circumvention_targets.clone(),
    |target: crate::types::CircumventionTarget, plan: &ExecutionPlan, tls| {
        run_circumvention_probe(&target, &plan.transport, tls)
    },
    |target: crate::types::CircumventionTarget| format!("Circumvention {}", target.tool),
    "circumvention_reachability"
);

connectivity_runner!(
    ThroughputRunner,
    ExecutionStageId::Throughput,
    "throughput",
    |plan: &ExecutionPlan| plan.request.throughput_targets.len(),
    |plan: &ExecutionPlan| plan.request.throughput_targets.clone(),
    |target: crate::types::ThroughputTarget, plan: &ExecutionPlan, _tls| run_throughput_probe(&target, &plan.transport),
    |target: crate::types::ThroughputTarget| format!("Throughput {}", target.label),
    "throughput_window"
);

impl ExecutionStageRunner for TelegramRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::Telegram
    }

    fn phase(&self) -> &'static str {
        "telegram"
    }

    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        usize::from(plan.request.telegram_target.is_some())
    }

    fn run(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome {
        let Some(target) = plan.request.telegram_target.as_ref() else {
            return RunnerOutcome::Completed;
        };
        if runtime.is_cancelled() {
            return RunnerOutcome::Cancelled;
        }
        let probe = run_telegram_probe(target, &plan.transport);
        let outcome = probe.outcome.clone();
        runtime.record_step(
            plan,
            self.phase(),
            "Telegram availability checked".to_string(),
            Some("telegram.org".to_string()),
            Some(outcome),
            RunnerArtifacts::from_probe(probe, "telegram", &plan.request.path_mode),
        );
        RunnerOutcome::Completed
    }
}

impl ExecutionStageRunner for StrategyDnsBaselineRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::StrategyDnsBaseline
    }

    fn phase(&self) -> &'static str {
        "dns_baseline"
    }

    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        usize::from(!plan.request.domain_targets.is_empty())
    }

    fn run(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome {
        let Some(strategy_plan) = plan.strategy.as_ref() else {
            return RunnerOutcome::Completed;
        };
        let Some(baseline) =
            detect_strategy_probe_dns_tampering(&plan.request.domain_targets, strategy_plan.runtime_context.as_ref())
        else {
            return RunnerOutcome::Completed;
        };
        let artifacts = RunnerArtifacts::from_results(
            baseline.results.clone(),
            "strategy_probe",
            "warn",
            format!(
                "Baseline classified as {} with {}",
                baseline.failure.class.as_str(),
                baseline.failure.action.as_str(),
            ),
        );
        runtime.record_step(
            plan,
            self.phase(),
            "Strategy baseline DNS classification".to_string(),
            Some("dns_baseline".to_string()),
            Some(baseline.failure.class.as_str().to_string()),
            artifacts,
        );
        runtime.results.push(classified_failure_probe_result("Current strategy", &baseline.failure));
        let tcp_candidates = strategy_plan
            .suite
            .tcp_candidates
            .iter()
            .map(|spec| {
                skipped_candidate_summary(
                    spec,
                    plan.request.domain_targets.len() * 2,
                    3,
                    "DNS tampering detected before fallback; TCP strategy escalation skipped",
                )
            })
            .collect::<Vec<_>>();
        let quic_specs = filter_quic_candidates_for_failure(
            strategy_plan.suite.quic_candidates.clone(),
            Some(FailureClass::QuicBreakage),
        );
        let quic_candidates = quic_specs
            .iter()
            .map(|spec| {
                skipped_candidate_summary(
                    spec,
                    plan.request.quic_targets.len(),
                    2,
                    "DNS tampering detected before fallback; QUIC strategy escalation skipped",
                )
            })
            .collect::<Vec<_>>();
        let fallback_quic =
            quic_specs.first().or_else(|| strategy_plan.suite.quic_candidates.first()).expect("quic candidate");
        let recommendation = StrategyProbeRecommendation {
            tcp_candidate_id: strategy_plan.suite.tcp_candidates.first().expect("tcp candidate").id.to_string(),
            tcp_candidate_label: strategy_plan.suite.tcp_candidates.first().expect("tcp candidate").label.to_string(),
            quic_candidate_id: fallback_quic.id.to_string(),
            quic_candidate_label: fallback_quic.label.to_string(),
            rationale: format!(
                "{} classified before fallback; keep current strategy and prefer resolver override",
                baseline.failure.class.as_str(),
            ),
            recommended_proxy_config_json: crate::candidates::strategy_probe_config_json(&strategy_plan.base_payload),
        };
        let strategy_probe_report = StrategyProbeReport {
            suite_id: strategy_plan.suite_id.clone(),
            tcp_candidates,
            quic_candidates,
            recommendation,
        };
        let report = build_report(
            plan.session_id.clone(),
            plan.request.clone(),
            plan.started_at,
            "DNS tampering classified before fallback; resolver override recommended".to_string(),
            runtime.results.clone(),
            runtime.observations.clone(),
            Some(strategy_probe_report),
            None,
        );
        runtime.finish_with_report(report);
        RunnerOutcome::Finished
    }
}

impl ExecutionStageRunner for StrategyTcpRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::StrategyTcpCandidates
    }

    fn phase(&self) -> &'static str {
        "tcp"
    }

    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        plan.strategy.as_ref().map_or(0, |strategy| strategy.suite.tcp_candidates.len())
    }

    fn run(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome {
        let Some(strategy_plan) = plan.strategy.as_ref() else {
            return RunnerOutcome::Completed;
        };
        let tcp_specs = &strategy_plan.suite.tcp_candidates;
        if tcp_specs.is_empty() {
            return RunnerOutcome::Completed;
        }
        let baseline_spec = tcp_specs.first().expect("tcp candidate");
        let baseline_execution = execute_tcp_candidate(
            baseline_spec,
            &plan.request.domain_targets,
            strategy_plan.runtime_context.as_ref(),
            strategy_plan.probe_seed,
            tls_verifier,
        );
        let baseline_observations = observations_for_results(&baseline_execution.results);
        runtime.strategy.baseline_failure = classify_strategy_probe_baseline_observations(&baseline_observations);
        let mut baseline_results = baseline_execution.results.clone();
        if let Some(failure) = &runtime.strategy.baseline_failure {
            baseline_results.push(classified_failure_probe_result(baseline_spec.label, failure));
        }
        runtime.record_step(
            plan,
            self.phase(),
            format!("Tested {}", baseline_spec.label),
            Some(baseline_spec.label.to_string()),
            Some(baseline_execution.summary.outcome.clone()),
            RunnerArtifacts::from_results(
                baseline_results,
                "strategy_probe",
                if runtime.strategy.baseline_failure.is_some() { "warn" } else { "info" },
                format!("Testing TCP candidate {}", baseline_spec.label),
            ),
        );
        let mut hostfake_family_succeeded = baseline_execution.summary.family == "hostfake"
            && baseline_execution.summary.succeeded_targets == baseline_execution.summary.total_targets;
        runtime.strategy.tcp_candidates.push(baseline_execution.summary);

        if tcp_specs.len() > 1 {
            thread::sleep(Duration::from_millis(candidate_pause_ms(
                strategy_plan.probe_seed,
                baseline_spec,
                runtime.strategy.baseline_failure.is_some(),
            )));
        }

        let ordered_tcp_specs = interleave_candidate_families(
            reorder_tcp_candidates_for_failure(
                tcp_specs,
                runtime.strategy.baseline_failure.as_ref().map(|value| value.class),
            )
            .into_iter()
            .skip(1)
            .collect(),
            strategy_plan.probe_seed,
        );
        let mut pending_tcp_specs = ordered_tcp_specs;
        let mut blocked_tcp_family = None::<&str>;
        let mut last_failed_tcp_family = None::<&str>;
        let mut consecutive_tcp_family_failures = 0usize;
        while !pending_tcp_specs.is_empty() {
            let spec = pending_tcp_specs.remove(next_candidate_index(&pending_tcp_specs, blocked_tcp_family));
            if runtime.is_cancelled() {
                return RunnerOutcome::Cancelled;
            }
            if strategy_plan.suite.short_circuit_hostfake && spec.family == "hostfake" && hostfake_family_succeeded {
                runtime.strategy.tcp_candidates.push(skipped_candidate_summary(
                    &spec,
                    plan.request.domain_targets.len() * 2,
                    6,
                    "Earlier hostfake candidate already achieved full success",
                ));
                runtime.completed_steps += 1;
                continue;
            }

            let execution = execute_tcp_candidate(
                &spec,
                &plan.request.domain_targets,
                strategy_plan.runtime_context.as_ref(),
                strategy_plan.probe_seed,
                tls_verifier,
            );
            if execution.summary.family == "hostfake"
                && execution.summary.succeeded_targets == execution.summary.total_targets
            {
                hostfake_family_succeeded = true;
            }
            let failed = execution.summary.outcome == "failed";
            runtime.record_step(
                plan,
                self.phase(),
                format!("Tested {}", spec.label),
                Some(spec.label.to_string()),
                Some(execution.summary.outcome.clone()),
                RunnerArtifacts::from_results(
                    execution.results.clone(),
                    "strategy_probe",
                    if failed { "warn" } else { "info" },
                    format!("Testing TCP candidate {}", spec.label),
                ),
            );
            runtime.strategy.tcp_candidates.push(execution.summary);
            if failed {
                if last_failed_tcp_family == Some(spec.family) {
                    consecutive_tcp_family_failures += 1;
                } else {
                    last_failed_tcp_family = Some(spec.family);
                    consecutive_tcp_family_failures = 1;
                }
                if consecutive_tcp_family_failures >= 2 {
                    blocked_tcp_family = Some(spec.family);
                    consecutive_tcp_family_failures = 0;
                }
            } else {
                last_failed_tcp_family = None;
                consecutive_tcp_family_failures = 0;
                blocked_tcp_family = None;
            }
            if blocked_tcp_family.is_some() && spec.family != blocked_tcp_family.unwrap_or_default() {
                blocked_tcp_family = None;
            }
            if !pending_tcp_specs.is_empty() {
                thread::sleep(Duration::from_millis(candidate_pause_ms(strategy_plan.probe_seed, &spec, failed)));
            }
        }
        RunnerOutcome::Completed
    }
}

impl ExecutionStageRunner for StrategyQuicRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::StrategyQuicCandidates
    }

    fn phase(&self) -> &'static str {
        "quic"
    }

    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        plan.strategy.as_ref().map_or(0, |strategy| strategy.suite.quic_candidates.len())
    }

    fn run(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome {
        let Some(strategy_plan) = plan.strategy.as_ref() else {
            return RunnerOutcome::Completed;
        };
        let winning_tcp = winning_candidate_index(&runtime.strategy.tcp_candidates).unwrap_or(0);
        let tcp_winner_spec = strategy_plan
            .suite
            .tcp_candidates
            .iter()
            .find(|spec| spec.id == runtime.strategy.tcp_candidates[winning_tcp].id)
            .unwrap_or_else(|| strategy_plan.suite.tcp_candidates.first().expect("tcp candidates"));
        let quic_specs = filter_quic_candidates_for_failure(
            build_quic_candidates_for_suite(&strategy_plan.suite_id, &tcp_winner_spec.config)
                .unwrap_or_else(|_| strategy_plan.suite.quic_candidates.clone()),
            runtime.strategy.baseline_failure.as_ref().map(|value| value.class),
        );
        let mut pending_quic_specs =
            interleave_candidate_families(quic_specs.clone(), stable_probe_hash(strategy_plan.probe_seed, "quic"));
        let mut quic_family_succeeded = false;
        let mut blocked_quic_family = None::<&str>;
        let mut last_failed_quic_family = None::<&str>;
        let mut consecutive_quic_family_failures = 0usize;
        while !pending_quic_specs.is_empty() {
            let spec = pending_quic_specs.remove(next_candidate_index(&pending_quic_specs, blocked_quic_family));
            if runtime.is_cancelled() {
                return RunnerOutcome::Cancelled;
            }
            if strategy_plan.suite.short_circuit_quic_burst && spec.family == "quic_burst" && quic_family_succeeded {
                runtime.strategy.quic_candidates.push(skipped_candidate_summary(
                    &spec,
                    plan.request.quic_targets.len(),
                    2,
                    "Earlier QUIC burst candidate already achieved full success",
                ));
                runtime.completed_steps += 1;
                continue;
            }

            let execution = execute_quic_candidate(
                &spec,
                &plan.request.quic_targets,
                strategy_plan.runtime_context.as_ref(),
                strategy_plan.probe_seed,
            );
            if execution.summary.family == "quic_burst"
                && execution.summary.succeeded_targets == execution.summary.total_targets
                && execution.summary.total_targets > 0
            {
                quic_family_succeeded = true;
            }
            let failed = execution.summary.outcome == "failed";
            runtime.record_step(
                plan,
                self.phase(),
                format!("Tested {}", spec.label),
                Some(spec.label.to_string()),
                Some(execution.summary.outcome.clone()),
                RunnerArtifacts::from_results(
                    execution.results.clone(),
                    "strategy_probe",
                    if failed { "warn" } else { "info" },
                    format!("Testing QUIC candidate {}", spec.label),
                ),
            );
            runtime.strategy.quic_candidates.push(execution.summary);
            if failed {
                if last_failed_quic_family == Some(spec.family) {
                    consecutive_quic_family_failures += 1;
                } else {
                    last_failed_quic_family = Some(spec.family);
                    consecutive_quic_family_failures = 1;
                }
                if consecutive_quic_family_failures >= 2 {
                    blocked_quic_family = Some(spec.family);
                    consecutive_quic_family_failures = 0;
                }
            } else {
                last_failed_quic_family = None;
                consecutive_quic_family_failures = 0;
                blocked_quic_family = None;
            }
            if blocked_quic_family.is_some() && spec.family != blocked_quic_family.unwrap_or_default() {
                blocked_quic_family = None;
            }
            if !pending_quic_specs.is_empty() {
                thread::sleep(Duration::from_millis(candidate_pause_ms(strategy_plan.probe_seed, &spec, failed)));
            }
        }
        RunnerOutcome::Completed
    }
}

impl ExecutionStageRunner for StrategyRecommendationRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::StrategyRecommendation
    }

    fn phase(&self) -> &'static str {
        "recommendation"
    }

    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        usize::from(plan.strategy.is_some())
    }

    fn run(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome {
        let Some(strategy_plan) = plan.strategy.as_ref() else {
            return RunnerOutcome::Completed;
        };
        if runtime.strategy.tcp_candidates.is_empty() || runtime.strategy.quic_candidates.is_empty() {
            runtime.strategy.summary = Some("Automatic probing finished".to_string());
            return RunnerOutcome::Completed;
        }
        let winning_tcp = winning_candidate_index(&runtime.strategy.tcp_candidates).unwrap_or(0);
        let winning_quic = winning_candidate_index(&runtime.strategy.quic_candidates).unwrap_or(0);
        let quic_winner_spec = strategy_plan
            .suite
            .quic_candidates
            .iter()
            .find(|spec| spec.id == runtime.strategy.quic_candidates[winning_quic].id)
            .unwrap_or_else(|| strategy_plan.suite.quic_candidates.first().expect("quic candidates"));
        let recommendation = StrategyProbeRecommendation {
            tcp_candidate_id: runtime.strategy.tcp_candidates[winning_tcp].id.clone(),
            tcp_candidate_label: runtime.strategy.tcp_candidates[winning_tcp].label.clone(),
            quic_candidate_id: runtime.strategy.quic_candidates[winning_quic].id.clone(),
            quic_candidate_label: runtime.strategy.quic_candidates[winning_quic].label.clone(),
            rationale: format!(
                "{} with {} weighted TCP success and {} weighted QUIC success",
                runtime.strategy.tcp_candidates[winning_tcp].label,
                runtime.strategy.tcp_candidates[winning_tcp].weighted_success_score,
                runtime.strategy.quic_candidates[winning_quic].weighted_success_score,
            ),
            recommended_proxy_config_json: crate::candidates::strategy_probe_config_json(&quic_winner_spec.config),
        };
        let summary = build_strategy_probe_summary(
            &strategy_plan.suite_id,
            &runtime.strategy.tcp_candidates,
            &runtime.strategy.quic_candidates,
            &recommendation,
        );
        runtime.strategy.strategy_probe_report = Some(StrategyProbeReport {
            suite_id: strategy_plan.suite_id.clone(),
            tcp_candidates: runtime.strategy.tcp_candidates.clone(),
            quic_candidates: runtime.strategy.quic_candidates.clone(),
            recommendation,
        });
        runtime.strategy.summary = Some(summary);
        runtime.completed_steps += 1;
        set_progress(
            &runtime.shared,
            ScanProgress {
                session_id: plan.session_id.clone(),
                phase: self.phase().to_string(),
                completed_steps: runtime.completed_steps,
                total_steps: plan.total_steps,
                message: "Prepared strategy recommendation".to_string(),
                is_finished: false,
                latest_probe_target: None,
                latest_probe_outcome: Some("ready".to_string()),
            },
        );
        RunnerOutcome::Completed
    }
}

fn execution_coordinator() -> ExecutionCoordinator {
    ExecutionCoordinator::new(vec![
        Box::new(EnvironmentRunner),
        Box::new(DnsRunner),
        Box::new(WebRunner),
        Box::new(QuicRunner),
        Box::new(TcpRunner),
        Box::new(ServiceRunner),
        Box::new(CircumventionRunner),
        Box::new(TelegramRunner),
        Box::new(ThroughputRunner),
        Box::new(StrategyDnsBaselineRunner),
        Box::new(StrategyTcpRunner),
        Box::new(StrategyQuicRunner),
        Box::new(StrategyRecommendationRunner),
    ])
}

fn connectivity_summary(
    results: &[ProbeResult],
    path_mode: &crate::types::ScanPathMode,
) -> String {
    let mut healthy = 0usize;
    let mut attention = 0usize;
    let mut failed = 0usize;
    let mut inconclusive = 0usize;

    for result in results {
        match classify_probe_outcome(&result.probe_type, path_mode, &result.outcome).bucket {
            crate::util::ProbeOutcomeBucket::Healthy => healthy += 1,
            crate::util::ProbeOutcomeBucket::Attention => attention += 1,
            crate::util::ProbeOutcomeBucket::Failed => failed += 1,
            crate::util::ProbeOutcomeBucket::Inconclusive => inconclusive += 1,
        }
    }

    let mut parts = vec![format!("{} completed", results.len()), format!("{healthy} healthy")];
    if attention > 0 {
        parts.push(format!("{attention} attention"));
    }
    if failed > 0 {
        parts.push(format!("{failed} failed"));
    }
    if inconclusive > 0 {
        parts.push(format!("{inconclusive} inconclusive"));
    }
    parts.join(" · ")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn connectivity_summary_reports_bucket_breakdown() {
        let mut results = vec![probe("network_environment", "wifi", "network_available")];
        results.extend((0..24).map(|index| probe("dns_integrity", format!("dns-{index}"), "dns_match")));
        results.extend((0..4).map(|index| probe("domain_reachability", format!("tls-{index}"), "tls_ok")));
        results.push(probe("tcp_fat_header", "16.15.219.241:443 (AWS)", "whitelist_sni_ok"));
        results.push(probe("tcp_fat_header", "172.67.70.222:443 (Cloudflare)", "whitelist_sni_failed"));

        assert_eq!(
            connectivity_summary(&results, &crate::types::ScanPathMode::RawPath),
            "31 completed · 30 healthy · 1 failed",
        );
    }

    #[test]
    fn connectivity_summary_omits_zero_value_non_healthy_buckets() {
        assert_eq!(connectivity_summary(&[], &crate::types::ScanPathMode::RawPath), "0 completed · 0 healthy");
    }

    fn probe(probe_type: &str, target: impl Into<String>, outcome: &str) -> ProbeResult {
        ProbeResult {
            probe_type: probe_type.to_string(),
            target: target.into(),
            outcome: outcome.to_string(),
            details: Vec::new(),
        }
    }
}
