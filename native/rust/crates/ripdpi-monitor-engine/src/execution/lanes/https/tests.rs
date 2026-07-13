use crate::tls::TlsObservation;

use super::outcome_classification::https_tls_error_detail;

#[test]
fn https_tls_error_detail_excludes_ech_resolution_failures_for_successful_https_outcomes() {
    let tls13 = tls_observation("tls_ok", None);
    let tls12 = tls_observation("tls_handshake_failed", Some("protocol version alert"));
    let tls_ech = tls_observation("tls_handshake_failed", Some("ech_resolution_failed: timeout"));

    assert_eq!(https_tls_error_detail("tls_version_split", &tls13, &tls12, &tls_ech), "protocol version alert");
    assert_eq!(https_tls_error_detail("tls_ok", &tls13, &tls12, &tls_ech), "protocol version alert");
}

#[test]
fn https_tls_error_detail_preserves_ech_resolution_failures_for_failed_https_outcomes() {
    let tls13 = tls_observation("tls_handshake_failed", None);
    let tls12 = tls_observation("tls_handshake_failed", None);
    let tls_ech = tls_observation("tls_handshake_failed", Some("ech_resolution_failed: timeout"));

    assert_eq!(
        https_tls_error_detail("tls_handshake_failed", &tls13, &tls12, &tls_ech),
        "ech_resolution_failed: timeout"
    );
}

fn tls_observation(status: &str, error: Option<&str>) -> TlsObservation {
    TlsObservation {
        status: status.to_string(),
        version: None,
        error: error.map(str::to_string),
        certificate_anomaly: false,
        ech_resolution_detail: None,
        ech_bootstrap_policy: None,
        ech_bootstrap_resolver_id: None,
        ech_outer_extension_policy: None,
        ech_first_flight_plan: None,
        tcp_connect_ms: None,
        tls_handshake_ms: None,
        cert_chain_length: None,
        cert_issuer: None,
        local_socket_ttl: None,
        ja3_fingerprint: None,
        tls_alert_code: None,
        tls_alert_description: None,
        tls_server_hello_received: None,
        tls_dpi_signature: None,
        connected_addr: None,
        local_addr: None,
        cdn_provider: None,
        route_report: None,
    }
}
