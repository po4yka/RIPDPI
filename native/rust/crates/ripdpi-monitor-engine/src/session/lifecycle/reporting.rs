use std::sync::atomic::Ordering;

use super::super::wire_json::{passive_events_to_json, progress_to_json, report_to_json};
use super::super::worker::join_finished_worker_locked;
use super::MonitorSession;

impl MonitorSession {
    pub fn poll_progress_json(&self) -> Result<Option<String>, String> {
        let shared = self.shared.lock().map_err(|_| "monitor shared state poisoned".to_string())?;
        progress_to_json(shared.progress.as_ref())
    }

    pub fn take_report_json(&self) -> Result<Option<String>, String> {
        self.try_join_worker();
        let mut scan_control = self.scan_control.lock().map_err(|_| "monitor scan control poisoned".to_string())?;
        if scan_control.report_delivered {
            return Ok(None);
        }
        // Ordering: observes cancellation published by cancel_scan before exposing a checkpoint.
        let cancellation_requested = self.cancel.load(Ordering::Acquire);
        let cancellation_reason =
            self.cancellation_reason.lock().map_err(|_| "monitor cancellation state poisoned".to_string())?.clone();
        let mut shared = self.shared.lock().map_err(|_| "monitor shared state poisoned".to_string())?;
        let scan_finished = shared.progress.as_ref().is_none_or(|progress| progress.is_finished);
        if !scan_finished && !cancellation_requested {
            return Ok(None);
        }
        if cancellation_requested && !scan_finished {
            let mut checkpoint = shared.checkpoint_report.take();
            if let Some(report) = checkpoint.as_mut() {
                report.completion_kind = crate::types::ScanCompletionKind::PartialResults;
                report.termination_reason = cancellation_reason;
            }
            let json = report_to_json(checkpoint.as_ref())?;
            scan_control.report_delivered = json.is_some();
            return Ok(json);
        }
        let report = shared.report.take();
        let json = report_to_json(report.as_ref())?;
        scan_control.report_delivered = json.is_some();
        Ok(json)
    }

    pub fn poll_passive_events_json(&self) -> Result<Option<String>, String> {
        let session_id = self.active_session_id.lock().map_err(|_| "monitor session id poisoned".to_string())?.clone();
        let events = session_id.as_deref().map(|id| self.platform_bridge.drain_passive_events(id)).unwrap_or_default();
        passive_events_to_json(events)
    }

    fn try_join_worker(&self) {
        let Ok(mut worker_guard) = self.worker.lock() else {
            return;
        };
        join_finished_worker_locked(&mut worker_guard);
    }
}
