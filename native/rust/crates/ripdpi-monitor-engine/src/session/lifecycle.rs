use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::Instant;

use rustls::client::danger::ServerCertVerifier;

use crate::execution::UnavailableCandidateRuntimeLauncher;
use crate::platform::NoopMonitorPlatformBridge;
use crate::types::SharedState;
use crate::{CandidateRuntimeLauncher, MonitorPlatformBridge};

mod cancellation;
mod reporting;
mod start;
mod teardown;

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
