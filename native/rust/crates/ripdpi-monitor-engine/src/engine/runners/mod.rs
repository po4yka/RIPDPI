mod connectivity;
mod parity;
mod registry;
mod strategy;

use std::sync::Arc;

use strategy::{
    StrategyConnectionConcurrencyRunner, StrategyDnsBaselineRunner, StrategyQuicRunner, StrategyRecommendationRunner,
    StrategyTcpRunner,
};

use crate::CandidateRuntimeLauncher;
use crate::execution::CandidateRuntimeSupervisor;

use super::runtime::{ExecutionCoordinator, ExecutionStageRunner};
pub(super) use parity::connectivity_runner_parity_snapshot;
pub use registry::probe_descriptors_as_json;
pub(in crate::engine) use registry::{PROBE_STAGE_REGISTRATIONS, registration_for_family};
pub(in crate::engine) use strategy::prepare_strategy_probe_report;

pub(super) fn execution_coordinator(
    candidate_runtime_launcher: Arc<dyn CandidateRuntimeLauncher>,
) -> (ExecutionCoordinator, Arc<CandidateRuntimeSupervisor>) {
    let supervisor = Arc::new(CandidateRuntimeSupervisor::default());
    // The 9 connectivity runners come from the descriptor/factory registry —
    // adding a connectivity probe is now a one-line addition to
    // `PROBE_STAGE_REGISTRATIONS`. The 4 strategy stage runners stay
    // hand-rolled here: they take a `CandidateRuntimeLauncher` ctor argument
    // and consume `StrategyCandidateSpec` inputs rather than the `Probe`
    // trait, so they are not in the registry by design.
    let mut runners: Vec<Box<dyn ExecutionStageRunner + Send + Sync>> =
        PROBE_STAGE_REGISTRATIONS.iter().map(|registration| (registration.make_runner)()).collect();
    runners.push(Box::new(StrategyDnsBaselineRunner));
    runners.push(Box::new(StrategyTcpRunner::new(candidate_runtime_launcher.clone(), supervisor.clone())));
    runners.push(Box::new(StrategyQuicRunner::new(candidate_runtime_launcher, supervisor.clone())));
    runners.push(Box::new(StrategyConnectionConcurrencyRunner));
    runners.push(Box::new(StrategyRecommendationRunner));
    (ExecutionCoordinator::new(runners), supervisor)
}
