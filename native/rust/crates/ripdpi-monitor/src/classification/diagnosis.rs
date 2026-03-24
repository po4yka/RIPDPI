use std::collections::{BTreeMap, BTreeSet};

use ripdpi_failure_classifier::{ClassifiedFailure, FailureAction, FailureClass, FailureStage};

use crate::observations::observation_for_probe;
use crate::types::{Diagnosis, ProbeResult, ScanRequest};

use super::strategy::{
    classify_strategy_probe_baseline_observations, strategy_probe_failure_priority, strategy_probe_observation_weight,
};

pub(crate) fn failure_detail_value<'a>(result: &'a ProbeResult, key: &str) -> Option<&'a str> {
    result.details.iter().find_map(|detail| (detail.key == key).then_some(detail.value.as_str()))
}

pub(crate) fn classify_transport_failure_text(text: &str, stage: FailureStage) -> Option<ClassifiedFailure> {
    let normalized = text.trim().to_ascii_lowercase();
    if normalized.is_empty() || normalized == "none" {
        return None;
    }
    if normalized.contains("alert") {
        return Some(ClassifiedFailure::new(
            FailureClass::TlsAlert,
            stage,
            FailureAction::RetryWithMatchingGroup,
            text,
        ));
    }
    if normalized.contains("reset")
        || normalized.contains("broken pipe")
        || normalized.contains("aborted")
        || normalized.contains("unexpected eof")
    {
        return Some(ClassifiedFailure::new(
            FailureClass::TcpReset,
            stage,
            FailureAction::RetryWithMatchingGroup,
            text,
        ));
    }
    if normalized.contains("timed out") || normalized.contains("timeout") || normalized.contains("would block") {
        return Some(ClassifiedFailure::new(
            FailureClass::SilentDrop,
            stage,
            FailureAction::RetryWithMatchingGroup,
            text,
        ));
    }
    None
}

pub(crate) fn strategy_probe_failure_weight(result: &ProbeResult) -> usize {
    observation_for_probe(result).as_ref().map_or_else(
        || match result.probe_type.as_str() {
            "strategy_https" | "strategy_quic" => 2,
            _ => 1,
        },
        strategy_probe_observation_weight,
    )
}

pub(crate) fn classify_strategy_probe_baseline_results(results: &[ProbeResult]) -> Option<ClassifiedFailure> {
    classify_strategy_probe_baseline_observations(&results.iter().filter_map(observation_for_probe).collect::<Vec<_>>())
}

