mod artifacts;
mod cancellation;
mod coordinator;
pub(crate) mod panic_recovery;
mod parallel;
mod plan;
mod progress;
mod recording;
mod stage;
mod state;

#[cfg(test)]
pub(super) use super::strategy_plan::StrategyExecutionPlan;
pub(super) use artifacts::RunnerArtifacts;
#[cfg(test)]
pub(super) use cancellation::cancelled_run_summary;
pub(super) use cancellation::publish_cancelled_run;
pub(super) use coordinator::ExecutionCoordinator;
pub(super) use plan::ExecutionPlan;
pub(super) use recording::{CollectedStageOutcome, CollectedStep};
pub(super) use stage::{ExecutionStageId, ExecutionStageRunner, RunnerOutcome};
pub(super) use state::ExecutionRuntime;

#[cfg(test)]
mod tests {
    include!("runtime_tests.rs");
}
