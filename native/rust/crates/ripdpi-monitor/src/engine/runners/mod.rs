mod connectivity;
mod strategy;

use connectivity::{
    CircumventionRunner, DnsRunner, EnvironmentRunner, QuicRunner, ServiceRunner, TcpRunner, TelegramRunner,
    ThroughputRunner, WebRunner,
};
use strategy::{StrategyDnsBaselineRunner, StrategyQuicRunner, StrategyRecommendationRunner, StrategyTcpRunner};

use super::runtime::ExecutionCoordinator;

pub(super) fn execution_coordinator() -> ExecutionCoordinator {
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
        Box::new(StrategyTcpRunner),
        Box::new(StrategyQuicRunner),
        Box::new(StrategyRecommendationRunner),
    ])
}
