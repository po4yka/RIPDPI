use std::io::ErrorKind;

use super::status_types::FatHeaderStatus;

/// Describe the observed failure without attributing its source or mechanism.
pub fn classify_tcp_block_method(status: &FatHeaderStatus) -> &'static str {
    match status {
        FatHeaderStatus::Success | FatHeaderStatus::Inconclusive => "none",
        FatHeaderStatus::Reset => "reset",
        FatHeaderStatus::Timeout => "timeout",
        FatHeaderStatus::ConnectFailed => "connect_failed",
        FatHeaderStatus::HandshakeFailed => "tls_handshake_failed",
    }
}

pub fn fat_status_label(status: &FatHeaderStatus) -> &'static str {
    match status {
        FatHeaderStatus::Success => "ok",
        FatHeaderStatus::Inconclusive => "inconclusive",
        FatHeaderStatus::Reset => "reset",
        FatHeaderStatus::Timeout => "timeout",
        FatHeaderStatus::ConnectFailed => "connect_failed",
        FatHeaderStatus::HandshakeFailed => "tls_failed",
    }
}

pub(super) fn classify_response_io_error(kind: ErrorKind) -> FatHeaderStatus {
    match kind {
        ErrorKind::TimedOut | ErrorKind::WouldBlock => FatHeaderStatus::Timeout,
        ErrorKind::ConnectionReset => FatHeaderStatus::Reset,
        _ => FatHeaderStatus::Inconclusive,
    }
}
