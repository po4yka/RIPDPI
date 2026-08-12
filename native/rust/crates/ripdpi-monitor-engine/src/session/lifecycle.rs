use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use rustls::client::danger::ServerCertVerifier;

use crate::execution::UnavailableCandidateRuntimeLauncher;
use crate::platform::NoopMonitorPlatformBridge;
use crate::types::{EngineScanRequestWire, SharedState};
use crate::{CandidateRuntimeLauncher, MonitorPlatformBridge};

use super::log_level::parse_native_log_level;
use super::reaper::WORKER_REAPER;
use super::validation::ValidatedScanRequest;
use super::wire_json::{passive_events_to_json, progress_to_json, report_to_json};
use super::worker::{ScanWorkerConfig, join_finished_worker_locked, spawn_scan_worker};

#[derive(Default)]
struct ScanControl {
    deadline: Option<Instant>,
    report_delivered: bool,
}

struct StartingGuard<'a>(&'a AtomicBool);

impl Drop for StartingGuard<'_> {
    fn drop(&mut self) {
        self.0.store(false, Ordering::Release);
    }
}

pub struct MonitorSession {
    // Lock order: worker -> active_session_id -> scan_control -> cancellation_reason -> shared.
    pub(super) shared: Arc<Mutex<SharedState>>,
    pub(super) cancel: Arc<AtomicBool>,
    starting: AtomicBool,
    destroyed: AtomicBool,
    pub(super) worker: Mutex<Option<JoinHandle<()>>>,
    active_session_id: Mutex<Option<String>>,
    scan_control: Mutex<ScanControl>,
    cancellation_reason: Arc<Mutex<Option<crate::types::ScanTerminationReason>>>,
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
            starting: AtomicBool::new(false),
            destroyed: AtomicBool::new(false),
            worker: Mutex::new(None),
            active_session_id: Mutex::new(None),
            scan_control: Mutex::new(ScanControl::default()),
            cancellation_reason: Arc::new(Mutex::new(None)),
            tls_verifier,
            platform_bridge,
            candidate_runtime_launcher,
        }
    }

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
    use crate::types::{
        NativeSessionEvent, ProbeResult, ScanCompletionKind, ScanPathMode, ScanProgress, ScanReport,
        ScanTerminationReason,
    };
    use ripdpi_telemetry::recorder::RecorderSnapshot;
    use std::sync::{Barrier, mpsc};
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
        });

        let report = session.take_report_json().expect("take finished report");

        assert!(report.is_some());
        assert_eq!(session.take_report_json().expect("report consumed"), None);
    }

    #[test]
    fn deadline_cancellation_exposes_checkpoint_without_ending_normal_polling() {
        let session = Arc::new(MonitorSession::new());
        session.scan_control.lock().expect("scan control").deadline = Some(Instant::now() - Duration::from_millis(1));
        let mut shared = session.shared.lock().expect("shared state");
        shared.progress = Some(ScanProgress {
            session_id: "dpi-full".to_string(),
            phase: "domain".to_string(),
            completed_steps: 1,
            total_steps: 2,
            message: "Domain probe blocked".to_string(),
            is_finished: false,
            latest_probe_target: None,
            latest_probe_outcome: None,
            strategy_probe_progress: None,
        });
        shared.checkpoint_report = Some(ScanReport {
            session_id: "dpi-full".to_string(),
            profile_id: "ru-dpi-full".to_string(),
            path_mode: ScanPathMode::RawPath,
            started_at: 10,
            finished_at: 20,
            summary: "Scan completed with partial results".to_string(),
            completion_kind: ScanCompletionKind::PartialResults,
            termination_reason: None,
            results: vec![ProbeResult {
                probe_type: "dns".to_string(),
                target: "example.com".to_string(),
                outcome: "dns_match".to_string(),
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
        });
        drop(shared);

        let normal_poll = session.take_report_json().expect("normal report poll");
        session.cancel_scan();
        let barrier = Arc::new(Barrier::new(3));
        let polls = (0..2)
            .map(|_| {
                let session = session.clone();
                let barrier = barrier.clone();
                thread::spawn(move || {
                    barrier.wait();
                    session.take_report_json().expect("concurrent cancellation report poll")
                })
            })
            .collect::<Vec<_>>();
        barrier.wait();
        let recovered_reports =
            polls.into_iter().filter_map(|poll| poll.join().expect("join report poll")).collect::<Vec<_>>();
        let recovered_json = recovered_reports.first().expect("one partial report");
        let recovered: ScanReport = serde_json::from_str(recovered_json).expect("decode partial report");

        assert_eq!(
            (
                normal_poll,
                recovered.completion_kind,
                recovered.termination_reason,
                recovered.results.len(),
                recovered_reports.len(),
            ),
            (None, ScanCompletionKind::PartialResults, Some(ScanTerminationReason::DeadlineExceeded), 1, 1),
        );
    }

    #[test]
    fn user_cancellation_keeps_cause_when_report_is_retrieved_after_deadline() {
        let session = MonitorSession::new();
        session.scan_control.lock().expect("scan control").deadline = Some(Instant::now() + Duration::from_secs(60));
        let mut shared = session.shared.lock().expect("shared state");
        shared.progress = Some(ScanProgress {
            session_id: "dpi-full".to_string(),
            phase: "domain".to_string(),
            completed_steps: 1,
            total_steps: 2,
            message: "Domain probe blocked".to_string(),
            is_finished: false,
            latest_probe_target: None,
            latest_probe_outcome: None,
            strategy_probe_progress: None,
        });
        shared.checkpoint_report = Some(partial_report());
        drop(shared);

        session.cancel_scan();
        session.scan_control.lock().expect("scan control").deadline = Some(Instant::now() - Duration::from_millis(1));
        let recovered_json = session.take_report_json().expect("cancellation report poll").expect("partial report");
        let recovered: ScanReport = serde_json::from_str(&recovered_json).expect("decode partial report");

        assert_eq!(recovered.termination_reason, Some(ScanTerminationReason::UserCancelled));
    }

    #[test]
    fn unfinished_terminal_report_is_not_rewritten_as_cancelled_checkpoint() {
        let session = MonitorSession::new();
        session.scan_control.lock().expect("scan control").deadline = Some(Instant::now() + Duration::from_secs(60));
        let mut terminal_report = partial_report();
        terminal_report.completion_kind = ScanCompletionKind::Normal;
        terminal_report.summary = "Diagnostics completed".to_string();
        let mut shared = session.shared.lock().expect("shared state");
        shared.progress = Some(ScanProgress {
            session_id: "dpi-full".to_string(),
            phase: "domain".to_string(),
            completed_steps: 2,
            total_steps: 2,
            message: "Publishing completion".to_string(),
            is_finished: false,
            latest_probe_target: None,
            latest_probe_outcome: None,
            strategy_probe_progress: None,
        });
        shared.report = Some(terminal_report);
        drop(shared);

        session.cancel_scan();
        let premature = session.take_report_json().expect("unfinished report poll");
        session.shared.lock().expect("shared state").progress.as_mut().expect("progress").is_finished = true;
        let completed_json = session.take_report_json().expect("finished report poll").expect("terminal report");
        let completed: ScanReport = serde_json::from_str(&completed_json).expect("decode terminal report");

        assert_eq!(
            (premature, completed.completion_kind, completed.termination_reason),
            (None, ScanCompletionKind::Normal, None),
        );
    }

    #[test]
    fn cancellation_waits_for_scan_initialization_transition() {
        let session = Arc::new(MonitorSession::new());
        session.scan_control.lock().expect("scan control").deadline = Some(Instant::now() + Duration::from_secs(60));
        let worker_guard = session.worker.lock().expect("worker lock");
        let barrier = Arc::new(Barrier::new(2));
        let cancel_thread = {
            let session = session.clone();
            let barrier = barrier.clone();
            thread::spawn(move || {
                barrier.wait();
                session.cancel_scan();
            })
        };
        barrier.wait();
        thread::yield_now();
        let cancelled_during_initialization = session.cancel.load(Ordering::Acquire);
        drop(worker_guard);
        cancel_thread.join().expect("join cancellation");
        let captured_reason = session.cancellation_reason.lock().expect("cancellation reason").clone();

        assert_eq!(
            (cancelled_during_initialization, session.cancel.load(Ordering::Acquire), captured_reason),
            (false, true, Some(ScanTerminationReason::UserCancelled)),
        );
    }

    #[test]
    fn starting_guard_clears_transition_after_unwind() {
        let session = MonitorSession::new();
        let unwind = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            session.starting.store(true, Ordering::Release);
            let _starting_guard = StartingGuard(&session.starting);
            panic!("platform callback panicked");
        }));

        assert!(unwind.is_err());
        assert!(!session.starting.load(Ordering::Acquire));
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

    fn partial_report() -> ScanReport {
        ScanReport {
            session_id: "dpi-full".to_string(),
            profile_id: "ru-dpi-full".to_string(),
            path_mode: ScanPathMode::RawPath,
            started_at: 10,
            finished_at: 20,
            summary: "Scan completed with partial results".to_string(),
            completion_kind: ScanCompletionKind::PartialResults,
            termination_reason: None,
            results: vec![ProbeResult {
                probe_type: "dns".to_string(),
                target: "example.com".to_string(),
                outcome: "dns_match".to_string(),
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
        }
    }
}
