use crate::types::{
    CircumventionObservationFact, DnsObservationFact, DnsObservationStatus, DomainObservationFact, EndpointProbeStatus,
    HttpProbeStatus, ObservationKind, ProbeObservation, ProbeResult, QuicObservationFact, QuicProbeStatus,
    ServiceObservationFact, StrategyObservationFact, StrategyProbeProtocol, StrategyProbeStatus, TcpObservationFact,
    TcpProbeStatus, TelegramObservationFact, TelegramTransferStatus, TelegramVerdict, ThroughputObservationFact,
    ThroughputProbeStatus, TlsProbeStatus, TransportFailureKind,
};

pub(crate) const ENGINE_ANALYSIS_VERSION: &str = "observations_v1";

pub(crate) fn observations_for_results(results: &[ProbeResult]) -> Vec<ProbeObservation> {
    results.iter().filter_map(observation_for_probe).collect()
}

pub(crate) fn observation_for_probe(result: &ProbeResult) -> Option<ProbeObservation> {
    match result.probe_type.as_str() {
        "dns_integrity" => Some(ProbeObservation {
            kind: ObservationKind::Dns,
            target: result.target.clone(),
            dns: Some(DnsObservationFact {
                domain: result.target.clone(),
                status: dns_status(&result.outcome),
                udp_addresses: detail_list(result, "udpAddresses"),
                encrypted_addresses: {
                    let encrypted = detail_list(result, "encryptedAddresses");
                    if encrypted.is_empty() {
                        detail_list(result, "dohAddresses")
                    } else {
                        encrypted
                    }
                },
            }),
            domain: None,
            tcp: None,
            quic: None,
            service: None,
            circumvention: None,
            telegram: None,
            throughput: None,
            strategy: None,
            evidence: vec![result.outcome.clone()],
        }),
        "domain_reachability" => Some(ProbeObservation {
            kind: ObservationKind::Domain,
            target: result.target.clone(),
            dns: None,
            domain: Some(DomainObservationFact {
                host: result.target.clone(),
                http_status: http_status(detail_value(result, "httpStatus")),
                tls13_status: tls_status(detail_value(result, "tls13Status")),
                tls12_status: tls_status(detail_value(result, "tls12Status")),
                transport_failure: transport_failure(
                    detail_value(result, "tlsError")
                        .filter(|value| *value != "none")
                        .or_else(|| detail_value(result, "tls13Error").filter(|value| *value != "none"))
                        .or_else(|| detail_value(result, "tls12Error").filter(|value| *value != "none"))
                        .unwrap_or("none"),
                ),
                certificate_anomaly: result.outcome == "tls_cert_invalid"
                    || detail_value(result, "tlsSignal") == Some("tls_cert_invalid"),
            }),
            tcp: None,
            quic: None,
            service: None,
            circumvention: None,
            telegram: None,
            throughput: None,
            strategy: None,
            evidence: vec![result.outcome.clone()],
        }),
        "tcp_fat_header" => Some(ProbeObservation {
            kind: ObservationKind::Tcp,
            target: result.target.clone(),
            dns: None,
            domain: None,
            tcp: Some(TcpObservationFact {
                provider: detail_value(result, "provider").unwrap_or(result.target.as_str()).to_string(),
                status: tcp_status(&result.outcome),
                selected_sni: detail_value(result, "selectedSni").map(str::to_string),
                bytes_sent: detail_value(result, "bytesSent").and_then(|value| value.parse::<usize>().ok()),
                responses_seen: detail_value(result, "responsesSeen").and_then(|value| value.parse::<usize>().ok()),
            }),
            quic: None,
            service: None,
            circumvention: None,
            telegram: None,
            throughput: None,
            strategy: None,
            evidence: vec![result.outcome.clone(), detail_value(result, "attempts").unwrap_or_default().to_string()],
        }),
        "quic_reachability" => Some(ProbeObservation {
            kind: ObservationKind::Quic,
            target: result.target.clone(),
            dns: None,
            domain: None,
            tcp: None,
            quic: Some(QuicObservationFact {
                host: result.target.clone(),
                status: quic_status(detail_value(result, "status").unwrap_or(result.outcome.as_str())),
                transport_failure: transport_failure(detail_value(result, "error").unwrap_or("none")),
            }),
            service: None,
            circumvention: None,
            telegram: None,
            throughput: None,
            strategy: None,
            evidence: vec![result.outcome.clone()],
        }),
        "service_reachability" => Some(ProbeObservation {
            kind: ObservationKind::Service,
            target: result.target.clone(),
            dns: None,
            domain: None,
            tcp: None,
            quic: None,
            service: Some(ServiceObservationFact {
                service: detail_value(result, "service").unwrap_or(result.target.as_str()).to_string(),
                bootstrap_status: http_status(detail_value(result, "bootstrapStatus")),
                media_status: http_status(detail_value(result, "mediaStatus")),
                endpoint_status: endpoint_status(detail_value(result, "gatewayStatus")),
                endpoint_failure: transport_failure(detail_value(result, "gatewayError").unwrap_or("none")),
                quic_status: quic_status(detail_value(result, "quicStatus").unwrap_or("not_run")),
                quic_failure: transport_failure(detail_value(result, "quicError").unwrap_or("none")),
            }),
            circumvention: None,
            telegram: None,
            throughput: None,
            strategy: None,
            evidence: vec![result.outcome.clone()],
        }),
        "circumvention_reachability" => Some(ProbeObservation {
            kind: ObservationKind::Circumvention,
            target: result.target.clone(),
            dns: None,
            domain: None,
            tcp: None,
            quic: None,
            service: None,
            circumvention: Some(CircumventionObservationFact {
                tool: detail_value(result, "tool").unwrap_or(result.target.as_str()).to_string(),
                bootstrap_status: http_status(detail_value(result, "bootstrapStatus")),
                handshake_status: endpoint_status(detail_value(result, "handshakeStatus")),
                handshake_failure: transport_failure(detail_value(result, "handshakeError").unwrap_or("none")),
            }),
            telegram: None,
            throughput: None,
            strategy: None,
            evidence: vec![result.outcome.clone()],
        }),
        "telegram_availability" => Some(ProbeObservation {
            kind: ObservationKind::Telegram,
            target: result.target.clone(),
            dns: None,
            domain: None,
            tcp: None,
            quic: None,
            service: None,
            circumvention: None,
            telegram: Some(TelegramObservationFact {
                verdict: telegram_verdict(detail_value(result, "verdict").unwrap_or(result.outcome.as_str())),
                quality_score: detail_value(result, "qualityScore")
                    .and_then(|value| value.parse::<i32>().ok())
                    .unwrap_or_default(),
                download_status: telegram_transfer_status(detail_value(result, "downloadStatus").unwrap_or("error")),
                upload_status: telegram_transfer_status(detail_value(result, "uploadStatus").unwrap_or("error")),
                dc_reachable: detail_value(result, "dcReachable")
                    .and_then(|value| value.parse::<usize>().ok())
                    .unwrap_or(0),
                dc_total: detail_value(result, "dcTotal").and_then(|value| value.parse::<usize>().ok()).unwrap_or(0),
            }),
            throughput: None,
            strategy: None,
            evidence: vec![result.outcome.clone()],
        }),
        "throughput_window" => Some(ProbeObservation {
            kind: ObservationKind::Throughput,
            target: result.target.clone(),
            dns: None,
            domain: None,
            tcp: None,
            quic: None,
            service: None,
            circumvention: None,
            telegram: None,
            throughput: Some(ThroughputObservationFact {
                label: result.target.clone(),
                status: throughput_status(&result.outcome),
                is_control: detail_value(result, "isControl").is_some_and(|value| value == "true"),
                median_bps: detail_value(result, "medianBps").and_then(|value| value.parse::<u64>().ok()).unwrap_or(0),
                sample_bps: detail_list(result, "bpsReadings")
                    .into_iter()
                    .filter_map(|value| value.parse::<u64>().ok())
                    .collect(),
                window_bytes: detail_value(result, "windowBytes")
                    .and_then(|value| value.parse::<usize>().ok())
                    .unwrap_or_default(),
            }),
            strategy: None,
            evidence: vec![result.outcome.clone()],
        }),
        "strategy_http" | "strategy_https" | "strategy_quic" => Some(ProbeObservation {
            kind: ObservationKind::Strategy,
            target: result.target.clone(),
            dns: None,
            domain: None,
            tcp: None,
            quic: None,
            service: None,
            circumvention: None,
            telegram: None,
            throughput: None,
            strategy: Some(StrategyObservationFact {
                candidate_id: detail_value(result, "candidateId").map(str::to_string),
                candidate_label: detail_value(result, "candidateLabel").map(str::to_string),
                candidate_family: detail_value(result, "candidateFamily").map(str::to_string),
                protocol: match result.probe_type.as_str() {
                    "strategy_http" => StrategyProbeProtocol::Http,
                    "strategy_https" => StrategyProbeProtocol::Https,
                    "strategy_quic" => StrategyProbeProtocol::Quic,
                    _ => StrategyProbeProtocol::Candidate,
                },
                status: strategy_status(&result.outcome),
                transport_failure: transport_failure(
                    detail_value(result, "error").or_else(|| detail_value(result, "tlsError")).unwrap_or("none"),
                ),
            }),
            evidence: vec![result.outcome.clone()],
        }),
        _ => None,
    }
}

