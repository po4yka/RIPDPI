use std::sync::atomic::Ordering;
use std::time::Instant;

use super::MonitorSession;

impl MonitorSession {
    pub fn cancel_scan(&self) {
        // Serialize cancellation with start_scan so initialization cannot clear a newly published request.
        let _worker_guard = (!self.starting.load(Ordering::Acquire))
            .then(|| self.worker.lock().unwrap_or_else(std::sync::PoisonError::into_inner));
        let reason = self.scan_control.lock().ok().map(|scan_control| {
            if scan_control.deadline.is_some_and(|deadline| Instant::now() >= deadline) {
                crate::types::ScanTerminationReason::DeadlineExceeded
            } else {
                crate::types::ScanTerminationReason::UserCancelled
            }
        });
        if let Ok(mut cancellation_reason) = self.cancellation_reason.lock()
            && cancellation_reason.is_none()
        {
            *cancellation_reason = reason;
        }
        self.cancel.store(true, Ordering::Release);
    }
}
