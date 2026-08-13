use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::Instant;

use log::LevelFilter;
use rustls::client::danger::ServerCertVerifier;

use crate::types::{ScanRequest, SharedState};
use crate::{CandidateRuntimeLauncher, MonitorPlatformBridge};

use super::panic_state::record_panic_terminal_state;
use run::run_scan;

mod run;

pub(super) struct ScanWorkerConfig {
    scan_deadline: Instant,
    cancellation_reason: Arc<Mutex<Option<crate::types::ScanTerminationReason>>>,
    tls_verifier: Option<Arc<dyn ServerCertVerifier>>,
    platform_bridge: Arc<dyn MonitorPlatformBridge>,
    candidate_runtime_launcher: Arc<dyn CandidateRuntimeLauncher>,
    native_log_level: Option<LevelFilter>,
}

impl ScanWorkerConfig {
    pub(super) fn new(
        scan_deadline: Instant,
        cancellation_reason: Arc<Mutex<Option<crate::types::ScanTerminationReason>>>,
        tls_verifier: Option<Arc<dyn ServerCertVerifier>>,
        platform_bridge: Arc<dyn MonitorPlatformBridge>,
        candidate_runtime_launcher: Arc<dyn CandidateRuntimeLauncher>,
        native_log_level: Option<LevelFilter>,
    ) -> Self {
        Self {
            scan_deadline,
            cancellation_reason,
            tls_verifier,
            platform_bridge,
            candidate_runtime_launcher,
            native_log_level,
        }
    }
}

pub(super) fn spawn_scan_worker(
    shared: Arc<Mutex<SharedState>>,
    cancel: Arc<AtomicBool>,
    session_id: String,
    request: ScanRequest,
    config: ScanWorkerConfig,
) -> JoinHandle<()> {
    let shared_panic = shared.clone();
    let session_id_panic = session_id.clone();
    let request_panic = request.clone();
    thread::spawn(move || {
        let started_at = crate::util::now_ms();
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            run_scan(shared, cancel, session_id, request, config);
        }));
        if let Err(panic_payload) = result {
            record_panic_terminal_state(shared_panic, session_id_panic, request_panic, started_at, panic_payload);
        }
    })
}

