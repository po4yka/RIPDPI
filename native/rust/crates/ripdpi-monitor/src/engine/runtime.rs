use std::collections::BTreeMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use crate::types::ScanKind;

use ripdpi_failure_classifier::ClassifiedFailure;
use ripdpi_proxy_config::ProxyRuntimeContext;
use rustls::client::danger::ServerCertVerifier;

use crate::candidates::StrategyProbeSuite;
use crate::connectivity::{push_event, set_progress, summarize_probe_event};
use crate::observations::{observation_for_probe, observations_for_results};
use crate::transport::TransportConfig;
use crate::types::{
    NativeSessionEvent, ProbeObservation, ProbeResult, ScanProgress, ScanReport, ScanRequest, SharedState,
    StrategyProbeCandidateSummary, StrategyProbeLiveProgress, StrategyProbeProgressLane, StrategyProbeReport,
};
use crate::util::{event_level_for_outcome, now_ms};

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
                runtime_id: None,
                mode: None,
                policy_signature: None,
                fingerprint_hash: None,
                subsystem: Some("diagnostics".to_string()),
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
                runtime_id: None,
                mode: None,
                policy_signature: None,
                fingerprint_hash: None,
                subsystem: Some("diagnostics".to_string()),
            }],
        }
    }

    pub(super) fn empty() -> Self {
        Self { probe_results: Vec::new(), observations: Vec::new(), events: Vec::new() }
    }
}

/// A single recorded step collected outside of `ExecutionRuntime`, used by the
/// parallel runner path to accumulate results without shared mutable state.
pub(super) struct CollectedStep {
    pub(super) phase: &'static str,
    pub(super) message: String,
    pub(super) latest_probe_target: Option<String>,
    pub(super) latest_probe_outcome: Option<String>,
    pub(super) artifacts: RunnerArtifacts,
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
        self.publish_progress(
            plan,
            phase,
            self.completed_steps,
            message,
            Some(candidate_label.to_string()),
            None,
            Some(StrategyProbeLiveProgress {
                lane,
                candidate_index,
                candidate_total,
                candidate_id: candidate_id.to_string(),
                candidate_label: candidate_label.to_string(),
            }),
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
        let strategy_probe_progress = StrategyProbeLiveProgress {
            lane,
            candidate_index,
            candidate_total,
            candidate_id: candidate_id.to_string(),
            candidate_label: candidate_label.to_string(),
        };
        self.record_step(
            plan,
            phase,
            message,
            Some(candidate_label.to_string()),
            latest_probe_outcome,
            Some(strategy_probe_progress),
            RunnerArtifacts::empty(),
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
        // For CONNECTIVITY scans, DNS + TCP + QUIC are independent I/O-bound
        // stages that can run concurrently. We collect their stages into a
        // parallel group and run them with std::thread::scope, then merge the
        // results back into runtime in order before continuing sequentially.
        const PARALLEL_GROUP: &[ExecutionStageId] =
            &[ExecutionStageId::Dns, ExecutionStageId::Tcp, ExecutionStageId::Quic];

        let is_connectivity = matches!(plan.request.kind, ScanKind::Connectivity);

        // Track which stages in the parallel group we've already handled so we
        // can skip them in the sequential loop below.
        let mut parallel_done = std::collections::HashSet::new();

        if is_connectivity {
            // Collect runners for the parallel group that are present in this
            // plan's stage_order and have at least one step.
            let parallel_runners: Vec<&ExecutionStageId> = plan
                .stage_order
                .iter()
                .filter(|stage| {
                    PARALLEL_GROUP.contains(stage) && self.runners.get(stage).is_some_and(|r| r.total_steps(plan) > 0)
                })
                .collect();

            if parallel_runners.len() > 1 {
                if runtime.is_cancelled() || runtime.is_past_deadline() {
                    return RunnerOutcome::Cancelled;
                }

                // Each thread collects its steps independently.
                // Vec slot order matches parallel_runners order.
                let mut thread_results: Vec<Option<Vec<CollectedStep>>> =
                    (0..parallel_runners.len()).map(|_| None).collect();

                std::thread::scope(|s| {
                    let mut handles = Vec::with_capacity(parallel_runners.len());
                    for stage in &parallel_runners {
                        let runner = self.runners.get(stage).expect("runner present");
                        let cancel = runtime.cancel_token();
                        handles.push(s.spawn(move || runner.run_collecting(plan, cancel, tls_verifier)));
                    }
                    for (i, handle) in handles.into_iter().enumerate() {
                        // join() only fails if the thread panicked; propagate.
                        thread_results[i] = handle.join().expect("parallel runner thread panicked");
                    }
                });

                // Merge results back into runtime in stage_order sequence.
                for (stage, collected_opt) in parallel_runners.iter().zip(thread_results.into_iter()) {
                    parallel_done.insert(*stage);
                    let Some(steps) = collected_opt else {
                        // Runner signalled cancellation.
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
                }
            }
        }

        for stage in &plan.stage_order {
            // Skip stages already handled by the parallel group.
            if parallel_done.contains(stage) {
                continue;
            }
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

#[cfg(test)]
mod tests {
    use std::sync::atomic::AtomicBool;
    use std::sync::{Arc, Mutex};

    use super::{ExecutionPlan, ExecutionRuntime};
    use crate::transport::TransportConfig;
    use crate::types::{
        DiagnosticProfileFamily, ScanKind, ScanPathMode, ScanRequest, SharedState, StrategyProbeProgressLane,
    };

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
            },
            started_at: 0,
            total_steps: 8,
            transport: TransportConfig::Direct,
            stage_order: Vec::new(),
            strategy: None,
        }
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
}
