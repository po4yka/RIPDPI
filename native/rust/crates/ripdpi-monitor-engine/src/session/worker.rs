use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};

use log::LevelFilter;
use rustls::client::danger::ServerCertVerifier;

use crate::engine::{build_report, panic_payload_message, run_engine_scan};
use crate::types::{ProbeDetail, ProbeResult, ScanProgress, ScanRequest, SharedState};
use crate::{CandidateRuntimeLauncher, MonitorPlatformBridge};

pub(super) fn spawn_scan_worker(
    shared: Arc<Mutex<SharedState>>,
    cancel: Arc<AtomicBool>,
    session_id: String,
    request: ScanRequest,
    tls_verifier: Option<Arc<dyn ServerCertVerifier>>,
    platform_bridge: Arc<dyn MonitorPlatformBridge>,
    candidate_runtime_launcher: Arc<dyn CandidateRuntimeLauncher>,
    native_log_level: Option<LevelFilter>,
) -> JoinHandle<()> {
    let shared_panic = shared.clone();
    let session_id_panic = session_id.clone();
    let request_panic = request.clone();
    thread::spawn(move || {
        let started_at = crate::util::now_ms();
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            run_scan(
                shared,
                cancel,
                session_id,
                request,
                tls_verifier,
                platform_bridge,
                candidate_runtime_launcher,
                native_log_level,
            );
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

fn run_scan(
    shared: Arc<Mutex<SharedState>>,
    cancel: Arc<AtomicBool>,
    session_id: String,
    request: ScanRequest,
    tls_verifier: Option<Arc<dyn ServerCertVerifier>>,
    platform_bridge: Arc<dyn MonitorPlatformBridge>,
    candidate_runtime_launcher: Arc<dyn CandidateRuntimeLauncher>,
    native_log_level: Option<LevelFilter>,
) {
    let _log_scope =
        native_log_level.map(|level| platform_bridge.scoped_log_level("diagnostics_native".to_string(), level));
    run_engine_scan(shared, cancel, session_id, request, tls_verifier, candidate_runtime_launcher);
}

fn record_panic_terminal_state(
    shared: Arc<Mutex<SharedState>>,
    session_id: String,
    request: ScanRequest,
    started_at: u64,
    panic_payload: Box<dyn std::any::Any + Send>,
) {
    let msg = panic_payload_message(&*panic_payload);
    let panic_result = ProbeResult {
        probe_type: "diagnostics_engine".to_string(),
        target: request.profile_id.clone(),
        outcome: "worker_panicked".to_string(),
        details: vec![ProbeDetail { key: "error".to_string(), value: msg.clone() }],
    };
    let panic_report = build_report(
        session_id.clone(),
        request,
        started_at,
        "Diagnostics failed: internal worker error".to_string(),
        vec![panic_result.clone()],
        Vec::new(),
        None,
        None,
    );
    let Ok(mut state) = shared.lock() else {
        return;
    };
    if let Some(report) = state.report.as_mut() {
        if !report.results.iter().any(|result| result.outcome == "worker_panicked") {
            report.results.push(panic_result);
        }
        report.finished_at = crate::util::now_ms();
        report.summary = "Diagnostics failed: internal worker error".to_string();
    } else {
        state.report = Some(panic_report);
    }
    let (completed_steps, total_steps) =
        state.progress.as_ref().map_or((1, 1), |progress| (progress.completed_steps, progress.total_steps.max(1)));
    state.progress = Some(ScanProgress {
        session_id,
        phase: "error".to_string(),
        completed_steps,
        total_steps,
        message: format!("Internal error: {msg}"),
        is_finished: true,
        latest_probe_target: None,
        latest_probe_outcome: Some("worker_panicked".to_string()),
        strategy_probe_progress: None,
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::{DiagnosticProfileFamily, ScanKind, ScanPathMode};

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
            report: Some(build_report(
                "panic-session".to_string(),
                request.clone(),
                100,
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
        let progress = state.progress.as_ref().expect("terminal progress");
        assert_eq!((progress.completed_steps, progress.total_steps), (3, 8));
        assert!(progress.is_finished);
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
