use std::sync::{Arc, Mutex};

use crate::connectivity::{push_event, set_report};
use crate::engine::report::{ReportBuildContext, build_report};
use crate::engine::runners::prepare_strategy_probe_report;
use crate::types::{
    CandidateRuntimeCleanupReceipt, ScanCompletionKind, ScanReportDisposition, ScanTerminationReason, SharedState,
};

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
    captured_reason: Option<ScanTerminationReason>,
    cleanup_receipt: Option<CandidateRuntimeCleanupReceipt>,
) {
    if runtime.strategy.strategy_probe_report.is_none() {
        prepare_strategy_probe_report(plan, &mut runtime);
    }
    let strategy_probe_report = runtime.strategy.strategy_probe_report.take();
    let has_partial_results =
        !runtime.results.is_empty() || !runtime.observations.is_empty() || strategy_probe_report.is_some();
    let summary = cancelled_run_summary(has_partial_results).to_string();
    let termination_reason = captured_reason.unwrap_or_else(|| {
        if runtime.is_past_deadline() {
            ScanTerminationReason::DeadlineExceeded
        } else {
            ScanTerminationReason::UserCancelled
        }
    });
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
    report.termination_reason = Some(termination_reason);
    report.candidate_runtime_cleanup = cleanup_receipt;
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

pub(in crate::engine) fn publish_partial_run_checkpoint(plan: &ExecutionPlan, runtime: &ExecutionRuntime) {
    if runtime.results.is_empty() && runtime.observations.is_empty() && runtime.strategy.strategy_probe_report.is_none()
    {
        return;
    }

    let mut report = build_report(
        ReportBuildContext {
            session_id: plan.session_id.clone(),
            request: plan.request.clone(),
            started_at: plan.started_at,
            execution_plan: Some(plan.snapshot(runtime.stage_executions())),
        },
        cancelled_run_summary(true).to_string(),
        runtime.results.clone(),
        runtime.observations.clone(),
        runtime.strategy.strategy_probe_report.clone(),
        None,
    );
    report.completion_kind = ScanCompletionKind::PartialResults;
    report.report_disposition = ScanReportDisposition::Checkpoint;
    let mut shared = runtime.shared.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    shared.checkpoint_report = Some(report);
}
