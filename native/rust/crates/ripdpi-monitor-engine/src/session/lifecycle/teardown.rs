use std::sync::atomic::Ordering;

use super::super::reaper::WORKER_REAPER;
use super::MonitorSession;

impl MonitorSession {
    /// Cancel the active scan and retire its worker without blocking the caller.
    ///
    /// An unfinished worker is joined by the process-wide diagnostics reaper.
    /// This keeps JNI teardown bounded even when a probe is inside blocking I/O;
    /// the worker still owns its state until it exits and is reaped.
    pub fn destroy(&self) {
        self.destroyed.store(true, Ordering::Release);
        self.cancel_scan();
        let handle = self.worker.lock().ok().and_then(|mut worker_guard| worker_guard.take());
        if let Some(handle) = handle
            && let Err(handle) = WORKER_REAPER.reap(handle)
        {
            log::error!("detaching diagnostics worker because the bounded reaper is saturated or unavailable");
            drop(handle);
        }
    }
}
