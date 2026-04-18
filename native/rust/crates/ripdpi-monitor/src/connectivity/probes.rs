use std::collections::BTreeSet;
use std::sync::Arc;

use ripdpi_dns_resolver::EncryptedDnsEndpoint;
use ripdpi_packets::{build_realistic_quic_initial, parse_quic_initial, QUIC_V1_VERSION};
use rustls::client::danger::ServerCertVerifier;

use crate::dns::*;
use crate::dns_analysis::{analyze_dns_response, compare_dns_responses, parse_record_set};
use crate::dns_oracle::{evaluate_dns_oracles, DnsOracleAssessment, DnsOracleResponse, DnsOracleTrust};
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
    // When UDP is suspiciously fast relative to encrypted, flag as injected.
    if udp > 0 {
        let ratio = (encrypted as f64) / (udp as f64);
        if ratio >= 20.0 {
            return "injected".to_string();
        }
    }
    match encrypted {
        0..=99 => "fast".to_string(),
        100..=500 => "normal".to_string(),
        _ => "slow".to_string(),
    }
}

/// Returns `true` when the UDP DNS response arrived suspiciously fast (<=5ms)
/// while the encrypted resolver returned different answers -- a strong signal
/// of in-path DNS injection (e.g., TSPU DPI equipment racing forged responses).
pub(crate) fn is_dns_injection_suspected(udp_latency_ms: &str, outcome: &str) -> bool {
    let udp: u64 = udp_latency_ms.parse().unwrap_or(u64::MAX);
    udp <= 5 && is_suspected_dns_tampering_outcome(outcome)
}

pub(crate) fn run_dns_probe(target: &DnsTarget, transport: &TransportConfig, path_mode: &ScanPathMode) -> ProbeResult {
    let udp_server = target.udp_server.clone().unwrap_or_else(|| DEFAULT_DNS_SERVER.to_string());
    let (encrypted_endpoint, encrypted_bootstrap_ips) = match encrypted_dns_endpoint_for_target(target) {
        Ok(value) => value,
        Err(err) => return dns_probe_unavailable_result(target, err),
    };
    let udp_started = std::time::Instant::now();
    let (udp_result, raw_udp_response) = resolve_via_udp_with_raw(&target.domain, &udp_server, transport);
    let udp_latency_ms = udp_started.elapsed().as_millis().to_string();
    let target_uses_default_resolver =
        target.encrypted_host.is_none() && target.encrypted_doh_url.is_none() && target.encrypted_protocol.is_none();
    let fallback_endpoints = if target_uses_default_resolver {
        build_fallback_encrypted_dns_endpoints(encrypted_endpoint.resolver_id.as_deref())
    } else {
        Vec::new()
    };
    let oracle_assessment = evaluate_dns_oracles(
        encrypted_endpoint.clone(),
        &fallback_endpoints,
        2,
        |endpoint| {
            let (result, raw_response) =
                resolve_via_encrypted_dns_with_raw(&target.domain, endpoint.clone(), transport);
            result.map(|addresses| DnsOracleResponse { addresses, raw_response })
        },
        |answer| answer.addresses.clone(),
    );
    let encrypted_result = oracle_result_for_probe(&oracle_assessment);
    let raw_encrypted_response =
        oracle_assessment.selected.as_ref().and_then(|selected| selected.value.raw_response.clone());
    let encrypted_latency_ms = oracle_assessment.preferred_latency_ms().to_string();

    let expected: BTreeSet<String> = target.expected_ips.iter().cloned().collect();
    let outcome = classify_dns_probe_outcome(&udp_result, &encrypted_result, path_mode, &udp_latency_ms, &expected);
    let injection_suspected = is_dns_injection_suspected(&udp_latency_ms, &outcome);
    let selected_endpoint =
        oracle_assessment.selected.as_ref().map(|selected| &selected.endpoint).unwrap_or(&encrypted_endpoint);
    let selected_bootstrap_ips = selected_endpoint.bootstrap_ips.iter().map(ToString::to_string).collect::<Vec<_>>();
    let encrypted_addresses = match &encrypted_result {
        Ok(addresses) if !addresses.is_empty() => addresses.join("|"),
        Ok(_) => "[]".to_string(),
        Err(err) => err.clone(),
    };

    let mut result = ProbeResult {
        probe_type: "dns_integrity".to_string(),
        target: target.domain.clone(),
        outcome,
        details: build_dns_probe_details(
            &udp_server,
            &udp_result,
            &udp_latency_ms,
            &encrypted_endpoint,
            &encrypted_bootstrap_ips,
            &selected_bootstrap_ips,
            &encrypted_addresses,
            &encrypted_latency_ms,
            injection_suspected,
            &expected,
            &oracle_assessment,
        ),
    };
    result.details.extend(oracle_assessment.detail_entries());

    if is_suspected_dns_tampering_outcome(result.outcome.as_str()) {
        append_injection_profile_details(
            &mut result,
            &udp_result,
            &encrypted_result,
            &udp_latency_ms,
            &encrypted_latency_ms,
        );
    }

    if let Some(raw) = raw_udp_response.as_deref() {
        append_udp_response_analysis(&mut result, raw);
    }

    if let (Some(udp_raw), Some(enc_raw)) = (raw_udp_response.as_deref(), raw_encrypted_response.as_deref()) {
        append_record_comparison_details(&mut result, udp_raw, enc_raw);
    }

    result
}

