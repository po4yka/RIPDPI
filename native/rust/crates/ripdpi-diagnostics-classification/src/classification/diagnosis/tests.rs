use super::*;
use crate::classification::strategy::strategy_probe_failure_priority;
use crate::types::{DiagnosticProfileFamily, ProbeDetail, ScanKind, ScanPathMode, ScanRequest};
use ripdpi_failure_classifier::{FailureClass, FailureStage};

#[test]
fn classify_transport_failure_text_alert() {
    let result = classify_transport_failure_text("received fatal alert: handshake_failure", FailureStage::TlsHandshake);
    assert!(result.is_some());
    assert_eq!(result.unwrap().class, FailureClass::TlsAlert);
}

#[test]
fn classify_transport_failure_text_reset() {
    let result = classify_transport_failure_text("connection reset by peer", FailureStage::FirstResponse);
    assert!(result.is_some());
    assert_eq!(result.unwrap().class, FailureClass::TcpReset);
}

#[test]
fn classify_transport_failure_text_timeout() {
    let result = classify_transport_failure_text("operation timed out", FailureStage::FirstResponse);
    assert!(result.is_some());
    assert_eq!(result.unwrap().class, FailureClass::SilentDrop);
}

#[test]
fn classify_transport_failure_text_empty_returns_none() {
    assert!(classify_transport_failure_text("", FailureStage::FirstResponse).is_none());
}

#[test]
fn classify_transport_failure_text_none_returns_none() {
    assert!(classify_transport_failure_text("none", FailureStage::FirstResponse).is_none());
}

#[test]
fn classify_transport_failure_text_unknown_returns_none() {
    assert!(classify_transport_failure_text("some random error", FailureStage::FirstResponse).is_none());
}

#[test]
fn strategy_probe_failure_priority_ordering() {
    assert!(
        strategy_probe_failure_priority(FailureClass::HttpBlockpage)
            > strategy_probe_failure_priority(FailureClass::TcpReset)
    );
    assert!(
        strategy_probe_failure_priority(FailureClass::TcpReset)
            > strategy_probe_failure_priority(FailureClass::SilentDrop)
    );
    assert!(
        strategy_probe_failure_priority(FailureClass::SilentDrop)
            > strategy_probe_failure_priority(FailureClass::TlsAlert)
    );
}

#[test]
fn strategy_probe_failure_weight_https_is_2() {
    let result = ProbeResult {
        probe_type: "strategy_https".to_string(),
        target: "test".to_string(),
        outcome: "tls_ok".to_string(),
        details: vec![],
    };
    assert_eq!(strategy_probe_failure_weight(&result), 2);
}

#[test]
fn strategy_probe_failure_weight_http_is_1() {
    let result = ProbeResult {
        probe_type: "strategy_http".to_string(),
        target: "test".to_string(),
        outcome: "http_ok".to_string(),
        details: vec![],
    };
    assert_eq!(strategy_probe_failure_weight(&result), 1);
}

fn connectivity_request() -> ScanRequest {
    ScanRequest {
        profile_id: "ru-dpi-full".to_string(),
        display_name: "Russia DPI Full".to_string(),
        path_mode: ScanPathMode::RawPath,
        kind: ScanKind::Connectivity,
        family: DiagnosticProfileFamily::DpiFull,
        region_tag: Some("ru".to_string()),
        manual_only: true,
        pack_refs: vec!["ru-independent-media@1".to_string()],
        proxy_host: None,
        proxy_port: None,
        in_path_route: None,
        probe_tasks: vec![],
        domain_targets: vec![],
        dns_targets: vec![],
        tcp_targets: vec![],
        quic_targets: vec![],
        service_targets: vec![],
        circumvention_targets: vec![],
        throughput_targets: vec![],
        whitelist_sni: vec![],
        telegram_target: None,
        strategy_probe: None,
        network_snapshot: None,
        route_probe: None,
        scan_deadline_ms: None,
        diagnostic_tls_keylog_path: None,
        confirm_good_dpi_evidence: None,
    }
}

fn connectivity_probe(probe_type: &str, target: &str, outcome: &str, details: &[(&str, &str)]) -> ProbeResult {
    ProbeResult {
        probe_type: probe_type.to_string(),
        target: target.to_string(),
        outcome: outcome.to_string(),
        details: details
            .iter()
            .map(|(key, value)| ProbeDetail { key: (*key).to_string(), value: (*value).to_string() })
            .collect(),
    }
}

