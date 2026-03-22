use std::collections::BTreeSet;
use std::io::{ErrorKind, Read, Write};
use std::net::IpAddr;
use std::sync::{Arc, Mutex};

use ciadpi_packets::{build_realistic_quic_initial, parse_quic_initial, QUIC_V1_VERSION};
use rustls::client::danger::ServerCertVerifier;

use crate::dns::*;
use crate::fat_header::*;
use crate::http::*;
use crate::tls::*;
use crate::transport::*;
use crate::types::SharedState;
use crate::types::*;
use crate::util::*;

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
            ProbeDetail { key: "udpLatencyMs".to_string(), value: udp_latency_ms },
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
            ProbeDetail { key: "encryptedLatencyMs".to_string(), value: encrypted_latency_ms },
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
    let http = try_http_request(&connect_target, http_port, transport, &target.host, &target.http_path, false);
    let tls_signal = classify_tls_signal(&tls13, &tls12);
    let preferred_tls = preferred_tls_observation(&tls13, &tls12);

    let outcome = if tls13.certificate_anomaly || tls12.certificate_anomaly {
        "tls_cert_invalid".to_string()
    } else if tls13.status == "tls_ok" && tls12.status == "tls_ok" {
        "tls_ok".to_string()
    } else if tls13.status == "tls_ok" || tls12.status == "tls_ok" {
        "tls_version_split".to_string()
    } else if is_blockpage(&http) {
        "http_blockpage".to_string()
    } else if http.status == "http_ok" {
        "http_ok".to_string()
    } else {
        "unreachable".to_string()
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
            ProbeDetail { key: "httpStatus".to_string(), value: http.status.clone() },
            ProbeDetail { key: "httpResponse".to_string(), value: describe_http_observation(&http) },
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

    let tried_whitelist_candidates = initial.status != FatHeaderStatus::Success
        && (matches!(target.port, 443) || target.sni.is_some())
        && !whitelist_sni.is_empty();
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
        bootstrap.as_ref().map(|observation| observation.status.clone()).unwrap_or_else(|| "not_run".to_string());
    let bootstrap_detail = bootstrap.as_ref().map(describe_http_observation).unwrap_or_else(|| "not_run".to_string());
    let media_status =
        media.as_ref().map(|observation| observation.status.clone()).unwrap_or_else(|| "not_run".to_string());
    let media_detail = media.as_ref().map(describe_http_observation).unwrap_or_else(|| "not_run".to_string());
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
    let bootstrap_status =
        bootstrap.as_ref().map(|observation| observation.status.clone()).unwrap_or_else(|| "not_run".to_string());
    let bootstrap_detail = bootstrap.as_ref().map(describe_http_observation).unwrap_or_else(|| "not_run".to_string());
    let outcome = if is_probe_failure(&bootstrap_status) || is_probe_failure(&handshake_status) {
        "circumvention_blocked"
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

#[derive(Clone)]
struct ThroughputSample {
    status: String,
    bytes_read: usize,
    bps: u64,
    error: String,
}

struct ParsedHttpTarget {
    host: String,
    path: String,
    port: u16,
    secure: bool,
    connect_target: TargetAddress,
}

fn measure_throughput_window(target: &ThroughputTarget, transport: &TransportConfig) -> ThroughputSample {
    let parsed = match parse_http_target(&target.url, target.connect_ip.as_deref(), target.port) {
        Ok(parsed) => parsed,
        Err(err) => {
            return ThroughputSample { status: "invalid_target".to_string(), bytes_read: 0, bps: 0, error: err }
        }
    };
    let started = std::time::Instant::now();
    let mut stream = match open_probe_stream(
        &parsed.connect_target,
        parsed.port,
        transport,
        if parsed.secure { Some(parsed.host.as_str()) } else { None },
        parsed.secure,
        TlsClientProfile::Auto,
        None,
    ) {
        Ok(stream) => stream,
        Err(err) => {
            return ThroughputSample { status: "http_unreachable".to_string(), bytes_read: 0, bps: 0, error: err }
        }
    };
    let request =
        format!("GET {} HTTP/1.1\r\nHost: {}\r\nAccept: */*\r\nConnection: close\r\n\r\n", parsed.path, parsed.host);
    if let Err(err) = stream.write_all(request.as_bytes()).and_then(|_| stream.flush()) {
        stream.shutdown();
        return ThroughputSample {
            status: "http_unreachable".to_string(),
            bytes_read: 0,
            bps: 0,
            error: err.to_string(),
        };
    }
    let headers = match read_http_headers(&mut stream, MAX_HTTP_BYTES) {
        Ok(headers) => headers,
        Err(err) => {
            stream.shutdown();
            return ThroughputSample { status: "http_unreachable".to_string(), bytes_read: 0, bps: 0, error: err };
        }
    };
    let header_end = match find_headers_end(&headers) {
        Some(index) => index,
        None => {
            stream.shutdown();
            return ThroughputSample {
                status: "http_unreachable".to_string(),
                bytes_read: 0,
                bps: 0,
                error: "response_missing_headers".to_string(),
            };
        }
    };
    let response = match parse_http_response(&headers[..header_end], headers[header_end + 4..].to_vec()) {
        Ok(response) => response,
        Err(err) => {
            stream.shutdown();
            return ThroughputSample { status: "http_unreachable".to_string(), bytes_read: 0, bps: 0, error: err };
        }
    };
    let status = classify_http_response(&response);
    let mut bytes_read = response.body.len().min(target.window_bytes);
    let mut last_error = "none".to_string();
    while bytes_read < target.window_bytes {
        let remaining = target.window_bytes - bytes_read;
        let mut chunk = vec![0u8; remaining.min(16 * 1024)];
        match stream.read(&mut chunk) {
            Ok(0) => break,
            Ok(read) => {
                bytes_read += read;
            }
            Err(err) if matches!(err.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {
                last_error = err.to_string();
                break;
            }
            Err(err) => {
                last_error = err.to_string();
                break;
            }
        }
    }
    stream.shutdown();
    let duration_ms = started.elapsed().as_millis().max(1) as u64;
    let bps = (bytes_read as u64).saturating_mul(8).saturating_mul(1000) / duration_ms;
    ThroughputSample { status, bytes_read, bps, error: last_error }
}

fn probe_http_url(
    url: &str,
    connect_ip: Option<&str>,
    port_override: Option<u16>,
    transport: &TransportConfig,
) -> HttpObservation {
    match parse_http_target(url, connect_ip, port_override) {
        Ok(parsed) => {
            try_http_request(&parsed.connect_target, parsed.port, transport, &parsed.host, &parsed.path, parsed.secure)
        }
        Err(err) => HttpObservation { status: "http_unreachable".to_string(), response: None, error: Some(err) },
    }
}

fn run_endpoint_probe(
    host: Option<&str>,
    connect_ip: Option<&str>,
    port: u16,
    tls_name: Option<&str>,
    transport: &TransportConfig,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> (String, String) {
    let Some(target) = connect_target_from_parts(host, connect_ip) else {
        return ("not_run".to_string(), "not_run".to_string());
    };
    if tls_name.is_some() || port == 443 {
        let server_name = tls_name.or(host).unwrap_or_default();
        let observation =
            try_tls_handshake(&target, port, transport, server_name, true, TlsClientProfile::Auto, tls_verifier);
        (observation.status, observation.error.unwrap_or_else(|| "none".to_string()))
    } else {
        match connect_transport(&target, port, transport) {
            Ok(stream) => {
                let _ = stream.shutdown(std::net::Shutdown::Both);
                ("tcp_connect_ok".to_string(), "none".to_string())
            }
            Err(err) => ("tcp_connect_failed".to_string(), err),
        }
    }
}

fn run_quic_endpoint_probe(
    host: Option<&str>,
    connect_ip: Option<&str>,
    port: u16,
    transport: &TransportConfig,
) -> (String, String) {
    let Some(host_name) = host else {
        return ("not_run".to_string(), "not_run".to_string());
    };
    let connect_target = connect_target_from_parts(Some(host_name), connect_ip)
        .unwrap_or_else(|| TargetAddress::Host(host_name.to_string()));
    let payload = build_realistic_quic_initial(QUIC_V1_VERSION, Some(host_name)).unwrap_or_default();
    match relay_udp_payload(&connect_target, port, transport, &payload) {
        Ok(bytes) if parse_quic_initial(&bytes).is_some() => ("quic_initial_response".to_string(), "none".to_string()),
        Ok(bytes) if !bytes.is_empty() => ("quic_response".to_string(), "none".to_string()),
        Ok(_) => ("quic_empty".to_string(), "none".to_string()),
        Err(err) => ("quic_error".to_string(), err),
    }
}

fn parse_http_target(
    url: &str,
    connect_ip: Option<&str>,
    port_override: Option<u16>,
) -> Result<ParsedHttpTarget, String> {
    let secure = url.starts_with("https://");
    let without_scheme = url
        .strip_prefix("https://")
        .or_else(|| url.strip_prefix("http://"))
        .ok_or_else(|| "unsupported_url_scheme".to_string())?;
    let (authority, path) = match without_scheme.split_once('/') {
        Some((authority, suffix)) => (authority, format!("/{}", suffix)),
        None => (without_scheme, "/".to_string()),
    };
    let (host, parsed_port) = split_host_and_port(authority);
    if host.is_empty() {
        return Err("missing_url_host".to_string());
    }
    let port = port_override.or(parsed_port).unwrap_or(if secure { 443 } else { 80 });
    let connect_target =
        connect_target_from_parts(Some(host.as_str()), connect_ip).unwrap_or_else(|| TargetAddress::Host(host.clone()));
    Ok(ParsedHttpTarget { host, path, port, secure, connect_target })
}

fn split_host_and_port(authority: &str) -> (String, Option<u16>) {
    if authority.starts_with('[') {
        return (authority.to_string(), None);
    }
    match authority.rsplit_once(':') {
        Some((host, port)) => match port.parse::<u16>() {
            Ok(parsed_port) => (host.to_string(), Some(parsed_port)),
            Err(_) => (authority.to_string(), None),
        },
        None => (authority.to_string(), None),
    }
}

fn connect_target_from_parts(host: Option<&str>, connect_ip: Option<&str>) -> Option<TargetAddress> {
    connect_ip
        .and_then(|value| value.parse::<IpAddr>().ok())
        .map(TargetAddress::Ip)
        .or_else(|| host.filter(|value| !value.is_empty()).map(|value| TargetAddress::Host(value.to_string())))
}

fn is_probe_failure(status: &str) -> bool {
    !matches!(status, "not_run" | "http_ok" | "tls_ok" | "tcp_connect_ok" | "quic_initial_response" | "quic_response")
}

pub(crate) fn summarize_probe_event(probe: &ProbeResult) -> String {
    match probe.probe_type.as_str() {
        "dns_integrity" => format!(
            "{} -> {} (udp={}, doh={})",
            probe.target,
            probe.outcome,
            probe_detail_value(probe, "udpAddresses"),
            probe_detail_value(probe, "dohAddresses"),
        ),
        "domain_reachability" => format!(
            "{} -> {} (tls13={}, tls12={}, http={})",
            probe.target,
            probe.outcome,
            probe_detail_value(probe, "tls13Status"),
            probe_detail_value(probe, "tls12Status"),
            probe_detail_value(probe, "httpStatus"),
        ),
        "tcp_fat_header" => format!(
            "{} -> {} (sni={}, bytes={}, responses={})",
            probe.target,
            probe.outcome,
            probe_detail_value(probe, "selectedSni"),
            probe_detail_value(probe, "bytesSent"),
            probe_detail_value(probe, "responsesSeen"),
        ),
        "quic_reachability" => format!(
            "{} -> {} (status={}, latency={}ms)",
            probe.target,
            probe.outcome,
            probe_detail_value(probe, "status"),
            probe_detail_value(probe, "latencyMs"),
        ),
        "service_reachability" => format!(
            "{} -> {} (bootstrap={}, media={}, gateway={})",
            probe.target,
            probe.outcome,
            probe_detail_value(probe, "bootstrapStatus"),
            probe_detail_value(probe, "mediaStatus"),
            probe_detail_value(probe, "gatewayStatus"),
        ),
        "circumvention_reachability" => format!(
            "{} -> {} (bootstrap={}, handshake={})",
            probe.target,
            probe.outcome,
            probe_detail_value(probe, "bootstrapStatus"),
            probe_detail_value(probe, "handshakeStatus"),
        ),
        "throughput_window" => {
            format!("{} -> {} (median={}bps)", probe.target, probe.outcome, probe_detail_value(probe, "medianBps"),)
        }
        _ => format!("{} -> {}", probe.target, probe.outcome),
    }
}

pub(crate) fn probe_detail_value<'a>(probe: &'a ProbeResult, key: &str) -> &'a str {
    probe.details.iter().find(|detail| detail.key == key).map_or("unknown", |detail| detail.value.as_str())
}

/// Build a synthetic `network_environment` ProbeResult from the OS-provided NetworkSnapshot.
/// Returns `None` when no snapshot is present (backward compat: no snapshot = no probe).
pub(crate) fn build_network_environment_probe(
    snapshot: Option<&ripdpi_proxy_config::NetworkSnapshot>,
) -> Option<ProbeResult> {
    let snap = snapshot?;
    let outcome = if snap.transport == "none" { "network_unavailable" } else { "network_available" };
    let mut details = vec![
        ProbeDetail { key: "transport".to_string(), value: snap.transport.clone() },
        ProbeDetail { key: "validated".to_string(), value: snap.validated.to_string() },
        ProbeDetail { key: "captivePortal".to_string(), value: snap.captive_portal.to_string() },
        ProbeDetail { key: "metered".to_string(), value: snap.metered.to_string() },
        ProbeDetail { key: "privateDnsMode".to_string(), value: snap.private_dns_mode.clone() },
        ProbeDetail { key: "dnsServerCount".to_string(), value: snap.dns_servers.len().to_string() },
        ProbeDetail { key: "capturedAtMs".to_string(), value: snap.captured_at_ms.to_string() },
    ];
    if let Some(mtu) = snap.mtu {
        details.push(ProbeDetail { key: "mtu".to_string(), value: mtu.to_string() });
    }
    if let Some(ref cell) = snap.cellular {
        details.push(ProbeDetail { key: "cellularGeneration".to_string(), value: cell.generation.clone() });
        details.push(ProbeDetail { key: "cellularRoaming".to_string(), value: cell.roaming.to_string() });
        push_network_detail(&mut details, "cellularDataNetworkType", &cell.data_network_type);
        push_network_detail(&mut details, "cellularServiceState", &cell.service_state);
        if let Some(carrier_id) = cell.carrier_id {
            details.push(ProbeDetail { key: "cellularCarrierId".to_string(), value: carrier_id.to_string() });
        }
        if let Some(signal_level) = cell.signal_level {
            details.push(ProbeDetail { key: "cellularSignalLevel".to_string(), value: signal_level.to_string() });
        }
        if let Some(signal_dbm) = cell.signal_dbm {
            details.push(ProbeDetail { key: "cellularSignalDbm".to_string(), value: signal_dbm.to_string() });
        }
    }
    if let Some(ref wifi) = snap.wifi {
        details.push(ProbeDetail { key: "wifiFrequencyBand".to_string(), value: wifi.frequency_band.clone() });
        if let Some(frequency_mhz) = wifi.frequency_mhz {
            details.push(ProbeDetail { key: "wifiFrequencyMhz".to_string(), value: frequency_mhz.to_string() });
        }
        if let Some(rssi_dbm) = wifi.rssi_dbm {
            details.push(ProbeDetail { key: "wifiRssiDbm".to_string(), value: rssi_dbm.to_string() });
        }
        if let Some(link_speed_mbps) = wifi.link_speed_mbps {
            details.push(ProbeDetail { key: "wifiLinkSpeedMbps".to_string(), value: link_speed_mbps.to_string() });
        }
        if let Some(rx_link_speed_mbps) = wifi.rx_link_speed_mbps {
            details.push(ProbeDetail { key: "wifiRxLinkSpeedMbps".to_string(), value: rx_link_speed_mbps.to_string() });
        }
        if let Some(tx_link_speed_mbps) = wifi.tx_link_speed_mbps {
            details.push(ProbeDetail { key: "wifiTxLinkSpeedMbps".to_string(), value: tx_link_speed_mbps.to_string() });
        }
        push_network_detail(&mut details, "wifiChannelWidth", &wifi.channel_width);
        push_network_detail(&mut details, "wifiStandard", &wifi.wifi_standard);
    }
    Some(ProbeResult {
        probe_type: "network_environment".to_string(),
        target: snap.transport.clone(),
        outcome: outcome.to_string(),
        details,
    })
}

fn push_network_detail(details: &mut Vec<ProbeDetail>, key: &str, value: &str) {
    if !value.is_empty() && value != "unknown" {
        details.push(ProbeDetail { key: key.to_string(), value: value.to_string() });
    }
}

pub(crate) fn set_progress(shared: &Arc<Mutex<SharedState>>, progress: ScanProgress) {
    let mut guard = shared.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    guard.progress = Some(progress);
}

pub(crate) fn set_report(shared: &Arc<Mutex<SharedState>>, report: ScanReport) {
    let mut guard = shared.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    guard.report = Some(report);
}

pub(crate) fn push_event(shared: &Arc<Mutex<SharedState>>, source: &str, level: &str, message: String) {
    let mut guard = shared.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    if guard.passive_events.len() >= MAX_PASSIVE_EVENTS {
        guard.passive_events.pop_front();
    }
    guard.passive_events.push_back(NativeSessionEvent {
        source: source.to_string(),
        level: level.to_string(),
        message,
        created_at: now_ms(),
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_proxy_config::{CellularSnapshot, NetworkSnapshot, WifiSnapshot};

    #[test]
    fn network_environment_probe_includes_extended_wifi_and_cellular_fields() {
        let snapshot = NetworkSnapshot {
            transport: "wifi".to_string(),
            validated: true,
            captive_portal: false,
            metered: false,
            private_dns_mode: "system".to_string(),
            dns_servers: vec!["1.1.1.1".to_string()],
            cellular: Some(CellularSnapshot {
                generation: "5g".to_string(),
                roaming: true,
                operator_code: "25001".to_string(),
                data_network_type: "NR".to_string(),
                service_state: "in_service".to_string(),
                carrier_id: Some(42),
                signal_level: Some(4),
                signal_dbm: Some(-95),
            }),
            wifi: Some(WifiSnapshot {
                frequency_band: "5ghz".to_string(),
                ssid_hash: "cafebabe".to_string(),
                frequency_mhz: Some(5180),
                rssi_dbm: Some(-58),
                link_speed_mbps: Some(866),
                rx_link_speed_mbps: Some(780),
                tx_link_speed_mbps: Some(720),
                channel_width: "80 MHz".to_string(),
                wifi_standard: "802.11ax".to_string(),
            }),
            mtu: Some(1500),
            traffic_tx_bytes: 10,
            traffic_rx_bytes: 20,
            captured_at_ms: 1_700_000_000_000,
        };

        let probe = build_network_environment_probe(Some(&snapshot)).expect("probe");

        assert_eq!(probe_detail_value(&probe, "wifiFrequencyMhz"), "5180");
        assert_eq!(probe_detail_value(&probe, "wifiRssiDbm"), "-58");
        assert_eq!(probe_detail_value(&probe, "wifiLinkSpeedMbps"), "866");
        assert_eq!(probe_detail_value(&probe, "wifiRxLinkSpeedMbps"), "780");
        assert_eq!(probe_detail_value(&probe, "wifiTxLinkSpeedMbps"), "720");
        assert_eq!(probe_detail_value(&probe, "wifiChannelWidth"), "80 MHz");
        assert_eq!(probe_detail_value(&probe, "wifiStandard"), "802.11ax");
        assert_eq!(probe_detail_value(&probe, "cellularDataNetworkType"), "NR");
        assert_eq!(probe_detail_value(&probe, "cellularServiceState"), "in_service");
        assert_eq!(probe_detail_value(&probe, "cellularCarrierId"), "42");
        assert_eq!(probe_detail_value(&probe, "cellularSignalLevel"), "4");
        assert_eq!(probe_detail_value(&probe, "cellularSignalDbm"), "-95");
    }

    #[test]
    fn network_environment_probe_keeps_old_snapshots_valid() {
        let snapshot = NetworkSnapshot {
            transport: "wifi".to_string(),
            wifi: Some(WifiSnapshot { frequency_band: "5ghz".to_string(), ..WifiSnapshot::default() }),
            ..NetworkSnapshot::default()
        };

        let probe = build_network_environment_probe(Some(&snapshot)).expect("probe");

        assert_eq!(probe_detail_value(&probe, "wifiFrequencyBand"), "5ghz");
        assert_eq!(probe_detail_value(&probe, "wifiChannelWidth"), "unknown");
        assert_eq!(probe_detail_value(&probe, "cellularSignalDbm"), "unknown");
    }
}