pub(crate) fn classify_connectivity_diagnoses(request: &ScanRequest, results: &[ProbeResult]) -> Vec<Diagnosis> {
    let mut diagnoses = Vec::new();
    let mut seen = BTreeSet::<String>::new();

    let mut domain_outcomes = BTreeMap::<String, Vec<&ProbeResult>>::new();
    let mut tcp_whitelist_bypass = false;
    let mut hard_failure_codes = BTreeSet::<String>::new();

    for result in results {
        if result.probe_type == "domain_reachability" {
            domain_outcomes.entry(normalize_host(&result.target)).or_default().push(result);
        }
        if result.probe_type == "tcp_fat_header" && result.outcome == "whitelist_sni_ok" {
            tcp_whitelist_bypass = true;
        }
    }

    for result in results.iter().filter(|result| result.probe_type == "dns_integrity") {
        if matches!(result.outcome.as_str(), "dns_substitution" | "dns_expected_mismatch") {
            push_diagnosis(
                &mut diagnoses,
                &mut seen,
                Diagnosis {
                    code: "dns_tampering".to_string(),
                    summary: format!("DNS answers for {} differ across resolvers", result.target),
                    severity: "negative".to_string(),
                    target: Some(result.target.clone()),
                    evidence: diagnosis_evidence(result, &["udpAddresses", "encryptedAddresses", "expected"]),
                },
            );
            hard_failure_codes.insert("dns_tampering".to_string());
            if domain_outcomes
                .get(&normalize_host(&result.target))
                .is_some_and(|matches| matches.iter().any(|probe| probe.outcome == "http_blockpage"))
            {
                push_diagnosis(
                    &mut diagnoses,
                    &mut seen,
                    Diagnosis {
                        code: "dns_blockpage_fingerprint".to_string(),
                        summary: format!("{} appears redirected to a blockpage fingerprint", result.target),
                        severity: "negative".to_string(),
                        target: Some(result.target.clone()),
                        evidence: diagnosis_evidence(result, &["udpAddresses", "encryptedAddresses"]),
                    },
                );
                hard_failure_codes.insert("dns_blockpage_fingerprint".to_string());
            }
        }
    }

    for result in results.iter().filter(|result| result.probe_type == "domain_reachability") {
        if result.outcome == "tls_cert_invalid" {
            push_diagnosis(
                &mut diagnoses,
                &mut seen,
                Diagnosis {
                    code: "tls_cert_mitm".to_string(),
                    summary: format!("TLS certificate anomaly observed for {}", result.target),
                    severity: "negative".to_string(),
                    target: Some(result.target.clone()),
                    evidence: diagnosis_evidence(result, &["tlsStatus", "tlsError", "tlsSignal"]),
                },
            );
            hard_failure_codes.insert("tls_cert_mitm".to_string());
        }

        let tls_error = failure_detail_value(result, "tlsError").unwrap_or_default().to_ascii_lowercase();
        if !tls_error.is_empty() && tls_error != "none" {
            let (code, summary) = if is_timeout_error(&tls_error) {
                ("tls_clienthello_timeout", format!("TLS handshake to {} timed out after ClientHello", result.target))
            } else if is_reset_error(&tls_error) {
                ("tls_clienthello_rst", format!("TLS handshake to {} was reset after ClientHello", result.target))
            } else if is_close_error(&tls_error) {
                (
                    "tls_clienthello_close",
                    format!("TLS handshake to {} closed unexpectedly after ClientHello", result.target),
                )
            } else {
                continue;
            };
            push_diagnosis(
                &mut diagnoses,
                &mut seen,
                Diagnosis {
                    code: code.to_string(),
                    summary,
                    severity: "negative".to_string(),
                    target: Some(result.target.clone()),
                    evidence: diagnosis_evidence(result, &["tlsStatus", "tlsError", "tls13Status", "tls12Status"]),
                },
            );
            hard_failure_codes.insert(code.to_string());
        }

        if result.outcome == "http_blockpage" {
            push_diagnosis(
                &mut diagnoses,
                &mut seen,
                Diagnosis {
                    code: "http_blockpage".to_string(),
                    summary: format!("HTTP blockpage observed for {}", result.target),
                    severity: "negative".to_string(),
                    target: Some(result.target.clone()),
                    evidence: diagnosis_evidence(result, &["httpStatus", "httpResponse"]),
                },
            );
            hard_failure_codes.insert("http_blockpage".to_string());
        }
    }

    for result in results.iter().filter(|result| result.probe_type == "tcp_fat_header") {
        if result.outcome == "tcp_16kb_blocked" {
            push_diagnosis(
                &mut diagnoses,
                &mut seen,
                Diagnosis {
                    code: "tcp_16kb_cutoff".to_string(),
                    summary: format!("Late-stage TCP cutoff observed for {}", result.target),
                    severity: "negative".to_string(),
                    target: Some(result.target.clone()),
                    evidence: diagnosis_evidence(result, &["bytesSent", "responsesSeen", "lastError"]),
                },
            );
        }
        if result.outcome == "whitelist_sni_ok" {
            push_diagnosis(
                &mut diagnoses,
                &mut seen,
                Diagnosis {
                    code: "whitelist_sni_bypassable".to_string(),
                    summary: format!("Whitelisted SNI recovered access for {}", result.target),
                    severity: "warning".to_string(),
                    target: Some(result.target.clone()),
                    evidence: diagnosis_evidence(result, &["selectedSni", "attempts"]),
                },
            );
        }
    }

    if tcp_whitelist_bypass
        && diagnoses.iter().any(|diagnosis| {
            matches!(
                diagnosis.code.as_str(),
                "tls_clienthello_timeout" | "tls_clienthello_rst" | "tls_clienthello_close"
            )
        })
    {
        push_diagnosis(
            &mut diagnoses,
            &mut seen,
            Diagnosis {
                code: "sni_triggered_tls_interference".to_string(),
                summary: "TLS interference appears sensitive to the requested SNI".to_string(),
                severity: "warning".to_string(),
                target: request.region_tag.clone(),
                evidence: vec![
                    "tls failures recovered when a whitelisted SNI was used".to_string(),
                    "whitelist_sni_ok".to_string(),
                ],
            },
        );
    }

    for result in results.iter().filter(|result| result.probe_type == "quic_reachability") {
        if !matches!(result.outcome.as_str(), "quic_initial_response" | "quic_response") {
            push_diagnosis(
                &mut diagnoses,
                &mut seen,
                Diagnosis {
                    code: "quic_blocked".to_string(),
                    summary: format!("QUIC appears blocked or degraded for {}", result.target),
                    severity: "warning".to_string(),
                    target: Some(result.target.clone()),
                    evidence: diagnosis_evidence(result, &["status", "error", "latencyMs"]),
                },
            );
        }
    }

    for result in results.iter().filter(|result| result.probe_type == "service_reachability") {
        let service_name = failure_detail_value(result, "service").unwrap_or(result.target.as_str()).to_string();
        let bootstrap_status = failure_detail_value(result, "bootstrapStatus").unwrap_or("not_run");
        let media_status = failure_detail_value(result, "mediaStatus").unwrap_or("not_run");
        let quic_status = failure_detail_value(result, "quicStatus").unwrap_or("not_run");
        if is_http_failure(bootstrap_status) {
            push_diagnosis(
                &mut diagnoses,
                &mut seen,
                Diagnosis {
                    code: "service_bootstrap_blocked".to_string(),
                    summary: format!("{service_name} bootstrap endpoint is blocked"),
                    severity: "negative".to_string(),
                    target: Some(service_name.clone()),
                    evidence: diagnosis_evidence(result, &["bootstrapStatus", "bootstrapDetail", "gatewayStatus"]),
                },
            );
        }
        if is_http_failure(media_status) {
            push_diagnosis(
                &mut diagnoses,
                &mut seen,
                Diagnosis {
                    code: "service_media_blocked".to_string(),
                    summary: format!("{service_name} media endpoint is blocked or throttled"),
                    severity: "negative".to_string(),
                    target: Some(service_name.clone()),
                    evidence: diagnosis_evidence(result, &["mediaStatus", "mediaDetail"]),
                },
            );
        }
        if is_quic_failure(quic_status) {
            push_diagnosis(
                &mut diagnoses,
                &mut seen,
                Diagnosis {
                    code: "quic_blocked".to_string(),
                    summary: format!("QUIC appears blocked or degraded for {service_name}"),
                    severity: "warning".to_string(),
                    target: Some(service_name),
                    evidence: diagnosis_evidence(result, &["quicStatus", "quicError"]),
                },
            );
        }
    }

    for result in results.iter().filter(|result| result.probe_type == "circumvention_reachability") {
        let tool_name = failure_detail_value(result, "tool").unwrap_or(result.target.as_str()).to_string();
        let bootstrap_status = failure_detail_value(result, "bootstrapStatus").unwrap_or("not_run");
        let handshake_status = failure_detail_value(result, "handshakeStatus").unwrap_or("not_run");
        if is_http_failure(bootstrap_status) {
            push_diagnosis(
                &mut diagnoses,
                &mut seen,
                Diagnosis {
                    code: "circumvention_bootstrap_blocked".to_string(),
                    summary: format!("{tool_name} bootstrap endpoint is blocked"),
                    severity: "negative".to_string(),
                    target: Some(tool_name.clone()),
                    evidence: diagnosis_evidence(result, &["bootstrapStatus", "bootstrapDetail"]),
                },
            );
        }
        if is_tls_failure(handshake_status) {
            push_diagnosis(
                &mut diagnoses,
                &mut seen,
                Diagnosis {
                    code: "circumvention_handshake_blocked".to_string(),
                    summary: format!("{tool_name} handshake endpoint is blocked"),
                    severity: "negative".to_string(),
                    target: Some(tool_name),
                    evidence: diagnosis_evidence(result, &["handshakeStatus", "handshakeError"]),
                },
            );
        }
    }

    if !hard_failure_codes.iter().any(|code| {
        matches!(
            code.as_str(),
            "dns_tampering"
                | "dns_blockpage_fingerprint"
                | "tls_clienthello_timeout"
                | "tls_clienthello_rst"
                | "tls_clienthello_close"
                | "tls_cert_mitm"
                | "http_blockpage"
        )
    }) {
        classify_throughput_diagnosis(results, &mut diagnoses, &mut seen);
    }

    diagnoses
}

