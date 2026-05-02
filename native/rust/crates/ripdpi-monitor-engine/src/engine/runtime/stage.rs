use std::sync::atomic::AtomicBool;
use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use super::artifacts::CollectedStep;
use super::plan::ExecutionPlan;
use super::state::ExecutionRuntime;

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub(in crate::engine) enum ExecutionStageId {
    Environment,
    Dns,
    Web,
    Quic,
    Tcp,
    Service,
    Circumvention,
    Telegram,
    Throughput,
    StrategyDnsBaseline,
    StrategyTcpCandidates,
    StrategyQuicCandidates,
    StrategyRecommendation,
}

pub(in crate::engine) enum RunnerOutcome {
    Completed,
    Cancelled,
    Finished,
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
        let Some(steps) = self.run_collecting(plan, runtime.cancel_token(), tls_verifier) else {
            return RunnerOutcome::Cancelled;
        };
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
        RunnerOutcome::Completed
    }

    /// Run without touching `runtime`, returning all steps collected independently.
    /// Used by the parallel connectivity runner path.
    /// Returns `None` if the runner was cancelled mid-way.
    fn run_collecting(
        &self,
        _plan: &ExecutionPlan,
        _cancel: &AtomicBool,
        _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> Option<Vec<CollectedStep>> {
        None
    }
}