fn oracle_result_for_probe(assessment: &DnsOracleAssessment<DnsOracleResponse>) -> Result<Vec<String>, String> {
    match assessment.trust {
        DnsOracleTrust::TrustedAgreement | DnsOracleTrust::PrimaryOnly => assessment
            .selected
            .as_ref()
            .map(|selected| selected.value.addresses.clone())
            .ok_or_else(|| "dns_oracle_unavailable".to_string()),
        DnsOracleTrust::SingleFallback => Err("dns_oracle_unavailable".to_string()),
        DnsOracleTrust::Disagreement => Err("dns_oracle_disagreement".to_string()),
        DnsOracleTrust::Unavailable => Err("dns_oracle_unavailable".to_string()),
    }
}

fn dns_probe_unavailable_result(target: &DnsTarget, err: String) -> ProbeResult {
    ProbeResult {
        probe_type: "dns_integrity".to_string(),
        target: target.domain.clone(),
        outcome: "dns_unavailable".to_string(),
        details: vec![ProbeDetail { key: "encryptedDnsError".to_string(), value: err }],
    }
}

#[inline(never)]
fn push_detail(details: &mut Vec<ProbeDetail>, key: &str, value: String) {
    details.push(ProbeDetail { key: key.to_string(), value });
}

#[inline(never)]
fn push_joined_string_detail(details: &mut Vec<ProbeDetail>, key: &str, values: &[String]) {
    push_detail(details, key, values.join("|"));
}

#[inline(never)]
fn push_joined_str_detail(details: &mut Vec<ProbeDetail>, key: &str, values: &[&str]) {
    push_detail(details, key, values.join("|"));
}

fn build_dns_probe_details(
    udp_server: &str,
    udp_result: &Result<Vec<String>, String>,
    udp_latency_ms: &str,
    encrypted_endpoint: &EncryptedDnsEndpoint,
    encrypted_bootstrap_ips: &[String],
    selected_bootstrap_ips: &[String],
    encrypted_addresses: &str,
    encrypted_latency_ms: &str,
    injection_suspected: bool,
    expected: &BTreeSet<String>,
    oracle_assessment: &DnsOracleAssessment<DnsOracleResponse>,
) -> Vec<ProbeDetail> {
    vec![
        ProbeDetail { key: "udpServer".to_string(), value: udp_server.to_string() },
        ProbeDetail { key: "udpAddresses".to_string(), value: format_result_set(udp_result) },
        ProbeDetail { key: "udpLatencyMs".to_string(), value: udp_latency_ms.to_string() },
        ProbeDetail {
            key: "encryptedResolverId".to_string(),
            value: encrypted_endpoint.resolver_id.clone().unwrap_or_default(),
        },
        ProbeDetail { key: "encryptedProtocol".to_string(), value: encrypted_endpoint.protocol.as_str().to_string() },
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
            value: (oracle_assessment.selected.is_some() && !selected_bootstrap_ips.is_empty()).to_string(),
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
        ProbeDetail { key: "encryptedAddresses".to_string(), value: encrypted_addresses.to_string() },
        ProbeDetail { key: "encryptedLatencyMs".to_string(), value: encrypted_latency_ms.to_string() },
        ProbeDetail {
            key: "dnsLatencyQuality".to_string(),
            value: classify_dns_latency_quality(udp_latency_ms, encrypted_latency_ms),
        },
        ProbeDetail { key: "dnsInjectionSuspected".to_string(), value: injection_suspected.to_string() },
        ProbeDetail {
            key: "expected".to_string(),
            value: if expected.is_empty() {
                "[]".to_string()
            } else {
                expected.iter().cloned().collect::<Vec<_>>().join("|")
            },
        },
        ProbeDetail {
            key: "resolverFallbackUsed".to_string(),
            value: oracle_assessment.fallback_resolver_used().unwrap_or_default(),
        },
    ]
}