fn classify_throughput_diagnosis(results: &[ProbeResult], diagnoses: &mut Vec<Diagnosis>, seen: &mut BTreeSet<String>) {
    let throughput_results = results.iter().filter(|result| result.probe_type == "throughput_window");
    let mut control_medians = Vec::<u64>::new();
    let mut suspicious = Vec::<(&ProbeResult, u64)>::new();

    for result in throughput_results {
        let median_bps =
            failure_detail_value(result, "medianBps").and_then(|value| value.parse::<u64>().ok()).unwrap_or(0);
        let is_control = failure_detail_value(result, "isControl").is_some_and(|value| value == "true");
        if is_control {
            if median_bps > 0 {
                control_medians.push(median_bps);
            }
        } else if result.target.to_ascii_lowercase().contains("youtube") && median_bps > 0 {
            suspicious.push((result, median_bps));
        }
    }

    let control_median = median(&mut control_medians);
    if control_median < 5_000_000 {
        return;
    }

    for (result, youtube_median) in suspicious {
        if youtube_median.saturating_mul(4) < control_median {
            push_diagnosis(
                diagnoses,
                seen,
                Diagnosis {
                    code: "youtube_throttled".to_string(),
                    summary: format!("{} throughput is far below the neutral control", result.target),
                    severity: "warning".to_string(),
                    target: Some(result.target.clone()),
                    evidence: diagnosis_evidence(result, &["medianBps", "bpsReadings", "windowBytes"]),
                },
            );
        }
    }
}

