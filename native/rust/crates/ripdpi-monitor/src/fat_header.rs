use std::io::{ErrorKind, Write};
use std::net::IpAddr;
use std::sync::Arc;
use std::time::Instant;

use crate::http::read_http_headers;
use crate::tls::{open_probe_stream, NoCertificateVerification, TlsClientProfile};
use crate::transport::{TargetAddress, TransportConfig};
use crate::types::TcpTarget;
use crate::util::*;

// --- Types ---

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum FatHeaderStatus {
    Success,
    ThresholdCutoff,
    FreezeAfterThreshold,
    Reset,
    Timeout,
    ConnectFailed,
    HandshakeFailed,
}

#[derive(Clone, Debug)]
pub(crate) struct FatHeaderObservation {
    pub(crate) status: FatHeaderStatus,
    pub(crate) bytes_sent: usize,
    pub(crate) responses_seen: usize,
    pub(crate) error: Option<String>,
    /// Time from SYN to SYN-ACK (TCP connect latency), establishing baseline RTT.
    pub(crate) syn_ack_latency_ms: Option<u64>,
    /// Time from the last successful I/O (SYN-ACK or last response) to the RST/error.
    /// Compared against `syn_ack_latency_ms` to classify in-path vs server RST.
    pub(crate) rst_timing_ms: Option<u64>,
    /// TCP window size observed from the server, if available (for window-cap detection).
    pub(crate) observed_window_size: Option<u32>,
}

// --- Functions ---

pub(crate) fn run_fat_header_attempt(
    target: &TcpTarget,
    transport: &TransportConfig,
    sni: &str,
    host_header: &str,
) -> FatHeaderObservation {
    let connect_target = match target.ip.parse::<IpAddr>() {
        Ok(ip) => TargetAddress::Ip(ip),
        Err(err) => {
            return FatHeaderObservation {
                status: FatHeaderStatus::ConnectFailed,
                bytes_sent: 0,
                responses_seen: 0,
                error: Some(err.to_string()),
                syn_ack_latency_ms: None,
                rst_timing_ms: None,
                observed_window_size: None,
            }
        }
    };

    let tls_sni = if !sni.is_empty() { Some(sni) } else { None };
    let uses_tls = tls_sni.is_some();
    // Diagnostic probe: explicitly skip certificate verification to detect
    // censorship-induced TLS interception (MITM middleboxes).
    let no_verify: Arc<dyn rustls::client::danger::ServerCertVerifier> = Arc::new(NoCertificateVerification);
    let probe_result = match open_probe_stream(
        &connect_target,
        target.port,
        transport,
        tls_sni,
        false,
        TlsClientProfile::Auto,
        Some(&no_verify),
    ) {
        Ok(result) => result,
        Err(err) => {
            let status = if uses_tls { FatHeaderStatus::HandshakeFailed } else { FatHeaderStatus::ConnectFailed };
            return FatHeaderObservation {
                status,
                bytes_sent: 0,
                responses_seen: 0,
                error: Some(err),
                syn_ack_latency_ms: None,
                rst_timing_ms: None,
                observed_window_size: None,
            };
        }
    };
    let syn_ack_latency_ms = Some(probe_result.tcp_connect_ms);
    let mut stream = probe_result.stream;

    let requests = target.fat_header_requests.unwrap_or(FAT_HEADER_REQUESTS).max(1);
    let mut bytes_sent = 0usize;
    let mut responses_seen = 0usize;
    let host_header = if host_header.is_empty() { "localhost" } else { host_header };
    // Track the time of the last successful I/O to measure RST timing.
    let mut last_io_success = Instant::now();

    for index in 0..requests {
        let pad = "A".repeat(8 * 1024 + (index * 128));
        let payload =
            format!("HEAD / HTTP/1.1\r\nHost: {host_header}\r\nConnection: keep-alive\r\nX-Pad: {pad}\r\n\r\n");
        bytes_sent += payload.len();
        if let Err(err) = stream.write_all(payload.as_bytes()).and_then(|_| stream.flush()) {
            let status = classify_fat_io_error(err.kind(), bytes_sent, responses_seen);
            let rst_timing_ms = Some(last_io_success.elapsed().as_millis() as u64);
            stream.shutdown();
            return FatHeaderObservation {
                status,
                bytes_sent,
                responses_seen,
                error: Some(err.to_string()),
                syn_ack_latency_ms,
                rst_timing_ms,
                observed_window_size: None,
            };
        }
        last_io_success = Instant::now();

        match read_http_headers(&mut stream, MAX_HTTP_BYTES) {
            Ok(_response_bytes) => {
                responses_seen += 1;
                last_io_success = Instant::now();
            }
            Err(err) => {
                let status = classify_fat_error_message(&err, bytes_sent, responses_seen);
                let rst_timing_ms = Some(last_io_success.elapsed().as_millis() as u64);
                stream.shutdown();
                return FatHeaderObservation {
                    status,
                    bytes_sent,
                    responses_seen,
                    error: Some(err),
                    syn_ack_latency_ms,
                    rst_timing_ms,
                    observed_window_size: None,
                };
            }
        }
    }

    stream.shutdown();
    FatHeaderObservation {
        status: FatHeaderStatus::Success,
        bytes_sent,
        responses_seen,
        error: None,
        syn_ack_latency_ms,
        rst_timing_ms: None,
        observed_window_size: None,
    }
}

