mod classification;
mod probe_execution;
mod request_io;
mod status_types;
#[cfg(test)]
mod tests;
mod timing;
mod tls_setup;

pub use classification::{
    classify_fat_error_message, classify_fat_io_error, classify_rst_origin, classify_tcp_block_method, fat_status_label,
};
pub use probe_execution::{run_fat_header_attempt, run_fat_header_attempt_with_key_log};
pub use status_types::{FatHeaderObservation, FatHeaderStatus};

pub fn classify_fat_header_outcome(status: &FatHeaderStatus) -> &'static str {
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
