use std::collections::BTreeMap;
use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use super::panic_recovery::{self, JoinedStageOutcome};
use super::plan::ExecutionPlan;
use super::recording::{CollectedStageOutcome, record_steps};
use super::stage::{ExecutionStageId, ExecutionStageRunner, RunnerOutcome};
use super::state::ExecutionRuntime;

type RunnerMap = BTreeMap<ExecutionStageId, Box<dyn ExecutionStageRunner + Send + Sync>>;

const CONNECTIVITY_PARALLEL_GROUP: &[ExecutionStageId] =
    &[ExecutionStageId::Dns, ExecutionStageId::Tcp, ExecutionStageId::Quic];

pub(super) fn is_connectivity_parallel_stage(stage: &ExecutionStageId) -> bool {
    CONNECTIVITY_PARALLEL_GROUP.contains(stage)
}

pub(super) fn is_connectivity_parallel_plan_stage(plan: &ExecutionPlan, stage: &ExecutionStageId) -> bool {
    matches!(plan.request.kind, crate::types::ScanKind::Connectivity) && is_connectivity_parallel_stage(stage)
}

pub(super) fn runnable_connectivity_parallel_stages<'a>(
    plan: &'a ExecutionPlan,
    runners: &RunnerMap,
) -> Vec<&'a ExecutionStageId> {
    plan.stage_order
        .iter()
        .filter(|candidate| {
            is_connectivity_parallel_stage(candidate)
                && runners.get(candidate).is_some_and(|runner| runner.total_steps(plan) > 0)
        })
        .collect()
}

pub(super) fn total_steps(stages: &[&ExecutionStageId], runners: &RunnerMap, plan: &ExecutionPlan) -> usize {
    stages.iter().filter_map(|stage| runners.get(stage)).map(|runner| runner.total_steps(plan)).sum::<usize>()
}

pub(super) fn run_connectivity_group(
    plan: &ExecutionPlan,
    runtime: &mut ExecutionRuntime,
    runners: &RunnerMap,
    stages: &[&ExecutionStageId],
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> RunnerOutcome {
    let thread_results = std::thread::scope(|scope| {
        let mut handles = Vec::with_capacity(stages.len());
        for stage in stages {
            let runner = runners.get(stage).expect("runner present");
            let cancel = runtime.cancel_token();
            let deadline = match (runtime.scan_deadline(), runtime.stage_deadline()) {
                (Some(scan), Some(stage)) => Some(scan.min(stage)),
                (scan, stage) => scan.or(stage),
            };
            handles.push(scope.spawn(move || {
                ripdpi_diagnostics_contracts::util::with_scan_io_deadline(deadline, || {
                    runner.run_collecting(plan, cancel, tls_verifier)
                })
            }));
        }
        handles.into_iter().map(|handle| panic_recovery::classify(handle.join())).collect::<Vec<_>>()
    });

    let mut cancelled = false;
    let mut panic_message = None;
    for (stage, joined) in stages.iter().zip(thread_results) {
        let runner = runners.get(stage).expect("runner present");
        let planned_steps = runner.total_steps(plan);
        let completed_before_stage = runtime.completed_steps;
        runtime.begin_stage(stage.as_str(), planned_steps);
        let (steps, stage_cancelled) = match joined {
            JoinedStageOutcome::Collected(CollectedStageOutcome::Completed(steps)) => (steps, false),
            JoinedStageOutcome::Collected(CollectedStageOutcome::Cancelled(steps)) => (steps, true),
            JoinedStageOutcome::Panicked(message) => {
                panic_message.get_or_insert_with(|| message.clone());
                (panic_recovery::handle_panicked_runner(stage, &message), false)
            }
        };
        record_steps(plan, runtime, steps);
        let stage_budget_exhausted =
            !runtime.is_cancelled() && runtime.is_past_stage_deadline() && !runtime.is_past_deadline();
        if stage_budget_exhausted {
            runtime.record_stage_budget_skips(
                plan,
                runner.phase(),
                stage.as_str(),
                planned_steps.saturating_sub(runtime.completed_steps - completed_before_stage),
            );
        } else if stage_cancelled {
            cancelled = true;
        }
        runtime.finish_stage();
    }
    if cancelled {
        RunnerOutcome::Cancelled
    } else if let Some(message) = panic_message {
        RunnerOutcome::Failed(format!("parallel diagnostics runner panicked: {message}"))
    } else {
        RunnerOutcome::Completed
    }
}
