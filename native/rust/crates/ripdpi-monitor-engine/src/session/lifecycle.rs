use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use rustls::client::danger::ServerCertVerifier;

use crate::execution::UnavailableCandidateRuntimeLauncher;
use crate::platform::NoopMonitorPlatformBridge;
use crate::types::{
    EngineScanRequestWire, ScanCompletionKind, ScanReportDisposition, ScanRequest, ScanTerminationReason, SharedState,
};
use crate::{CandidateRuntimeLauncher, MonitorPlatformBridge};

use super::log_level::parse_native_log_level;
use super::reaper::WORKER_REAPER;
use super::validation::ValidatedScanRequest;
use super::wire_json::{passive_events_to_json, progress_to_json, report_to_json};
use super::worker::{ScanWorkerConfig, join_finished_worker_locked, spawn_scan_worker};

#[derive(Default)]
struct ScanControl {
    deadline: Option<Instant>,
    checkpoint_report_delivered: bool,
    terminal_report_delivered: bool,
    start_in_progress: bool,
}

enum StartMarkerState<'a> {
    NotAdmitted,
    Admitted(&'a Mutex<ScanControl>),
}

struct StartInProgressGuard<'a> {
    state: StartMarkerState<'a>,
}

impl<'a> StartInProgressGuard<'a> {
    fn new() -> Self {
        Self { state: StartMarkerState::NotAdmitted }
    }

    fn mark_admitted(&mut self, scan_control: &'a Mutex<ScanControl>) {
        self.state = StartMarkerState::Admitted(scan_control);
    }

    fn clear(&mut self) {
        let StartMarkerState::Admitted(scan_control) =
            std::mem::replace(&mut self.state, StartMarkerState::NotAdmitted)
        else {
            return;
        };
        let mut control = scan_control.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        control.start_in_progress = false;
    }
}

impl Drop for StartInProgressGuard<'_> {
    fn drop(&mut self) {
        self.clear();
    }
}

type ScanWorkerSpawner =
    fn(Arc<Mutex<SharedState>>, Arc<AtomicBool>, String, ScanRequest, ScanWorkerConfig) -> JoinHandle<()>;

