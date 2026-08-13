use crate::engine::report::{ReportBuildContext, build_report};
use crate::types::{ScanCompletionKind, ScanKind};

use super::super::plan::ExecutionPlan;
use super::super::state::ExecutionRuntime;
use super::cancelled_run_summary;

pub(in crate::engine) fn publish_partial_run_checkpoint(plan: &ExecutionPlan, runtime: &ExecutionRuntime) {
    if plan.request.kind != ScanKind::Connectivity || (runtime.results.is_empty() && runtime.observations.is_empty()) {
        return;
    }

    let mut report = build_report(
        ReportBuildContext {
            session_id: plan.session_id.clone(),
            request: plan.request.clone(),
            started_at: plan.started_at,
            execution_plan: Some(plan.snapshot()),
        },
        cancelled_run_summary(true).to_string(),
        runtime.results.clone(),
        runtime.observations.clone(),
        None,
        None,
    );
    report.completion_kind = ScanCompletionKind::PartialResults;
    let mut shared = runtime.shared.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    shared.checkpoint_report = Some(report);
}
