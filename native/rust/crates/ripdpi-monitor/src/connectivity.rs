use std::collections::BTreeSet;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use rustls::client::danger::ServerCertVerifier;

use crate::dns::*;
use crate::fat_header::*;
use crate::http::*;
use crate::telegram::*;
use crate::tls::*;
use crate::transport::*;
use crate::types::SharedState;
use crate::types::*;
use crate::util::*;

pub(crate) fn run_connectivity_scan(
    shared: Arc<Mutex<SharedState>>,
    cancel: Arc<AtomicBool>,
    session_id: String,
    request: ScanRequest,
    tls_verifier: Option<Arc<dyn ServerCertVerifier>>,
) {
    let started_at = now_ms();
    let telegram_steps = if request.telegram_target.is_some() { 1 } else { 0 };
    let total_steps =
        (request.dns_targets.len() + request.domain_targets.len() + request.tcp_targets.len() + telegram_steps)
            .max(1);
    let transport = transport_for_request(&request);
    let mut completed_steps = 0usize;
    let mut results = Vec::new();

    set_progress(
        &shared,
        ScanProgress {
            session_id: session_id.clone(),
            phase: "starting".to_string(),
            completed_steps,
            total_steps,
            message: format!("Preparing {}", request.display_name),
            is_finished: false,
            latest_probe_target: None,
            latest_probe_outcome: None,
        },
    );
    push_event(
        &shared,
        "engine",
        "info",
        format!(
            "Starting {} in {:?} transport={}",
            request.display_name,
            request.path_mode,
            describe_transport(&transport),
        ),
    );

    // --- Network snapshot handling ---
    // Build the network_environment ProbeResult from the OS-provided snapshot (if any), and
    // short-circuit if the OS reports no network is available.
    let network_env_probe = build_network_environment_probe(request.network_snapshot.as_ref());
    if let Some(ref snap) = request.network_snapshot {
        if snap.transport == "none" {
            push_event(&shared, "engine", "warn", "OS reports no network; aborting scan".to_string());
            let mut short_results = Vec::new();
            if let Some(probe) = network_env_probe {
                short_results.push(probe);
            }
            let report = ScanReport {
                session_id: session_id.clone(),
                profile_id: request.profile_id,
                path_mode: request.path_mode,
                started_at,
                finished_at: now_ms(),
                summary: "network_unavailable".to_string(),
                results: short_results,
                strategy_probe_report: None,
            };
            set_report(&shared, report);
            set_progress(
                &shared,
                ScanProgress {
                    session_id,
                    phase: "finished".to_string(),
                    completed_steps: total_steps,
                    total_steps,
                    message: "No network available".to_string(),
                    is_finished: true,
                    latest_probe_target: None,
                    latest_probe_outcome: None,
                },
            );
            return;
        }
        if !snap.validated && !snap.captive_portal {
            push_event(
                &shared,
                "engine",
                "warn",
                "OS reports unvalidated network; probe results may be unreliable".to_string(),
            );
        }
    }
    // Prepend the environment probe so it appears first in results.
    if let Some(probe) = network_env_probe {
        results.push(probe);
    }

    for dns_target in &request.dns_targets {
        if cancel.load(Ordering::Acquire) {
            persist_cancelled_report(shared, session_id, request, started_at, results);
            return;
        }
        let probe = run_dns_probe(dns_target, &transport, &request.path_mode);
        push_event(&shared, "dns_integrity", event_level_for_outcome(&probe.outcome), summarize_probe_event(&probe));
        let probe_target = probe.target.clone();
        let probe_outcome = probe.outcome.clone();
        results.push(probe);
        completed_steps += 1;
        set_progress(
            &shared,
            ScanProgress {
                session_id: session_id.clone(),
                phase: "dns".to_string(),
                completed_steps,
                total_steps,
                message: format!("DNS probe {}", dns_target.domain),
                is_finished: false,
                latest_probe_target: Some(probe_target),
                latest_probe_outcome: Some(probe_outcome),
            },
        );
    }

    for domain_target in &request.domain_targets {
        if cancel.load(Ordering::Acquire) {
            persist_cancelled_report(shared, session_id, request, started_at, results);
            return;
        }
        let probe = run_domain_probe(domain_target, &transport, tls_verifier.as_ref());
        push_event(
            &shared,
            "domain_reachability",
            event_level_for_outcome(&probe.outcome),
            summarize_probe_event(&probe),
        );
        let probe_target = probe.target.clone();
        let probe_outcome = probe.outcome.clone();
        results.push(probe);
        completed_steps += 1;
        set_progress(
            &shared,
            ScanProgress {
                session_id: session_id.clone(),
                phase: "reachability".to_string(),
                completed_steps,
                total_steps,
                message: format!("Reachability {}", domain_target.host),
                is_finished: false,
                latest_probe_target: Some(probe_target),
                latest_probe_outcome: Some(probe_outcome),
            },
        );
    }

    for tcp_target in &request.tcp_targets {
        if cancel.load(Ordering::Acquire) {
            persist_cancelled_report(shared, session_id, request, started_at, results);
            return;
        }
        let probe = run_tcp_probe(tcp_target, &request.whitelist_sni, &transport);
        push_event(&shared, "tcp_fat_header", event_level_for_outcome(&probe.outcome), summarize_probe_event(&probe));
        let probe_target = probe.target.clone();
        let probe_outcome = probe.outcome.clone();
        results.push(probe);
        completed_steps += 1;
        set_progress(
            &shared,
            ScanProgress {
                session_id: session_id.clone(),
                phase: "tcp".to_string(),
                completed_steps,
                total_steps,
                message: format!("TCP {}", tcp_target.provider),
                is_finished: false,
                latest_probe_target: Some(probe_target),
                latest_probe_outcome: Some(probe_outcome),
            },
        );
    }

    if let Some(ref telegram_target) = request.telegram_target {
        if cancel.load(Ordering::Acquire) {
            persist_cancelled_report(shared, session_id, request, started_at, results);
            return;
        }
        set_progress(
            &shared,
            ScanProgress {
                session_id: session_id.clone(),
                phase: "telegram_download".to_string(),
                completed_steps,
                total_steps,
                message: "Telegram download probe".to_string(),
                is_finished: false,
                latest_probe_target: None,
                latest_probe_outcome: None,
            },
        );
        let probe = run_telegram_probe(telegram_target, &transport);
        push_event(
            &shared,
            "telegram",
            event_level_for_outcome(&probe.outcome),
            summarize_probe_event(&probe),
        );
        let probe_target = probe.target.clone();
        let probe_outcome = probe.outcome.clone();
        results.push(probe);
        completed_steps += 1;
        set_progress(
            &shared,
            ScanProgress {
                session_id: session_id.clone(),
                phase: "telegram".to_string(),
                completed_steps,
                total_steps,
                message: "Telegram availability checked".to_string(),
                is_finished: false,
                latest_probe_target: Some(probe_target),
                latest_probe_outcome: Some(probe_outcome),
            },
        );
    }

    let success_count = results.iter().filter(|result| probe_is_success(&result.outcome)).count();
    let summary = format!("{success_count}/{} probes succeeded", results.len());
    let report = ScanReport {
        session_id: session_id.clone(),
        profile_id: request.profile_id,
        path_mode: request.path_mode,
        started_at,
        finished_at: now_ms(),
        summary,
        results,
        strategy_probe_report: None,
    };

    set_report(&shared, report);
    push_event(&shared, "engine", "info", "Diagnostics finished".to_string());
    set_progress(
        &shared,
        ScanProgress {
            session_id,
            phase: "finished".to_string(),
            completed_steps: total_steps,
            total_steps,
            message: "Diagnostics finished".to_string(),
            is_finished: true,
            latest_probe_target: None,
            latest_probe_outcome: None,
        },
    );
}

