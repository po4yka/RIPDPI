mod endpoint;
mod probes;
mod report;

use std::sync::{Arc, Mutex};

use android_support::log_with_level;

use crate::types::SharedState;
use crate::types::*;
use crate::util::*;

pub(crate) use probes::{
    run_circumvention_probe, run_dns_probe, run_domain_probe, run_quic_probe, run_service_probe, run_tcp_probe,
    run_throughput_probe,
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

fn diagnostics_log_message(
    session_id: &str,
    profile_id: &str,
    path_mode: &ScanPathMode,
    source: &str,
    message: &str,
) -> String {
    format!(
        "subsystem=diagnostics session={} profile={} pathMode={} source={} {}",
        session_id,
        profile_id,
        scan_path_mode_label(path_mode),
        source,
        message
    )
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
    log_with_level(level, diagnostics_log_message(session_id, profile_id, path_mode, source, &message));
    let mut guard = shared.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    if guard.passive_events.len() >= MAX_PASSIVE_EVENTS {
        guard.passive_events.pop_front();
    }
    guard.passive_events.push_back(NativeSessionEvent {
        source: source.to_string(),
        level: level.to_string(),
        message,
        created_at: now_ms(),
    });
}
