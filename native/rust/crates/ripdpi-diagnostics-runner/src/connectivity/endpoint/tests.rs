use super::{is_probe_failure, is_server_error};

#[test]
fn is_probe_failure_false_for_success_outcomes() {
    assert!(!is_probe_failure("http_ok"));
    assert!(!is_probe_failure("tls_ok"));
    assert!(!is_probe_failure("http_redirect"));
    assert!(!is_probe_failure("not_run"));
    assert!(!is_probe_failure("tcp_connect_ok"));
    assert!(!is_probe_failure("quic_initial_response"));
    assert!(!is_probe_failure("quic_response"));
}

#[test]
fn is_probe_failure_false_for_3xx_redirects() {
    assert!(!is_probe_failure("http_status_301"));
    assert!(!is_probe_failure("http_status_302"));
    assert!(!is_probe_failure("http_status_307"));
    assert!(!is_probe_failure("http_status_308"));
}

#[test]
fn is_probe_failure_true_for_failures() {
    assert!(is_probe_failure("http_unreachable"));
    assert!(is_probe_failure("http_blockpage"));
    assert!(is_probe_failure("tls_handshake_failed"));
    assert!(is_probe_failure("http_status_429"));
    assert!(is_probe_failure("http_status_500"));
}

#[test]
fn is_server_error_detects_4xx_and_5xx() {
    assert!(is_server_error("http_status_429"));
    assert!(is_server_error("http_status_400"));
    assert!(is_server_error("http_status_500"));
    assert!(is_server_error("http_status_502"));
    assert!(is_server_error("http_status_503"));
}

#[test]
fn is_server_error_false_for_non_server_errors() {
    assert!(!is_server_error("http_ok"));
    assert!(!is_server_error("http_blockpage"));
    assert!(!is_server_error("http_unreachable"));
    assert!(!is_server_error("http_status_301"));
    assert!(!is_server_error("tls_handshake_failed"));
}
