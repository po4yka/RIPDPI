use std::collections::BTreeMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use ripdpi_failure_classifier::ClassifiedFailure;
use ripdpi_proxy_config::{ProxyRuntimeContext, ProxyUiConfig};
use rustls::client::danger::ServerCertVerifier;

use crate::candidates::StrategyProbeSuite;
use crate::connectivity::{push_event, set_progress, summarize_probe_event};
use crate::observations::{observation_for_probe, observations_for_results};
use crate::transport::TransportConfig;
use crate::types::{
    NativeSessionEvent, ProbeObservation, ProbeResult, ScanProgress, ScanReport, ScanRequest, SharedState,
    StrategyProbeCandidateSummary, StrategyProbeReport,
};
use crate::util::{event_level_for_outcome, now_ms};

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(super) enum ExecutionStageId {
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

pub(super) enum RunnerOutcome {
    Completed,
    Cancelled,
    Finished,
}

pub(super) struct RunnerArtifacts {
    pub(super) probe_results: Vec<ProbeResult>,
    pub(super) observations: Vec<ProbeObservation>,
    pub(super) events: Vec<NativeSessionEvent>,
}

impl RunnerArtifacts {
    pub(super) fn from_probe(probe: ProbeResult, source: &str, path_mode: &crate::types::ScanPathMode) -> Self {
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

    pub(super) fn from_results(results: Vec<ProbeResult>, source: &str, level: &str, message: String) -> Self {
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

pub(super) trait ExecutionStageRunner {
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

pub(super) struct ExecutionPlan {
    pub(super) session_id: String,
    pub(super) request: ScanRequest,
    pub(super) started_at: u64,
    pub(super) total_steps: usize,
    pub(super) transport: TransportConfig,
    pub(super) stage_order: Vec<ExecutionStageId>,
    pub(super) strategy: Option<StrategyExecutionPlan>,
}

pub(super) struct StrategyExecutionPlan {
    pub(super) suite_id: String,
    pub(super) base_payload: ProxyUiConfig,
    pub(super) runtime_context: Option<ProxyRuntimeContext>,
    pub(super) suite: StrategyProbeSuite,
    pub(super) probe_seed: u64,
}

#[derive(Default)]
pub(super) struct StrategyExecutionState {
    pub(super) baseline_failure: Option<ClassifiedFailure>,
    pub(super) tcp_candidates: Vec<StrategyProbeCandidateSummary>,
    pub(super) quic_candidates: Vec<StrategyProbeCandidateSummary>,
    pub(super) summary: Option<String>,
    pub(super) strategy_probe_report: Option<StrategyProbeReport>,
}

pub(super) struct ExecutionRuntime {
    pub(super) shared: Arc<Mutex<SharedState>>,
    cancel: Arc<AtomicBool>,
    pub(super) completed_steps: usize,
    pub(super) results: Vec<ProbeResult>,
    pub(super) observations: Vec<ProbeObservation>,
    pub(super) final_report: Option<ScanReport>,
    pub(super) strategy: StrategyExecutionState,
    pub(super) scan_deadline: Option<std::time::Instant>,
}

impl ExecutionRuntime {
    pub(super) fn new(shared: Arc<Mutex<SharedState>>, cancel: Arc<AtomicBool>) -> Self {
        Self {
            shared,
            cancel,
            completed_steps: 0,
            results: Vec::new(),
            observations: Vec::new(),
            final_report: None,
            strategy: StrategyExecutionState::default(),
            scan_deadline: None,
        }
    }

    pub(super) fn is_cancelled(&self) -> bool {
        self.cancel.load(Ordering::Acquire)
    }

    pub(super) fn cancel_token(&self) -> &AtomicBool {
        &self.cancel
    }

    pub(super) fn set_scan_deadline(&mut self, deadline: std::time::Instant) {
        self.scan_deadline = Some(deadline);
    }

    pub(super) fn is_past_deadline(&self) -> bool {
        self.scan_deadline.map_or(false, |d| std::time::Instant::now() >= d)
    }

    pub(super) fn record_step(
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

    pub(super) fn finish_with_report(&mut self, report: ScanReport) {
        self.final_report = Some(report);
    }
}

pub(super) struct ExecutionCoordinator {
    runners: BTreeMap<ExecutionStageId, Box<dyn ExecutionStageRunner + Send + Sync>>,
}

impl ExecutionCoordinator {
    pub(super) fn new(runners: Vec<Box<dyn ExecutionStageRunner + Send + Sync>>) -> Self {
        let runners = runners.into_iter().map(|runner| (runner.id(), runner)).collect();
        Self { runners }
    }

    pub(super) fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        plan.stage_order
            .iter()
            .filter_map(|stage| self.runners.get(stage))
            .map(|runner| runner.total_steps(plan))
            .sum::<usize>()
            .max(1)
    }

    pub(super) fn run(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome {
        for stage in &plan.stage_order {
            let Some(runner) = self.runners.get(stage) else {
                continue;
            };
            if runtime.is_cancelled() || runtime.is_past_deadline() {
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
