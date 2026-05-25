use std::sync::{Arc, Mutex};

use crate::connectivity::{push_event, set_progress, set_report};
use crate::engine::report::build_report;
use crate::engine::runners::prepare_strategy_probe_report;
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
    mut runtime: ExecutionRuntime,
) {
    if runtime.strategy.strategy_probe_report.is_none() {
        prepare_strategy_probe_report(plan, &mut runtime);
    }
    let strategy_probe_report = runtime.strategy.strategy_probe_report.take();
    let has_partial_results =
        !runtime.results.is_empty() || !runtime.observations.is_empty() || strategy_probe_report.is_some();
    let summary = cancelled_run_summary(has_partial_results).to_string();
    let report = build_report(
        plan.session_id.clone(),
        plan.request.clone(),
        plan.started_at,
        summary,
        runtime.results,
        runtime.observations,
        strategy_probe_report,
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
