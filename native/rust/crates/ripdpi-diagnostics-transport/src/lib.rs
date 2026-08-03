// Crate-local unsafe hardening. The single `unsafe` block in this crate is the
// `getsockopt(IP_TTL)` FFI call in `platform_ttl`. Re-enabling the
// workspace-deferred `undocumented_unsafe_blocks` and
// `multiple_unsafe_ops_per_block` lints locally turns the per-block
// `// SAFETY:` documentation requirement into a build-time error here, matching
// the convention in ripdpi-vless / ripdpi-io-uring / ripdpi-privileged-ops /
// ripdpi-proxy-runtime, while the rest of the workspace migrates gradually.
#![warn(clippy::undocumented_unsafe_blocks)]
#![warn(clippy::multiple_unsafe_ops_per_block)]

pub mod platform_ttl;
pub mod transport;
pub mod ws_tls;

pub(crate) mod util {
    #![allow(unused_imports)]
    pub use ripdpi_diagnostics_contracts::util::{
        CONNECT_TIMEOUT, DEFAULT_DNS_SERVER, DEFAULT_DOH_BOOTSTRAP_IPS, DEFAULT_DOH_HOST, DEFAULT_DOH_PORT,
        DEFAULT_DOH_URL, DnsAnswerOverlap, FAT_HEADER_REQUESTS, FAT_HEADER_THRESHOLD_BYTES,
        HTTP_FAKE_PROFILE_CLOUDFLARE_GET, IO_TIMEOUT, MAX_HTTP_BYTES, ProbeOutcomeBucket, ProbeOutcomeClassification,
        STRATEGY_PROBE_SUITE_FULL_MATRIX_V1, STRATEGY_PROBE_SUITE_QUICK_V1, TELEGRAM_CHUNK_SIZE,
        TELEGRAM_DOWNLOAD_EXPECTED_BYTES, TELEGRAM_SPEED_SAMPLE_INTERVAL, TLS_FAKE_PROFILE_GOOGLE_CHROME,
        TLS_FAKE_PROFILE_GOOGLE_CHROME_HRR, UDP_FAKE_PROFILE_DNS_QUERY, bounded_scan_io_timeout,
        classify_dns_answer_overlap, classify_probe_outcome, default_diagnosis_severity,
        default_diagnostic_profile_family, default_http_path, default_quic_port, default_scan_kind,
        default_strategy_probe_suite, default_telegram_dc_port, default_telegram_stall_timeout_ms,
        default_telegram_total_timeout_ms, default_telegram_upload_size, default_throughput_runs,
        default_throughput_window_bytes, event_level_for_outcome, fat_threshold_reached, find_headers_end,
        format_result_set, format_socket_result, ip_set, ipv4_prefix_24, ipv6_prefix_48,
        is_suspected_dns_tampering_outcome, late_stage_cutoff, looks_like_sinkhole, now_ms, parse_content_length,
        probe_session_seed, ranged_probe_delay, stable_probe_hash,
    };
}

pub(crate) use ripdpi_diagnostics_contracts as types;
