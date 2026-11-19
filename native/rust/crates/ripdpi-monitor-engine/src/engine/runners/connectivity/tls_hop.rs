use crate::engine::runtime::{
    CollectedStageOutcome, CollectedStep, ExecutionPlan, ExecutionStageId, ExecutionStageRunner, RunnerArtifacts,
};
use crate::types::ScanPathMode;
use rustls::client::danger::ServerCertVerifier;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

pub(in crate::engine::runners) struct TlsHopComparisonRunner;
impl ExecutionStageRunner for TlsHopComparisonRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::TlsHopComparison
    }
    fn phase(&self) -> &'static str {
        "tls_hop_comparison"
    }
    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        usize::from(plan.request.tls_hop_comparison.is_some())
    }
    fn run_collecting(
        &self,
        plan: &ExecutionPlan,
        cancel: &AtomicBool,
        _verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> CollectedStageOutcome {
        let Some(config) = &plan.request.tls_hop_comparison else {
            return CollectedStageOutcome::Completed(vec![]);
        };
        let result = crate::connectivity::run_tls_hop_comparison(
            config,
            &plan.transport,
            plan.request.path_mode == ScanPathMode::RawPath,
            cancel,
        );
        let steps = vec![CollectedStep {
            phase: "tls_hop_comparison",
            latest_probe_target: Some(result.target.clone()),
            message: result.target.clone(),
            latest_probe_outcome: Some(result.outcome.clone()),
            artifacts: RunnerArtifacts::from_probe(result, "tls_hop_comparison", &plan.request.path_mode),
        }];
        // Acquire observes the scan owner's published cancellation flag.
        if cancel.load(Ordering::Acquire) {
            CollectedStageOutcome::Cancelled(steps)
        } else {
            CollectedStageOutcome::Completed(steps)
        }
    }
}
