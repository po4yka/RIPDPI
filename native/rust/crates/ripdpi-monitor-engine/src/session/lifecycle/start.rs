use std::sync::atomic::Ordering;
use std::time::{Duration, Instant};

use crate::types::EngineScanRequestWire;

use super::super::log_level::parse_native_log_level;
use super::super::validation::ValidatedScanRequest;
use super::super::worker::{ScanWorkerConfig, join_finished_worker_locked, spawn_scan_worker};
use super::{MonitorSession, ScanControl, StartingGuard};

impl MonitorSession {
    pub fn start_scan(&self, session_id: String, request: EngineScanRequestWire) -> Result<(), String> {
        if self.destroyed.load(Ordering::Acquire) {
            return Err("diagnostics session destroyed".to_string());
        }
        let request = ValidatedScanRequest::try_from(request)?;
        let native_log_level = parse_native_log_level(request.as_wire().native_log_level.as_deref())?;
        let mut worker_guard = self.worker.lock().map_err(|_| "monitor worker poisoned".to_string())?;
        join_finished_worker_locked(&mut worker_guard);
        if worker_guard.is_some() || self.starting.load(Ordering::Acquire) {
            return Err("diagnostics scan already running".to_string());
        }
        self.cancel.store(false, Ordering::Release);
        *self.active_session_id.lock().map_err(|_| "monitor session id poisoned".to_string())? =
            Some(session_id.clone());
        let scan_deadline =
            Instant::now() + Duration::from_millis(request.as_wire().scan_deadline_ms.unwrap_or(360_000));
        *self.scan_control.lock().map_err(|_| "monitor scan control poisoned".to_string())? =
            ScanControl { deadline: Some(scan_deadline), report_delivered: false };
        *self.cancellation_reason.lock().map_err(|_| "monitor cancellation state poisoned".to_string())? = None;
        {
            let mut shared = self.shared.lock().map_err(|_| "monitor shared state poisoned".to_string())?;
            shared.progress = None;
            shared.report = None;
            shared.checkpoint_report = None;
            shared.log_context = request.as_wire().log_context.clone();
        }
        self.starting.store(true, Ordering::Release);
        let _starting_guard = StartingGuard(&self.starting);
        drop(worker_guard);
        self.platform_bridge.clear_passive_events(&session_id);
        if self.destroyed.load(Ordering::Acquire) {
            return Err("diagnostics session destroyed".to_string());
        }
        let domain_request = request.into();
        let worker_config = ScanWorkerConfig::new(
            scan_deadline,
            self.cancellation_reason.clone(),
            self.tls_verifier.clone(),
            self.platform_bridge.clone(),
            self.candidate_runtime_launcher.clone(),
            native_log_level,
        );
        let mut worker_guard = self.worker.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if self.destroyed.load(Ordering::Acquire) {
            return Err("diagnostics session destroyed".to_string());
        }
        *worker_guard = Some(spawn_scan_worker(
            self.shared.clone(),
            self.cancel.clone(),
            session_id,
            domain_request,
            worker_config,
        ));
        Ok(())
    }
}