#[inline(never)]
fn append_injection_profile_details(
    result: &mut ProbeResult,
    udp_result: &Result<Vec<String>, String>,
    encrypted_result: &Result<Vec<String>, String>,
    udp_latency_ms: &str,
    encrypted_latency_ms: &str,
) {
    let udp_ms: u64 = udp_latency_ms.parse().unwrap_or(0);
    let enc_ms: u64 = encrypted_latency_ms.parse().unwrap_or(0);
    let ratio_x100: u64 = if udp_ms > 0 { (enc_ms * 100) / udp_ms } else { 0 };
    result.details.push(ProbeDetail { key: "injectionLatencyRatio".to_string(), value: ratio_x100.to_string() });

    let empty = vec![];
    let udp_set = ip_set(udp_result.as_ref().unwrap_or(&empty));
    let enc_set = ip_set(encrypted_result.as_ref().unwrap_or(&empty));
    let forged: Vec<String> = udp_set.difference(&enc_set).cloned().collect();
    if !forged.is_empty() {
        result.details.push(ProbeDetail { key: "forgedAddresses".to_string(), value: forged.join(",") });
    }
}

#[inline(never)]
fn append_udp_response_analysis(result: &mut ProbeResult, raw: &[u8]) {
    let analysis = analyze_dns_response(raw);
    push_detail(&mut result.details, "udpResponseSize", analysis.response_size.to_string());
    push_detail(&mut result.details, "udpAaFlag", analysis.aa_flag.to_string());
    push_detail(&mut result.details, "udpRcode", analysis.rcode.to_string());
    push_detail(&mut result.details, "udpAnswerCount", analysis.answer_count.to_string());
    push_detail(&mut result.details, "udpAuthorityCount", analysis.authority_count.to_string());
    push_detail(&mut result.details, "udpAdditionalCount", analysis.additional_count.to_string());
    push_detail(&mut result.details, "udpMinTtl", analysis.min_ttl.map_or_else(String::new, |value| value.to_string()));
    push_detail(&mut result.details, "udpMaxTtl", analysis.max_ttl.map_or_else(String::new, |value| value.to_string()));
    push_detail(&mut result.details, "udpHasEdns0", analysis.has_edns0.to_string());
    push_joined_string_detail(&mut result.details, "udpCnameTargets", &analysis.cname_targets);
    push_detail(&mut result.details, "udpTamperingScore", analysis.tampering_score.to_string());
    push_joined_str_detail(&mut result.details, "udpAnomalySignals", &analysis.signals);
    push_detail(&mut result.details, "malformedPointers", analysis.malformed_pointers.to_string());
}

#[inline(never)]
fn append_record_comparison_details(result: &mut ProbeResult, udp_raw: &[u8], enc_raw: &[u8]) {
    let udp_records = parse_record_set(udp_raw);
    let enc_records = parse_record_set(enc_raw);
    let comparison = compare_dns_responses(&udp_records, &enc_records);

    let udp_types: Vec<&str> = udp_records.answers.iter().map(|r| r.rtype_name).collect();
    let enc_types: Vec<&str> = enc_records.answers.iter().map(|r| r.rtype_name).collect();

    push_detail(&mut result.details, "udpRecordTypes", udp_types.join("|"));
    push_detail(&mut result.details, "encryptedRecordTypes", enc_types.join("|"));
    push_detail(&mut result.details, "recordTypeMismatch", comparison.record_type_mismatch.to_string());
    push_detail(&mut result.details, "answerCountDivergence", comparison.answer_count_divergence.to_string());
    push_detail(
        &mut result.details,
        "ttlDivergence",
        comparison.ttl_divergence.map_or_else(String::new, |value| value.to_string()),
    );
    push_detail(&mut result.details, "authorityMismatch", comparison.authority_mismatch.to_string());
    push_joined_string_detail(&mut result.details, "extraCnames", &comparison.extra_cnames);
    push_detail(&mut result.details, "comparisonScore", comparison.comparison_score.to_string());
    push_joined_str_detail(&mut result.details, "comparisonSignals", &comparison.comparison_signals);
}

