use crate::engine::runtime::{
    CollectedStageOutcome, CollectedStep, ExecutionPlan, ExecutionStageId, ExecutionStageRunner, RunnerArtifacts,
};
use crate::types::ScanPathMode;
use rustls::client::danger::ServerCertVerifier;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

pub(in crate::engine::runners) struct Http3Runner;
impl ExecutionStageRunner for Http3Runner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::Http3
    }
    fn phase(&self) -> &'static str {
        "http3"
    }
    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        if plan.request.http3_probe.is_some() { 1 } else { 0 }
    }
    fn run_collecting(
        &self,
        plan: &ExecutionPlan,
        cancel: &AtomicBool,
        verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> CollectedStageOutcome {
        let Some(config) = &plan.request.http3_probe else {
            return CollectedStageOutcome::Completed(vec![]);
        };
        let results = vec![crate::connectivity::run_http3_probe(
            config,
            &plan.transport,
            plan.request.path_mode == ScanPathMode::RawPath,
            cancel,
            verifier,
        )];
        let steps = results
            .into_iter()
            .map(|result| CollectedStep {
                phase: "http3",
                latest_probe_target: Some(result.target.clone()),
                message: result.target.clone(),
                latest_probe_outcome: Some(result.outcome.clone()),
                artifacts: RunnerArtifacts::from_probe(result, "http3", &plan.request.path_mode),
            })
            .collect();
        if cancel.load(Ordering::Acquire) {
            CollectedStageOutcome::Cancelled(steps)
        } else {
            CollectedStageOutcome::Completed(steps)
        }
    }
}