pub(crate) fn classify_fat_header_outcome(status: &FatHeaderStatus) -> &'static str {
    match status {
        FatHeaderStatus::Success => "tcp_fat_header_ok",
        FatHeaderStatus::ThresholdCutoff => "tcp_16kb_blocked",
        FatHeaderStatus::FreezeAfterThreshold => "tcp_freeze_after_threshold",
        FatHeaderStatus::Reset => "tcp_reset",
        FatHeaderStatus::Timeout => "tcp_timeout",
        FatHeaderStatus::ConnectFailed => "tcp_connect_failed",
        FatHeaderStatus::HandshakeFailed => "tls_handshake_failed",
    }
}

/// Classify the TCP block method from the probe observation.
///
/// - `rst_injection`: RST received (connection reset / broken pipe).
/// - `window_cap`: Connection cut off after threshold bytes (16KB window capping).
/// - `timeout`: Connection timed out or froze.
/// - `connection_refused`: Could not establish TCP connection at all.
pub(crate) fn classify_tcp_block_method(status: &FatHeaderStatus) -> &'static str {
    match status {
        FatHeaderStatus::Success => "none",
        FatHeaderStatus::Reset => "rst_injection",
        FatHeaderStatus::ThresholdCutoff | FatHeaderStatus::FreezeAfterThreshold => "window_cap",
        FatHeaderStatus::Timeout => "timeout",
        FatHeaderStatus::ConnectFailed | FatHeaderStatus::HandshakeFailed => "connection_refused",
    }
}

/// Classify RST origin based on timing relative to the SYN-ACK RTT baseline.
///
/// If RST arrives within 2x the SYN-ACK RTT, it is likely injected by an
/// in-path DPI device racing the real server. If it arrives later, it is
/// more likely a legitimate server rejection.
pub(crate) fn classify_rst_origin(syn_ack_ms: Option<u64>, rst_ms: Option<u64>) -> &'static str {
    match (syn_ack_ms, rst_ms) {
        (Some(rtt), Some(rst)) if rtt > 0 && rst <= rtt.saturating_mul(2) => "in_path_rst",
        (Some(_), Some(_)) => "server_rst",
        _ => "unknown",
    }
}

pub(crate) fn fat_status_label(status: &FatHeaderStatus) -> &'static str {
    match status {
        FatHeaderStatus::Success => "ok",
        FatHeaderStatus::ThresholdCutoff => "threshold_cutoff",
        FatHeaderStatus::FreezeAfterThreshold => "freeze_after_threshold",
        FatHeaderStatus::Reset => "reset",
        FatHeaderStatus::Timeout => "timeout",
        FatHeaderStatus::ConnectFailed => "connect_failed",
        FatHeaderStatus::HandshakeFailed => "tls_failed",
    }
}

pub(crate) fn classify_fat_io_error(kind: ErrorKind, bytes_sent: usize, responses_seen: usize) -> FatHeaderStatus {
    match kind {
        ErrorKind::TimedOut | ErrorKind::WouldBlock => {
            if late_stage_cutoff(bytes_sent, responses_seen) {
                FatHeaderStatus::FreezeAfterThreshold
            } else {
                FatHeaderStatus::Timeout
            }
        }
        ErrorKind::ConnectionReset
        | ErrorKind::UnexpectedEof
        | ErrorKind::BrokenPipe
        | ErrorKind::ConnectionAborted => {
            if late_stage_cutoff(bytes_sent, responses_seen) {
                FatHeaderStatus::ThresholdCutoff
            } else {
                FatHeaderStatus::Reset
            }
        }
        _ => FatHeaderStatus::ConnectFailed,
    }
}