pub struct MonitorSession {
    // Lock order: worker -> active_session_id -> scan_control -> cancellation_reason -> shared.
    pub(super) shared: Arc<Mutex<SharedState>>,
    pub(super) cancel: Arc<AtomicBool>,
    pub(super) worker: Mutex<Option<JoinHandle<()>>>,
    active_session_id: Mutex<Option<String>>,
    scan_control: Mutex<ScanControl>,
    cancellation_reason: Arc<Mutex<Option<ScanTerminationReason>>>,
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
            scan_control: Mutex::new(ScanControl::default()),
            cancellation_reason: Arc::new(Mutex::new(None)),
            tls_verifier,
            platform_bridge,
            candidate_runtime_launcher,
        }
    }

    pub fn start_scan(&self, session_id: String, request: EngineScanRequestWire) -> Result<(), String> {
        self.start_scan_with_spawner(session_id, request, spawn_scan_worker)
    }

    fn start_scan_with_spawner(
        &self,
        session_id: String,
        request: EngineScanRequestWire,
        spawn_worker: ScanWorkerSpawner,
    ) -> Result<(), String> {
        let request = ValidatedScanRequest::try_from(request)?;
        let native_log_level = parse_native_log_level(request.as_wire().native_log_level.as_deref())?;
        {
            let mut worker_guard = self.worker.lock().map_err(|_| "monitor worker poisoned".to_string())?;
            join_finished_worker_locked(&mut worker_guard);
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
            // The scan-control lock is the admission barrier. A cancellation
            // racing after admission blocks on this lock and publishes its
            // flag/cause only after the prior generation has been reset.
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
        join_finished_worker_locked(&mut worker_guard);
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

    fn lock_shared_state_recovering(&self) -> MutexGuard<'_, SharedState> {
        // SharedState is an owned diagnostics snapshot store. A writer panic can
        // poison the mutex even after panic recovery published a safe terminal
        // report into that same store; public readers/reset must still expose or
        // clear the terminal state instead of making it unreachable.
        self.shared.lock().unwrap_or_else(|poisoned| {
            log::warn!("recovering diagnostics shared state after mutex poison");
            poisoned.into_inner()
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::{
        DIAGNOSTICS_ENGINE_SCHEMA_VERSION, DiagnosticProfileFamily, NativeSessionEvent, ProbeResult,
        ScanCompletionKind, ScanKind, ScanPathMode, ScanProgress, ScanReport, ScanTerminationReason,
    };
    use ripdpi_telemetry::recorder::RecorderSnapshot;
    use std::sync::{Barrier, Weak, mpsc};
    use std::thread;
    use std::time::{Duration, Instant};

    #[derive(Default)]
    struct RecordingPlatformBridge {
        drained_session_ids: Mutex<Vec<String>>,
    }

    struct BlockingClearPlatformBridge {
        started_tx: Mutex<Option<mpsc::Sender<()>>>,
        release_rx: Mutex<mpsc::Receiver<()>>,
    }

    struct ReentrantCancelPlatformBridge {
        session: Mutex<Option<Weak<MonitorSession>>>,
    }

    struct PanickingClearPlatformBridge;

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

    impl MonitorPlatformBridge for BlockingClearPlatformBridge {
        fn clear_passive_events(&self, _session_id: &str) {
            if let Some(started_tx) = self.started_tx.lock().expect("started sender").take() {
                started_tx.send(()).expect("signal clear_passive_events");
            }
            self.release_rx.lock().expect("release receiver").recv().expect("wait for release");
        }
    }

    impl MonitorPlatformBridge for ReentrantCancelPlatformBridge {
        fn clear_passive_events(&self, _session_id: &str) {
            if let Some(session) = self.session.lock().expect("session weak").as_ref().and_then(Weak::upgrade) {
                session.cancel_scan();
            }
        }
    }

    impl MonitorPlatformBridge for PanickingClearPlatformBridge {
        fn clear_passive_events(&self, _session_id: &str) {
            panic!("clear_passive_events panic");
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
            report_disposition: ScanReportDisposition::Terminal,
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
    fn cancelled_scan_exposes_partial_checkpoint_once_without_consuming_normal_polling() {
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
        shared.checkpoint_report = Some(partial_report());
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
    fn user_cancellation_reason_survives_later_deadline() {
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
    fn checkpoint_recovery_does_not_consume_later_terminal_report() {
        let session = MonitorSession::new();
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
        shared.checkpoint_report = Some(partial_report());
        drop(shared);

        session.cancel_scan();
        let recovered_json = session.take_report_json().expect("checkpoint poll").expect("checkpoint report");
        let recovered: ScanReport = serde_json::from_str(&recovered_json).expect("decode checkpoint report");

        let cleanup_receipt = crate::types::CandidateRuntimeCleanupReceipt {
            started: 1,
            stopped: 1,
            joined: 1,
            forced_abort: 1,
            ..Default::default()
        };
        let mut terminal = partial_report();
        terminal.finished_at = recovered.finished_at + 1;
        terminal.candidate_runtime_cleanup = Some(cleanup_receipt.clone());
        let mut shared = session.shared.lock().expect("shared state");
        shared.progress = Some(ScanProgress {
            session_id: "dpi-full".to_string(),
            phase: "cancelled".to_string(),
            completed_steps: 1,
            total_steps: 2,
            message: "Diagnostics cancelled".to_string(),
            is_finished: true,
            latest_probe_target: None,
            latest_probe_outcome: Some("cancelled".to_string()),
            strategy_probe_progress: None,
        });
        shared.report = Some(terminal);
        drop(shared);

        let terminal_json = session.take_report_json().expect("terminal poll").expect("terminal report");
        let terminal: ScanReport = serde_json::from_str(&terminal_json).expect("decode terminal report");

        assert_eq!(recovered.termination_reason, Some(ScanTerminationReason::DeadlineExceeded));
        assert_eq!(recovered.report_disposition, ScanReportDisposition::Checkpoint);
        assert!(recovered.finished_at > 20, "checkpoint finishedAt must reflect cancellation/recovery time");
        assert_eq!(terminal.report_disposition, ScanReportDisposition::Terminal);
        assert_eq!(terminal.candidate_runtime_cleanup, Some(cleanup_receipt));
        assert_eq!(session.take_report_json().expect("terminal consumed"), None);
    }

    #[test]
    fn checkpoint_recovery_is_one_shot_even_if_newer_checkpoint_is_published() {
        let session = MonitorSession::new();
        session.scan_control.lock().expect("scan control").deadline = Some(Instant::now() + Duration::from_secs(60));
        let mut shared = session.shared.lock().expect("shared state");
        shared.progress = Some(ScanProgress {
            session_id: "dpi-full".to_string(),
            phase: "domain".to_string(),
            completed_steps: 1,
            total_steps: 3,
            message: "Domain probe blocked".to_string(),
            is_finished: false,
            latest_probe_target: None,
            latest_probe_outcome: None,
            strategy_probe_progress: None,
        });
        shared.checkpoint_report = Some(partial_report());
        drop(shared);

        session.cancel_scan();
        assert!(session.take_report_json().expect("first checkpoint poll").is_some());

        let mut newer_checkpoint = partial_report();
        newer_checkpoint.results.push(ProbeResult {
            probe_type: "tcp".to_string(),
            target: "example.com:443".to_string(),
            outcome: "connection_refused".to_string(),
            details: Vec::new(),
        });
        session.shared.lock().expect("shared state").checkpoint_report = Some(newer_checkpoint);

        assert_eq!(session.take_report_json().expect("second checkpoint poll"), None);
    }

    #[test]
    fn take_report_json_delivers_panic_report_after_shared_state_poison() {
        let session = MonitorSession::new();
        let poison_shared = session.shared.clone();
        let prev_hook = std::panic::take_hook();
        std::panic::set_hook(Box::new(|_| {}));
        let _ = thread::spawn(move || {
            let _guard = poison_shared.lock().expect("shared state");
            panic!("poison shared state for public report API");
        })
        .join();
        std::panic::set_hook(prev_hook);

        super::super::panic_state::record_panic_terminal_state(
            session.shared.clone(),
            "panic-session".to_string(),
            request_wire().into(),
            100,
            Box::new("probe exploded".to_string()),
        );

        let report_json = session.take_report_json().expect("take report after poison").expect("panic report");
        let report: ScanReport = serde_json::from_str(&report_json).expect("decode panic report");

        assert_eq!(report.session_id, "panic-session");
        assert_eq!(report.report_disposition, ScanReportDisposition::Terminal);
        assert_eq!(report.termination_reason, Some(ScanTerminationReason::WorkerPanicked));
    }

    #[test]
    fn cancel_during_start_scan_preserves_cancellation_reason_after_initialization() {
        let (started_tx, started_rx) = mpsc::channel();
        let (release_tx, release_rx) = mpsc::channel();
        let bridge = Arc::new(BlockingClearPlatformBridge {
            started_tx: Mutex::new(Some(started_tx)),
            release_rx: Mutex::new(release_rx),
        });
        let session = Arc::new(MonitorSession::with_platform_bridge(bridge));
        let start_session = session.clone();
        let start = thread::spawn(move || {
            start_session.start_scan("start-race".to_string(), request_wire()).expect("start scan");
        });
        started_rx.recv_timeout(Duration::from_secs(1)).expect("start reached passive clear");

        let cancel_session = session.clone();
        let cancel = thread::spawn(move || {
            cancel_session.cancel_scan();
        });

        release_tx.send(()).expect("release start");
        start.join().expect("join start");
        cancel.join().expect("join cancel");

        assert_eq!(
            *session.cancellation_reason.lock().expect("cancellation reason"),
            Some(ScanTerminationReason::UserCancelled)
        );
    }

    #[test]
    fn rejected_start_does_not_reset_active_scan_cancellation() {
        let session = MonitorSession::new();
        session.cancel.store(true, Ordering::Release);
        *session.cancellation_reason.lock().expect("cancellation reason") =
            Some(ScanTerminationReason::DeadlineExceeded);
        session.scan_control.lock().expect("scan control").start_in_progress = true;

        let result = session.start_scan("rejected-start".to_string(), request_wire());

        assert_eq!(result, Err("diagnostics scan already running".to_string()));
        assert!(session.cancel.load(Ordering::Acquire));
        assert_eq!(
            *session.cancellation_reason.lock().expect("cancellation reason"),
            Some(ScanTerminationReason::DeadlineExceeded),
        );
    }

    #[test]
    fn start_marker_is_cleared_when_passive_event_callback_panics() {
        let session = MonitorSession::with_platform_bridge(Arc::new(PanickingClearPlatformBridge));
        let prev_hook = std::panic::take_hook();
        std::panic::set_hook(Box::new(|_| {}));

        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let _ = session.start_scan("callback-panic".to_string(), request_wire());
        }));

        std::panic::set_hook(prev_hook);
        assert!(result.is_err());
        assert!(!session.scan_control.lock().expect("scan control").start_in_progress);
    }

    #[test]
    fn start_marker_is_cleared_when_worker_spawn_panics() {
        let session = MonitorSession::new();
        let prev_hook = std::panic::take_hook();
        std::panic::set_hook(Box::new(|_| {}));

        let result = session.start_scan_with_spawner("spawn-panic".to_string(), request_wire(), |_, _, _, _, _| {
            panic!("worker spawn panic")
        });

        std::panic::set_hook(prev_hook);
        assert_eq!(result, Err("diagnostics worker spawn panicked".to_string()));
        assert!(!session.scan_control.lock().expect("scan control").start_in_progress);
        assert!(session.worker.lock().is_ok(), "worker mutex must not be poisoned by a contained spawn panic");
    }

    #[test]
    fn start_scan_allows_reentrant_cancel_from_passive_event_clear() {
        let bridge = Arc::new(ReentrantCancelPlatformBridge { session: Mutex::new(None) });
        let session = Arc::new(MonitorSession::with_platform_bridge(bridge.clone()));
        *bridge.session.lock().expect("session weak") = Some(Arc::downgrade(&session));
        let (done_tx, done_rx) = mpsc::channel();

        thread::spawn(move || {
            done_tx
                .send(session.start_scan("reentrant-cancel".to_string(), request_wire()))
                .expect("send start result");
        });

        let result = done_rx
            .recv_timeout(Duration::from_millis(250))
            .expect("reentrant cancel during passive clear must not deadlock");
        assert!(result.is_ok(), "start_scan failed: {result:?}");
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
            report_disposition: ScanReportDisposition::Terminal,
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
            candidate_runtime_cleanup: None,
        }
    }

    fn request_wire() -> EngineScanRequestWire {
        EngineScanRequestWire {
            schema_version: DIAGNOSTICS_ENGINE_SCHEMA_VERSION,
            profile_id: "test".to_string(),
            display_name: "Test".to_string(),
            path_mode: ScanPathMode::RawPath,
            kind: ScanKind::Connectivity,
            family: DiagnosticProfileFamily::General,
            region_tag: None,
            pack_refs: Vec::new(),
            proxy_host: None,
            proxy_port: None,
            in_path_route: None,
            probe_tasks: Vec::new(),
            domain_targets: Vec::new(),
            dns_targets: Vec::new(),
            tcp_targets: Vec::new(),
            quic_targets: Vec::new(),
            service_targets: Vec::new(),
            circumvention_targets: Vec::new(),
            throughput_targets: Vec::new(),
            whitelist_sni: Vec::new(),
            telegram_target: None,
            strategy_probe: None,
            confirm_good_dpi_evidence: None,
            network_snapshot: None,
            route_probe: None,
            scan_deadline_ms: Some(1_000),
            native_log_level: None,
            log_context: None,
            diagnostic_tls_keylog_path: None,
        }
    }
}
