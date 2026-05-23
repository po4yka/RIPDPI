use std::sync::atomic::AtomicBool;

use super::connectivity::{
    CircumventionRunner, DnsRunner, EnvironmentRunner, QuicRunner, ServiceRunner, TcpRunner, TelegramRunner,
    ThroughputRunner, WebRunner,
};
use crate::engine::contract_fixture::{RunnerParityRecord, RunnerStepSnapshot};
use crate::engine::runtime::{CollectedStageOutcome, ExecutionPlan, ExecutionStageRunner};

/// Run all connectivity runners against a deterministic no-network fixture plan.
pub(in crate::engine) fn connectivity_runner_parity_snapshot(plan: &ExecutionPlan) -> Vec<RunnerParityRecord> {
    let cancel = AtomicBool::new(false);
    runners().iter().map(|(stage_id, runner)| record_for_runner(plan, &cancel, stage_id, *runner)).collect()
}

fn runners() -> Vec<(&'static str, &'static dyn ExecutionStageRunner)> {
    vec![
        ("Environment", &EnvironmentRunner),
        ("Dns", &DnsRunner),
        ("Web", &WebRunner),
        ("Quic", &QuicRunner),
        ("Tcp", &TcpRunner),
        ("Service", &ServiceRunner),
        ("Circumvention", &CircumventionRunner),
        ("Telegram", &TelegramRunner),
        ("Throughput", &ThroughputRunner),
    ]
}

fn record_for_runner(
    plan: &ExecutionPlan,
    cancel: &AtomicBool,
    stage_id: &str,
    runner: &dyn ExecutionStageRunner,
) -> RunnerParityRecord {
    let total_steps = runner.total_steps(plan);
    let (outcome, raw_steps) = match runner.run_collecting(plan, cancel, None) {
        CollectedStageOutcome::Completed(steps) => ("Completed", steps),
        CollectedStageOutcome::Cancelled(steps) => ("Cancelled", steps),
    };
    RunnerParityRecord {
        stage_id: stage_id.to_string(),
        phase: runner.phase().to_string(),
        total_steps,
        outcome: outcome.to_string(),
        steps: raw_steps.into_iter().map(RunnerStepSnapshot::from).collect(),
    }
}

impl From<crate::engine::runtime::CollectedStep> for RunnerStepSnapshot {
    fn from(step: crate::engine::runtime::CollectedStep) -> Self {
        Self {
            phase: step.phase.to_string(),
            message: step.message,
            latest_probe_target: step.latest_probe_target,
            latest_probe_outcome: step.latest_probe_outcome,
        }
    }
}
