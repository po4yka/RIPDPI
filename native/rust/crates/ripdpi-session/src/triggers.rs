use ripdpi_packets::{is_http_redirect, is_tls_client_hello, is_tls_server_hello, tls_session_id_mismatch};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TriggerEvent {
    Redirect,
    SslErr,
    Connect,
    Torst,
}

pub fn detect_response_trigger(request: &[u8], response: &[u8]) -> Option<TriggerEvent> {
    if is_http_redirect(request, response) {
        return Some(TriggerEvent::Redirect);
    }
    if (is_tls_client_hello(request) && !is_tls_server_hello(response)) || tls_session_id_mismatch(request, response) {
        return Some(TriggerEvent::SslErr);
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detect_response_trigger_tls_to_non_tls_is_ssl_err() {
        let tls_hello = ripdpi_packets::DEFAULT_FAKE_TLS;
        let non_tls = b"HTTP/1.1 200 OK\r\n\r\n";
        assert_eq!(detect_response_trigger(tls_hello, non_tls), Some(TriggerEvent::SslErr));
    }

    #[test]
    fn detect_response_trigger_returns_none_for_non_tls_non_redirect() {
        let request = b"GET / HTTP/1.1\r\n\r\n";
        let response = b"HTTP/1.1 200 OK\r\n\r\n";
        assert_eq!(detect_response_trigger(request, response), None);
    }

    #[test]
    fn trigger_event_equality() {
        assert_eq!(TriggerEvent::Redirect, TriggerEvent::Redirect);
        assert_ne!(TriggerEvent::Redirect, TriggerEvent::SslErr);
        assert_ne!(TriggerEvent::Connect, TriggerEvent::Torst);
    }
}
