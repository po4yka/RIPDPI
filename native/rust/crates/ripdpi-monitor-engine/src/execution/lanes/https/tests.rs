use crate::tls::{ProbeStreamFailureStage, TlsObservation};

use super::detail_builder::build_https_probe_details;
use super::observation_collection::HttpsObservationCollection;
use super::outcome_classification::https_tls_error_detail;

#[test]
fn https_probe_details_export_typed_failure_stage_and_duration() {
    let mut tls13 = tls_observation("tls_handshake_failed", Some("connection refused"));
    tls13.failure_stage = Some(ProbeStreamFailureStage::TcpConnect);
    tls13.failure_duration_ms = Some(17);
    let observations = HttpsObservationCollection {
        tls13,
        tls12: tls_observation("tls_handshake_failed", None),
        tls_ech: tls_observation("tls_handshake_failed", None),
        latency_ms: 17,
        https_port: 443,
        socket_leg: "direct",
    };
    let candidate =
        crate::candidates::candidate_spec("test", "Test", "test", ripdpi_proxy_config::ProxyUiConfig::default());

    let details = build_https_probe_details(&candidate, &observations, "tls_handshake_failed");

    assert_eq!(detail_value(&details, "tls13FailureStage"), "tcp_connect");
    assert_eq!(detail_value(&details, "tls13FailureDurationMs"), "17");
    assert_eq!(detail_value(&details, "tls12FailureStage"), "none");
}

#[test]
fn https_report_keeps_alert_facts_from_the_failed_attempt() {
    let mut tls13 = tls_observation("tls_ok", None);
    tls13.tls_server_hello_received = Some(true);
    tls13.connected_addr = Some("127.0.0.1:443".parse().unwrap());
    let mut tls12 = tls_observation("tls_handshake_failed", Some("protocol version alert"));
    tls12.failure_stage = Some(ProbeStreamFailureStage::TlsHandshake);
    tls12.tls_server_hello_received = Some(false);
    tls12.tls_alert_code = Some(70);
    tls12.tls_alert_description = Some("ProtocolVersion".to_string());
    tls12.tls_handshake_ms = Some(8);
    tls12.connected_addr = Some("127.0.0.2:443".parse().unwrap());
    let observations = HttpsObservationCollection {
        tls13,
        tls12,
        tls_ech: tls_observation("tls_handshake_failed", None),
        latency_ms: 17,
        https_port: 443,
        socket_leg: "direct",
    };
    let candidate =
        crate::candidates::candidate_spec("test", "Test", "test", ripdpi_proxy_config::ProxyUiConfig::default());

    let details = build_https_probe_details(&candidate, &observations, "tls_version_split");

    assert_eq!(detail_value(&details, "tlsServerHelloReceived"), "false");
    assert_eq!(detail_value(&details, "tlsAlertCode"), "70");
    assert_eq!(detail_value(&details, "tlsAlertDescription"), "ProtocolVersion");
    assert_eq!(detail_value(&details, "tlsAlertSource"), "unknown");
    assert_eq!(detail_value(&details, "tlsFailureProfile"), "tls12");
    assert_eq!(detail_value(&details, "tlsFailureHandshakeMs"), "8");
    assert_eq!(detail_value(&details, "tlsVersionComparisonScope"), "mixed_peers");
    assert!(!details.iter().any(|detail| detail.key == "tlsDpiSignature"));
}

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
        failure_stage: None,
        failure_duration_ms: None,
        certificate_anomaly: false,
        ech_resolution_detail: None,
        ech_bootstrap_policy: None,
        ech_bootstrap_resolver_id: None,
        ech_outer_extension_policy: None,
        ech_first_flight_plan: None,
        transport_open_ms: None,
        tls_handshake_ms: None,
        cert_chain_length: None,
        cert_issuer: None,
        local_socket_ttl: None,
        ja3_fingerprint: None,
        tls_alert_code: None,
        tls_alert_description: None,
        tls_server_hello_received: None,
        connected_addr: None,
        local_addr: None,
        cdn_provider: None,
        route_report: None,
    }
}

fn detail_value<'a>(details: &'a [crate::types::ProbeDetail], key: &str) -> &'a str {
    details.iter().find(|detail| detail.key == key).map(|detail| detail.value.as_str()).expect("detail exists")
}