fn classify_dns_probe_outcome(
    udp_result: &Result<Vec<String>, String>,
    encrypted_result: &Result<Vec<String>, String>,
    path_mode: &ScanPathMode,
    udp_latency_ms: &str,
    expected: &BTreeSet<String>,
) -> String {
    match (udp_result, encrypted_result) {
        (Ok(udp_ips), Ok(encrypted_ips)) => match classify_dns_answer_overlap(udp_ips, encrypted_ips) {
            DnsAnswerOverlap::Match => {
                if !expected.is_empty() && ip_set(udp_ips) != expected.clone() {
                    "dns_expected_mismatch".to_string()
                } else {
                    "dns_match".to_string()
                }
            }
            DnsAnswerOverlap::CompatibleDivergence => {
                if udp_latency_ms.parse::<u64>().unwrap_or(u64::MAX) <= 5 {
                    "dns_suspicious_divergence".to_string()
                } else {
                    "dns_compatible_divergence".to_string()
                }
            }
            DnsAnswerOverlap::SinkholeSubstitution => "dns_sinkhole_substitution".to_string(),
        },
        (Ok(_), Err(_)) => "dns_oracle_unavailable".to_string(),
        (Err(err), Ok(_)) if err == "dns_nxdomain" => "dns_nxdomain_mismatch".to_string(),
        (Err(_), Ok(_)) => {
            if matches!(path_mode, ScanPathMode::InPath) {
                "udp_skipped_or_blocked".to_string()
            } else {
                "udp_blocked".to_string()
            }
        }
        (Err(_), Err(_)) => "dns_unavailable".to_string(),
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

    // Single retry on connect failure to distinguish transient from consistent unreachability.
    let mut probe_retry_count: usize = 0;
    let effective_initial = if initial.status == FatHeaderStatus::ConnectFailed {
        probe_retry_count = 1;
        let retry = run_fat_header_attempt(target, transport, &initial_candidate, &base_host_header);
        attempted_candidates.push(format!(
            "{}:retry:{}",
            if initial_candidate.is_empty() { "<empty>" } else { initial_candidate.as_str() },
            fat_status_label(&retry.status)
        ));
        retry
    } else {
        initial.clone()
    };

    let mut outcome = classify_fat_header_outcome(&effective_initial.status).to_string();
    let mut winning_sni = None;
    let mut final_observation = effective_initial.clone();

    let tried_whitelist_candidates =
        effective_initial.status != FatHeaderStatus::Success && target.sni.is_some() && !whitelist_sni.is_empty();
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

    let tcp_block_method = classify_tcp_block_method(&final_observation.status);
    let rst_origin = classify_rst_origin(final_observation.syn_ack_latency_ms, final_observation.rst_timing_ms);
    // For window-cap outcomes, use bytes_sent at cutoff as the observed window size
    // since actual TCP window size is not available from userspace sockets.
    let observed_window_size = match final_observation.status {
        FatHeaderStatus::ThresholdCutoff | FatHeaderStatus::FreezeAfterThreshold => {
            Some(final_observation.bytes_sent as u32)
        }
        _ => final_observation.observed_window_size,
    };

    let mut details = vec![
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
        ProbeDetail { key: "probeRetryCount".to_string(), value: probe_retry_count.to_string() },
        ProbeDetail { key: "tcpBlockMethod".to_string(), value: tcp_block_method.to_string() },
        ProbeDetail {
            key: "synAckLatencyMs".to_string(),
            value: final_observation.syn_ack_latency_ms.map_or_else(String::new, |v| v.to_string()),
        },
        ProbeDetail {
            key: "rstTimingMs".to_string(),
            value: final_observation.rst_timing_ms.map_or_else(String::new, |v| v.to_string()),
        },
        ProbeDetail { key: "rstOrigin".to_string(), value: rst_origin.to_string() },
        ProbeDetail {
            key: "observedWindowSize".to_string(),
            value: observed_window_size.map_or_else(String::new, |v| v.to_string()),
        },
    ];
    details.push(ProbeDetail { key: "port".to_string(), value: target.port.to_string() });
    if final_observation.status == FatHeaderStatus::FreezeAfterThreshold {
        details.push(ProbeDetail {
            key: "freezeThresholdBytes".to_string(),
            value: final_observation.bytes_sent.to_string(),
        });
    }
    // When the main port fails and an alternative port is configured, probe the
    // alt port to detect port-specific policing (e.g. TSPU targeting port 443).
    if let Some(alt_port) = target.alt_port {
        if matches!(
            final_observation.status,
            FatHeaderStatus::ThresholdCutoff
                | FatHeaderStatus::FreezeAfterThreshold
                | FatHeaderStatus::Reset
                | FatHeaderStatus::Timeout
        ) {
            let alt_target = TcpTarget { port: alt_port, alt_port: None, ..target.clone() };
            let alt_host = target.host_header.as_deref().or(target.sni.as_deref()).unwrap_or("localhost");
            let alt_sni = target.sni.as_deref().unwrap_or("");
            let alt_obs = run_fat_header_attempt(&alt_target, transport, alt_sni, alt_host);
            details.push(ProbeDetail { key: "altPort".to_string(), value: alt_port.to_string() });
            details.push(ProbeDetail {
                key: "altPortStatus".to_string(),
                value: fat_status_label(&alt_obs.status).to_string(),
            });
            details.push(ProbeDetail { key: "altPortBytesSent".to_string(), value: alt_obs.bytes_sent.to_string() });
            details.push(ProbeDetail {
                key: "altPortResponsesSeen".to_string(),
                value: alt_obs.responses_seen.to_string(),
            });
        }
    }

    ProbeResult {
        probe_type: "tcp_fat_header".to_string(),
        target: format!("{}:{} ({})", target.ip, target.port, target.provider),
        outcome,
        details,
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
    let bootstrap = target.bootstrap_url.as_ref().map(|url| probe_http_url(url, None, &[], None, transport));
    let media = target.media_url.as_ref().map(|url| probe_http_url(url, None, &[], None, transport));
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
    let bootstrap = target.bootstrap_url.as_ref().map(|url| probe_http_url(url, None, &[], None, transport));
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
        let retry = target.bootstrap_url.as_ref().map(|url| probe_http_url(url, None, &[], None, transport));
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
    use std::collections::{BTreeMap, BTreeSet};

    use ripdpi_dns_resolver::{EncryptedDnsEndpoint, EncryptedDnsProtocol};

    use crate::dns_oracle::evaluate_dns_oracles;
    use crate::types::ScanPathMode;

    use super::{classify_dns_latency_quality, classify_dns_probe_outcome, oracle_result_for_probe};
    use crate::dns_oracle::DnsOracleResponse;

    fn endpoint(id: &str) -> EncryptedDnsEndpoint {
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Doh,
            resolver_id: Some(id.to_string()),
            host: format!("{id}.example"),
            port: 443,
            tls_server_name: None,
            bootstrap_ips: Vec::new(),
            doh_url: Some(format!("https://{id}.example/dns-query")),
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        }
    }

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
        // UDP 50ms, encrypted 600ms => ratio 12 (below injected threshold)
        assert_eq!(classify_dns_latency_quality("50", "600"), "slow");
    }

    #[test]
    fn dns_latency_quality_unknown_for_zero() {
        assert_eq!(classify_dns_latency_quality("0", "0"), "unknown");
    }

    #[test]
    fn dns_latency_quality_injected_for_high_ratio() {
        // UDP 3ms, encrypted 200ms => ratio ~66.7 => "injected"
        assert_eq!(classify_dns_latency_quality("3", "200"), "injected");
        // UDP 5ms, encrypted 100ms => ratio 20.0 => "injected"
        assert_eq!(classify_dns_latency_quality("5", "100"), "injected");
    }

    #[test]
    fn dns_latency_quality_not_injected_below_threshold() {
        // UDP 10ms, encrypted 99ms => ratio 9.9 => "fast" (below 20x)
        assert_eq!(classify_dns_latency_quality("10", "99"), "fast");
    }

    #[test]
    fn dns_probe_gates_single_fallback_success_as_oracle_unavailable() {
        let answers = BTreeMap::from([
            ("primary".to_string(), Err("connection reset".to_string())),
            (
                "fallback".to_string(),
                Ok(DnsOracleResponse { addresses: vec!["198.51.100.77".to_string()], raw_response: None }),
            ),
        ]);
        let assessment = evaluate_dns_oracles(
            endpoint("primary"),
            &[endpoint("fallback")],
            1,
            |endpoint| {
                answers
                    .get(endpoint.resolver_id.as_deref().unwrap_or_default())
                    .cloned()
                    .unwrap_or_else(|| Err("missing".to_string()))
            },
            |answer| answer.addresses.clone(),
        );
        let encrypted_result = oracle_result_for_probe(&assessment);
        let outcome = classify_dns_probe_outcome(
            &Ok(vec!["203.0.113.10".to_string()]),
            &encrypted_result,
            &ScanPathMode::RawPath,
            "25",
            &BTreeSet::new(),
        );

        assert_eq!(outcome, "dns_oracle_unavailable");
    }
}
