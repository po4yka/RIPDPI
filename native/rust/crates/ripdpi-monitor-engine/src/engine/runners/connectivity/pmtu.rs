use crate::engine::runtime::{
    CollectedStageOutcome, CollectedStep, ExecutionPlan, ExecutionStageId, ExecutionStageRunner, RunnerArtifacts,
};
use crate::types::ScanPathMode;
use rustls::client::danger::ServerCertVerifier;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

pub(in crate::engine::runners) struct PmtuRunner;
impl ExecutionStageRunner for PmtuRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::Pmtu
    }
    fn phase(&self) -> &'static str {
        "pmtu"
    }
    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        if plan.request.pmtu_probe.is_some() { 2 } else { 0 }
    }
    fn run_collecting(
        &self,
        plan: &ExecutionPlan,
        cancel: &AtomicBool,
        verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> CollectedStageOutcome {
        let Some(config) = &plan.request.pmtu_probe else {
            return CollectedStageOutcome::Completed(vec![]);
        };
        let results = crate::connectivity::run_pmtu_probe(
            config,
            &plan.transport,
            plan.request.path_mode == ScanPathMode::RawPath,
            cancel,
            verifier,
        );
        let steps = results
            .into_iter()
            .map(|result| CollectedStep {
                phase: "pmtu",
                latest_probe_target: Some(result.target.clone()),
                message: result.target.clone(),
                latest_probe_outcome: Some(result.outcome.clone()),
                artifacts: RunnerArtifacts::from_probe(result, "pmtu", &plan.request.path_mode),
            })
            .collect();
        if cancel.load(Ordering::Acquire) {
            CollectedStageOutcome::Cancelled(steps)
        } else {
            CollectedStageOutcome::Completed(steps)
        }
    }
}
