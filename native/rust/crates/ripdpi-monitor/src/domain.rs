use std::collections::BTreeMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use rustls::client::danger::ServerCertVerifier;

use crate::transport::TransportConfig;
use crate::types::{ProbeResult, ProbeTaskFamily, ScanProgress, ScanRequest, SharedState};

pub(crate) struct ExecutionPlan {
    pub(crate) session_id: String,
    pub(crate) request: ScanRequest,
    pub(crate) started_at: u64,
    pub(crate) total_steps: usize,
    pub(crate) transport: TransportConfig,
    pub(crate) family_order: Vec<ProbeTaskFamily>,
}

pub(crate) enum RunnerOutcome {
    Completed,
    Cancelled,
}

pub(crate) trait ProbeFamilyRunner {
    fn family(&self) -> ProbeTaskFamily;

    fn phase(&self) -> &'static str;

    fn total_steps(&self, plan: &ExecutionPlan) -> usize;

    fn run(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome;
}

pub(crate) struct ExecutionCoordinator {
    runners: BTreeMap<ProbeTaskFamily, Box<dyn ProbeFamilyRunner + Send + Sync>>,
}

impl ExecutionCoordinator {
    pub(crate) fn new(runners: Vec<Box<dyn ProbeFamilyRunner + Send + Sync>>) -> Self {
        let runners = runners.into_iter().map(|runner| (runner.family(), runner)).collect();
        Self { runners }
    }

    pub(crate) fn run(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome {
        for family in &plan.family_order {
            let Some(runner) = self.runners.get(family) else {
                continue;
            };
            if runtime.is_cancelled() {
                return RunnerOutcome::Cancelled;
            }
            if runner.total_steps(plan) == 0 {
                continue;
            }
            if matches!(runner.run(plan, runtime, tls_verifier), RunnerOutcome::Cancelled) {
                return RunnerOutcome::Cancelled;
            }
        }
        RunnerOutcome::Completed
    }

    pub(crate) fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        plan.family_order
            .iter()
            .filter_map(|family| self.runners.get(family))
            .map(|runner| runner.total_steps(plan))
            .sum::<usize>()
            .max(1)
    }
}

pub(crate) struct ExecutionRuntime {
    pub(crate) shared: Arc<Mutex<SharedState>>,
    cancel: Arc<AtomicBool>,
    pub(crate) completed_steps: usize,
    pub(crate) results: Vec<ProbeResult>,
}

impl ExecutionRuntime {
    pub(crate) fn new(
        shared: Arc<Mutex<SharedState>>,
        cancel: Arc<AtomicBool>,
        seed_results: Vec<ProbeResult>,
    ) -> Self {
        Self { shared, cancel, completed_steps: 0, results: seed_results }
    }

    pub(crate) fn is_cancelled(&self) -> bool {
        self.cancel.load(Ordering::Acquire)
    }

    pub(crate) fn push_result(&mut self, plan: &ExecutionPlan, phase: &str, message: String, result: ProbeResult) {
        let probe_target = result.target.clone();
        let probe_outcome = result.outcome.clone();
        self.results.push(result);
        self.completed_steps += 1;
        crate::connectivity::set_progress(
            &self.shared,
            ScanProgress {
                session_id: plan.session_id.clone(),
                phase: phase.to_string(),
                completed_steps: self.completed_steps,
                total_steps: plan.total_steps,
                message,
                is_finished: false,
                latest_probe_target: Some(probe_target),
                latest_probe_outcome: Some(probe_outcome),
                strategy_probe_progress: None,
            },
        );
    }

    pub(crate) fn into_results(self) -> Vec<ProbeResult> {
        self.results
    }
}
