mod artifacts;
mod cancellation;
mod coordinator;
mod plan;
mod progress;
mod stage;
mod state;

pub(super) use artifacts::{CollectedStageOutcome, CollectedStep, RunnerArtifacts};
#[cfg(test)]
pub(super) use cancellation::cancelled_run_summary;
pub(super) use cancellation::publish_cancelled_run;
pub(super) use coordinator::ExecutionCoordinator;
pub(super) use plan::{ExecutionPlan, StrategyExecutionPlan};
pub(super) use stage::{ExecutionStageId, ExecutionStageRunner, RunnerOutcome};
pub(super) use state::ExecutionRuntime;

#[cfg(test)]
mod tests {
    include!("runtime_tests.rs");
}
