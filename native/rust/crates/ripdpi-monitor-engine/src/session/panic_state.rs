use std::sync::{Arc, Mutex};

use crate::engine::{ReportBuildContext, build_report, panic_payload_message};
use crate::types::{
    ProbeDetail, ProbeResult, ScanCompletionKind, ScanProgress, ScanReportDisposition, ScanRequest,
    ScanTerminationReason, SharedState,
};

pub(super) fn record_panic_terminal_state(
    shared: Arc<Mutex<SharedState>>,
    session_id: String,
    request: ScanRequest,
    started_at: u64,
    panic_payload: Box<dyn std::any::Any + Send>,
) {
    let msg = panic_payload_message(&*panic_payload);
    let panic_result = ProbeResult {
        probe_type: "diagnostics_engine".to_string(),
        target: request.profile_id.clone(),
        outcome: "worker_panicked".to_string(),
        details: vec![ProbeDetail { key: "error".to_string(), value: msg.clone() }],
    };
    let mut panic_report = build_report(
        ReportBuildContext { session_id: session_id.clone(), request, started_at, execution_plan: None },
        "Diagnostics failed: internal worker error".to_string(),
        vec![panic_result.clone()],
        Vec::new(),
        None,
        None,
    );
    panic_report.completion_kind = ScanCompletionKind::Terminated;
    panic_report.termination_reason = Some(ScanTerminationReason::WorkerPanicked);
    let mut state = shared.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    if state.report.is_none()
        && let Some(checkpoint_report) = state.checkpoint_report.take()
    {
        state.report = Some(checkpoint_report);
    }
    if let Some(report) = state.report.as_mut() {
        if !report.results.iter().any(|result| result.outcome == "worker_panicked") {
            report.results.push(panic_result);
        }
        report.finished_at = crate::util::now_ms();
        report.summary = "Diagnostics failed: internal worker error".to_string();
        report.report_disposition = ScanReportDisposition::Terminal;
        report.completion_kind = ScanCompletionKind::PartialResults;
        report.termination_reason = Some(ScanTerminationReason::WorkerPanicked);
    } else {
        state.report = Some(panic_report);
    }
    let (completed_steps, total_steps) =
        state.progress.as_ref().map_or((1, 1), |progress| (progress.completed_steps, progress.total_steps.max(1)));
    state.progress = Some(ScanProgress {
        session_id,
        phase: "error".to_string(),
        completed_steps,
        total_steps,
        message: format!("Internal error: {msg}"),
        is_finished: true,
        latest_probe_target: None,
        latest_probe_outcome: Some("worker_panicked".to_string()),
        strategy_probe_progress: None,
    });
}
