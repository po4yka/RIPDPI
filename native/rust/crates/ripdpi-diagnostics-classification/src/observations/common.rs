use crate::types::{
    DnsObservationStatus, EndpointProbeStatus, HttpProbeStatus, ObservationKind, ProbeObservation, ProbeResult,
    QuicProbeStatus, StrategyProbeStatus, TcpProbeStatus, TelegramTransferStatus, TelegramVerdict,
    TelegramWsTunnelStatus, ThroughputProbeStatus, TlsProbeStatus, TransportFailureKind,
};

pub(crate) fn base_observation(result: &ProbeResult, kind: ObservationKind) -> ProbeObservation {
    ProbeObservation {
        kind,
        target: result.target.clone(),
        dns: None,
        domain: None,
        tcp: None,
        quic: None,
        service: None,
        circumvention: None,
        telegram: None,
        throughput: None,
        strategy: None,
        connection_concurrency: None,
        evidence: vec![result.outcome.clone()],
    }
}

pub fn detail_value<'a>(probe: &'a ProbeResult, key: &str) -> Option<&'a str> {
    probe.details.iter().find_map(|detail| (detail.key == key).then_some(detail.value.as_str()))
}

pub fn detail_list(probe: &ProbeResult, key: &str) -> Vec<String> {
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

pub fn transport_failure(text: &str) -> TransportFailureKind {
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

pub(crate) fn dns_status(outcome: &str) -> DnsObservationStatus {
    match outcome {
        "dns_match" => DnsObservationStatus::Match,
        "dns_expected_mismatch" => DnsObservationStatus::ExpectedMismatch,
        "dns_compatible_divergence" => DnsObservationStatus::CompatibleDivergence,
        "dns_suspicious_divergence" => DnsObservationStatus::SuspiciousDivergence,
        "dns_sinkhole_substitution" => DnsObservationStatus::SinkholeSubstitution,
        "dns_nxdomain_mismatch" => DnsObservationStatus::NxdomainMismatch,
        "dns_oracle_unavailable" => DnsObservationStatus::OracleUnavailable,
        "udp_blocked" | "udp_skipped_or_blocked" => DnsObservationStatus::UdpBlocked,
        _ => DnsObservationStatus::Unavailable,
    }
}

pub(crate) fn http_status(status: Option<&str>) -> HttpProbeStatus {
    match status.unwrap_or("not_run") {
        "http_ok" => HttpProbeStatus::Ok,
        value if http_status_code(value).is_some_and(|code| (200..400).contains(&code)) => HttpProbeStatus::Ok,
        "http_blockpage" => HttpProbeStatus::Blockpage,
        "not_run" => HttpProbeStatus::NotRun,
        _ => HttpProbeStatus::Unreachable,
    }
}

fn http_status_code(value: &str) -> Option<u16> {
    value.strip_prefix("http_status_")?.parse().ok()
}

pub(crate) fn tls_status(status: Option<&str>) -> TlsProbeStatus {
    match status.unwrap_or("not_run") {
        "tls_ok" => TlsProbeStatus::Ok,
        "tls_version_split" => TlsProbeStatus::VersionSplit,
        "tls_cert_invalid" => TlsProbeStatus::CertInvalid,
        "not_run" => TlsProbeStatus::NotRun,
        _ => TlsProbeStatus::HandshakeFailed,
    }
}

pub(crate) fn tcp_status(outcome: &str) -> TcpProbeStatus {
    match outcome {
        "whitelist_sni_ok" => TcpProbeStatus::WhitelistSniOk,
        "tcp_16kb_blocked" => TcpProbeStatus::Blocked16Kb,
        "tcp_freeze_after_threshold" => TcpProbeStatus::FreezeAfterThreshold,
        "tcp_ok" | "fat_ok" => TcpProbeStatus::Ok,
        "tcp_connect_failed" => TcpProbeStatus::ConnectFailed,
        _ => TcpProbeStatus::Error,
    }
}

pub(crate) fn quic_status(status: &str) -> QuicProbeStatus {
    match status {
        "quic_initial_response" => QuicProbeStatus::InitialResponse,
        "quic_response" => QuicProbeStatus::Response,
        "quic_empty" => QuicProbeStatus::Empty,
        "not_run" => QuicProbeStatus::NotRun,
        _ => QuicProbeStatus::Error,
    }
}

pub(crate) fn endpoint_status(status: Option<&str>) -> EndpointProbeStatus {
    match status.unwrap_or("not_run") {
        "tls_ok" | "tcp_connect_ok" => EndpointProbeStatus::Ok,
        "not_run" => EndpointProbeStatus::NotRun,
        value if value.contains("blocked") => EndpointProbeStatus::Blocked,
        _ => EndpointProbeStatus::Failed,
    }
}

pub(crate) fn telegram_verdict(value: &str) -> TelegramVerdict {
    match value {
        "ok" => TelegramVerdict::Ok,
        "slow" => TelegramVerdict::Slow,
        "partial" => TelegramVerdict::Partial,
        "blocked" => TelegramVerdict::Blocked,
        _ => TelegramVerdict::Error,
    }
}

pub(crate) fn telegram_transfer_status(value: &str) -> TelegramTransferStatus {
    match value {
        "ok" => TelegramTransferStatus::Ok,
        "slow" => TelegramTransferStatus::Slow,
        "stalled" => TelegramTransferStatus::Stalled,
        "blocked" => TelegramTransferStatus::Blocked,
        _ => TelegramTransferStatus::Error,
    }
}

pub(crate) fn telegram_ws_tunnel_status(value: &str) -> TelegramWsTunnelStatus {
    match value {
        "ok" => TelegramWsTunnelStatus::Ok,
        "unreachable" => TelegramWsTunnelStatus::Unreachable,
        _ => TelegramWsTunnelStatus::Unknown,
    }
}

pub(crate) fn throughput_status(value: &str) -> ThroughputProbeStatus {
    match value {
        "throughput_measured" => ThroughputProbeStatus::Measured,
        "invalid_target" => ThroughputProbeStatus::InvalidTarget,
        _ => ThroughputProbeStatus::HttpUnreachable,
    }
}

pub(crate) fn strategy_status(value: &str) -> StrategyProbeStatus {
    match value {
        "http_ok" | "http_redirect" | "tls_ok" | "tls_version_split" | "quic_initial_response" | "quic_response" => {
            StrategyProbeStatus::Success
        }
        "tls_ech_only" => StrategyProbeStatus::Partial,
        "partial" => StrategyProbeStatus::Partial,
        "skipped" => StrategyProbeStatus::Skipped,
        "not_applicable" => StrategyProbeStatus::NotApplicable,
        _ => StrategyProbeStatus::Failed,
    }
}
