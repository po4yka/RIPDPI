use std::io;

pub(crate) enum ProbeResult {
    Success,
    DpiFailure(&'static str),
    NetworkError(&'static str),
}

pub(crate) fn classify_connect_error(err: &io::Error) -> ProbeResult {
    if is_dpi_connect_error(err) {
        ProbeResult::DpiFailure("connect_reset")
    } else {
        ProbeResult::NetworkError("connect_failed")
    }
}

pub(crate) fn classify_write_error(err: &io::Error) -> ProbeResult {
    if is_dpi_write_error(err) {
        ProbeResult::DpiFailure("write_reset")
    } else {
        ProbeResult::NetworkError("write_failed")
    }
}

pub(crate) fn classify_tls_response(header: [u8; 5], handshake_type: Option<u8>) -> ProbeResult {
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

pub(crate) fn classify_read_error(err: &io::Error) -> ProbeResult {
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