pub(crate) fn classify_fat_error_message(message: &str, bytes_sent: usize, responses_seen: usize) -> FatHeaderStatus {
    let lower = message.to_ascii_lowercase();
    if lower.contains("timed out") {
        if late_stage_cutoff(bytes_sent, responses_seen) {
            FatHeaderStatus::FreezeAfterThreshold
        } else {
            FatHeaderStatus::Timeout
        }
    } else if lower.contains("connection reset")
        || lower.contains("broken pipe")
        || lower.contains("unexpected eof")
        || lower.contains("connection aborted")
    {
        if late_stage_cutoff(bytes_sent, responses_seen) {
            FatHeaderStatus::ThresholdCutoff
        } else {
            FatHeaderStatus::Reset
        }
    } else {
        FatHeaderStatus::ConnectFailed
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classify_fat_io_error_timeout() {
        assert_eq!(classify_fat_io_error(ErrorKind::TimedOut, 0, 0), FatHeaderStatus::Timeout);
        assert_eq!(classify_fat_io_error(ErrorKind::WouldBlock, 0, 0), FatHeaderStatus::Timeout);
    }

    #[test]
    fn classify_fat_io_error_timeout_before_threshold_unchanged() {
        assert_eq!(classify_fat_io_error(ErrorKind::TimedOut, 100, 0), FatHeaderStatus::Timeout);
    }

    #[test]
    fn classify_fat_io_error_freeze_after_threshold_bytes() {
        assert_eq!(
            classify_fat_io_error(ErrorKind::TimedOut, FAT_HEADER_THRESHOLD_BYTES, 0),
            FatHeaderStatus::FreezeAfterThreshold
        );
    }

    #[test]
    fn classify_fat_io_error_freeze_after_threshold_with_responses() {
        assert_eq!(classify_fat_io_error(ErrorKind::WouldBlock, 8 * 1024, 1), FatHeaderStatus::FreezeAfterThreshold);
    }

    #[test]
    fn classify_fat_io_error_reset_early() {
        assert_eq!(classify_fat_io_error(ErrorKind::ConnectionReset, 100, 0), FatHeaderStatus::Reset);
        assert_eq!(classify_fat_io_error(ErrorKind::BrokenPipe, 100, 0), FatHeaderStatus::Reset);
    }

    #[test]
    fn classify_fat_io_error_threshold_cutoff_late() {
        assert_eq!(
            classify_fat_io_error(ErrorKind::ConnectionReset, FAT_HEADER_THRESHOLD_BYTES, 0),
            FatHeaderStatus::ThresholdCutoff
        );
    }

    #[test]
    fn classify_fat_io_error_threshold_cutoff_with_responses() {
        assert_eq!(classify_fat_io_error(ErrorKind::UnexpectedEof, 8 * 1024, 1), FatHeaderStatus::ThresholdCutoff);
    }

    #[test]
    fn classify_fat_io_error_unknown_is_connect_failed() {
        assert_eq!(classify_fat_io_error(ErrorKind::Other, 0, 0), FatHeaderStatus::ConnectFailed);
    }

    #[test]
    fn classify_fat_error_message_timed_out() {
        assert_eq!(classify_fat_error_message("timed out", 0, 0), FatHeaderStatus::Timeout);
    }

    #[test]
    fn classify_fat_error_message_timeout_before_threshold_unchanged() {
        assert_eq!(classify_fat_error_message("timed out", 100, 0), FatHeaderStatus::Timeout);
    }

    #[test]
    fn classify_fat_error_message_freeze_after_threshold() {
        assert_eq!(
            classify_fat_error_message("timed out", FAT_HEADER_THRESHOLD_BYTES, 0),
            FatHeaderStatus::FreezeAfterThreshold
        );
    }

    #[test]
    fn classify_fat_error_message_freeze_after_threshold_with_responses() {
        assert_eq!(classify_fat_error_message("timed out", 8 * 1024, 1), FatHeaderStatus::FreezeAfterThreshold);
    }

    #[test]
    fn classify_fat_error_message_connection_reset_early() {
        assert_eq!(classify_fat_error_message("connection reset", 100, 0), FatHeaderStatus::Reset);
    }

    #[test]
    fn classify_fat_error_message_connection_reset_late() {
        assert_eq!(
            classify_fat_error_message("connection reset", FAT_HEADER_THRESHOLD_BYTES, 0),
            FatHeaderStatus::ThresholdCutoff
        );
    }

    #[test]
    fn classify_fat_error_message_broken_pipe() {
        assert_eq!(classify_fat_error_message("broken pipe", 8 * 1024, 1), FatHeaderStatus::ThresholdCutoff);
    }

    #[test]
    fn classify_fat_error_message_unknown() {
        assert_eq!(classify_fat_error_message("something else", 0, 0), FatHeaderStatus::ConnectFailed);
    }

    #[test]
    fn classify_fat_header_outcome_all_variants() {
        assert_eq!(classify_fat_header_outcome(&FatHeaderStatus::Success), "tcp_fat_header_ok");
        assert_eq!(classify_fat_header_outcome(&FatHeaderStatus::ThresholdCutoff), "tcp_16kb_blocked");
        assert_eq!(classify_fat_header_outcome(&FatHeaderStatus::FreezeAfterThreshold), "tcp_freeze_after_threshold");
        assert_eq!(classify_fat_header_outcome(&FatHeaderStatus::Reset), "tcp_reset");
        assert_eq!(classify_fat_header_outcome(&FatHeaderStatus::Timeout), "tcp_timeout");
        assert_eq!(classify_fat_header_outcome(&FatHeaderStatus::ConnectFailed), "tcp_connect_failed");
        assert_eq!(classify_fat_header_outcome(&FatHeaderStatus::HandshakeFailed), "tls_handshake_failed");
    }

    #[test]
    fn classify_tcp_block_method_all_variants() {
        assert_eq!(classify_tcp_block_method(&FatHeaderStatus::Success), "none");
        assert_eq!(classify_tcp_block_method(&FatHeaderStatus::Reset), "rst_injection");
        assert_eq!(classify_tcp_block_method(&FatHeaderStatus::ThresholdCutoff), "window_cap");
        assert_eq!(classify_tcp_block_method(&FatHeaderStatus::FreezeAfterThreshold), "window_cap");
        assert_eq!(classify_tcp_block_method(&FatHeaderStatus::Timeout), "timeout");
        assert_eq!(classify_tcp_block_method(&FatHeaderStatus::ConnectFailed), "connection_refused");
        assert_eq!(classify_tcp_block_method(&FatHeaderStatus::HandshakeFailed), "connection_refused");
    }

    #[test]
    fn classify_rst_origin_in_path() {
        // RST at 10ms with RTT of 8ms => within 2x => in-path
        assert_eq!(classify_rst_origin(Some(8), Some(10)), "in_path_rst");
        // RST at exactly 2x RTT => still in-path
        assert_eq!(classify_rst_origin(Some(10), Some(20)), "in_path_rst");
    }

    #[test]
    fn classify_rst_origin_server() {
        // RST at 50ms with RTT of 10ms => beyond 2x => server
        assert_eq!(classify_rst_origin(Some(10), Some(50)), "server_rst");
    }

    #[test]
    fn classify_rst_origin_unknown() {
        assert_eq!(classify_rst_origin(None, Some(10)), "unknown");
        assert_eq!(classify_rst_origin(Some(10), None), "unknown");
        assert_eq!(classify_rst_origin(None, None), "unknown");
    }

    #[test]
    fn classify_rst_origin_zero_rtt_is_server() {
        // Zero RTT means we cannot meaningfully compare, falls through to server_rst
        assert_eq!(classify_rst_origin(Some(0), Some(5)), "server_rst");
    }

    #[test]
    fn fat_status_label_all_variants() {
        assert_eq!(fat_status_label(&FatHeaderStatus::Success), "ok");
        assert_eq!(fat_status_label(&FatHeaderStatus::ThresholdCutoff), "threshold_cutoff");
        assert_eq!(fat_status_label(&FatHeaderStatus::FreezeAfterThreshold), "freeze_after_threshold");
        assert_eq!(fat_status_label(&FatHeaderStatus::Reset), "reset");
        assert_eq!(fat_status_label(&FatHeaderStatus::Timeout), "timeout");
        assert_eq!(fat_status_label(&FatHeaderStatus::ConnectFailed), "connect_failed");
        assert_eq!(fat_status_label(&FatHeaderStatus::HandshakeFailed), "tls_failed");
    }
}
