use std::io::ErrorKind;

use crate::util::FAT_HEADER_THRESHOLD_BYTES;

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
    // RST at 10ms with RTT of 8ms => within 2x => in-path.
    assert_eq!(classify_rst_origin(Some(8), Some(10)), "in_path_rst");
    // RST at exactly 2x RTT => still in-path.
    assert_eq!(classify_rst_origin(Some(10), Some(20)), "in_path_rst");
}

#[test]
fn classify_rst_origin_server() {
    // RST at 50ms with RTT of 10ms => beyond 2x => server.
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
    // Zero RTT means we cannot meaningfully compare, falls through to server_rst.
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
