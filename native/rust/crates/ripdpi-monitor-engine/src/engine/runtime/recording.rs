use super::artifacts::RunnerArtifacts;
use super::plan::ExecutionPlan;
use super::stage::RunnerOutcome;
use super::state::ExecutionRuntime;
use crate::types::{ProbeResult, ScanPathMode};

/// A single recorded step collected outside of `ExecutionRuntime`, used by the
/// parallel runner path to accumulate results without shared mutable state.
pub(in crate::engine) struct CollectedStep {
    pub(in crate::engine) phase: &'static str,
    pub(in crate::engine) message: String,
    pub(in crate::engine) latest_probe_target: Option<String>,
    pub(in crate::engine) latest_probe_outcome: Option<String>,
    pub(in crate::engine) artifacts: RunnerArtifacts,
}

impl CollectedStep {
    pub(in crate::engine) fn from_probe(
        phase: &'static str,
        message: String,
        probe: ProbeResult,
        source: &str,
        path_mode: &ScanPathMode,
    ) -> Self {
        Self {
            phase,
            latest_probe_target: Some(message.clone()),
            message,
            latest_probe_outcome: Some(probe.outcome.clone()),
            artifacts: RunnerArtifacts::from_probe(probe, source, path_mode),
        }
    }
}

pub(in crate::engine) enum CollectedStageOutcome {
    Completed(Vec<CollectedStep>),
    Cancelled(Vec<CollectedStep>),
}

pub(in crate::engine) fn record_steps(plan: &ExecutionPlan, runtime: &mut ExecutionRuntime, steps: Vec<CollectedStep>) {
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

pub(in crate::engine) fn record_collected_outcome(
    plan: &ExecutionPlan,
    runtime: &mut ExecutionRuntime,
    outcome: CollectedStageOutcome,
) -> RunnerOutcome {
    match outcome {
        CollectedStageOutcome::Completed(steps) => {
            record_steps(plan, runtime, steps);
            RunnerOutcome::Completed
        }
        CollectedStageOutcome::Cancelled(steps) => {
            record_steps(plan, runtime, steps);
            RunnerOutcome::Cancelled
        }
    }
}
