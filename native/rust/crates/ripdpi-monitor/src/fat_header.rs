use std::io::{ErrorKind, Write};
use std::net::IpAddr;

use crate::http::read_http_headers;
use crate::tls::{open_probe_stream, TlsClientProfile};
use crate::transport::{TargetAddress, TransportConfig};
use crate::types::TcpTarget;
use crate::util::*;

// --- Types ---

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum FatHeaderStatus {
    Success,
    ThresholdCutoff,
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
            }
        }
    };

    let tls_sni = if !sni.is_empty() { Some(sni) } else { None };
    let uses_tls = tls_sni.is_some();
    let mut stream = match open_probe_stream(
        &connect_target,
        target.port,
        transport,
        tls_sni,
        false,
        TlsClientProfile::Auto,
        None,
    ) {
        Ok(result) => result.stream,
        Err(err) => {
            let status = if uses_tls { FatHeaderStatus::HandshakeFailed } else { FatHeaderStatus::ConnectFailed };
            return FatHeaderObservation { status, bytes_sent: 0, responses_seen: 0, error: Some(err) };
        }
    };

    let requests = target.fat_header_requests.unwrap_or(FAT_HEADER_REQUESTS).max(1);
    let mut bytes_sent = 0usize;
    let mut responses_seen = 0usize;
    let host_header = if host_header.is_empty() { "localhost" } else { host_header };

    for index in 0..requests {
        let pad = "A".repeat(8 * 1024 + (index * 128));
        let payload =
            format!("HEAD / HTTP/1.1\r\nHost: {host_header}\r\nConnection: keep-alive\r\nX-Pad: {pad}\r\n\r\n");
        bytes_sent += payload.len();
        if let Err(err) = stream.write_all(payload.as_bytes()).and_then(|_| stream.flush()) {
            let status = classify_fat_io_error(err.kind(), bytes_sent, responses_seen);
            stream.shutdown();
            return FatHeaderObservation { status, bytes_sent, responses_seen, error: Some(err.to_string()) };
        }

        match read_http_headers(&mut stream, MAX_HTTP_BYTES) {
            Ok(_) => {
                responses_seen += 1;
            }
            Err(err) => {
                let status = classify_fat_error_message(&err, bytes_sent, responses_seen);
                stream.shutdown();
                return FatHeaderObservation { status, bytes_sent, responses_seen, error: Some(err) };
            }
        }
    }

    stream.shutdown();
    FatHeaderObservation { status: FatHeaderStatus::Success, bytes_sent, responses_seen, error: None }
}

pub(crate) fn classify_fat_header_outcome(status: &FatHeaderStatus) -> &'static str {
    match status {
        FatHeaderStatus::Success => "tcp_fat_header_ok",
        FatHeaderStatus::ThresholdCutoff => "tcp_16kb_blocked",
        FatHeaderStatus::Reset => "tcp_reset",
        FatHeaderStatus::Timeout => "tcp_timeout",
        FatHeaderStatus::ConnectFailed => "tcp_connect_failed",
        FatHeaderStatus::HandshakeFailed => "tls_handshake_failed",
    }
}

pub(crate) fn fat_status_label(status: &FatHeaderStatus) -> &'static str {
    match status {
        FatHeaderStatus::Success => "ok",
        FatHeaderStatus::ThresholdCutoff => "threshold_cutoff",
        FatHeaderStatus::Reset => "reset",
        FatHeaderStatus::Timeout => "timeout",
        FatHeaderStatus::ConnectFailed => "connect_failed",
        FatHeaderStatus::HandshakeFailed => "tls_failed",
    }
}

pub(crate) fn classify_fat_io_error(kind: ErrorKind, bytes_sent: usize, responses_seen: usize) -> FatHeaderStatus {
    match kind {
        ErrorKind::TimedOut | ErrorKind::WouldBlock => FatHeaderStatus::Timeout,
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
        FatHeaderStatus::Timeout
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
        assert_eq!(classify_fat_header_outcome(&FatHeaderStatus::Reset), "tcp_reset");
        assert_eq!(classify_fat_header_outcome(&FatHeaderStatus::Timeout), "tcp_timeout");
        assert_eq!(classify_fat_header_outcome(&FatHeaderStatus::ConnectFailed), "tcp_connect_failed");
        assert_eq!(classify_fat_header_outcome(&FatHeaderStatus::HandshakeFailed), "tls_handshake_failed");
    }

    #[test]
    fn fat_status_label_all_variants() {
        assert_eq!(fat_status_label(&FatHeaderStatus::Success), "ok");
        assert_eq!(fat_status_label(&FatHeaderStatus::ThresholdCutoff), "threshold_cutoff");
        assert_eq!(fat_status_label(&FatHeaderStatus::Reset), "reset");
        assert_eq!(fat_status_label(&FatHeaderStatus::Timeout), "timeout");
        assert_eq!(fat_status_label(&FatHeaderStatus::ConnectFailed), "connect_failed");
        assert_eq!(fat_status_label(&FatHeaderStatus::HandshakeFailed), "tls_failed");
    }
}
