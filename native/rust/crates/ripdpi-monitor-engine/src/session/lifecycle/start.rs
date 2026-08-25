use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use crate::types::{ScanRequest, SharedState};
use ripdpi_diagnostics_contracts::EngineScanRequestWire;

use super::super::log_level::parse_native_log_level;
use super::super::validation::ValidatedScanRequest;
use super::super::worker::{ScanWorkerConfig, spawn_scan_worker};
use super::{MonitorSession, ScanControl};

struct StartInProgressGuard<'a> {
    admitted_scan_control: Option<&'a Mutex<ScanControl>>,
}

impl<'a> StartInProgressGuard<'a> {
    fn new() -> Self {
        Self { admitted_scan_control: None }
    }

    fn mark_admitted(&mut self, scan_control: &'a Mutex<ScanControl>) {
        self.admitted_scan_control = Some(scan_control);
    }
}

impl Drop for StartInProgressGuard<'_> {
    fn drop(&mut self) {
        let Some(scan_control) = self.admitted_scan_control.take() else {
            return;
        };
        let mut control = scan_control.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        control.start_in_progress = false;
    }
}

type ScanWorkerSpawner =
    fn(Arc<Mutex<SharedState>>, Arc<AtomicBool>, String, ScanRequest, ScanWorkerConfig) -> JoinHandle<()>;

impl MonitorSession {
    pub fn start_scan(&self, session_id: String, request: EngineScanRequestWire) -> Result<(), String> {
        self.start_scan_with_spawner(session_id, request, spawn_scan_worker)
    }

    pub(crate) fn start_scan_with_spawner(
        &self,
        session_id: String,
        request: EngineScanRequestWire,
        spawn_worker: ScanWorkerSpawner,
    ) -> Result<(), String> {
        let request = ValidatedScanRequest::try_from(request)?;
        let native_log_level = parse_native_log_level(request.as_wire().native_log_level.as_deref())?;
        {
            let mut worker_guard = self.worker.lock().map_err(|_| "monitor worker poisoned".to_string())?;
            super::super::worker::join_finished_worker_locked(&mut worker_guard);
            if worker_guard.is_some() {
                return Err("diagnostics scan already running".to_string());
            }
        }
        let scan_deadline =
            Instant::now() + Duration::from_millis(request.as_wire().scan_deadline_ms.unwrap_or(360_000));
        let log_context = request.as_wire().log_context.clone();
        let mut start_guard = StartInProgressGuard::new();
        {
            let mut active_session_id =
                self.active_session_id.lock().map_err(|_| "monitor session id poisoned".to_string())?;
            let mut scan_control = self.scan_control.lock().map_err(|_| "monitor scan control poisoned".to_string())?;
            if scan_control.start_in_progress {
                return Err("diagnostics scan already running".to_string());
            }
            *active_session_id = Some(session_id.clone());
            *scan_control = ScanControl {
                deadline: Some(scan_deadline),
                checkpoint_report_delivered: false,
                terminal_report_delivered: false,
                start_in_progress: true,
            };
            start_guard.mark_admitted(&self.scan_control);
            // Ordering: the scan-control admission barrier serializes reset before cancellation publication.
            self.cancel.store(false, Ordering::Release);
            *self.cancellation_reason.lock().map_err(|_| "monitor cancellation state poisoned".to_string())? = None;
            let mut shared = self.lock_shared_state_recovering();
            shared.progress = None;
            shared.report = None;
            shared.checkpoint_report = None;
            shared.log_context = log_context;
            shared.terminal_session_id = None;
        }
        self.platform_bridge.clear_passive_events(&session_id);
        let domain_request = request.into();
        let worker_config = ScanWorkerConfig::new(
            scan_deadline,
            self.cancellation_reason.clone(),
            self.tls_verifier.clone(),
            self.platform_bridge.clone(),
            self.candidate_runtime_launcher.clone(),
            native_log_level,
        );
        let mut worker_guard = self.worker.lock().map_err(|_| "monitor worker poisoned".to_string())?;
        super::super::worker::join_finished_worker_locked(&mut worker_guard);
        if worker_guard.is_some() {
            return Err("diagnostics scan already running".to_string());
        }
        let worker = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            spawn_worker(self.shared.clone(), self.cancel.clone(), session_id, domain_request, worker_config)
        }))
        .map_err(|_| "diagnostics worker spawn panicked".to_string())?;
        *worker_guard = Some(worker);
        drop(start_guard);
        Ok(())
    }
}
