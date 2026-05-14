use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::engine::runtime::{CollectedStageOutcome, CollectedStep, ExecutionPlan, RunnerArtifacts};
use crate::types::ProbeResult;

pub(super) trait ConnectivityProbeFamily {
    type Target: Clone;

    const PHASE: &'static str;
    const ARTIFACT_SOURCE: &'static str;

    fn targets(plan: &ExecutionPlan) -> Vec<Self::Target>;
    fn message(target: &Self::Target) -> String;
    fn latest_target(target: &Self::Target) -> String {
        Self::message(target)
    }
    fn run_probe(
        target: &Self::Target,
        plan: &ExecutionPlan,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> ProbeResult;
}

pub(super) fn target_count<F: ConnectivityProbeFamily>(plan: &ExecutionPlan) -> usize {
    F::targets(plan).len()
}

pub(super) fn collect_family_steps<F: ConnectivityProbeFamily>(
    plan: &ExecutionPlan,
    cancel: &AtomicBool,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> CollectedStageOutcome {
    let targets = F::targets(plan);
    let mut steps = Vec::with_capacity(targets.len());
    for target in targets {
        if cancel.load(Ordering::Acquire) {
            return CollectedStageOutcome::Cancelled(steps);
        }
        let message = F::message(&target);
        let latest_target = F::latest_target(&target);
        let probe = F::run_probe(&target, plan, tls_verifier);
        let outcome = probe.outcome.clone();
        let artifacts = RunnerArtifacts::from_probe(probe, F::ARTIFACT_SOURCE, &plan.request.path_mode);
        steps.push(CollectedStep {
            phase: F::PHASE,
            message,
            latest_probe_target: Some(latest_target),
            latest_probe_outcome: Some(outcome),
            artifacts,
        });
        if cancel.load(Ordering::Acquire) {
            return CollectedStageOutcome::Cancelled(steps);
        }
    }
    CollectedStageOutcome::Completed(steps)
}
