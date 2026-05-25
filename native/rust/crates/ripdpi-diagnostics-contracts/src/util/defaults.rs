use crate::types::{DiagnosticProfileFamily, ScanKind};

use super::constants::STRATEGY_PROBE_SUITE_QUICK_V1;

pub fn default_http_path() -> String {
    "/".to_string()
}

pub fn default_quic_port() -> u16 {
    443
}

pub fn default_strategy_probe_suite() -> String {
    STRATEGY_PROBE_SUITE_QUICK_V1.to_string()
}

pub fn default_scan_kind() -> ScanKind {
    ScanKind::Connectivity
}

pub fn default_diagnostic_profile_family() -> DiagnosticProfileFamily {
    DiagnosticProfileFamily::General
}

pub fn default_throughput_window_bytes() -> usize {
    8 * 1024 * 1024
}

pub fn default_throughput_runs() -> usize {
    2
}

pub fn default_diagnosis_severity() -> String {
    "warning".to_string()
}

pub fn default_telegram_dc_port() -> u16 {
    443
}

pub fn default_telegram_stall_timeout_ms() -> u64 {
    3_000
}

pub fn default_telegram_total_timeout_ms() -> u64 {
    10_000
}

pub fn default_telegram_upload_size() -> usize {
    10_485_760
}
