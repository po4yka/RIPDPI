mod endpoint;
mod probes;
mod report;

use std::sync::{Arc, Mutex};

use ripdpi_proxy_config::ProxyLogContext;

use crate::types::SharedState;
use crate::types::*;
pub(crate) use probes::{
    classify_dns_latency_quality, run_circumvention_probe, run_dns_probe, run_domain_probe, run_quic_probe,
    run_service_probe, run_tcp_probe, run_throughput_probe,
};
pub(crate) use report::{build_network_environment_probe, summarize_probe_event};

pub(crate) fn set_progress(shared: &Arc<Mutex<SharedState>>, progress: ScanProgress) {
    let mut guard = shared.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    guard.progress = Some(progress);
}

pub(crate) fn set_report(shared: &Arc<Mutex<SharedState>>, report: ScanReport) {
    let mut guard = shared.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    guard.report = Some(report);
}

fn scan_path_mode_label(path_mode: &ScanPathMode) -> &'static str {
    match path_mode {
        ScanPathMode::RawPath => "RAW_PATH",
        ScanPathMode::InPath => "IN_PATH",
    }
}

fn emit_diagnostics_event(
    log_context: Option<&ProxyLogContext>,
    session_id: &str,
    profile_id: &str,
    path_mode: &ScanPathMode,
    source: &str,
    level: &str,
    message: &str,
) {
    let runtime_id = log_context.and_then(|context| context.runtime_id.as_deref()).unwrap_or("");
    let mode = log_context.and_then(|context| context.mode.as_deref()).unwrap_or("");
    let policy_signature = log_context.and_then(|context| context.policy_signature.as_deref()).unwrap_or("");
    let fingerprint_hash = log_context.and_then(|context| context.fingerprint_hash.as_deref()).unwrap_or("");
    let diagnostics_session_id =
        log_context.and_then(|context| context.diagnostics_session_id.as_deref()).unwrap_or("");

    match level.trim().to_ascii_lowercase().as_str() {
        "trace" => tracing::trace!(
            ring = "diagnostics",
            subsystem = "diagnostics",
            session = session_id,
            profile = profile_id,
            path_mode = scan_path_mode_label(path_mode),
            source,
            runtime_id,
            mode,
            policy_signature,
            fingerprint_hash,
            diagnostics_session_id,
            "{message}"
        ),
        "debug" => tracing::debug!(
            ring = "diagnostics",
            subsystem = "diagnostics",
            session = session_id,
            profile = profile_id,
            path_mode = scan_path_mode_label(path_mode),
            source,
            runtime_id,
            mode,
            policy_signature,
            fingerprint_hash,
            diagnostics_session_id,
            "{message}"
        ),
        "warn" | "warning" => tracing::warn!(
            ring = "diagnostics",
            subsystem = "diagnostics",
            session = session_id,
            profile = profile_id,
            path_mode = scan_path_mode_label(path_mode),
            source,
            runtime_id,
            mode,
            policy_signature,
            fingerprint_hash,
            diagnostics_session_id,
            "{message}"
        ),
        "error" => tracing::error!(
            ring = "diagnostics",
            subsystem = "diagnostics",
            session = session_id,
            profile = profile_id,
            path_mode = scan_path_mode_label(path_mode),
            source,
            runtime_id,
            mode,
            policy_signature,
            fingerprint_hash,
            diagnostics_session_id,
            "{message}"
        ),
        _ => tracing::info!(
            ring = "diagnostics",
            subsystem = "diagnostics",
            session = session_id,
            profile = profile_id,
            path_mode = scan_path_mode_label(path_mode),
            source,
            runtime_id,
            mode,
            policy_signature,
            fingerprint_hash,
            diagnostics_session_id,
            "{message}"
        ),
    }
}

pub(crate) fn push_event(
    shared: &Arc<Mutex<SharedState>>,
    session_id: &str,
    profile_id: &str,
    path_mode: &ScanPathMode,
    source: &str,
    level: &str,
    message: String,
) {
    let log_context = {
        let guard = shared.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        guard.log_context.clone()
    };
    emit_diagnostics_event(log_context.as_ref(), session_id, profile_id, path_mode, source, level, &message);
}