pub(crate) fn persist_cancelled_report(
    shared: Arc<Mutex<SharedState>>,
    session_id: String,
    request: ScanRequest,
    started_at: u64,
    results: Vec<ProbeResult>,
) {
    let report = ScanReport {
        session_id: session_id.clone(),
        profile_id: request.profile_id,
        path_mode: request.path_mode,
        started_at,
        finished_at: now_ms(),
        summary: "Scan cancelled".to_string(),
        results,
        strategy_probe_report: None,
    };
    set_report(&shared, report);
    push_event(&shared, "engine", "warn", "Diagnostics cancelled".to_string());
    set_progress(
        &shared,
        ScanProgress {
            session_id,
            phase: "cancelled".to_string(),
            completed_steps: 0,
            total_steps: 0,
            message: "Diagnostics cancelled".to_string(),
            is_finished: true,
            latest_probe_target: None,
            latest_probe_outcome: None,
        },
    );
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
            ProbeDetail {
                key: "encryptedHost".to_string(),
                value: encrypted_endpoint.host.clone(),
            },
            ProbeDetail {
                key: "encryptedPort".to_string(),
                value: encrypted_endpoint.port.to_string(),
            },
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
    let tls13 =
        try_tls_handshake(&connect_target, https_port, transport, &target.host, true, TlsClientProfile::Tls13Only, tls_verifier);
    let tls12 =
        try_tls_handshake(&connect_target, https_port, transport, &target.host, true, TlsClientProfile::Tls12Only, tls_verifier);
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
    if let Some(ref cell) = snap.cellular {
        details.push(ProbeDetail { key: "cellularGeneration".to_string(), value: cell.generation.clone() });
        details.push(ProbeDetail { key: "cellularRoaming".to_string(), value: cell.roaming.to_string() });
    }
    if let Some(ref wifi) = snap.wifi {
        details.push(ProbeDetail { key: "wifiFrequencyBand".to_string(), value: wifi.frequency_band.clone() });
    }
    Some(ProbeResult {
        probe_type: "network_environment".to_string(),
        target: snap.transport.clone(),
        outcome: outcome.to_string(),
        details,
    })
}

pub(crate) fn set_progress(shared: &Arc<Mutex<SharedState>>, progress: ScanProgress) {
    if let Ok(mut guard) = shared.lock() {
        guard.progress = Some(progress);
    }
}

pub(crate) fn set_report(shared: &Arc<Mutex<SharedState>>, report: ScanReport) {
    if let Ok(mut guard) = shared.lock() {
        guard.report = Some(report);
    }
}

pub(crate) fn push_event(shared: &Arc<Mutex<SharedState>>, source: &str, level: &str, message: String) {
    if let Ok(mut guard) = shared.lock() {
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
}
