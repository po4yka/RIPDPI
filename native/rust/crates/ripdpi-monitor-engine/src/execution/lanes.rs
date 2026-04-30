use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, LazyLock};
use std::thread;
use std::time::Duration;

use rustls::client::danger::ServerCertVerifier;

use ripdpi_packets::{build_realistic_quic_initial, parse_quic_initial, QUIC_V1_VERSION};
use ripdpi_proxy_config::ProxyRuntimeContext;

use crate::blockpage_fingerprints::{load_fingerprints, BlockpageFingerprint};
use crate::candidates::{target_probe_pause_ms, StrategyCandidateSpec};
use crate::http::{classify_http_response_with_fingerprints, is_blockpage, try_http_request_targets};
use crate::tls::{
    planned_tls_template_metadata, planned_tls_template_profile, try_tls_handshake, try_tls_handshake_targets,
    TlsClientProfile, TlsObservation,
};
use crate::transport::{
    domain_connect_target, domain_connect_targets, quic_connect_targets, relay_udp_payload_observed, TransportConfig,
};
use crate::types::{DomainTarget, ProbeDetail, ProbeResult, QuicTarget};
use crate::util::{now_ms, stable_probe_hash};

use super::runtime::{probe_runtime_transport, run_candidate_warmup};
use super::scoring::{
    build_candidate_execution, cancelled_candidate_execution, failed_candidate_execution,
    not_applicable_candidate_execution, CandidateExecution, CandidateScore, ProbeSample,
};

static BLOCKPAGE_FINGERPRINTS: LazyLock<Vec<BlockpageFingerprint>> = LazyLock::new(load_fingerprints);

pub fn execute_tcp_candidate(
    spec: &StrategyCandidateSpec,
    targets: &[DomainTarget],
    runtime_context: Option<&ProxyRuntimeContext>,
    probe_seed: u64,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    cancel: &AtomicBool,
) -> CandidateExecution {
    if targets.is_empty() {
        return not_applicable_candidate_execution(spec, 0, 3, "No HTTP or HTTPS targets configured");
    }
    let probe_started = std::time::Instant::now();
    match probe_runtime_transport(spec, runtime_context) {
        Ok(runtime) => {
            let transport = runtime.transport();
            run_candidate_warmup(spec, &transport, targets, tls_verifier);
            if cancel.load(Ordering::Acquire) {
                drop(runtime);
                return cancelled_candidate_execution(spec, CandidateScore::default(), 3);
            }
            let mut score = CandidateScore::default();
            let mut ordered_targets = targets.to_vec();
            ordered_targets
                .sort_by_key(|target| stable_probe_hash(stable_probe_hash(probe_seed, spec.id), &target.host));

            // Test domains in parallel batches to reduce per-candidate probe time.
            // Batch size of 3 keeps concurrency safe (different destinations, no DPI
            // state collision) while cutting wall-clock time from ~15-20s to ~6-8s.
            const PARALLEL_DOMAIN_BATCH_SIZE: usize = 3;
            let chunks: Vec<&[DomainTarget]> = ordered_targets.chunks(PARALLEL_DOMAIN_BATCH_SIZE).collect();
            for (chunk_index, chunk) in chunks.iter().enumerate() {
                if cancel.load(Ordering::Acquire) {
                    drop(runtime);
                    return cancelled_candidate_execution(spec, score, 3);
                }
                if chunk_index > 0 {
                    // Inter-chunk pause: use the first target in the chunk as the key.
                    thread::sleep(Duration::from_millis(target_probe_pause_ms(probe_seed, spec, &chunk[0].host)));
                }
                // Run HTTP + HTTPS for each domain in this chunk concurrently.
                let chunk_results: Vec<Vec<ProbeSample>> = thread::scope(|s| {
                    chunk
                        .iter()
                        .map(|target| {
                            let transport = transport.clone();
                            s.spawn(move || {
                                let samples = vec![
                                    run_http_strategy_probe(&transport, target, spec),
                                    run_https_strategy_probe(&transport, target, spec, tls_verifier),
                                ];
                                samples
                            })
                        })
                        .collect::<Vec<_>>()
                        .into_iter()
                        .map(|handle| handle.join().unwrap_or_default())
                        .collect()
                });
                for samples in chunk_results {
                    for sample in samples {
                        score.add(sample);
                    }
                }
                if cancel.load(Ordering::Acquire) {
                    drop(runtime);
                    return cancelled_candidate_execution(spec, score, 3);
                }
            }
            drop(runtime);
            let candidate_id = spec.id.to_string();
            metrics::histogram!(
                "ripdpi_strategy_probe_duration_seconds",
                "candidate_id" => candidate_id,
                "family" => "tcp",
            )
            .record(probe_started.elapsed().as_secs_f64());
            build_candidate_execution(spec, score, 3)
        }
        Err(err) => failed_candidate_execution(spec, targets.len() * 2, 3, err),
    }
}

