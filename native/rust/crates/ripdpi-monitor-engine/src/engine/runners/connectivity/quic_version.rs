use crate::engine::runtime::{
    CollectedStageOutcome, CollectedStep, ExecutionPlan, ExecutionStageId, ExecutionStageRunner, RunnerArtifacts,
};
use crate::types::ScanPathMode;
use rustls::client::danger::ServerCertVerifier;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

pub(in crate::engine::runners) struct QuicVersionMatrixRunner;
impl ExecutionStageRunner for QuicVersionMatrixRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::QuicVersionMatrix
    }
    fn phase(&self) -> &'static str {
        "quic_version_matrix"
    }
    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        plan.request
            .quic_version_matrix
            .as_ref()
            .map_or(0, |config| if config.residual_version.is_some() { 4 } else { 3 })
    }
    fn run_collecting(
        &self,
        plan: &ExecutionPlan,
        cancel: &AtomicBool,
        _verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> CollectedStageOutcome {
        let Some(config) = &plan.request.quic_version_matrix else {
            return CollectedStageOutcome::Completed(vec![]);
        };
        let results = crate::connectivity::run_quic_version_matrix_probe(
            config,
            &plan.transport,
            plan.request.path_mode == ScanPathMode::RawPath,
            cancel,
        );
        let steps = results
            .into_iter()
            .map(|result| CollectedStep {
                phase: "quic_version_matrix",
                latest_probe_target: Some(result.target.clone()),
                message: result.target.clone(),
                latest_probe_outcome: Some(result.outcome.clone()),
                artifacts: RunnerArtifacts::from_probe(result, "quic_version_matrix", &plan.request.path_mode),
            })
            .collect();
        if cancel.load(Ordering::Acquire) {
            CollectedStageOutcome::Cancelled(steps)
        } else {
            CollectedStageOutcome::Completed(steps)
        }
    }
}
