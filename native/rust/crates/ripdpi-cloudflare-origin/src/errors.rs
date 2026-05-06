use std::io;

const STRUCTURED_ERROR_PREFIX: &str = "RIPDPI-ERROR|cloudflare-origin|";

pub(crate) fn join_error_to_io(error: tokio::task::JoinError) -> io::Error {
    io::Error::other(format!("cloudflare origin task join failed: {error}"))
}

pub(crate) fn classify_error(error: &io::Error) -> &'static str {
    let message = error.to_string().to_ascii_lowercase();
    if error.kind() == io::ErrorKind::PermissionDenied || message.contains("uuid") {
        "auth"
    } else if error.kind() == io::ErrorKind::NotFound || message.contains("resolve") || message.contains("dns") {
        "dns"
    } else if message.contains("vless") || message.contains("xhttp") {
        "handshake"
    } else {
        "tcp"
    }
}

pub(crate) fn emit_structured_error(failure_class: &str, error: &io::Error) {
    eprintln!(
        "{STRUCTURED_ERROR_PREFIX}{failure_class}|{}",
        error.to_string().replace('|', "/").replace(['\n', '\r'], " "),
    );
}
