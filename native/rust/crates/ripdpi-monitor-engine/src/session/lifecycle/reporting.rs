use std::sync::atomic::Ordering;
use std::time::Instant;

use crate::types::{ScanCompletionKind, ScanReportDisposition, ScanTerminationReason};

use super::super::reaper::WORKER_REAPER;
use super::super::wire_json::{passive_events_to_json, progress_to_json, report_to_json};
use super::super::worker::join_finished_worker_locked;
use super::MonitorSession;

impl MonitorSession {
    pub fn cancel_scan(&self) {
        let reason = self.scan_control.lock().ok().map_or(ScanTerminationReason::UserCancelled, |scan_control| {
            if scan_control.deadline.is_some_and(|deadline| Instant::now() >= deadline) {
                ScanTerminationReason::DeadlineExceeded
            } else {
                ScanTerminationReason::UserCancelled
            }
        });
        if let Ok(mut cancellation_reason) = self.cancellation_reason.lock()
            && cancellation_reason.is_none()
        {
            *cancellation_reason = Some(reason);
        }
        self.cancel.store(true, Ordering::Release);
    }

    pub fn poll_progress_json(&self) -> Result<Option<String>, String> {
        let shared = self.lock_shared_state_recovering();
        progress_to_json(shared.progress.as_ref())
    }

    pub fn take_report_json(&self) -> Result<Option<String>, String> {
        self.try_join_worker();
        let mut scan_control = self.scan_control.lock().map_err(|_| "monitor scan control poisoned".to_string())?;
        if scan_control.terminal_report_delivered {
            return Ok(None);
        }
        // Ordering: observes cancellation published by cancel_scan before exposing a checkpoint.
        let cancellation_requested = self.cancel.load(Ordering::Acquire);
        let cancellation_reason =
            self.cancellation_reason.lock().map_err(|_| "monitor cancellation state poisoned".to_string())?.clone();
        let mut shared = self.lock_shared_state_recovering();
        let scan_finished = shared.progress.as_ref().is_none_or(|progress| progress.is_finished);
        if !scan_finished && !cancellation_requested {
            return Ok(None);
        }
        if cancellation_requested && !scan_finished {
            if scan_control.checkpoint_report_delivered {
                return Ok(None);
            }
            let mut checkpoint = shared.checkpoint_report.take();
            if let Some(report) = checkpoint.as_mut() {
                report.completion_kind = ScanCompletionKind::PartialResults;
                report.report_disposition = ScanReportDisposition::Checkpoint;
                report.termination_reason = cancellation_reason;
                report.finished_at = crate::util::now_ms();
            }
            let json = report_to_json(checkpoint.as_ref())?;
            scan_control.checkpoint_report_delivered = json.is_some();
            return Ok(json);
        }
        let report = shared.report.take();
        let json = report_to_json(report.as_ref())?;
        scan_control.terminal_report_delivered = json.is_some();
        Ok(json)
    }

    pub fn poll_passive_events_json(&self) -> Result<Option<String>, String> {
        let session_id = self.active_session_id.lock().map_err(|_| "monitor session id poisoned".to_string())?.clone();
        let events = session_id.as_deref().map(|id| self.platform_bridge.drain_passive_events(id)).unwrap_or_default();
        passive_events_to_json(events)
    }

    /// Cancel the active scan and retire its worker without blocking the caller.
    ///
    /// An unfinished worker is joined by the process-wide diagnostics reaper.
    /// This keeps JNI teardown bounded even when a probe is inside blocking I/O;
    /// the worker still owns its state until it exits and is reaped.
    pub fn destroy(&self) {
        self.cancel_scan();
        let handle = self.worker.lock().ok().and_then(|mut worker_guard| worker_guard.take());
        if let Some(handle) = handle
            && let Err(handle) = WORKER_REAPER.reap(handle)
        {
            log::error!("detaching diagnostics worker because the bounded reaper is saturated or unavailable");
            drop(handle);
        }
    }

    fn try_join_worker(&self) {
        let Ok(mut worker_guard) = self.worker.lock() else {
            return;
        };
        join_finished_worker_locked(&mut worker_guard);
    }
}