pub fn execute_quic_candidate(
    spec: &StrategyCandidateSpec,
    targets: &[QuicTarget],
    runtime_context: Option<&ProxyRuntimeContext>,
    probe_seed: u64,
    cancel: &AtomicBool,
) -> CandidateExecution {
    if targets.is_empty() {
        return not_applicable_candidate_execution(spec, 0, 2, "No QUIC targets configured");
    }
    let probe_started = std::time::Instant::now();
    match probe_runtime_transport(spec, runtime_context) {
        Ok(runtime) => {
            let transport = runtime.transport();
            let mut score = CandidateScore::default();
            let mut ordered_targets = targets.to_vec();
            ordered_targets
                .sort_by_key(|target| stable_probe_hash(stable_probe_hash(probe_seed, spec.id), &target.host));
            for (index, target) in ordered_targets.iter().enumerate() {
                if cancel.load(Ordering::Acquire) {
                    drop(runtime);
                    return cancelled_candidate_execution(spec, score, 2);
                }
                if index > 0 {
                    thread::sleep(Duration::from_millis(target_probe_pause_ms(probe_seed, spec, &target.host)));
                }
                score.add(run_quic_strategy_probe(&transport, target, spec));
            }
            drop(runtime);
            let candidate_id = spec.id.to_string();
            metrics::histogram!(
                "ripdpi_strategy_probe_duration_seconds",
                "candidate_id" => candidate_id,
                "family" => "quic",
            )
            .record(probe_started.elapsed().as_secs_f64());
            build_candidate_execution(spec, score, 2)
        }
        Err(err) => failed_candidate_execution(spec, targets.len(), 2, err),
    }
}

fn https_tls_error_detail(
    outcome: &str,
    tls13: &TlsObservation,
    tls12: &TlsObservation,
    tls_ech: &TlsObservation,
) -> String {
    let include_ech_error = !matches!(outcome, "tls_ok" | "tls_version_split");
    tls13
        .error
        .clone()
        .or_else(|| tls12.error.clone())
        .or_else(|| include_ech_error.then(|| tls_ech.error.clone()).flatten())
        .unwrap_or_else(|| "none".to_string())
}

