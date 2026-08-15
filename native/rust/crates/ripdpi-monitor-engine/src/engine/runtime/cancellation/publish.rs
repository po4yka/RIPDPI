use std::sync::{Arc, Mutex};

use crate::connectivity::{push_event, set_report};
use crate::engine::report::{ReportBuildContext, build_report};
use crate::engine::runners::prepare_strategy_probe_report;
use crate::types::{ScanCompletionKind, ScanTerminationReason, SharedState};

use super::super::plan::ExecutionPlan;
use super::super::state::ExecutionRuntime;
use super::progress;

pub(in crate::engine) fn cancelled_run_summary(has_partial_results: bool) -> &'static str {
    if has_partial_results { "Scan completed with partial results" } else { "Scan cancelled" }
}

pub(in crate::engine) fn publish_cancelled_run(
    plan: &ExecutionPlan,
    shared: &Arc<Mutex<SharedState>>,
    mut runtime: ExecutionRuntime,
) {
    if runtime.strategy.strategy_probe_report.is_none() {
        prepare_strategy_probe_report(plan, &mut runtime);
    }
    let strategy_probe_report = runtime.strategy.strategy_probe_report.take();
    let has_partial_results =
        !runtime.results.is_empty() || !runtime.observations.is_empty() || strategy_probe_report.is_some();
    let summary = cancelled_run_summary(has_partial_results).to_string();
    let deadline_exceeded = runtime.is_past_deadline();
    let mut report = build_report(
        ReportBuildContext {
            session_id: plan.session_id.clone(),
            request: plan.request.clone(),
            started_at: plan.started_at,
            execution_plan: Some(plan.snapshot(runtime.stage_executions())),
        },
        summary,
        runtime.results,
        runtime.observations,
        strategy_probe_report,
        None,
    );
    report.completion_kind =
        if has_partial_results { ScanCompletionKind::PartialResults } else { ScanCompletionKind::Terminated };
    report.termination_reason = Some(if deadline_exceeded {
        ScanTerminationReason::DeadlineExceeded
    } else {
        ScanTerminationReason::UserCancelled
    });
    set_report(shared, report);
    push_event(
        shared,
        &plan.session_id,
        &plan.request.profile_id,
        &plan.request.path_mode,
        "engine",
        "warn",
        "Diagnostics cancelled".to_string(),
    );
    progress::publish_cancelled_progress(plan, shared, runtime.completed_steps);
}
