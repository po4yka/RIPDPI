mod constants;
mod deadline;
mod defaults;
mod dns_helpers;
mod http_helpers;
mod outcome_policy;
mod scheduling;
#[cfg(test)]
mod tests;
mod time;

pub use constants::{
    CONNECT_TIMEOUT, DEFAULT_DNS_SERVER, DEFAULT_DOH_BOOTSTRAP_IPS, DEFAULT_DOH_HOST, DEFAULT_DOH_PORT,
    DEFAULT_DOH_URL, FAT_HEADER_REQUESTS, FAT_HEADER_THRESHOLD_BYTES, HTTP_FAKE_PROFILE_CLOUDFLARE_GET, IO_TIMEOUT,
    MAX_HTTP_BYTES, STRATEGY_PROBE_SUITE_FULL_MATRIX_V1, STRATEGY_PROBE_SUITE_QUICK_V1, TELEGRAM_CHUNK_SIZE,
    TELEGRAM_DOWNLOAD_EXPECTED_BYTES, TELEGRAM_SPEED_SAMPLE_INTERVAL, TLS_FAKE_PROFILE_GOOGLE_CHROME,
    TLS_FAKE_PROFILE_GOOGLE_CHROME_HRR, UDP_FAKE_PROFILE_DNS_QUERY,
};
pub use deadline::{active_scan_io_deadline, bounded_scan_io_timeout, with_scan_io_deadline};
pub use defaults::{
    default_diagnosis_severity, default_diagnostic_profile_family, default_http_path, default_quic_port,
    default_scan_kind, default_strategy_probe_suite, default_telegram_dc_port, default_telegram_stall_timeout_ms,
    default_telegram_total_timeout_ms, default_telegram_upload_size, default_throughput_runs,
    default_throughput_window_bytes,
};
pub use dns_helpers::{
    DnsAnswerOverlap, classify_dns_answer_overlap, format_result_set, format_socket_result, ip_set, ipv4_prefix_24,
    ipv6_prefix_48, is_suspected_dns_tampering_outcome, looks_like_sinkhole,
};
pub use http_helpers::{fat_threshold_reached, find_headers_end, late_stage_cutoff, parse_content_length};
pub use outcome_policy::{
    ProbeOutcomeBucket, ProbeOutcomeClassification, classify_probe_outcome, event_level_for_outcome,
};
pub use scheduling::{probe_session_seed, ranged_probe_delay, stable_probe_hash};
pub use time::now_ms;