fn median(values: &mut [u64]) -> u64 {
    if values.is_empty() {
        return 0;
    }
    values.sort_unstable();
    values[values.len() / 2]
}

fn push_diagnosis(diagnoses: &mut Vec<Diagnosis>, seen: &mut BTreeSet<String>, diagnosis: Diagnosis) {
    let key = format!("{}:{}", diagnosis.code, diagnosis.target.as_deref().unwrap_or("*"));
    if seen.insert(key) {
        diagnoses.push(diagnosis);
    }
}

fn diagnosis_evidence(result: &ProbeResult, keys: &[&str]) -> Vec<String> {
    keys.iter().filter_map(|key| failure_detail_value(result, key).map(|value| format!("{key}={value}"))).collect()
}

fn normalize_host(value: &str) -> String {
    value.trim().to_ascii_lowercase().trim_start_matches("www.").to_string()
}

fn is_timeout_error(value: &str) -> bool {
    value.contains("timed out") || value.contains("timeout") || value.contains("would block")
}

fn is_reset_error(value: &str) -> bool {
    value.contains("reset") || value.contains("broken pipe") || value.contains("aborted")
}

fn is_close_error(value: &str) -> bool {
    value.contains("unexpected eof") || value.contains("closed") || value.contains("close notify")
}

fn is_http_failure(value: &str) -> bool {
    !matches!(value, "not_run" | "http_ok")
}

fn is_tls_failure(value: &str) -> bool {
    !matches!(value, "not_run" | "tls_ok" | "tcp_connect_ok")
}

fn is_quic_failure(value: &str) -> bool {
    !matches!(value, "not_run" | "quic_initial_response" | "quic_response")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::{DiagnosticProfileFamily, ProbeDetail, ScanKind, ScanPathMode, ScanRequest};
    use ripdpi_failure_classifier::FailureClass;

    #[test]
    fn classify_transport_failure_text_alert() {
        let result =
            classify_transport_failure_text("received fatal alert: handshake_failure", FailureStage::TlsHandshake);
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
                    "meduza.io",
                    "dns_substitution",
                    &[("udpAddresses", "203.0.113.10"), ("encryptedAddresses", "104.22.1.1")],
                ),
                connectivity_probe(
                    "domain_reachability",
                    "meduza.io",
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
}
