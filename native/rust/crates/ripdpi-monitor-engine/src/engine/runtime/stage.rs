mod id;
mod runner;

use std::sync::Arc;
use std::sync::atomic::AtomicBool;

use rustls::client::danger::ServerCertVerifier;

use super::plan::ExecutionPlan;
use super::recording::CollectedStageOutcome;
use super::state::ExecutionRuntime;

pub(in crate::engine) use id::ExecutionStageId;

pub(in crate::engine) enum RunnerOutcome {
    Completed,
    Cancelled,
    Finished,
    Failed(String),
}

pub(in crate::engine) trait ExecutionStageRunner {
    fn id(&self) -> ExecutionStageId;

    fn phase(&self) -> &'static str;

    fn total_steps(&self, plan: &ExecutionPlan) -> usize;

    fn run(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome {
        runner::run(self, plan, runtime, tls_verifier)
    }

    /// Run without touching `runtime`, returning all steps collected independently.
    /// Used by the parallel connectivity runner path.
    /// Returns collected partial steps alongside a cancellation marker if the
    /// runner was cancelled mid-stage.
    fn run_collecting(
        &self,
        _plan: &ExecutionPlan,
        _cancel: &AtomicBool,
        _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> CollectedStageOutcome {
        CollectedStageOutcome::Cancelled(Vec::new())
    }
}
