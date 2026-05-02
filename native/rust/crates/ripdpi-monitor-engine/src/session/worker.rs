use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};

use log::LevelFilter;
use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::set_progress;
use crate::engine::run_engine_scan;
use crate::types::{ScanProgress, ScanRequest, SharedState};
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
    thread::spawn(move || {
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
            record_panic_progress(shared_panic, session_id_panic, panic_payload);
        }
    })
}

pub(super) fn join_finished_worker_locked(worker_guard: &mut Option<JoinHandle<()>>) {
    let finished = worker_guard.as_ref().is_some_and(JoinHandle::is_finished);
    if finished {
        let handle = worker_guard.take().expect("finished worker handle must exist");
        let _ = handle.join();
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

fn record_panic_progress(
    shared: Arc<Mutex<SharedState>>,
    session_id: String,
    panic_payload: Box<dyn std::any::Any + Send>,
) {
    let msg = panic_payload
        .downcast_ref::<String>()
        .map(String::as_str)
        .or_else(|| panic_payload.downcast_ref::<&str>().copied())
        .unwrap_or("unknown panic");
    set_progress(
        &shared,
        ScanProgress {
            session_id,
            phase: "error".to_string(),
            completed_steps: 1,
            total_steps: 1,
            message: format!("Internal error: {msg}"),
            is_finished: true,
            latest_probe_target: None,
            latest_probe_outcome: None,
            strategy_probe_progress: None,
        },
    );
}
