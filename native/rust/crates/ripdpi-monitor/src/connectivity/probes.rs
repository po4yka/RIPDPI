use std::collections::BTreeSet;
use std::sync::Arc;

use ripdpi_packets::{build_realistic_quic_initial, parse_quic_initial, QUIC_V1_VERSION};
use rustls::client::danger::ServerCertVerifier;

use crate::dns::*;
use crate::fat_header::*;
use crate::http::*;
use crate::tls::*;
use crate::transport::*;
use crate::types::*;
use crate::util::*;

use super::endpoint::{
    is_probe_failure, is_server_error, measure_throughput_window, probe_http_url, run_endpoint_probe,
    run_quic_endpoint_probe,
};

/// Classify DNS latency into a human-readable quality tier.
///
/// Thresholds:
/// - UDP > 3000ms => "throttled"
/// - encrypted < 100ms => "fast"
/// - encrypted 100..=500ms => "normal"
/// - encrypted > 500ms => "slow"
/// - parse failure => "unknown"
pub(crate) fn classify_dns_latency_quality(udp_latency_ms: &str, encrypted_latency_ms: &str) -> String {
    let udp: u64 = udp_latency_ms.parse().unwrap_or(0);
    let encrypted: u64 = encrypted_latency_ms.parse().unwrap_or(0);
    if udp > 3000 {
        return "throttled".to_string();
    }
    if encrypted == 0 && udp == 0 {
        return "unknown".to_string();
    }
    match encrypted {
        0..=99 => "fast".to_string(),
        100..=500 => "normal".to_string(),
        _ => "slow".to_string(),
    }
}

pub(crate) fn run_dns_probe(target: &DnsTarget, transport: &TransportConfig, path_mode: &ScanPathMode) -> ProbeResult {
    let udp_server = target.udp_server.clone().unwrap_or_else(|| DEFAULT_DNS_SERVER.to_string());
    let (encrypted_endpoint, encrypted_bootstrap_ips) = match encrypted_dns_endpoint_for_target(target) {
        Ok(value) => value,
        Err(err) => {
            return ProbeResult {
                probe_type: "dns_integrity".to_string(),
                target: target.domain.clone(),
                outcome: "dns_unavailable".to_string(),
                details: vec![ProbeDetail { key: "encryptedDnsError".to_string(), value: err }],
            };
        }
    };
    let udp_started = std::time::Instant::now();
    let udp_result = resolve_via_udp(&target.domain, &udp_server, transport);
    let udp_latency_ms = udp_started.elapsed().as_millis().to_string();
    let encrypted_started = std::time::Instant::now();
    let encrypted_result = resolve_via_encrypted_dns(&target.domain, encrypted_endpoint.clone(), transport);
    let encrypted_latency_ms = encrypted_started.elapsed().as_millis().to_string();
    let expected: BTreeSet<String> = target.expected_ips.iter().cloned().collect();

    let outcome = match (&udp_result, &encrypted_result) {
        (Ok(udp_ips), Ok(encrypted_ips)) if ip_set(udp_ips) == ip_set(encrypted_ips) => {
            if !expected.is_empty() && ip_set(udp_ips) != expected {
                "dns_expected_mismatch".to_string()
            } else {
                "dns_match".to_string()
            }
        }
        (Ok(_), Ok(_)) => "dns_substitution".to_string(),
        (Ok(_), Err(_)) => "encrypted_dns_blocked".to_string(),
        (Err(err), Ok(_)) if err == "dns_nxdomain" => "dns_nxdomain".to_string(),
        (Err(_), Ok(_)) => {
            if matches!(path_mode, ScanPathMode::InPath) {
                "udp_skipped_or_blocked".to_string()
            } else {
                "udp_blocked".to_string()
            }
        }
        (Err(_), Err(_)) => "dns_unavailable".to_string(),
    };

    ProbeResult {
        probe_type: "dns_integrity".to_string(),
        target: target.domain.clone(),
        outcome,
        details: vec![
            ProbeDetail { key: "udpServer".to_string(), value: udp_server },
            ProbeDetail { key: "udpAddresses".to_string(), value: format_result_set(&udp_result) },
            ProbeDetail { key: "udpLatencyMs".to_string(), value: udp_latency_ms.clone() },
            ProbeDetail {
                key: "encryptedResolverId".to_string(),
                value: encrypted_endpoint.resolver_id.clone().unwrap_or_default(),
            },
            ProbeDetail {
                key: "encryptedProtocol".to_string(),
                value: encrypted_endpoint.protocol.as_str().to_string(),
            },
            ProbeDetail {
                key: "encryptedEndpoint".to_string(),
                value: encrypted_endpoint
                    .doh_url
                    .clone()
                    .unwrap_or_else(|| format!("{}:{}", encrypted_endpoint.host, encrypted_endpoint.port)),
            },
            ProbeDetail { key: "encryptedHost".to_string(), value: encrypted_endpoint.host.clone() },
            ProbeDetail { key: "encryptedPort".to_string(), value: encrypted_endpoint.port.to_string() },
            ProbeDetail {
                key: "encryptedTlsServerName".to_string(),
                value: encrypted_endpoint.tls_server_name.clone().unwrap_or_default(),
            },
            ProbeDetail { key: "encryptedBootstrapIps".to_string(), value: encrypted_bootstrap_ips.join("|") },
            ProbeDetail {
                key: "encryptedBootstrapValidated".to_string(),
                value: (encrypted_result.is_ok() && !encrypted_bootstrap_ips.is_empty()).to_string(),
            },
            ProbeDetail {
                key: "encryptedDohUrl".to_string(),
                value: encrypted_endpoint.doh_url.clone().unwrap_or_default(),
            },
            ProbeDetail {
                key: "encryptedDnscryptProviderName".to_string(),
                value: encrypted_endpoint.dnscrypt_provider_name.clone().unwrap_or_default(),
            },
            ProbeDetail {
                key: "encryptedDnscryptPublicKey".to_string(),
                value: encrypted_endpoint.dnscrypt_public_key.clone().unwrap_or_default(),
            },
            ProbeDetail { key: "encryptedAddresses".to_string(), value: format_result_set(&encrypted_result) },
            ProbeDetail { key: "encryptedLatencyMs".to_string(), value: encrypted_latency_ms.clone() },
            ProbeDetail {
                key: "dnsLatencyQuality".to_string(),
                value: classify_dns_latency_quality(&udp_latency_ms, &encrypted_latency_ms),
            },
            ProbeDetail {
                key: "expected".to_string(),
                value: if expected.is_empty() {
                    "[]".to_string()
                } else {
                    expected.iter().cloned().collect::<Vec<_>>().join("|")
                },
            },
        ],
    }
}

