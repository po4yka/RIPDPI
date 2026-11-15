use crate::tls::{ProbeStreamFailureStage, TlsObservation};
use crate::transport::TransportConfig;

use super::detail_builder::build_https_probe_details;
use super::observation_collection::HttpsObservationCollection;

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
    };
    let candidate =
        crate::candidates::candidate_spec("test", "Test", "test", ripdpi_proxy_config::ProxyUiConfig::default());

    let details = build_https_probe_details(
        &candidate,
        &observations,
        "tls_handshake_failed",
        &TransportConfig::Direct { route_experiment: None },
    );

    assert_eq!(detail_value(&details, "tls13FailureStage"), "tcp_connect");
    assert_eq!(detail_value(&details, "tls13FailureDurationMs"), "17");
    assert_eq!(detail_value(&details, "tls12FailureStage"), "none");
}

#[test]
fn https_probe_report_keeps_each_peer_with_its_own_measurements() {
    let mut tls13 = tls_observation("tls_handshake_failed", Some("protocol version alert"));
    tls13.connected_addr = Some("192.0.2.1:443".parse().unwrap());
    tls13.transport_open_ms = Some(7);
    tls13.tls_alert_code = Some(70);
    let mut tls12 = tls_observation("tls_ok", None);
    tls12.connected_addr = Some("192.0.2.2:443".parse().unwrap());
    tls12.transport_open_ms = Some(13);
    tls12.tls_handshake_ms = Some(17);
    tls12.ja3_fingerprint = Some("de4795e56a15bf5fbccf14d36b3380f5".into());
    tls12.local_socket_ttl = Some(64);
    let mut tls_ech = tls_observation("tls_ok", None);
    tls_ech.connected_addr = Some("192.0.2.3:443".parse().unwrap());
    tls_ech.ja3_fingerprint = Some("8f53d3b4922115c655ae45dc6a35daaa".into());
    let mut observations = HttpsObservationCollection { tls13, tls12, tls_ech, latency_ms: 30, https_port: 443 };
    let candidate =
        crate::candidates::candidate_spec("test", "Test", "test", ripdpi_proxy_config::ProxyUiConfig::default());

    let transport = TransportConfig::Socks5 { host: "127.0.0.1".to_string(), port: 1080, credentials: None };
    let details = build_https_probe_details(&candidate, &observations, "tls_version_split", &transport);

    assert_eq!(detail_value(&details, "tlsReportedAttempt"), "tls12");
    assert_eq!(detail_value(&details, "connectedIp"), "192.0.2.2");
    assert_eq!(detail_value(&details, "tlsError"), "none");
    assert_eq!(detail_value(&details, "tls12ConnectedIp"), "192.0.2.2");
    assert_eq!(detail_value(&details, "tls12ConnectedPort"), "443");
    assert_eq!(detail_value(&details, "tls12PeerRole"), "socks_requested_destination");
    assert_eq!(detail_value(&details, "tls12TransportOpenMs"), "13");
    assert_eq!(detail_value(&details, "tls12HandshakeMs"), "17");
    assert_eq!(detail_value(&details, "tls12Ja3Fingerprint"), "de4795e56a15bf5fbccf14d36b3380f5");
    assert_eq!(detail_value(&details, "tls12LocalSocketTtl"), "64");
    assert_eq!(detail_value(&details, "tls12LocalSocketTtlLeg"), "client_to_proxy");
    assert_eq!(detail_value(&details, "tlsVersionComparisonScope"), "mixed_peers");
    assert_eq!(detail_value(&details, "tls13ConnectedIp"), "192.0.2.1");
    assert_eq!(detail_value(&details, "tls13AlertCode"), "70");
    assert_eq!(detail_value(&details, "tls13AlertSource"), "unknown");
    assert_eq!(detail_value(&details, "tlsEchConnectedIp"), "192.0.2.3");
    assert_eq!(detail_value(&details, "tlsEchJa3Fingerprint"), "8f53d3b4922115c655ae45dc6a35daaa");
    assert!(!details.iter().any(|detail| detail.key == "tls12AlertCode" || detail.key == "observedServerTtl"));

    observations.tls13.connected_addr = None;
    observations.tls13.transport_open_ms = None;
    observations.tls12.status = "tls_handshake_failed".into();
    observations.tls_ech.status = "not_run".into();
    let details = build_https_probe_details(&candidate, &observations, "tls_handshake_failed", &transport);
    assert_eq!(detail_value(&details, "tlsReportedAttempt"), "tls12");
    assert_eq!(detail_value(&details, "connectedIp"), "192.0.2.2");
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