#[test]
fn classify_connectivity_diagnoses_detects_dns_blockpage_fingerprint() {
    let diagnoses = classify_connectivity_diagnoses(
        &connectivity_request(),
        &[
            connectivity_probe(
                "dns_integrity",
                "blocked.example",
                "dns_sinkhole_substitution",
                &[("udpAddresses", "203.0.113.10"), ("encryptedAddresses", "104.22.1.1")],
            ),
            connectivity_probe(
                "domain_reachability",
                "blocked.example",
                "http_blockpage",
                &[("httpStatus", "http_blockpage"), ("httpResponse", "403 blocked")],
            ),
        ],
    );
    assert!(diagnoses.iter().any(|diagnosis| diagnosis.code == "dns_tampering"));
    assert!(diagnoses.iter().any(|diagnosis| diagnosis.code == "dns_blockpage_fingerprint"));
}

#[test]
fn classify_connectivity_diagnoses_detects_tls_timeout_rst_close_and_mitm() {
    let diagnoses = classify_connectivity_diagnoses(
        &connectivity_request(),
        &[
            connectivity_probe(
                "domain_reachability",
                "discord.com",
                "unreachable",
                &[("tlsError", "operation timed out"), ("tlsStatus", "tls_handshake_failed")],
            ),
            connectivity_probe(
                "domain_reachability",
                "signal.org",
                "unreachable",
                &[("tlsError", "connection reset by peer"), ("tlsStatus", "tls_handshake_failed")],
            ),
            connectivity_probe(
                "domain_reachability",
                "whatsapp.com",
                "unreachable",
                &[("tlsError", "unexpected eof"), ("tlsStatus", "tls_handshake_failed")],
            ),
            connectivity_probe(
                "domain_reachability",
                "torproject.org",
                "tls_cert_invalid",
                &[("tlsSignal", "tls_cert_invalid"), ("tlsError", "unknown issuer")],
            ),
        ],
    );
    assert!(diagnoses.iter().any(|diagnosis| diagnosis.code == "tls_clienthello_timeout"));
    assert!(diagnoses.iter().any(|diagnosis| diagnosis.code == "tls_clienthello_rst"));
    assert!(diagnoses.iter().any(|diagnosis| diagnosis.code == "tls_clienthello_close"));
    assert!(diagnoses.iter().any(|diagnosis| diagnosis.code == "tls_cert_mitm"));
}

#[test]
fn classify_connectivity_diagnoses_detects_http_quic_whitelist_and_tcp_cutoff() {
    let diagnoses = classify_connectivity_diagnoses(
        &connectivity_request(),
        &[
            connectivity_probe(
                "tcp_fat_header",
                "1.1.1.1:443 (Cloudflare)",
                "tcp_16kb_blocked",
                &[("bytesSent", "16384"), ("responsesSeen", "1"), ("lastError", "unexpected eof")],
            ),
            connectivity_probe(
                "tcp_fat_header",
                "1.1.1.1:443 (Cloudflare)",
                "whitelist_sni_ok",
                &[("selectedSni", "vk.com"), ("attempts", "example.com:reset|vk.com:ok")],
            ),
            connectivity_probe(
                "domain_reachability",
                "youtube.com",
                "unreachable",
                &[("tlsError", "connection reset by peer"), ("tlsStatus", "tls_handshake_failed")],
            ),
            connectivity_probe(
                "quic_reachability",
                "youtube.com",
                "quic_error",
                &[("status", "quic_error"), ("error", "timeout"), ("latencyMs", "800")],
            ),
        ],
    );
    assert!(diagnoses.iter().any(|diagnosis| diagnosis.code == "tcp_16kb_cutoff"));
    assert!(diagnoses.iter().any(|diagnosis| diagnosis.code == "whitelist_sni_bypassable"));
    assert!(diagnoses.iter().any(|diagnosis| diagnosis.code == "sni_triggered_tls_interference"));
    assert!(diagnoses.iter().any(|diagnosis| diagnosis.code == "quic_blocked"));
}

#[test]
fn classify_connectivity_diagnoses_detects_connection_freeze() {
    let diagnoses = classify_connectivity_diagnoses(
        &connectivity_request(),
        &[connectivity_probe(
            "tcp_fat_header",
            "1.1.1.1:443 (Cloudflare)",
            "tcp_freeze_after_threshold",
            &[
                ("bytesSent", "16384"),
                ("responsesSeen", "1"),
                ("lastError", "timed out"),
                ("freezeThresholdBytes", "16384"),
            ],
        )],
    );
    assert!(diagnoses.iter().any(|d| d.code == "connection_freeze_detected"));
    let diag = diagnoses.iter().find(|d| d.code == "connection_freeze_detected").unwrap();
    assert_eq!(diag.severity, "negative");
    assert!(diag.recommendation.is_some());
}

