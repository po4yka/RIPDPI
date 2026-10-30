use std::io;

const STRUCTURED_ERROR_PREFIX: &str = "RIPDPI-ERROR|naiveproxy|";
/// Tells the service that an upstream open succeeded, first after start or after a reported failure.
pub(crate) const UPSTREAM_OK_LINE: &str = "RIPDPI-UPSTREAM-OK|naiveproxy|";

pub(crate) fn emit_structured_error(error: &io::Error) {
    eprintln!("{}", structured_error_line(error));
}

pub(crate) fn structured_error_line(error: &io::Error) -> String {
    format!("{STRUCTURED_ERROR_PREFIX}{}|{}", classify_io_error(error), sanitize_structured_message(&error.to_string()))
}

/// The error line of the resolve-only mode. The class is always `dns`: every
/// failure in that mode, including bad arguments, blocks the proxy start on name resolution.
pub(crate) fn resolve_error_line(error: &io::Error) -> String {
    format!("{STRUCTURED_ERROR_PREFIX}dns|{}", sanitize_structured_message(&error.to_string()))
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
    // Only the proxy's 407 is auth; a socket-level EACCES/EPERM (protect, firewall) is a connect failure.
    if message.contains("proxy-authorization") || message.contains("status 407") {
        "auth"
    } else if message.contains("connect response")
        || message.contains("connect with status")
        || message.contains("http status")
    {
        "http_connect"
    } else if error.kind() == io::ErrorKind::AddrNotAvailable
        || error.kind() == io::ErrorKind::NotFound
        || message.contains("resolved to no addresses")
        || message.contains("resolve relay hostname")
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
            | io::ErrorKind::PermissionDenied
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
    use crate::connect_tunnel::connect_status_error;

    #[test]
    fn only_407_connect_rejection_is_an_auth_failure() {
        assert_eq!(classify_io_error(&connect_status_error(http::StatusCode::PROXY_AUTHENTICATION_REQUIRED)), "auth");
        for status in
            [http::StatusCode::FORBIDDEN, http::StatusCode::BAD_GATEWAY, http::StatusCode::SERVICE_UNAVAILABLE]
        {
            assert_eq!(classify_io_error(&connect_status_error(status)), "http_connect", "{status}");
        }
        assert_eq!(classify_io_error(&io::Error::from_raw_os_error(13)), "connect", "EACCES is not auth");
    }

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
