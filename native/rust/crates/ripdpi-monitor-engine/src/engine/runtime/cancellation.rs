use std::sync::{Arc, Mutex};

use crate::connectivity::{push_event, set_progress, set_report};
use crate::engine::report::build_report;
use crate::types::{ScanProgress, SharedState};

use super::plan::ExecutionPlan;
use super::state::ExecutionRuntime;

pub(in crate::engine) fn cancelled_run_summary(has_partial_results: bool) -> &'static str {
    if has_partial_results {
        "Scan completed with partial results"
    } else {
        "Scan cancelled"
    }
}

pub(in crate::engine) fn publish_cancelled_run(
    plan: &ExecutionPlan,
    shared: &Arc<Mutex<SharedState>>,
    runtime: ExecutionRuntime,
) {
    let summary = cancelled_run_summary(!runtime.results.is_empty() || !runtime.observations.is_empty()).to_string();
    let report = build_report(
        plan.session_id.clone(),
        plan.request.clone(),
        plan.started_at,
        summary,
        runtime.results,
        runtime.observations,
        None,
        None,
    );
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
    set_progress(
        shared,
        ScanProgress {
            session_id: plan.session_id.clone(),
            phase: "cancelled".to_string(),
            completed_steps: runtime.completed_steps,
            total_steps: plan.total_steps,
            message: "Diagnostics cancelled".to_string(),
            is_finished: true,
            latest_probe_target: None,
            latest_probe_outcome: None,
            strategy_probe_progress: None,
        },
    );
}