#[test]
fn classify_connectivity_diagnoses_detects_port_443_policing() {
    let diagnoses = classify_connectivity_diagnoses(
        &connectivity_request(),
        &[connectivity_probe(
            "tcp_fat_header",
            "1.1.1.1:443 (Cloudflare)",
            "tcp_freeze_after_threshold",
            &[
                ("bytesSent", "16384"),
                ("responsesSeen", "1"),
                ("lastError", "timed out"),
                ("freezeThresholdBytes", "16384"),
                ("port", "443"),
                ("altPort", "8443"),
                ("altPortStatus", "ok"),
                ("altPortResponsesSeen", "16"),
            ],
        )],
    );
    assert!(diagnoses.iter().any(|d| d.code == "connection_freeze_detected"));
    assert!(diagnoses.iter().any(|d| d.code == "port_443_policed"));
    let diag = diagnoses.iter().find(|d| d.code == "port_443_policed").unwrap();
    assert_eq!(diag.severity, "negative");
    assert!(diag.recommendation.as_ref().unwrap().contains("8443"));
}

#[test]
fn classify_connectivity_diagnoses_no_port_policing_when_alt_port_also_fails() {
    let diagnoses = classify_connectivity_diagnoses(
        &connectivity_request(),
        &[connectivity_probe(
            "tcp_fat_header",
            "1.1.1.1:443 (Cloudflare)",
            "tcp_freeze_after_threshold",
            &[
                ("bytesSent", "16384"),
                ("responsesSeen", "1"),
                ("lastError", "timed out"),
                ("port", "443"),
                ("altPort", "8443"),
                ("altPortStatus", "timeout"),
            ],
        )],
    );
    assert!(diagnoses.iter().any(|d| d.code == "connection_freeze_detected"));
    assert!(!diagnoses.iter().any(|d| d.code == "port_443_policed"));
}

#[test]
fn classify_connectivity_diagnoses_detects_service_and_circumvention_blocking() {
    let diagnoses = classify_connectivity_diagnoses(
        &connectivity_request(),
        &[
            connectivity_probe(
                "service_reachability",
                "Signal",
                "service_blocked",
                &[
                    ("service", "Signal"),
                    ("bootstrapStatus", "http_blockpage"),
                    ("bootstrapDetail", "451 blocked"),
                    ("mediaStatus", "http_ok"),
                    ("mediaDetail", "200 ok"),
                    ("quicStatus", "quic_error"),
                    ("quicError", "timeout"),
                ],
            ),
            connectivity_probe(
                "circumvention_reachability",
                "Psiphon",
                "circumvention_blocked",
                &[
                    ("tool", "Psiphon"),
                    ("bootstrapStatus", "http_blockpage"),
                    ("bootstrapDetail", "403 blocked"),
                    ("handshakeStatus", "tls_handshake_failed"),
                    ("handshakeError", "connection reset"),
                ],
            ),
        ],
    );
    assert!(diagnoses.iter().any(|diagnosis| diagnosis.code == "service_bootstrap_blocked"));
    assert!(diagnoses.iter().any(|diagnosis| diagnosis.code == "quic_blocked"));
    assert!(diagnoses.iter().any(|diagnosis| diagnosis.code == "circumvention_bootstrap_blocked"));
    assert!(diagnoses.iter().any(|diagnosis| diagnosis.code == "circumvention_handshake_blocked"));
}

#[test]
fn classify_connectivity_diagnoses_detects_youtube_throttling() {
    let diagnoses = classify_connectivity_diagnoses(
        &connectivity_request(),
        &[
            connectivity_probe(
                "throughput_window",
                "YouTube Web",
                "throughput_measured",
                &[
                    ("isControl", "false"),
                    ("medianBps", "1000000"),
                    ("bpsReadings", "900000|1000000"),
                    ("windowBytes", "8388608"),
                ],
            ),
            connectivity_probe(
                "throughput_window",
                "Cloudflare Control",
                "throughput_measured",
                &[
                    ("isControl", "true"),
                    ("medianBps", "8000000"),
                    ("bpsReadings", "7800000|8000000"),
                    ("windowBytes", "8388608"),
                ],
            ),
        ],
    );
    assert!(diagnoses.iter().any(|diagnosis| diagnosis.code == "youtube_throttled"));
}
