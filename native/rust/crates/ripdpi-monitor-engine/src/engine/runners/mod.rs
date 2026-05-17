mod connectivity;
mod parity;
mod strategy;

use std::sync::Arc;

use connectivity::*;
use strategy::{StrategyDnsBaselineRunner, StrategyQuicRunner, StrategyRecommendationRunner, StrategyTcpRunner};

use crate::CandidateRuntimeLauncher;

use super::runtime::ExecutionCoordinator;
pub(super) use parity::connectivity_runner_parity_snapshot;

pub(super) fn execution_coordinator(
    candidate_runtime_launcher: Arc<dyn CandidateRuntimeLauncher>,
) -> ExecutionCoordinator {
    ExecutionCoordinator::new(vec![
        Box::new(EnvironmentRunner),
        Box::new(DnsRunner),
        Box::new(WebRunner),
        Box::new(QuicRunner),
        Box::new(TcpRunner),
        Box::new(ServiceRunner),
        Box::new(CircumventionRunner),
        Box::new(TelegramRunner),
        Box::new(ThroughputRunner),
        Box::new(StrategyDnsBaselineRunner),
        Box::new(StrategyTcpRunner::new(candidate_runtime_launcher.clone())),
        Box::new(StrategyQuicRunner::new(candidate_runtime_launcher)),
        Box::new(StrategyRecommendationRunner),
    ])
}
