use std::io;

const STRUCTURED_ERROR_PREFIX: &str = "RIPDPI-ERROR|naiveproxy|";

pub(crate) fn emit_structured_error(error: &io::Error) {
    eprintln!(
        "{STRUCTURED_ERROR_PREFIX}{}|{}",
        classify_io_error(error),
        sanitize_structured_message(&error.to_string())
    );
}

/// The diagnostic line for one failed client connection. It is not a
/// `RIPDPI-ERROR` line: the service keeps the class of those for its restart
/// policy, while a client reset, a malformed request or the readiness probe's
/// early close is routine. It also carries no error text, which can echo
/// client bytes and would trip the service's plain " error" matcher.
pub(crate) fn connection_failure_line(error: &io::Error) -> String {
    format!("naiveproxy connection ended: {}", classify_io_error(error))
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn connection_failures_are_not_helper_failures() {
        let probe_close = io::Error::new(io::ErrorKind::UnexpectedEof, "early eof");
        let client_text = io::Error::new(io::ErrorKind::PermissionDenied, "tls proxy-authorization error from client");
        for error in [probe_close, client_text] {
            let line = connection_failure_line(&error);
            assert!(!line.starts_with("RIPDPI-ERROR"), "{line}");
            assert!(!line.to_ascii_lowercase().contains(" error"), "{line}");
            assert!(!line.contains("client"), "no error text: {line}");
        }
    }
}
