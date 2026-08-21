use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex};

use crate::engine::run_engine_scan;
use crate::types::{ScanRequest, SharedState};

use super::ScanWorkerConfig;

pub(crate) fn run_scan(
    shared: Arc<Mutex<SharedState>>,
    cancel: Arc<AtomicBool>,
    session_id: String,
    request: ScanRequest,
    config: ScanWorkerConfig,
) {
    let _log_scope = config
        .native_log_level
        .map(|level| config.platform_bridge.scoped_log_level("diagnostics_native".to_string(), level));
    run_engine_scan(
        shared,
        cancel,
        session_id,
        request,
        config.scan_deadline,
        config.cancellation_reason,
        config.tls_verifier,
        config.candidate_runtime_launcher,
    );
}