pub(crate) fn detail_value<'a>(probe: &'a ProbeResult, key: &str) -> Option<&'a str> {
    probe.details.iter().find_map(|detail| (detail.key == key).then_some(detail.value.as_str()))
}

pub(crate) fn detail_list(probe: &ProbeResult, key: &str) -> Vec<String> {
    detail_value(probe, key)
        .map(|value| {
            value
                .split('|')
                .flat_map(|entry| entry.split(','))
                .map(str::trim)
                .filter(|entry| !entry.is_empty() && *entry != "none" && *entry != "unknown")
                .map(str::to_string)
                .collect()
        })
        .unwrap_or_default()
}

pub(crate) fn transport_failure(text: &str) -> TransportFailureKind {
    let normalized = text.trim().to_ascii_lowercase();
    if normalized.is_empty() || normalized == "none" || normalized == "not_run" {
        return TransportFailureKind::None;
    }
    if normalized.contains("alert") {
        return TransportFailureKind::Alert;
    }
    if normalized.contains("reset") || normalized.contains("broken pipe") || normalized.contains("aborted") {
        return TransportFailureKind::Reset;
    }
    if normalized.contains("unexpected eof") || normalized.contains("closed") || normalized.contains("close notify") {
        return TransportFailureKind::Close;
    }
    if normalized.contains("timed out") || normalized.contains("timeout") || normalized.contains("would block") {
        return TransportFailureKind::Timeout;
    }
    if normalized.contains("issuer") || normalized.contains("certificate") {
        return TransportFailureKind::Certificate;
    }
    TransportFailureKind::Other
}

