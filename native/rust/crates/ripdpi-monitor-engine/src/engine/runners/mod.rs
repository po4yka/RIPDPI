mod connectivity;
mod strategy;

use std::sync::atomic::AtomicBool;
use std::sync::Arc;

use connectivity::{
    CircumventionRunner, DnsRunner, EnvironmentRunner, QuicRunner, ServiceRunner, TcpRunner, TelegramRunner,
    ThroughputRunner, WebRunner,
};
use strategy::{StrategyDnsBaselineRunner, StrategyQuicRunner, StrategyRecommendationRunner, StrategyTcpRunner};

use crate::CandidateRuntimeLauncher;

use super::runtime::{CollectedStageOutcome, ExecutionCoordinator, ExecutionStageRunner};

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

// ---------------------------------------------------------------------------
// Behavioral parity snapshot (row-73 acceptance criteria).
// Kept in this module because the runner structs are only visible here.
// ---------------------------------------------------------------------------

use super::contract_fixture::{RunnerParityRecord, RunnerStepSnapshot};

/// Run all 9 connectivity runners against a deterministic no-network fixture
/// plan (empty target lists, no `network_snapshot`, no `telegram_target`) and
/// return one [`RunnerParityRecord`] per runner in canonical stage order.
///
/// No network connections are made. The result is fully deterministic and
/// suitable for checking into version control as a golden snapshot.
pub(super) fn connectivity_runner_parity_snapshot(plan: &super::runtime::ExecutionPlan) -> Vec<RunnerParityRecord> {
    let cancel = AtomicBool::new(false);

    // Canonical stage order (Environment first, then the 8 probe families).
    let runners: &[(&str, &dyn ExecutionStageRunner)] = &[
        ("Environment", &EnvironmentRunner),
        ("Dns", &DnsRunner),
        ("Web", &WebRunner),
        ("Quic", &QuicRunner),
        ("Tcp", &TcpRunner),
        ("Service", &ServiceRunner),
        ("Circumvention", &CircumventionRunner),
        ("Telegram", &TelegramRunner),
        ("Throughput", &ThroughputRunner),
    ];

    runners
        .iter()
        .map(|(stage_id, runner)| {
            let total_steps = runner.total_steps(plan);
            let collected = runner.run_collecting(plan, &cancel, None);
            let (outcome, raw_steps) = match collected {
                CollectedStageOutcome::Completed(steps) => ("Completed", steps),
                CollectedStageOutcome::Cancelled(steps) => ("Cancelled", steps),
            };
            let steps = raw_steps
                .into_iter()
                .map(|s| RunnerStepSnapshot {
                    phase: s.phase.to_string(),
                    message: s.message,
                    latest_probe_target: s.latest_probe_target,
                    latest_probe_outcome: s.latest_probe_outcome,
                })
                .collect();
            RunnerParityRecord {
                stage_id: stage_id.to_string(),
                phase: runner.phase().to_string(),
                total_steps,
                outcome: outcome.to_string(),
                steps,
            }
        })
        .collect()
}
