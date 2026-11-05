use std::io::ErrorKind;

use crate::util::late_stage_cutoff;

use super::status_types::FatHeaderStatus;

/// Describe the observed failure without attributing its source or mechanism.
pub fn classify_tcp_block_method(status: &FatHeaderStatus) -> &'static str {
    match status {
        FatHeaderStatus::Success => "none",
        FatHeaderStatus::Reset => "reset_or_close",
        FatHeaderStatus::ThresholdCutoff => "transfer_cutoff",
        FatHeaderStatus::FreezeAfterThreshold => "transfer_stall",
        FatHeaderStatus::Timeout => "timeout",
        FatHeaderStatus::ConnectFailed => "connect_failed",
        FatHeaderStatus::HandshakeFailed => "tls_handshake_failed",
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