pub fn run_http_strategy_probe(
    transport: &TransportConfig,
    target: &DomainTarget,
    candidate: &StrategyCandidateSpec,
) -> ProbeSample {
    let started = now_ms();
    let http_port = target.http_port.unwrap_or(80);
    let connect_targets = domain_connect_targets(target);
    let observation =
        try_http_request_targets(&connect_targets, http_port, transport, &target.host, &target.http_path, false);
    let latency_ms = now_ms().saturating_sub(started);
    // Try fingerprint-based classification first, then fall back to heuristics.
    let (outcome, fingerprint_name) = if let Some(response) = &observation.response {
        let (fp_outcome, fp_name) = classify_http_response_with_fingerprints(response, &BLOCKPAGE_FINGERPRINTS);
        let outcome = if fp_name.is_some() {
            fp_outcome
        } else if is_blockpage(&observation) {
            "http_blockpage".to_string()
        } else if observation.status == "http_ok" {
            "http_ok".to_string()
        } else if observation.status.starts_with("http_status_3") {
            "http_redirect".to_string()
        } else if observation.error.is_some() {
            "http_unreachable".to_string()
        } else {
            observation.status.clone()
        };
        (outcome, fp_name)
    } else if observation.error.is_some() {
        ("http_unreachable".to_string(), None)
    } else {
        (observation.status.clone(), None)
    };
    let h3_advertised =
        observation.response.as_ref().and_then(|r| r.headers.get("alt-svc")).is_some_and(|v| v.contains("h3"));
    let mut details = vec![
        ProbeDetail { key: "candidateId".to_string(), value: candidate.id.to_string() },
        ProbeDetail { key: "candidateLabel".to_string(), value: candidate.label.to_string() },
        ProbeDetail { key: "candidateFamily".to_string(), value: candidate.family.to_string() },
        ProbeDetail { key: "protocol".to_string(), value: "HTTP".to_string() },
        ProbeDetail { key: "latencyMs".to_string(), value: latency_ms.to_string() },
        ProbeDetail { key: "status".to_string(), value: observation.status },
        ProbeDetail { key: "error".to_string(), value: observation.error.unwrap_or_else(|| "none".to_string()) },
        ProbeDetail {
            key: "redirectLocation".to_string(),
            value: if outcome == "http_redirect" {
                observation
                    .response
                    .as_ref()
                    .and_then(|r| r.headers.get("location"))
                    .cloned()
                    .unwrap_or_else(|| "none".to_string())
            } else {
                "none".to_string()
            },
        },
    ];
    if let Some(fp) = &fingerprint_name {
        details.push(ProbeDetail { key: "blockpageFingerprint".to_string(), value: fp.clone() });
    }
    details.push(ProbeDetail { key: "h3Advertised".to_string(), value: h3_advertised.to_string() });
    ProbeSample {
        result: ProbeResult {
            probe_type: "strategy_http".to_string(),
            target: format!("{} · {}", candidate.label, target.host),
            outcome: outcome.clone(),
            details,
        },
        success: outcome == "http_ok" || outcome == "http_redirect",
        weight: 1,
        domain: Some(target.host.clone()),
        quality: if outcome == "http_ok" {
            3
        } else if outcome == "http_redirect" {
            2
        } else if outcome == "http_blockpage" {
            1
        } else {
            0
        },
        latency_ms,
    }
}