pub(super) fn join_finished_worker_locked(worker_guard: &mut Option<JoinHandle<()>>) {
    let finished = worker_guard.as_ref().is_some_and(JoinHandle::is_finished);
    if finished {
        // The guard above already proved `Some`; take it without a panic path.
        if let Some(handle) = worker_guard.take() {
            // The worker body catches its own panics (see `spawn_scan_worker`),
            // so `join()` here is the cleanup join — ignore any join error
            // rather than re-propagating it into the lock-holding caller.
            let _ = handle.join();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::engine::{ReportBuildContext, build_report};
    use crate::types::{DiagnosticProfileFamily, ProbeResult, ScanKind, ScanPathMode, ScanProgress};

    #[test]
    fn worker_panic_publishes_terminal_report() {
        let shared = Arc::new(Mutex::new(SharedState::default()));

        record_panic_terminal_state(
            shared.clone(),
            "panic-session".to_string(),
            request(),
            100,
            Box::new("probe exploded".to_string()),
        );

        let state = shared.lock().expect("shared state");
        let progress = state.progress.as_ref().expect("terminal progress");
        assert!(progress.is_finished);
        assert_eq!(progress.phase, "error");
        let report = state.report.as_ref().expect("panic must publish a terminal report");
        assert_eq!(report.session_id, "panic-session");
        assert_eq!(report.completion_kind, crate::types::ScanCompletionKind::Terminated);
        assert_eq!(report.termination_reason, Some(crate::types::ScanTerminationReason::WorkerPanicked));
        assert!(report.results.iter().any(|result| result.outcome == "worker_panicked"));
    }

    #[test]
    fn worker_panic_preserves_existing_partial_report_and_progress_counts() {
        let request = request();
        let partial_result = ProbeResult {
            probe_type: "dns_integrity".to_string(),
            target: "example.com".to_string(),
            outcome: "dns_match".to_string(),
            details: Vec::new(),
        };
        let shared = Arc::new(Mutex::new(SharedState {
            progress: Some(ScanProgress {
                session_id: "panic-session".to_string(),
                phase: "dns".to_string(),
                completed_steps: 3,
                total_steps: 8,
                message: "DNS".to_string(),
                is_finished: false,
                latest_probe_target: None,
                latest_probe_outcome: None,
                strategy_probe_progress: None,
            }),
            report: None,
            checkpoint_report: Some(build_report(
                ReportBuildContext {
                    session_id: "panic-session".to_string(),
                    request: request.clone(),
                    started_at: 100,
                    execution_plan: None,
                },
                "Partial".to_string(),
                vec![partial_result],
                Vec::new(),
                None,
                None,
            )),
            log_context: None,
        }));

        record_panic_terminal_state(
            shared.clone(),
            "panic-session".to_string(),
            request,
            100,
            Box::new("probe exploded".to_string()),
        );

        let state = shared.lock().expect("shared state");
        let report = state.report.as_ref().expect("terminal partial report");
        assert_eq!(report.results.len(), 2);
        assert_eq!(report.results[0].outcome, "dns_match");
        assert_eq!(report.results[1].outcome, "worker_panicked");
        assert_eq!(report.completion_kind, crate::types::ScanCompletionKind::PartialResults);
        assert_eq!(report.termination_reason, Some(crate::types::ScanTerminationReason::WorkerPanicked));
        let progress = state.progress.as_ref().expect("terminal progress");
        assert_eq!((progress.completed_steps, progress.total_steps), (3, 8));
        assert!(progress.is_finished);
    }

    #[test]
    fn worker_panic_preserves_existing_terminal_report_evidence() {
        let request = request();
        let shared = Arc::new(Mutex::new(SharedState {
            progress: Some(ScanProgress {
                session_id: "panic-session".to_string(),
                phase: "finishing".to_string(),
                completed_steps: 8,
                total_steps: 8,
                message: "Publishing completion".to_string(),
                is_finished: false,
                latest_probe_target: None,
                latest_probe_outcome: None,
                strategy_probe_progress: None,
            }),
            report: Some(build_report(
                ReportBuildContext {
                    session_id: "panic-session".to_string(),
                    request: request.clone(),
                    started_at: 100,
                    execution_plan: None,
                },
                "Diagnostics completed".to_string(),
                vec![ProbeResult {
                    probe_type: "dns_integrity".to_string(),
                    target: "example.com".to_string(),
                    outcome: "dns_match".to_string(),
                    details: Vec::new(),
                }],
                Vec::new(),
                None,
                None,
            )),
            checkpoint_report: None,
            log_context: None,
        }));

        record_panic_terminal_state(
            shared.clone(),
            "panic-session".to_string(),
            request,
            100,
            Box::new("probe exploded".to_string()),
        );

        let state = shared.lock().expect("shared state");
        let report = state.report.as_ref().expect("terminal partial report");
        assert_eq!(
            (
                report.results.iter().map(|result| result.outcome.as_str()).collect::<Vec<_>>(),
                report.completion_kind.clone(),
                report.termination_reason.clone(),
            ),
            (
                vec!["dns_match", "worker_panicked"],
                crate::types::ScanCompletionKind::PartialResults,
                Some(crate::types::ScanTerminationReason::WorkerPanicked),
            ),
        );
    }

    fn request() -> ScanRequest {
        ScanRequest {
            profile_id: "panic-profile".to_string(),
            display_name: "Panic profile".to_string(),
            path_mode: ScanPathMode::RawPath,
            kind: ScanKind::Connectivity,
            family: DiagnosticProfileFamily::General,
            region_tag: None,
            manual_only: false,
            pack_refs: Vec::new(),
            proxy_host: None,
            proxy_port: None,
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
            scan_deadline_ms: None,
            diagnostic_tls_keylog_path: None,
        }
    }
}