fn dns_status(outcome: &str) -> DnsObservationStatus {
    match outcome {
        "dns_match" => DnsObservationStatus::Match,
        "dns_expected_mismatch" => DnsObservationStatus::ExpectedMismatch,
        "dns_substitution" => DnsObservationStatus::Substitution,
        "encrypted_dns_blocked" => DnsObservationStatus::EncryptedBlocked,
        "udp_blocked" | "udp_skipped_or_blocked" => DnsObservationStatus::UdpBlocked,
        _ => DnsObservationStatus::Unavailable,
    }
}

fn http_status(status: Option<&str>) -> HttpProbeStatus {
    match status.unwrap_or("not_run") {
        "http_ok" => HttpProbeStatus::Ok,
        "http_blockpage" => HttpProbeStatus::Blockpage,
        "not_run" => HttpProbeStatus::NotRun,
        _ => HttpProbeStatus::Unreachable,
    }
}

fn tls_status(status: Option<&str>) -> TlsProbeStatus {
    match status.unwrap_or("not_run") {
        "tls_ok" => TlsProbeStatus::Ok,
        "tls_version_split" => TlsProbeStatus::VersionSplit,
        "tls_cert_invalid" => TlsProbeStatus::CertInvalid,
        "not_run" => TlsProbeStatus::NotRun,
        _ => TlsProbeStatus::HandshakeFailed,
    }
}

