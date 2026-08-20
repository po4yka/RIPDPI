use std::collections::BTreeMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use rustls::client::danger::ServerCertVerifier;

use crate::types::{ProbeResult, ProbeTaskFamily, ScanProgress, ScanRequest, SharedState};
use ripdpi_diagnostics_protocols::transport::TransportConfig;

pub struct ExecutionPlan {
    pub session_id: String,
    pub request: ScanRequest,
    pub started_at: u64,
    pub total_steps: usize,
    pub transport: TransportConfig,
    pub family_order: Vec<ProbeTaskFamily>,
}

pub enum RunnerOutcome {
    Completed,
    Cancelled,
}

/// Small lane-registration contract used by diagnostics-runner.
///
/// Concrete DNS/HTTP/TLS/Telegram/transport lanes stay behind their adapter
/// modules; the coordinator only sees family identity, scheduling phase,
/// step count, and a bounded run entrypoint.
pub trait ProbeFamilyRunner {
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

pub struct ExecutionCoordinator {
    runners: BTreeMap<ProbeTaskFamily, Box<dyn ProbeFamilyRunner + Send + Sync>>,
}

impl ExecutionCoordinator {
    pub fn new(runners: Vec<Box<dyn ProbeFamilyRunner + Send + Sync>>) -> Self {
        let runners = runners.into_iter().map(|runner| (runner.family(), runner)).collect();
        Self { runners }
    }

    pub fn run(
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

    pub fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        plan.family_order
            .iter()
            .filter_map(|family| self.runners.get(family))
            .map(|runner| runner.total_steps(plan))
            .sum::<usize>()
            .max(1)
    }
}

pub struct ExecutionRuntime {
    pub shared: Arc<Mutex<SharedState>>,
    cancel: Arc<AtomicBool>,
    pub completed_steps: usize,
    pub results: Vec<ProbeResult>,
}

impl ExecutionRuntime {
    pub fn new(shared: Arc<Mutex<SharedState>>, cancel: Arc<AtomicBool>, seed_results: Vec<ProbeResult>) -> Self {
        Self { shared, cancel, completed_steps: 0, results: seed_results }
    }

    pub fn is_cancelled(&self) -> bool {
        self.cancel.load(Ordering::Acquire)
    }

    pub fn push_result(&mut self, plan: &ExecutionPlan, phase: &str, message: String, result: ProbeResult) {
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

    pub fn into_results(self) -> Vec<ProbeResult> {
        self.results
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_diagnostics_protocols::transport::direct_transport;
    use std::sync::atomic::AtomicBool;

    struct FakeRunner {
        family: ProbeTaskFamily,
        steps: usize,
    }

    impl ProbeFamilyRunner for FakeRunner {
        fn family(&self) -> ProbeTaskFamily {
            self.family.clone()
        }

        fn phase(&self) -> &'static str {
            "fake"
        }

        fn total_steps(&self, _plan: &ExecutionPlan) -> usize {
            self.steps
        }

        fn run(
            &self,
            plan: &ExecutionPlan,
            runtime: &mut ExecutionRuntime,
            _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
        ) -> RunnerOutcome {
            runtime.push_result(
                plan,
                self.phase(),
                "fake complete".to_string(),
                ProbeResult {
                    probe_type: "fake".to_string(),
                    target: "example.test".to_string(),
                    outcome: "ok".to_string(),
                    details: Vec::new(),
                },
            );
            RunnerOutcome::Completed
        }
    }

    fn plan() -> ExecutionPlan {
        ExecutionPlan {
            session_id: "session".to_string(),
            request: ScanRequest {
                profile_id: "profile".to_string(),
                display_name: "Profile".to_string(),
                path_mode: crate::types::ScanPathMode::RawPath,
                kind: crate::types::ScanKind::Connectivity,
                family: crate::types::DiagnosticProfileFamily::General,
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
                network_snapshot: None,
                route_probe: None,
                scan_deadline_ms: None,
                diagnostic_tls_keylog_path: None,
                confirm_good_dpi_evidence: None,
            },
            started_at: 0,
            total_steps: 1,
            transport: direct_transport(),
            family_order: vec![ProbeTaskFamily::Dns],
        }
    }

    #[test]
    fn coordinator_runs_only_registered_lane_family() {
        let coordinator = ExecutionCoordinator::new(vec![
            Box::new(FakeRunner { family: ProbeTaskFamily::Dns, steps: 1 }),
            Box::new(FakeRunner { family: ProbeTaskFamily::Tcp, steps: 1 }),
        ]);
        let shared = Arc::new(Mutex::new(SharedState::default()));
        let mut runtime = ExecutionRuntime::new(shared, Arc::new(AtomicBool::new(false)), Vec::new());

        assert!(matches!(coordinator.run(&plan(), &mut runtime, None), RunnerOutcome::Completed));
        assert_eq!(runtime.results.len(), 1);
        assert_eq!(runtime.results[0].probe_type, "fake");
    }
}
