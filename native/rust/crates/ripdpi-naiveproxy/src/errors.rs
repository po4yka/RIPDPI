use std::io;

const STRUCTURED_ERROR_PREFIX: &str = "RIPDPI-ERROR|naiveproxy|";

pub(crate) fn emit_structured_error(error: &io::Error) {
    eprintln!(
        "{STRUCTURED_ERROR_PREFIX}{}|{}",
        classify_io_error(error),
        sanitize_structured_message(&error.to_string())
    );
}

fn sanitize_structured_message(message: &str) -> String {
    message.replace('|', "/").replace(['\n', '\r'], " ")
}

fn classify_io_error(error: &io::Error) -> &'static str {
    let message = error.to_string().to_ascii_lowercase();
    if error.kind() == io::ErrorKind::PermissionDenied
        || message.contains("proxy-authorization")
        || message.contains("status 407")
    {
        "auth"
    } else if message.contains("connect response")
        || message.contains("connect with status")
        || message.contains("http status")
    {
        "http_connect"
    } else if error.kind() == io::ErrorKind::AddrNotAvailable
        || error.kind() == io::ErrorKind::NotFound
        || message.contains("resolved to no addresses")
        || message.contains("dns")
    {
        "dns"
    } else if message.contains("certificate")
        || message.contains("tls")
        || message.contains("rustls")
        || message.contains("invalid naiveproxy server name")
    {
        "tls"
    } else if matches!(
        error.kind(),
        io::ErrorKind::ConnectionRefused
            | io::ErrorKind::ConnectionAborted
            | io::ErrorKind::ConnectionReset
            | io::ErrorKind::TimedOut
            | io::ErrorKind::BrokenPipe
            | io::ErrorKind::NotConnected
    ) {
        "connect"
    } else if matches!(error.kind(), io::ErrorKind::InvalidInput | io::ErrorKind::Unsupported) {
        "config"
    } else {
        "runtime"
    }
}