fn tcp_status(outcome: &str) -> TcpProbeStatus {
    match outcome {
        "whitelist_sni_ok" => TcpProbeStatus::WhitelistSniOk,
        "tcp_16kb_blocked" => TcpProbeStatus::Blocked16Kb,
        "tcp_ok" | "fat_ok" => TcpProbeStatus::Ok,
        "tcp_connect_failed" => TcpProbeStatus::ConnectFailed,
        _ => TcpProbeStatus::Error,
    }
}

fn quic_status(status: &str) -> QuicProbeStatus {
    match status {
        "quic_initial_response" => QuicProbeStatus::InitialResponse,
        "quic_response" => QuicProbeStatus::Response,
        "quic_empty" => QuicProbeStatus::Empty,
        "not_run" => QuicProbeStatus::NotRun,
        _ => QuicProbeStatus::Error,
    }
}

fn endpoint_status(status: Option<&str>) -> EndpointProbeStatus {
    match status.unwrap_or("not_run") {
        "tls_ok" | "tcp_connect_ok" => EndpointProbeStatus::Ok,
        "not_run" => EndpointProbeStatus::NotRun,
        value if value.contains("blocked") => EndpointProbeStatus::Blocked,
        _ => EndpointProbeStatus::Failed,
    }
}

fn telegram_verdict(value: &str) -> TelegramVerdict {
    match value {
        "ok" => TelegramVerdict::Ok,
        "slow" => TelegramVerdict::Slow,
        "partial" => TelegramVerdict::Partial,
        "blocked" => TelegramVerdict::Blocked,
        _ => TelegramVerdict::Error,
    }
}

fn telegram_transfer_status(value: &str) -> TelegramTransferStatus {
    match value {
        "ok" => TelegramTransferStatus::Ok,
        "slow" => TelegramTransferStatus::Slow,
        "stalled" => TelegramTransferStatus::Stalled,
        "blocked" => TelegramTransferStatus::Blocked,
        _ => TelegramTransferStatus::Error,
    }
}

fn throughput_status(value: &str) -> ThroughputProbeStatus {
    match value {
        "throughput_measured" => ThroughputProbeStatus::Measured,
        "invalid_target" => ThroughputProbeStatus::InvalidTarget,
        _ => ThroughputProbeStatus::HttpUnreachable,
    }
}

fn strategy_status(value: &str) -> StrategyProbeStatus {
    match value {
        "http_ok" | "tls_ok" | "tls_version_split" | "quic_initial_response" | "quic_response" => {
            StrategyProbeStatus::Success
        }
        "partial" => StrategyProbeStatus::Partial,
        "skipped" => StrategyProbeStatus::Skipped,
        "not_applicable" => StrategyProbeStatus::NotApplicable,
        _ => StrategyProbeStatus::Failed,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::ProbeDetail;

    #[test]
    fn throughput_probe_builds_typed_observation() {
        let observation = observation_for_probe(&ProbeResult {
            probe_type: "throughput_window".to_string(),
            target: "YouTube".to_string(),
            outcome: "throughput_measured".to_string(),
            details: vec![
                ProbeDetail { key: "isControl".to_string(), value: "false".to_string() },
                ProbeDetail { key: "medianBps".to_string(), value: "1000".to_string() },
                ProbeDetail { key: "bpsReadings".to_string(), value: "1000|800".to_string() },
                ProbeDetail { key: "windowBytes".to_string(), value: "8192".to_string() },
            ],
        })
        .expect("observation");

        let throughput = observation.throughput.expect("throughput payload");
        assert_eq!(observation.kind, ObservationKind::Throughput);
        assert_eq!(throughput.status, ThroughputProbeStatus::Measured);
        assert_eq!(throughput.sample_bps, vec![1000, 800]);
        assert_eq!(throughput.window_bytes, 8192);
    }

    #[test]
    fn transport_failure_parses_timeout_reset_and_close() {
        assert_eq!(transport_failure("operation timed out"), TransportFailureKind::Timeout);
        assert_eq!(transport_failure("connection reset by peer"), TransportFailureKind::Reset);
        assert_eq!(transport_failure("unexpected eof"), TransportFailureKind::Close);
    }
}
