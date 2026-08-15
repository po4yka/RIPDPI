use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;

use rustls::client::danger::ServerCertVerifier;

use crate::execution::UnavailableCandidateRuntimeLauncher;
use crate::platform::NoopMonitorPlatformBridge;
use crate::types::{EngineScanRequestWire, SharedState};
use crate::{CandidateRuntimeLauncher, MonitorPlatformBridge};

use super::log_level::parse_native_log_level;
use super::reaper::WORKER_REAPER;
use super::validation::ValidatedScanRequest;
use super::wire_json::{passive_events_to_json, progress_to_json, report_to_json};
use super::worker::{join_finished_worker_locked, spawn_scan_worker};

pub struct MonitorSession {
    pub(super) shared: Arc<Mutex<SharedState>>,
    pub(super) cancel: Arc<AtomicBool>,
    pub(super) worker: Mutex<Option<JoinHandle<()>>>,
    active_session_id: Mutex<Option<String>>,
    pub(super) tls_verifier: Option<Arc<dyn ServerCertVerifier>>,
    pub(super) platform_bridge: Arc<dyn MonitorPlatformBridge>,
    pub(super) candidate_runtime_launcher: Arc<dyn CandidateRuntimeLauncher>,
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
            active_session_id: Mutex::new(None),
            tls_verifier,
            platform_bridge,
            candidate_runtime_launcher,
        }
    }

    pub fn start_scan(&self, session_id: String, request: EngineScanRequestWire) -> Result<(), String> {
        let request = ValidatedScanRequest::try_from(request)?;
        let native_log_level = parse_native_log_level(request.as_wire().native_log_level.as_deref())?;
        let mut worker_guard = self.worker.lock().map_err(|_| "monitor worker poisoned".to_string())?;
        join_finished_worker_locked(&mut worker_guard);
        if worker_guard.is_some() {
            return Err("diagnostics scan already running".to_string());
        }
        self.cancel.store(false, Ordering::Release);
        self.platform_bridge.clear_passive_events(&session_id);
        *self.active_session_id.lock().map_err(|_| "monitor session id poisoned".to_string())? =
            Some(session_id.clone());
        {
            let mut shared = self.shared.lock().map_err(|_| "monitor shared state poisoned".to_string())?;
            shared.progress = None;
            shared.report = None;
            shared.log_context = request.as_wire().log_context.clone();
            shared.terminal_session_id = None;
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
        let mut shared = self.shared.lock().map_err(|_| "monitor shared state poisoned".to_string())?;
        let report = shared.report.take();
        report_to_json(report.as_ref())
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::{NativeSessionEvent, ProbeResult, ScanCompletionKind, ScanPathMode, ScanReport};
    use ripdpi_telemetry::recorder::RecorderSnapshot;
    use std::sync::mpsc;
    use std::thread;
    use std::time::{Duration, Instant};

    #[derive(Default)]
    struct RecordingPlatformBridge {
        drained_session_ids: Mutex<Vec<String>>,
    }

    impl MonitorPlatformBridge for RecordingPlatformBridge {
        fn drain_passive_events(&self, session_id: &str) -> Vec<NativeSessionEvent> {
            self.drained_session_ids.lock().expect("drained session ids").push(session_id.to_string());
            vec![NativeSessionEvent {
                source: "test".to_string(),
                level: "info".to_string(),
                message: session_id.to_string(),
                created_at: 0,
                runtime_id: None,
                mode: None,
                policy_signature: None,
                fingerprint_hash: None,
                subsystem: Some("diagnostics".to_string()),
            }]
        }
    }

    #[test]
    fn passive_events_are_polled_for_the_active_session_only() {
        let platform_bridge = Arc::new(RecordingPlatformBridge::default());
        let session = MonitorSession::with_platform_bridge(platform_bridge.clone());
        *session.active_session_id.lock().expect("active session id") = Some("stage-b".to_string());

        let payload = session.poll_passive_events_json().expect("poll passive events").expect("event payload");
        let events: Vec<NativeSessionEvent> = serde_json::from_str(&payload).expect("decode event payload");

        assert_eq!(events.len(), 1);
        assert_eq!(events[0].message, "stage-b");
        assert_eq!(*platform_bridge.drained_session_ids.lock().expect("drained session ids"), ["stage-b"]);
    }

    #[test]
    fn take_report_consumes_finished_report() {
        let session = MonitorSession::new();
        session.shared.lock().expect("shared state").report = Some(ScanReport {
            session_id: "finished-session".to_string(),
            profile_id: "default".to_string(),
            path_mode: ScanPathMode::RawPath,
            started_at: 10,
            finished_at: 20,
            summary: "Finished".to_string(),
            completion_kind: ScanCompletionKind::Normal,
            termination_reason: None,
            results: vec![ProbeResult {
                probe_type: "connectivity".to_string(),
                target: "example.com".to_string(),
                outcome: "reachable".to_string(),
                details: Vec::new(),
            }],
            observations: Vec::new(),
            engine_analysis_version: None,
            diagnoses: Vec::new(),
            classifier_version: None,
            pack_versions: std::collections::BTreeMap::default(),
            strategy_probe_report: None,
            confirm_good_dpi_verdict: None,
            metrics_summary: None::<RecorderSnapshot>,
            execution_plan: None,
            candidate_runtime_cleanup: None,
        });

        let report = session.take_report_json().expect("take finished report");

        assert!(report.is_some());
        assert_eq!(session.take_report_json().expect("report consumed"), None);
    }

    #[test]
    fn destroy_returns_without_waiting_for_blocked_worker() {
        let session = MonitorSession::new();
        let (started_tx, started_rx) = mpsc::channel();
        let (release_tx, release_rx) = mpsc::channel();
        let worker = thread::spawn(move || {
            started_tx.send(()).expect("signal worker start");
            release_rx.recv().expect("wait for worker release");
        });
        started_rx.recv_timeout(Duration::from_secs(1)).expect("worker started");
        *session.worker.lock().expect("worker lock") = Some(worker);

        let started = Instant::now();
        session.destroy();
        assert!(started.elapsed() < Duration::from_millis(250), "destroy must not join a blocked probe worker");
        assert!(session.worker.lock().expect("worker lock").is_none());

        release_tx.send(()).expect("release worker for asynchronous join");
    }
    #[test]
    fn take_report_returns_without_waiting_for_blocked_worker() {
        let session = Arc::new(MonitorSession::new());
        let (started_tx, started_rx) = mpsc::channel();
        let (release_tx, release_rx) = mpsc::channel();
        let worker = thread::spawn(move || {
            started_tx.send(()).expect("signal worker start");
            release_rx.recv().expect("wait for worker release");
        });
        started_rx.recv_timeout(Duration::from_secs(1)).expect("worker started");
        *session.worker.lock().expect("worker lock") = Some(worker);

        let (poll_tx, poll_rx) = mpsc::channel();
        thread::spawn(move || {
            poll_tx.send(session.take_report_json()).expect("return report polling result");
        });

        let result = poll_rx.recv_timeout(Duration::from_millis(100));
        release_tx.send(()).expect("release worker after bounded poll");
        assert!(matches!(result, Ok(Ok(None))), "report polling must not join a running worker: {result:?}");
    }
}
