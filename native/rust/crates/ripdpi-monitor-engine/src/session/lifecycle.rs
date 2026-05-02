use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;

use rustls::client::danger::ServerCertVerifier;

use crate::execution::UnavailableCandidateRuntimeLauncher;
use crate::platform::NoopMonitorPlatformBridge;
use crate::types::{EngineScanRequestWire, SharedState};
use crate::{CandidateRuntimeLauncher, MonitorPlatformBridge};

use super::log_level::native_log_level_from_str;
use super::validation::validate_scan_request;
use super::wire_json::{passive_events_to_json, progress_to_json, report_to_json};
use super::worker::{join_finished_worker_locked, spawn_scan_worker};

pub struct MonitorSession {
    pub(super) shared: Arc<Mutex<SharedState>>,
    pub(super) cancel: Arc<AtomicBool>,
    pub(super) worker: Mutex<Option<JoinHandle<()>>>,
    pub(super) tls_verifier: Option<Arc<dyn ServerCertVerifier>>,
    pub(super) platform_bridge: Arc<dyn MonitorPlatformBridge>,
    pub(super) candidate_runtime_launcher: Arc<dyn CandidateRuntimeLauncher>,
}

impl Default for MonitorSession {
    fn default() -> Self {
        Self::new()
    }
}

impl MonitorSession {
    pub fn new() -> Self {
        Self::with_parts(None, Arc::new(NoopMonitorPlatformBridge), Arc::new(UnavailableCandidateRuntimeLauncher))
    }

    pub fn with_platform_bridge(platform_bridge: Arc<dyn MonitorPlatformBridge>) -> Self {
        Self::with_parts(None, platform_bridge, Arc::new(UnavailableCandidateRuntimeLauncher))
    }

    pub fn with_tls_verifier(tls_verifier: Option<Arc<dyn ServerCertVerifier>>) -> Self {
        Self::with_parts(
            tls_verifier,
            Arc::new(NoopMonitorPlatformBridge),
            Arc::new(UnavailableCandidateRuntimeLauncher),
        )
    }

    pub fn with_candidate_runtime_launcher(candidate_runtime_launcher: Arc<dyn CandidateRuntimeLauncher>) -> Self {
        Self::with_parts(None, Arc::new(NoopMonitorPlatformBridge), candidate_runtime_launcher)
    }

    pub fn with_platform_bridge_and_candidate_runtime_launcher(
        platform_bridge: Arc<dyn MonitorPlatformBridge>,
        candidate_runtime_launcher: Arc<dyn CandidateRuntimeLauncher>,
    ) -> Self {
        Self::with_parts(None, platform_bridge, candidate_runtime_launcher)
    }

    fn with_parts(
        tls_verifier: Option<Arc<dyn ServerCertVerifier>>,
        platform_bridge: Arc<dyn MonitorPlatformBridge>,
        candidate_runtime_launcher: Arc<dyn CandidateRuntimeLauncher>,
    ) -> Self {
        Self {
            shared: Arc::new(Mutex::new(SharedState::default())),
            cancel: Arc::new(AtomicBool::new(false)),
            worker: Mutex::new(None),
            tls_verifier,
            platform_bridge,
            candidate_runtime_launcher,
        }
    }

    pub fn start_scan(&self, session_id: String, request: EngineScanRequestWire) -> Result<(), String> {
        validate_scan_request(&request)?;
        let native_log_level = request
            .native_log_level
            .as_deref()
            .map(|value| {
                native_log_level_from_str(value)
                    .ok_or_else(|| format!("Unsupported diagnostics nativeLogLevel: {value}"))
            })
            .transpose()?;
        let mut worker_guard = self.worker.lock().map_err(|_| "monitor worker poisoned".to_string())?;
        join_finished_worker_locked(&mut worker_guard);
        if worker_guard.is_some() {
            return Err("diagnostics scan already running".to_string());
        }
        self.cancel.store(false, Ordering::Release);
        self.platform_bridge.clear_passive_events();
        {
            let mut shared = self.shared.lock().map_err(|_| "monitor shared state poisoned".to_string())?;
            shared.progress = None;
            shared.report = None;
            shared.log_context = request.log_context.clone();
        }
        let domain_request = request.into();
        *worker_guard = Some(spawn_scan_worker(
            self.shared.clone(),
            self.cancel.clone(),
            session_id,
            domain_request,
            self.tls_verifier.clone(),
            self.platform_bridge.clone(),
            self.candidate_runtime_launcher.clone(),
            native_log_level,
        ));
        Ok(())
    }

    pub fn cancel_scan(&self) {
        self.cancel.store(true, Ordering::Release);
    }

    pub fn poll_progress_json(&self) -> Result<Option<String>, String> {
        let shared = self.shared.lock().map_err(|_| "monitor shared state poisoned".to_string())?;
        progress_to_json(shared.progress.as_ref())
    }

    pub fn take_report_json(&self) -> Result<Option<String>, String> {
        self.try_join_worker();
        let shared = self.shared.lock().map_err(|_| "monitor shared state poisoned".to_string())?;
        report_to_json(shared.report.as_ref())
    }

    pub fn poll_passive_events_json(&self) -> Result<Option<String>, String> {
        passive_events_to_json(self.platform_bridge.drain_passive_events())
    }

    pub fn destroy(&self) {
        self.cancel_scan();
        self.try_join_worker();
    }

    fn try_join_worker(&self) {
        let Ok(mut worker_guard) = self.worker.lock() else {
            return;
        };
        join_finished_worker_locked(&mut worker_guard);
        if let Some(handle) = worker_guard.take() {
            let _ = handle.join();
        }
    }
}