pub fn run_https_strategy_probe(
    transport: &TransportConfig,
    target: &DomainTarget,
    candidate: &StrategyCandidateSpec,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> ProbeSample {
    let started = now_ms();
    let https_port = target.https_port.unwrap_or(443);
    let connect_targets = domain_connect_targets(target);
    let tls13 = try_tls_handshake_targets(
        &connect_targets,
        https_port,
        transport,
        &target.host,
        true,
        TlsClientProfile::Tls13Only,
        tls_verifier,
    );
    let tls12 = try_tls_handshake_targets(
        &connect_targets,
        https_port,
        transport,
        &target.host,
        true,
        TlsClientProfile::Tls12Only,
        tls_verifier,
    );
    let tls_ech = try_tls_handshake_targets(
        &connect_targets,
        https_port,
        transport,
        &target.host,
        true,
        TlsClientProfile::Tls13WithEch,
        tls_verifier,
    );
    let latency_ms = now_ms().saturating_sub(started);
    let tls13_template = planned_tls_template_metadata(TlsClientProfile::Tls13Only);
    let tls12_template = planned_tls_template_metadata(TlsClientProfile::Tls12Only);
    let tls_ech_template = planned_tls_template_metadata(TlsClientProfile::Tls13WithEch);
    let outcome = if tls13.certificate_anomaly || tls12.certificate_anomaly {
        "tls_cert_invalid".to_string()
    } else if tls13.status == "tls_ok" && tls12.status == "tls_ok" {
        "tls_ok".to_string()
    } else if tls13.status == "tls_ok" || tls12.status == "tls_ok" {
        "tls_version_split".to_string()
    } else if tls_ech.status == "tls_ok" {
        "tls_ech_only".to_string()
    } else {
        "tls_handshake_failed".to_string()
    };
    // Pick timing and cert info from the preferred successful observation (tls13 first).
    let preferred = if tls13.tcp_connect_ms.is_some() { &tls13 } else { &tls12 };
    let tcp_connect_ms = preferred.tcp_connect_ms;
    let tls_handshake_ms = preferred.tls_handshake_ms;
    let cert_chain_length = preferred.cert_chain_length.or(tls12.cert_chain_length);
    let cert_issuer = preferred.cert_issuer.clone().or_else(|| tls12.cert_issuer.clone());
    let observed_server_ttl = preferred.observed_server_ttl;
    let estimated_hop_count = preferred.estimated_hop_count;
    let ja3_fingerprint = preferred.ja3_fingerprint.clone().or_else(|| tls12.ja3_fingerprint.clone());

    // Extract TLS alert forensic fields from whichever observation has them (tls13 first).
    let tls_alert_code = tls13.tls_alert_code.or(tls12.tls_alert_code);
    let tls_alert_description = tls13.tls_alert_description.clone().or_else(|| tls12.tls_alert_description.clone());
    let tls_server_hello_received = tls13.tls_server_hello_received.or(tls12.tls_server_hello_received);
    let tls_dpi_signature = tls13.tls_dpi_signature.clone().or_else(|| tls12.tls_dpi_signature.clone());
    let tls_negotiated_version = tls13.version.clone().or_else(|| tls12.version.clone());
    let tls_ech_error = tls_ech.error.clone().unwrap_or_else(|| "none".to_string());
    let tls_ech_resolution_detail = tls_ech.ech_resolution_detail.clone().unwrap_or_else(|| "none".to_string());
    let tls_error = https_tls_error_detail(&outcome, &tls13, &tls12, &tls_ech);
    let connected_addr = tls13.connected_addr.or(tls12.connected_addr).or(tls_ech.connected_addr);
    let cdn_provider =
        tls13.cdn_provider.clone().or_else(|| tls12.cdn_provider.clone()).or_else(|| tls_ech.cdn_provider.clone());

    let mut details = vec![
        ProbeDetail { key: "candidateId".to_string(), value: candidate.id.to_string() },
        ProbeDetail { key: "candidateLabel".to_string(), value: candidate.label.to_string() },
        ProbeDetail { key: "candidateFamily".to_string(), value: candidate.family.to_string() },
        ProbeDetail { key: "protocol".to_string(), value: "HTTPS".to_string() },
        ProbeDetail { key: "latencyMs".to_string(), value: latency_ms.to_string() },
        ProbeDetail { key: "tls13Status".to_string(), value: tls13.status },
        ProbeDetail { key: "tls12Status".to_string(), value: tls12.status },
        ProbeDetail { key: "tlsEchStatus".to_string(), value: tls_ech.status },
        ProbeDetail {
            key: "tls13TemplateProfileId".to_string(),
            value: planned_tls_template_profile(TlsClientProfile::Tls13Only).to_string(),
        },
        ProbeDetail {
            key: "tls12TemplateProfileId".to_string(),
            value: planned_tls_template_profile(TlsClientProfile::Tls12Only).to_string(),
        },
        ProbeDetail {
            key: "tlsEchTemplateProfileId".to_string(),
            value: planned_tls_template_profile(TlsClientProfile::Tls13WithEch).to_string(),
        },
        ProbeDetail {
            key: "tls13TemplateBrowserTrack".to_string(),
            value: tls13_template.parity_targets.browser_track.to_string(),
        },
        ProbeDetail {
            key: "tls12TemplateBrowserTrack".to_string(),
            value: tls12_template.parity_targets.browser_track.to_string(),
        },
        ProbeDetail {
            key: "tlsEchTemplateBrowserTrack".to_string(),
            value: tls_ech_template.parity_targets.browser_track.to_string(),
        },
        ProbeDetail {
            key: "tlsEchTemplateGreaseStyle".to_string(),
            value: tls_ech_template.template.grease_style.to_string(),
        },
        ProbeDetail {
            key: "tlsEchTemplateBootstrapPolicy".to_string(),
            value: tls_ech_template.template.ech_bootstrap_policy.to_string(),
        },
        ProbeDetail {
            key: "tlsEchTemplateBootstrapResolverId".to_string(),
            value: tls_ech_template.template.ech_bootstrap_resolver_id.unwrap_or("none").to_string(),
        },
        ProbeDetail {
            key: "tlsEchTemplateOuterExtensionPolicy".to_string(),
            value: tls_ech_template.template.ech_outer_extension_policy.to_string(),
        },
        ProbeDetail {
            key: "tlsEchTemplateAlpn".to_string(),
            value: tls_ech_template.template.alpn_template.to_string(),
        },
        ProbeDetail {
            key: "tlsEchVersion".to_string(),
            value: tls_ech.version.unwrap_or_else(|| "unknown".to_string()),
        },
        ProbeDetail { key: "tlsEchError".to_string(), value: tls_ech_error },
        ProbeDetail { key: "tlsEchResolutionDetail".to_string(), value: tls_ech_resolution_detail },
        ProbeDetail { key: "tlsError".to_string(), value: tls_error },
    ];
    if let Some(policy) = tls_ech.ech_bootstrap_policy.clone() {
        details.push(ProbeDetail { key: "tlsEchBootstrapPolicy".to_string(), value: policy });
    }
    if let Some(resolver_id) = tls_ech.ech_bootstrap_resolver_id.clone() {
        details.push(ProbeDetail { key: "tlsEchBootstrapResolverId".to_string(), value: resolver_id });
    }
    if let Some(policy) = tls_ech.ech_outer_extension_policy.clone() {
        details.push(ProbeDetail { key: "tlsEchOuterExtensionPolicy".to_string(), value: policy });
    }
    if let Some(plan) = tls_ech.ech_first_flight_plan.clone() {
        details.push(ProbeDetail { key: "tlsEchFirstFlightPlan".to_string(), value: plan });
    }
    if let Some(addr) = connected_addr {
        details.push(ProbeDetail { key: "connectedIp".to_string(), value: addr.ip().to_string() });
    }
    if let Some(provider) = cdn_provider {
        details.push(ProbeDetail { key: "cdnProvider".to_string(), value: provider });
    }
    details.push(ProbeDetail {
        key: "echCapable".to_string(),
        value: (outcome == "tls_ech_only" || tls_ech.ech_resolution_detail.as_deref() == Some("ech_config_available"))
            .to_string(),
    });
    details.push(ProbeDetail {
        key: "tlsEchTemplateCapable".to_string(),
        value: tls_ech_template.template.ech_capable.to_string(),
    });

    if let Some(ms) = tcp_connect_ms {
        details.push(ProbeDetail { key: "tcpConnectMs".to_string(), value: ms.to_string() });
    }
    if let Some(ms) = tls_handshake_ms {
        details.push(ProbeDetail { key: "tlsHandshakeMs".to_string(), value: ms.to_string() });
    }
    if let Some(len) = cert_chain_length {
        details.push(ProbeDetail { key: "tlsCertChainLength".to_string(), value: len.to_string() });
    }
    if let Some(issuer) = cert_issuer {
        details.push(ProbeDetail { key: "tlsCertIssuer".to_string(), value: issuer });
    }
    if let Some(ttl) = observed_server_ttl {
        details.push(ProbeDetail { key: "observedServerTtl".to_string(), value: ttl.to_string() });
    }
    if let Some(hops) = estimated_hop_count {
        details.push(ProbeDetail { key: "estimatedHopCount".to_string(), value: hops.to_string() });
    }
    if let Some(ja3) = ja3_fingerprint {
        details.push(ProbeDetail { key: "ja3Fingerprint".to_string(), value: ja3 });
    }
    if let Some(code) = tls_alert_code {
        details.push(ProbeDetail { key: "tlsAlertCode".to_string(), value: code.to_string() });
    }
    if let Some(desc) = tls_alert_description {
        details.push(ProbeDetail { key: "tlsAlertDescription".to_string(), value: desc });
    }
    if let Some(version) = tls_negotiated_version {
        details.push(ProbeDetail { key: "tlsNegotiatedVersion".to_string(), value: version });
    }
    if let Some(server_hello) = tls_server_hello_received {
        details.push(ProbeDetail { key: "tlsServerHelloReceived".to_string(), value: server_hello.to_string() });
    }
    if let Some(sig) = tls_dpi_signature {
        details.push(ProbeDetail { key: "tlsDpiSignature".to_string(), value: sig });
    }

    // On total TLS failure, perform a single retry to distinguish consistent
    // blocking from intermittent failures.
    let (retry_count, final_outcome) = if outcome == "tls_handshake_failed" {
        let retry = try_tls_handshake(
            &domain_connect_target(target),
            https_port,
            transport,
            &target.host,
            true,
            TlsClientProfile::Tls13Only,
            tls_verifier,
        );
        let retry_outcome = if retry.status == "tls_ok" { "tls_ok" } else { "tls_handshake_failed" };
        details.push(ProbeDetail { key: "retryOutcome".to_string(), value: retry_outcome.to_string() });
        details.push(ProbeDetail {
            key: "retryError".to_string(),
            value: retry.error.unwrap_or_else(|| "none".to_string()),
        });
        // If the retry succeeded, upgrade the overall outcome.
        let upgraded = if retry_outcome == "tls_ok" { "tls_ok".to_string() } else { outcome.clone() };
        (1_usize, upgraded)
    } else {
        (0, outcome.clone())
    };
    details.push(ProbeDetail { key: "probeRetryCount".to_string(), value: retry_count.to_string() });

    ProbeSample {
        result: ProbeResult {
            probe_type: "strategy_https".to_string(),
            target: format!("{} · {}", candidate.label, target.host),
            outcome: final_outcome.clone(),
            details,
        },
        success: matches!(final_outcome.as_str(), "tls_ok" | "tls_version_split"),
        weight: 2,
        domain: Some(target.host.clone()),
        quality: match final_outcome.as_str() {
            "tls_ok" => 4,
            "tls_version_split" => 3,
            _ => 0,
        },
        latency_ms,
    }
}

pub fn run_quic_strategy_probe(
    transport: &TransportConfig,
    target: &QuicTarget,
    candidate: &StrategyCandidateSpec,
) -> ProbeSample {
    let started = now_ms();
    let connect_targets = quic_connect_targets(target);
    let payload = build_realistic_quic_initial(QUIC_V1_VERSION, Some(target.host.as_str())).unwrap_or_default();
    let response = relay_udp_payload_observed(&connect_targets, target.port, transport, &payload);
    let latency_ms = now_ms().saturating_sub(started);
    let (outcome, status, error, connected_addr) = match response {
        Ok(result) if parse_quic_initial(&result.payload).is_some() => (
            "quic_initial_response".to_string(),
            "quic_initial_response".to_string(),
            "none".to_string(),
            result.connected_addr,
        ),
        Ok(result) if !result.payload.is_empty() => {
            ("quic_response".to_string(), "quic_response".to_string(), "none".to_string(), result.connected_addr)
        }
        Ok(result) => ("quic_empty".to_string(), "quic_empty".to_string(), "none".to_string(), result.connected_addr),
        Err(err) => ("quic_error".to_string(), "quic_error".to_string(), err, None),
    };
    let mut details = vec![
        ProbeDetail { key: "candidateId".to_string(), value: candidate.id.to_string() },
        ProbeDetail { key: "candidateLabel".to_string(), value: candidate.label.to_string() },
        ProbeDetail { key: "candidateFamily".to_string(), value: candidate.family.to_string() },
        ProbeDetail { key: "protocol".to_string(), value: "QUIC".to_string() },
        ProbeDetail { key: "latencyMs".to_string(), value: latency_ms.to_string() },
        ProbeDetail { key: "port".to_string(), value: target.port.to_string() },
        ProbeDetail { key: "status".to_string(), value: status },
        ProbeDetail { key: "error".to_string(), value: error },
    ];
    if let Some(addr) = connected_addr {
        details.push(ProbeDetail { key: "connectedIp".to_string(), value: addr.ip().to_string() });
        if let Some(provider) = crate::cdn_ech::opportunistic_ech_provider_for_ip(addr.ip()) {
            details.push(ProbeDetail { key: "cdnProvider".to_string(), value: provider.to_string() });
        }
    }
    ProbeSample {
        result: ProbeResult {
            probe_type: "strategy_quic".to_string(),
            target: format!("{} · {}", candidate.label, target.host),
            outcome: outcome.clone(),
            details,
        },
        success: matches!(outcome.as_str(), "quic_initial_response" | "quic_response"),
        weight: 2,
        domain: Some(target.host.clone()),
        quality: match outcome.as_str() {
            "quic_initial_response" => 4,
            "quic_response" => 3,
            _ => 0,
        },
        latency_ms,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
            certificate_anomaly: false,
            ech_resolution_detail: None,
            ech_bootstrap_policy: None,
            ech_bootstrap_resolver_id: None,
            ech_outer_extension_policy: None,
            ech_first_flight_plan: None,
            tcp_connect_ms: None,
            tls_handshake_ms: None,
            cert_chain_length: None,
            cert_issuer: None,
            observed_server_ttl: None,
            estimated_hop_count: None,
            ja3_fingerprint: None,
            tls_alert_code: None,
            tls_alert_description: None,
            tls_server_hello_received: None,
            tls_dpi_signature: None,
            connected_addr: None,
            local_addr: None,
            cdn_provider: None,
            route_report: None,
        }
    }
}
