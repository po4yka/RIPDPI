use std::collections::BTreeMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use crate::types::ScanKind;

use ripdpi_failure_classifier::ClassifiedFailure;
use ripdpi_proxy_config::ProxyRuntimeContext;
use rustls::client::danger::ServerCertVerifier;

use crate::candidates::StrategyProbeSuite;
use crate::connectivity::{push_event, set_progress};
use crate::transport::TransportConfig;
use crate::types::{
    ProbeObservation, ProbeResult, ScanProgress, ScanReport, ScanRequest, SharedState, StrategyProbeCandidateSummary,
    StrategyProbeLiveProgress, StrategyProbeProgressLane, StrategyProbeReport,
};

include!("runtime_artifacts.rs");

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
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

pub(super) trait ExecutionStageRunner {
    fn id(&self) -> ExecutionStageId;

    fn phase(&self) -> &'static str;

    fn total_steps(&self, plan: &ExecutionPlan) -> usize;

    fn run(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome {
        let Some(steps) = self.run_collecting(plan, runtime.cancel_token(), tls_verifier) else {
            return RunnerOutcome::Cancelled;
        };
        for step in steps {
            runtime.record_step(
                plan,
                step.phase,
                step.message,
                step.latest_probe_target,
                step.latest_probe_outcome,
                None,
                step.artifacts,
            );
        }
        RunnerOutcome::Completed
    }

    /// Run without touching `runtime`, returning all steps collected independently.
    /// Used by the parallel connectivity runner path.
    /// Returns `None` if the runner was cancelled mid-way.
    fn run_collecting(
        &self,
        _plan: &ExecutionPlan,
        _cancel: &AtomicBool,
        _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> Option<Vec<CollectedStep>> {
        None
    }
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
    /// When DNS tampering is detected, holds domain targets with `connect_ip`
    /// set to encrypted-DNS-resolved addresses, bypassing poisoned system DNS.
    pub(super) dns_override_domain_targets: Option<Vec<crate::types::DomainTarget>>,
    pub(super) dns_override_quic_targets: Option<Vec<crate::types::QuicTarget>>,
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
        self.scan_deadline.is_some_and(|d| std::time::Instant::now() >= d)
    }

    fn publish_progress(
        &self,
        plan: &ExecutionPlan,
        phase: &str,
        completed_steps: usize,
        message: String,
        latest_probe_target: Option<String>,
        latest_probe_outcome: Option<String>,
        strategy_probe_progress: Option<StrategyProbeLiveProgress>,
    ) {
        set_progress(
            &self.shared,
            ScanProgress {
                session_id: plan.session_id.clone(),
                phase: phase.to_string(),
                completed_steps,
                total_steps: plan.total_steps,
                message,
                is_finished: false,
                latest_probe_target,
                latest_probe_outcome,
                strategy_probe_progress,
            },
        );
    }

    #[allow(clippy::too_many_arguments)]
    pub(super) fn publish_strategy_probe_candidate_started(
        &self,
        plan: &ExecutionPlan,
        phase: &str,
        lane: StrategyProbeProgressLane,
        candidate_index: usize,
        candidate_total: usize,
        candidate_id: &str,
        candidate_label: &str,
        message: String,
    ) {
        let progress = StrategyProbeLiveProgress {
            lane,
            candidate_index,
            candidate_total,
            candidate_id: candidate_id.to_string(),
            candidate_label: candidate_label.to_string(),
            succeeded_targets: 0,
            total_targets: 0,
        };
        self.publish_progress(
            plan,
            phase,
            self.completed_steps,
            message,
            Some(candidate_label.to_string()),
            None,
            Some(progress),
        );
    }

    pub(super) fn record_step(
        &mut self,
        plan: &ExecutionPlan,
        phase: &str,
        message: String,
        latest_probe_target: Option<String>,
        latest_probe_outcome: Option<String>,
        strategy_probe_progress: Option<StrategyProbeLiveProgress>,
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
        self.publish_progress(
            plan,
            phase,
            self.completed_steps,
            message,
            latest_probe_target,
            latest_probe_outcome,
            strategy_probe_progress,
        );
    }

    #[allow(clippy::too_many_arguments)]
    pub(super) fn record_skipped_strategy_probe_candidate(
        &mut self,
        plan: &ExecutionPlan,
        phase: &str,
        lane: StrategyProbeProgressLane,
        candidate_index: usize,
        candidate_total: usize,
        candidate_id: &str,
        candidate_label: &str,
        latest_probe_outcome: Option<String>,
        message: String,
    ) {
        let progress = StrategyProbeLiveProgress {
            lane,
            candidate_index,
            candidate_total,
            candidate_id: candidate_id.to_string(),
            candidate_label: candidate_label.to_string(),
            succeeded_targets: 0,
            total_targets: 0,
        };
        self.record_step(
            plan,
            phase,
            message,
            Some(candidate_label.to_string()),
            latest_probe_outcome,
            Some(progress),
            RunnerArtifacts::empty(),
        );
    }

    pub(super) fn finish_with_report(&mut self, report: ScanReport) {
        self.final_report = Some(report);
    }
}

include!("runtime_coordinator.rs");

#[cfg(test)]
mod tests {
    include!("runtime_tests.rs");
}
