use std::sync::{Arc, Mutex};

use crate::connectivity::set_progress;
use crate::types::{ScanProgress, SharedState};

use super::super::plan::ExecutionPlan;

pub(in crate::engine) fn cancelled_run_summary(has_partial_results: bool) -> &'static str {
    if has_partial_results { "Scan completed with partial results" } else { "Scan cancelled" }
}

pub(super) fn publish_cancelled_progress(
    plan: &ExecutionPlan,
    shared: &Arc<Mutex<SharedState>>,
    completed_steps: usize,
) {
    set_progress(
        shared,
        ScanProgress {
            session_id: plan.session_id.clone(),
            phase: "cancelled".to_string(),
            completed_steps,
            total_steps: plan.total_steps,
            message: "Diagnostics cancelled".to_string(),
            is_finished: true,
            latest_probe_target: None,
            latest_probe_outcome: None,
            strategy_probe_progress: None,
        },
    );
}
