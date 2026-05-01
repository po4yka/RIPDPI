use std::io::ErrorKind;

use crate::util::late_stage_cutoff;

use super::status_types::FatHeaderStatus;

/// Classify the TCP block method from the probe observation.
///
/// - `rst_injection`: RST received (connection reset / broken pipe).
/// - `window_cap`: Connection cut off after threshold bytes (16KB window capping).
/// - `timeout`: Connection timed out or froze.
/// - `connection_refused`: Could not establish TCP connection at all.
pub fn classify_tcp_block_method(status: &FatHeaderStatus) -> &'static str {
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
pub fn classify_rst_origin(syn_ack_ms: Option<u64>, rst_ms: Option<u64>) -> &'static str {
    match (syn_ack_ms, rst_ms) {
        (Some(rtt), Some(rst)) if rtt > 0 && rst <= rtt.saturating_mul(2) => "in_path_rst",
        (Some(_), Some(_)) => "server_rst",
        _ => "unknown",
    }
}

pub fn fat_status_label(status: &FatHeaderStatus) -> &'static str {
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

pub fn classify_fat_io_error(kind: ErrorKind, bytes_sent: usize, responses_seen: usize) -> FatHeaderStatus {
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

pub fn classify_fat_error_message(message: &str, bytes_sent: usize, responses_seen: usize) -> FatHeaderStatus {
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
