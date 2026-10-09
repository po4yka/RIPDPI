use crate::engine::runtime::{
    CollectedStageOutcome, CollectedStep, ExecutionPlan, ExecutionStageId, ExecutionStageRunner, RunnerArtifacts,
};
use crate::types::ScanPathMode;
use rustls::client::danger::ServerCertVerifier;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

pub(in crate::engine::runners) struct IpFamilyRunner;
impl ExecutionStageRunner for IpFamilyRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::IpFamily
    }
    fn phase(&self) -> &'static str {
        "ip_family"
    }
    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        if plan.request.ip_family_probe.is_some() { 3 } else { 0 }
    }
    fn run_collecting(
        &self,
        plan: &ExecutionPlan,
        cancel: &AtomicBool,
        _verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> CollectedStageOutcome {
        let Some(config) = &plan.request.ip_family_probe else {
            return CollectedStageOutcome::Completed(vec![]);
        };
        let servers =
            plan.request.network_snapshot.as_ref().map_or(&[][..], |snapshot| snapshot.dns_servers.as_slice());
        let results = crate::connectivity::run_ip_family_probes(
            config,
            servers,
            &plan.transport,
            plan.request.path_mode == ScanPathMode::RawPath,
            cancel,
        );
        let steps = results
            .into_iter()
            .map(|result| CollectedStep {
                phase: "ip_family",
                latest_probe_target: Some(result.target.clone()),
                message: result.target.clone(),
                latest_probe_outcome: Some(result.outcome.clone()),
                artifacts: RunnerArtifacts::from_probe(result, "ip_family", &plan.request.path_mode),
            })
            .collect();
        if cancel.load(Ordering::Acquire) {
            CollectedStageOutcome::Cancelled(steps)
        } else {
            CollectedStageOutcome::Completed(steps)
        }
    }
}