pub(crate) fn run_domain_probe(
    target: &DomainTarget,
    transport: &TransportConfig,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> ProbeResult {
    let https_port = target.https_port.unwrap_or(443);
    let http_port = target.http_port.unwrap_or(80);
    let connect_target = domain_connect_target(target);
    let resolved = resolve_addresses(&connect_target, https_port);
    let tls13 = try_tls_handshake(
        &connect_target,
        https_port,
        transport,
        &target.host,
        true,
        TlsClientProfile::Tls13Only,
        tls_verifier,
    );
    let tls12 = try_tls_handshake(
        &connect_target,
        https_port,
        transport,
        &target.host,
        true,
        TlsClientProfile::Tls12Only,
        tls_verifier,
    );
    let tls_ech = try_tls_handshake(
        &connect_target,
        https_port,
        transport,
        &target.host,
        true,
        TlsClientProfile::Tls13WithEch,
        tls_verifier,
    );
    let http = try_http_request(&connect_target, http_port, transport, &target.host, &target.http_path, false);
    let alt_svc_value = http.response.as_ref().and_then(|r| r.headers.get("alt-svc")).cloned();
    let h3_advertised = alt_svc_value.as_ref().is_some_and(|v| v.contains("h3"));
    let tls_signal = classify_tls_signal(&tls13, &tls12);
    let preferred_tls = preferred_tls_observation(&tls13, &tls12);

    let outcome = if tls13.certificate_anomaly || tls12.certificate_anomaly {
        "tls_cert_invalid".to_string()
    } else if tls13.status == "tls_ok" && tls12.status == "tls_ok" {
        "tls_ok".to_string()
    } else if tls13.status == "tls_ok" || tls12.status == "tls_ok" {
        if is_server_tls_version_rejection(&tls13, &tls12) {
            "tls_ok".to_string()
        } else {
            "tls_version_split".to_string()
        }
    } else if tls_ech.status == "tls_ok" {
        "tls_ech_only".to_string()
    } else if is_blockpage(&http) {
        "http_blockpage".to_string()
    } else if http.status == "http_ok" {
        "http_ok".to_string()
    } else {
        "unreachable".to_string()
    };

    // Single retry on total failure to distinguish transient from consistent blocking
    let (outcome, probe_retry_count) = if outcome == "unreachable" {
        let retry = try_tls_handshake(
            &connect_target,
            https_port,
            transport,
            &target.host,
            true,
            TlsClientProfile::Tls13Only,
            tls_verifier,
        );
        if retry.status == "tls_ok" {
            ("tls_ok".to_string(), 1usize)
        } else {
            ("unreachable".to_string(), 1usize)
        }
    } else {
        (outcome, 0usize)
    };

    ProbeResult {
        probe_type: "domain_reachability".to_string(),
        target: target.host.clone(),
        outcome,
        details: vec![
            ProbeDetail { key: "resolved".to_string(), value: format_socket_result(&resolved) },
            ProbeDetail { key: "tlsStatus".to_string(), value: preferred_tls.status.clone() },
            ProbeDetail {
                key: "tlsVersion".to_string(),
                value: preferred_tls.version.clone().unwrap_or_else(|| "unknown".to_string()),
            },
            ProbeDetail {
                key: "tlsError".to_string(),
                value: preferred_tls.error.clone().unwrap_or_else(|| "none".to_string()),
            },
            ProbeDetail { key: "tlsSignal".to_string(), value: tls_signal.to_string() },
            ProbeDetail { key: "tls13Status".to_string(), value: tls13.status },
            ProbeDetail {
                key: "tls13Version".to_string(),
                value: tls13.version.unwrap_or_else(|| "unknown".to_string()),
            },
            ProbeDetail { key: "tls13Error".to_string(), value: tls13.error.unwrap_or_else(|| "none".to_string()) },
            ProbeDetail { key: "tls12Status".to_string(), value: tls12.status },
            ProbeDetail {
                key: "tls12Version".to_string(),
                value: tls12.version.unwrap_or_else(|| "unknown".to_string()),
            },
            ProbeDetail { key: "tls12Error".to_string(), value: tls12.error.unwrap_or_else(|| "none".to_string()) },
            ProbeDetail { key: "tlsEchStatus".to_string(), value: tls_ech.status },
            ProbeDetail {
                key: "tlsEchVersion".to_string(),
                value: tls_ech.version.unwrap_or_else(|| "unknown".to_string()),
            },
            ProbeDetail { key: "tlsEchError".to_string(), value: tls_ech.error.unwrap_or_else(|| "none".to_string()) },
            ProbeDetail {
                key: "tlsEchResolutionDetail".to_string(),
                value: tls_ech.ech_resolution_detail.unwrap_or_else(|| "none".to_string()),
            },
            ProbeDetail { key: "httpStatus".to_string(), value: http.status.clone() },
            ProbeDetail { key: "httpResponse".to_string(), value: describe_http_observation(&http) },
            ProbeDetail { key: "h3Advertised".to_string(), value: h3_advertised.to_string() },
            ProbeDetail { key: "altSvc".to_string(), value: alt_svc_value.unwrap_or_else(|| "none".to_string()) },
            ProbeDetail { key: "isControl".to_string(), value: target.is_control.to_string() },
            ProbeDetail { key: "probeRetryCount".to_string(), value: probe_retry_count.to_string() },
        ],
    }
}

pub(crate) fn run_tcp_probe(target: &TcpTarget, whitelist_sni: &[String], transport: &TransportConfig) -> ProbeResult {
    let base_host_header =
        target.host_header.clone().or_else(|| target.sni.clone()).unwrap_or_else(|| target.provider.clone());
    let mut attempted_candidates = Vec::new();

    let initial_candidate = target.sni.clone().unwrap_or_default();
    let initial = run_fat_header_attempt(target, transport, &initial_candidate, &base_host_header);
    attempted_candidates.push(format!(
        "{}:{}",
        if initial_candidate.is_empty() { "<empty>" } else { initial_candidate.as_str() },
        fat_status_label(&initial.status)
    ));

    let mut outcome = classify_fat_header_outcome(&initial.status).to_string();
    let mut winning_sni = None;
    let mut final_observation = initial.clone();

    let tried_whitelist_candidates =
        initial.status != FatHeaderStatus::Success && target.sni.is_some() && !whitelist_sni.is_empty();
    if tried_whitelist_candidates {
        for candidate in whitelist_sni {
            let candidate_result = run_fat_header_attempt(target, transport, candidate, candidate);
            attempted_candidates.push(format!("{}:{}", candidate, fat_status_label(&candidate_result.status)));
            final_observation = candidate_result.clone();
            if candidate_result.status == FatHeaderStatus::Success || candidate_result.responses_seen > 0 {
                outcome = "whitelist_sni_ok".to_string();
                winning_sni = Some(candidate.clone());
                break;
            }
        }
        if winning_sni.is_none() {
            outcome = "whitelist_sni_failed".to_string();
        }
    }

    ProbeResult {
        probe_type: "tcp_fat_header".to_string(),
        target: format!("{}:{} ({})", target.ip, target.port, target.provider),
        outcome,
        details: vec![
            ProbeDetail { key: "provider".to_string(), value: target.provider.clone() },
            ProbeDetail { key: "attempts".to_string(), value: attempted_candidates.join("|") },
            ProbeDetail {
                key: "selectedSni".to_string(),
                value: winning_sni.unwrap_or_else(|| {
                    if initial_candidate.is_empty() {
                        "<empty>".to_string()
                    } else {
                        initial_candidate
                    }
                }),
            },
            ProbeDetail { key: "asn".to_string(), value: target.asn.clone().unwrap_or_else(|| "unknown".to_string()) },
            ProbeDetail { key: "bytesSent".to_string(), value: final_observation.bytes_sent.to_string() },
            ProbeDetail { key: "responsesSeen".to_string(), value: final_observation.responses_seen.to_string() },
            ProbeDetail {
                key: "lastError".to_string(),
                value: final_observation.error.unwrap_or_else(|| "none".to_string()),
            },
        ],
    }
}

pub(crate) fn run_quic_probe(target: &QuicTarget, transport: &TransportConfig) -> ProbeResult {
    let started = now_ms();
    let connect_target = quic_connect_target(target);
    let payload = build_realistic_quic_initial(QUIC_V1_VERSION, Some(target.host.as_str())).unwrap_or_default();
    let response = relay_udp_payload(&connect_target, target.port, transport, &payload);
    let latency_ms = now_ms().saturating_sub(started);
    let (outcome, status, error) = match response {
        Ok(bytes) if parse_quic_initial(&bytes).is_some() => {
            ("quic_initial_response".to_string(), "quic_initial_response".to_string(), "none".to_string())
        }
        Ok(bytes) if !bytes.is_empty() => {
            ("quic_response".to_string(), "quic_response".to_string(), "none".to_string())
        }
        Ok(_) => ("quic_empty".to_string(), "quic_empty".to_string(), "none".to_string()),
        Err(err) => ("quic_error".to_string(), "quic_error".to_string(), err),
    };
    ProbeResult {
        probe_type: "quic_reachability".to_string(),
        target: target.host.clone(),
        outcome,
        details: vec![
            ProbeDetail { key: "status".to_string(), value: status },
            ProbeDetail { key: "error".to_string(), value: error },
            ProbeDetail { key: "latencyMs".to_string(), value: latency_ms.to_string() },
            ProbeDetail { key: "port".to_string(), value: target.port.to_string() },
        ],
    }
}

pub(crate) fn run_service_probe(
    target: &ServiceTarget,
    transport: &TransportConfig,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> ProbeResult {
    let bootstrap = target.bootstrap_url.as_ref().map(|url| probe_http_url(url, None, None, transport));
    let media = target.media_url.as_ref().map(|url| probe_http_url(url, None, None, transport));
    let (gateway_status, gateway_error) = run_endpoint_probe(
        target.tcp_endpoint_host.as_deref(),
        target.tcp_endpoint_ip.as_deref(),
        target.tcp_endpoint_port,
        target.tls_server_name.as_deref().or(target.tcp_endpoint_host.as_deref()),
        transport,
        tls_verifier,
    );
    let (quic_status, quic_error) = run_quic_endpoint_probe(
        target.quic_host.as_deref(),
        target.quic_connect_ip.as_deref(),
        target.quic_port,
        transport,
    );

    let bootstrap_status =
        bootstrap.as_ref().map_or_else(|| "not_run".to_string(), |observation| observation.status.clone());
    let bootstrap_detail = bootstrap.as_ref().map_or_else(|| "not_run".to_string(), describe_http_observation);
    let media_status = media.as_ref().map_or_else(|| "not_run".to_string(), |observation| observation.status.clone());
    let media_detail = media.as_ref().map_or_else(|| "not_run".to_string(), describe_http_observation);
    let outcome = if is_probe_failure(&bootstrap_status)
        || is_probe_failure(&media_status)
        || is_probe_failure(&gateway_status)
        || is_probe_failure(&quic_status)
    {
        if bootstrap_status == "http_ok"
            && media_status == "http_ok"
            && matches!(gateway_status.as_str(), "not_run" | "tls_ok" | "tcp_connect_ok")
            && matches!(quic_status.as_str(), "not_run" | "quic_initial_response" | "quic_response")
        {
            "service_ok"
        } else if bootstrap_status != "not_run" && bootstrap_status != "http_ok" {
            "service_blocked"
        } else {
            "service_partial"
        }
    } else {
        "service_ok"
    };

    ProbeResult {
        probe_type: "service_reachability".to_string(),
        target: target.service.clone(),
        outcome: outcome.to_string(),
        details: vec![
            ProbeDetail { key: "id".to_string(), value: target.id.clone() },
            ProbeDetail { key: "service".to_string(), value: target.service.clone() },
            ProbeDetail { key: "bootstrapStatus".to_string(), value: bootstrap_status },
            ProbeDetail { key: "bootstrapDetail".to_string(), value: bootstrap_detail },
            ProbeDetail { key: "mediaStatus".to_string(), value: media_status },
            ProbeDetail { key: "mediaDetail".to_string(), value: media_detail },
            ProbeDetail { key: "gatewayStatus".to_string(), value: gateway_status },
            ProbeDetail { key: "gatewayError".to_string(), value: gateway_error },
            ProbeDetail { key: "quicStatus".to_string(), value: quic_status },
            ProbeDetail { key: "quicError".to_string(), value: quic_error },
        ],
    }
}

pub(crate) fn run_circumvention_probe(
    target: &CircumventionTarget,
    transport: &TransportConfig,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> ProbeResult {
    let bootstrap = target.bootstrap_url.as_ref().map(|url| probe_http_url(url, None, None, transport));
    let (handshake_status, handshake_error) = run_endpoint_probe(
        target.handshake_host.as_deref(),
        target.handshake_ip.as_deref(),
        target.handshake_port,
        target.tls_server_name.as_deref().or(target.handshake_host.as_deref()),
        transport,
        tls_verifier,
    );
    let initial_bootstrap_status =
        bootstrap.as_ref().map_or_else(|| "not_run".to_string(), |observation| observation.status.clone());
    let bootstrap_detail = bootstrap.as_ref().map_or_else(|| "not_run".to_string(), describe_http_observation);

    // Retry bootstrap once if it failed, to distinguish transient from consistent
    let (bootstrap_status, circumvention_retry_count) = if is_probe_failure(&initial_bootstrap_status)
        && initial_bootstrap_status != "not_run"
    {
        let retry = target.bootstrap_url.as_ref().map(|url| probe_http_url(url, None, None, transport));
        let retry_status = retry.as_ref().map_or_else(|| initial_bootstrap_status.clone(), |obs| obs.status.clone());
        (retry_status, 1usize)
    } else {
        (initial_bootstrap_status, 0usize)
    };

    let outcome = if is_probe_failure(&handshake_status) {
        "circumvention_blocked"
    } else if is_probe_failure(&bootstrap_status) {
        if is_server_error(&bootstrap_status) {
            "circumvention_degraded"
        } else {
            "circumvention_blocked"
        }
    } else {
        "circumvention_ok"
    };
    ProbeResult {
        probe_type: "circumvention_reachability".to_string(),
        target: target.tool.clone(),
        outcome: outcome.to_string(),
        details: vec![
            ProbeDetail { key: "id".to_string(), value: target.id.clone() },
            ProbeDetail { key: "tool".to_string(), value: target.tool.clone() },
            ProbeDetail { key: "bootstrapStatus".to_string(), value: bootstrap_status },
            ProbeDetail { key: "bootstrapDetail".to_string(), value: bootstrap_detail },
            ProbeDetail { key: "handshakeStatus".to_string(), value: handshake_status },
            ProbeDetail { key: "handshakeError".to_string(), value: handshake_error },
            ProbeDetail { key: "probeRetryCount".to_string(), value: circumvention_retry_count.to_string() },
        ],
    }
}

pub(crate) fn run_throughput_probe(target: &ThroughputTarget, transport: &TransportConfig) -> ProbeResult {
    let samples = (0..target.runs.max(1)).map(|_| measure_throughput_window(target, transport)).collect::<Vec<_>>();
    let mut bps_values = samples.iter().map(|sample| sample.bps).filter(|bps| *bps > 0).collect::<Vec<_>>();
    bps_values.sort_unstable();
    let median_bps = if bps_values.is_empty() { 0 } else { bps_values[bps_values.len() / 2] };
    let outcome = if samples.iter().any(|sample| sample.status == "http_ok" && sample.bps > 0) {
        "throughput_measured"
    } else {
        "throughput_failed"
    };
    ProbeResult {
        probe_type: "throughput_window".to_string(),
        target: target.label.clone(),
        outcome: outcome.to_string(),
        details: vec![
            ProbeDetail { key: "id".to_string(), value: target.id.clone() },
            ProbeDetail { key: "url".to_string(), value: target.url.clone() },
            ProbeDetail { key: "isControl".to_string(), value: target.is_control.to_string() },
            ProbeDetail { key: "windowBytes".to_string(), value: target.window_bytes.to_string() },
            ProbeDetail { key: "runs".to_string(), value: target.runs.to_string() },
            ProbeDetail {
                key: "bpsReadings".to_string(),
                value: samples.iter().map(|sample| sample.bps.to_string()).collect::<Vec<_>>().join("|"),
            },
            ProbeDetail {
                key: "statusReadings".to_string(),
                value: samples.iter().map(|sample| sample.status.clone()).collect::<Vec<_>>().join("|"),
            },
            ProbeDetail {
                key: "byteReadings".to_string(),
                value: samples.iter().map(|sample| sample.bytes_read.to_string()).collect::<Vec<_>>().join("|"),
            },
            ProbeDetail {
                key: "errorReadings".to_string(),
                value: samples.iter().map(|sample| sample.error.clone()).collect::<Vec<_>>().join("|"),
            },
            ProbeDetail { key: "medianBps".to_string(), value: median_bps.to_string() },
        ],
    }
}

#[cfg(test)]
mod tests {
    use super::classify_dns_latency_quality;

    #[test]
    fn dns_latency_quality_throttled_for_slow_udp() {
        assert_eq!(classify_dns_latency_quality("6000", "100"), "throttled");
        assert_eq!(classify_dns_latency_quality("3001", "50"), "throttled");
    }

    #[test]
    fn dns_latency_quality_fast_for_quick_encrypted() {
        assert_eq!(classify_dns_latency_quality("20", "50"), "fast");
        assert_eq!(classify_dns_latency_quality("20", "99"), "fast");
    }

    #[test]
    fn dns_latency_quality_normal_for_moderate() {
        assert_eq!(classify_dns_latency_quality("20", "250"), "normal");
    }

    #[test]
    fn dns_latency_quality_slow_for_high_encrypted() {
        assert_eq!(classify_dns_latency_quality("20", "600"), "slow");
    }

    #[test]
    fn dns_latency_quality_unknown_for_zero() {
        assert_eq!(classify_dns_latency_quality("0", "0"), "unknown");
    }
}
