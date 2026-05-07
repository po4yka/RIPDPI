use std::io;
use std::net::{IpAddr, SocketAddr};

pub use ripdpi_failure_classifier::*;

pub fn should_track_strategy_target(target: SocketAddr) -> bool {
    !is_tunnel_infrastructure_dns_target(target)
}

pub enum ProbeResult {
    Success,
    DpiFailure(&'static str),
    NetworkError(&'static str),
}

pub fn classify_probe_connect_error(err: &io::Error) -> ProbeResult {
    if is_dpi_connect_error(err) {
        ProbeResult::DpiFailure("connect_reset")
    } else {
        ProbeResult::NetworkError("connect_failed")
    }
}

pub fn classify_probe_write_error(err: &io::Error) -> ProbeResult {
    if is_dpi_write_error(err) {
        ProbeResult::DpiFailure("write_reset")
    } else {
        ProbeResult::NetworkError("write_failed")
    }
}

pub fn classify_probe_tls_response(header: [u8; 5], handshake_type: Option<u8>) -> ProbeResult {
    if header[0] == 0x16 {
        classify_handshake_record(handshake_type)
    } else if header[0] == 0x15 {
        ProbeResult::DpiFailure("tls_alert")
    } else if header.starts_with(b"HTTP/") {
        ProbeResult::DpiFailure("http_blockpage")
    } else {
        ProbeResult::DpiFailure("unexpected_response")
    }
}

pub fn classify_probe_read_error(err: &io::Error) -> ProbeResult {
    if err.kind() == io::ErrorKind::TimedOut {
        ProbeResult::DpiFailure("timeout")
    } else if err.kind() == io::ErrorKind::ConnectionReset {
        ProbeResult::DpiFailure("read_reset")
    } else if err.kind() == io::ErrorKind::UnexpectedEof {
        ProbeResult::DpiFailure("eof")
    } else {
        ProbeResult::NetworkError("read_failed")
    }
}

pub fn classify_warmup_send_error(err: &io::Error) -> ClassifiedFailure {
    classify_transport_error(FailureStage::FirstWrite, err)
}

pub fn classify_warmup_first_response_error(err: &io::Error) -> ClassifiedFailure {
    classify_transport_error(FailureStage::FirstResponse, err)
}

pub fn classify_warmup_closed_before_response() -> ClassifiedFailure {
    ClassifiedFailure::new(
        FailureClass::SilentDrop,
        FailureStage::FirstResponse,
        FailureAction::RetryWithMatchingGroup,
        "warmup: upstream closed before first response",
    )
}

pub fn classify_first_response_closed_before_response() -> ClassifiedFailure {
    ClassifiedFailure::new(
        FailureClass::SilentDrop,
        FailureStage::FirstResponse,
        FailureAction::RetryWithMatchingGroup,
        "upstream closed before first response",
    )
}

pub fn classify_first_response_partial_tls_timeout() -> ClassifiedFailure {
    ClassifiedFailure::new(
        FailureClass::SilentDrop,
        FailureStage::FirstResponse,
        FailureAction::RetryWithMatchingGroup,
        "partial TLS response timed out",
    )
}

pub fn classify_first_response_transport_error(err: &io::Error) -> ClassifiedFailure {
    classify_transport_error(FailureStage::FirstResponse, err)
}

fn is_tunnel_infrastructure_dns_target(target: SocketAddr) -> bool {
    if target.port() != 853 {
        return false;
    }
    match target.ip() {
        IpAddr::V4(ipv4) => {
            let [a, b, ..] = ipv4.octets();
            a == 198 && matches!(b, 18 | 19)
        }
        IpAddr::V6(_) => false,
    }
}

fn classify_handshake_record(handshake_type: Option<u8>) -> ProbeResult {
    if handshake_type == Some(0x02) {
        ProbeResult::Success
    } else {
        ProbeResult::DpiFailure("tls_unexpected_handshake")
    }
}

fn is_dpi_connect_error(err: &io::Error) -> bool {
    matches!(err.kind(), io::ErrorKind::ConnectionReset | io::ErrorKind::ConnectionRefused | io::ErrorKind::TimedOut)
}

fn is_dpi_write_error(err: &io::Error) -> bool {
    matches!(err.kind(), io::ErrorKind::ConnectionReset | io::ErrorKind::BrokenPipe)
}
