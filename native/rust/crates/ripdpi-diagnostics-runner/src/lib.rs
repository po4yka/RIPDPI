pub mod connectivity;
pub mod domain;
pub mod strategy;

pub mod blockpage_fingerprints {
    pub use ripdpi_diagnostics_http::blockpage_fingerprints::*;
}
pub mod candidates {
    pub use ripdpi_diagnostics_candidates::candidates::*;
}
pub mod cdn_ech {
    pub use ripdpi_diagnostics_dns::cdn_ech::*;
}
pub mod classification {
    pub use ripdpi_diagnostics_classification::classification::*;
}
pub mod dns {
    pub use ripdpi_diagnostics_dns::dns::*;
}
pub mod dns_analysis {
    pub use ripdpi_diagnostics_dns::dns_analysis::*;
}
pub mod dns_oracle {
    pub use ripdpi_diagnostics_dns::dns_oracle::*;
}
pub mod fat_header {
    pub use ripdpi_diagnostics_fat_header::fat_header::*;
}
pub mod http {
    pub use ripdpi_diagnostics_http::http::*;
}
pub mod observations {
    pub use ripdpi_diagnostics_classification::observations::*;
}
pub mod telegram {
    pub use ripdpi_diagnostics_telegram::telegram::*;
}
pub mod tls {
    pub use ripdpi_diagnostics_tls::tls::*;
}
pub mod transport {
    pub use ripdpi_diagnostics_transport::transport::*;
}
pub mod util {
    pub use ripdpi_diagnostics_contracts::util::{
        classify_dns_answer_overlap, classify_probe_outcome, default_diagnosis_severity,
        default_diagnostic_profile_family, default_http_path, default_quic_port, default_scan_kind,
        default_strategy_probe_suite, default_telegram_dc_port, default_telegram_stall_timeout_ms,
        default_telegram_total_timeout_ms, default_telegram_upload_size, default_throughput_runs,
        default_throughput_window_bytes, event_level_for_outcome, fat_threshold_reached, find_headers_end,
        format_result_set, format_socket_result, ip_set, ipv4_prefix_24, ipv6_prefix_48,
        is_suspected_dns_tampering_outcome, late_stage_cutoff, looks_like_sinkhole, now_ms, parse_content_length,
        probe_session_seed, ranged_probe_delay, stable_probe_hash, DnsAnswerOverlap, ProbeOutcomeBucket,
        ProbeOutcomeClassification, CONNECT_TIMEOUT, DEFAULT_DNS_SERVER, DEFAULT_DOH_BOOTSTRAP_IPS, DEFAULT_DOH_HOST,
        DEFAULT_DOH_PORT, DEFAULT_DOH_URL, FAT_HEADER_REQUESTS, FAT_HEADER_THRESHOLD_BYTES,
        HTTP_FAKE_PROFILE_CLOUDFLARE_GET, IO_TIMEOUT, MAX_HTTP_BYTES, STRATEGY_PROBE_SUITE_FULL_MATRIX_V1,
        STRATEGY_PROBE_SUITE_QUICK_V1, TELEGRAM_CHUNK_SIZE, TELEGRAM_DOWNLOAD_EXPECTED_BYTES,
        TELEGRAM_SPEED_SAMPLE_INTERVAL, TLS_FAKE_PROFILE_GOOGLE_CHROME, TLS_FAKE_PROFILE_GOOGLE_CHROME_HRR,
        UDP_FAKE_PROFILE_DNS_QUERY,
    };
}

pub(crate) use ripdpi_diagnostics_contracts as types;

#[cfg(test)]
pub mod test_fixtures;
