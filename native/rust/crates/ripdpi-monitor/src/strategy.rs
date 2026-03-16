use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use ripdpi_failure_classifier::{confirm_dns_tampering, FailureClass};
use ripdpi_proxy_config::{parse_proxy_config_json, ProxyConfigPayload, ProxyRuntimeContext};

use crate::candidates::*;
use crate::classification::*;
use crate::connectivity::*;
use crate::dns::*;
use crate::execution::*;
use crate::transport::*;
use crate::types::SharedState;
use crate::types::*;
use crate::util::*;

pub(crate) fn run_strategy_probe_scan(
    shared: Arc<Mutex<SharedState>>,
    cancel: Arc<AtomicBool>,
    session_id: String,
    request: ScanRequest,
) {
    let started_at = now_ms();
    let strategy_probe = request.strategy_probe.clone().expect("validated strategy_probe request");
    let mut results = Vec::new();

    let Some(base_proxy_config_json) = strategy_probe.base_proxy_config_json.as_deref() else {
        let report = ScanReport {
            session_id,
            profile_id: request.profile_id,
            path_mode: request.path_mode,
            started_at,
            finished_at: now_ms(),
            summary: "Automatic probing could not start".to_string(),
            results,
            strategy_probe_report: None,
        };
        set_report(&shared, report);
        return;
    };

    let (base_payload, runtime_context) = match parse_proxy_config_json(base_proxy_config_json).map_err(|err| err.to_string()) {
        Ok(ProxyConfigPayload::Ui { config, runtime_context }) => (config, runtime_context),
        Ok(ProxyConfigPayload::CommandLine { .. }) => {
            let report = ScanReport {
                session_id,
                profile_id: request.profile_id,
                path_mode: request.path_mode,
                started_at,
                finished_at: now_ms(),
                summary: "Automatic probing requires UI-configured RIPDPI settings".to_string(),
                results,
                strategy_probe_report: None,
            };
            set_report(&shared, report);
            return;
        }
        Err(message) => {
            let report = ScanReport {
                session_id,
                profile_id: request.profile_id,
                path_mode: request.path_mode,
                started_at,
                finished_at: now_ms(),
                summary: message,
                results,
                strategy_probe_report: None,
            };
            set_report(&shared, report);
            return;
        }
    };

    let suite = match build_strategy_probe_suite(&strategy_probe.suite_id, &base_payload) {
        Ok(suite) => suite,
        Err(message) => {
            let report = ScanReport {
                session_id,
                profile_id: request.profile_id,
                path_mode: request.path_mode,
                started_at,
                finished_at: now_ms(),
                summary: message,
                results,
                strategy_probe_report: None,
            };
            set_report(&shared, report);
            return;
        }
    };
    let total_steps = suite.total_steps();
    let mut completed_steps = 0usize;

    set_progress(
        &shared,
        ScanProgress {
            session_id: session_id.clone(),
            phase: "starting".to_string(),
            completed_steps,
            total_steps,
            message: format!("Preparing {}", request.display_name),
            is_finished: false,
        },
    );
    push_event(
        &shared,
        "strategy_probe",
        "info",
        format!("Starting {} suite={} in {:?}", request.display_name, strategy_probe.suite_id, request.path_mode),
    );

    let StrategyProbeSuite {
        suite_id,
        tcp_candidates: tcp_specs,
        quic_candidates: preview_quic_specs,
        short_circuit_hostfake,
        short_circuit_quic_burst,
    } = suite;
    let probe_seed = probe_session_seed(base_payload.network_scope_key.as_deref(), &session_id);

    if let Some(baseline) = detect_strategy_probe_dns_tampering(&request.domain_targets, runtime_context.as_ref()) {
        push_event(
            &shared,
            "strategy_probe",
            "warn",
            format!(
                "Baseline classified as {} with {}",
                baseline.failure.class.as_str(),
                baseline.failure.action.as_str(),
            ),
        );
        results.extend(baseline.results);
        results.push(classified_failure_probe_result("Current strategy", &baseline.failure));

        let tcp_candidates = tcp_specs
            .iter()
            .map(|spec| {
                skipped_candidate_summary(
                    spec,
                    request.domain_targets.len() * 2,
                    3,
                    "DNS tampering detected before fallback; TCP strategy escalation skipped",
                )
            })
            .collect::<Vec<_>>();
        let quic_specs =
            filter_quic_candidates_for_failure(preview_quic_specs.clone(), Some(FailureClass::QuicBreakage));
        let quic_candidates = quic_specs
            .iter()
            .map(|spec| {
                skipped_candidate_summary(
                    spec,
                    request.quic_targets.len(),
                    2,
                    "DNS tampering detected before fallback; QUIC strategy escalation skipped",
                )
            })
            .collect::<Vec<_>>();
        let fallback_quic = quic_specs.first().or_else(|| preview_quic_specs.first()).expect("quic candidate");
        let recommendation = StrategyProbeRecommendation {
            tcp_candidate_id: tcp_specs.first().expect("tcp candidate").id.to_string(),
            tcp_candidate_label: tcp_specs.first().expect("tcp candidate").label.to_string(),
            quic_candidate_id: fallback_quic.id.to_string(),
            quic_candidate_label: fallback_quic.label.to_string(),
            rationale: format!(
                "{} classified before fallback; keep current strategy and prefer resolver override",
                baseline.failure.class.as_str(),
            ),
            recommended_proxy_config_json: strategy_probe_config_json(&base_payload),
        };
        let strategy_probe_report = StrategyProbeReport {
            suite_id: strategy_probe.suite_id,
            tcp_candidates,
            quic_candidates,
            recommendation: recommendation.clone(),
        };
        let report = ScanReport {
            session_id: session_id.clone(),
            profile_id: request.profile_id,
            path_mode: request.path_mode,
            started_at,
            finished_at: now_ms(),
            summary: "DNS tampering classified before fallback; resolver override recommended".to_string(),
            results,
            strategy_probe_report: Some(strategy_probe_report),
        };
        set_report(&shared, report);
        set_progress(
            &shared,
            ScanProgress {
                session_id,
                phase: "finished".to_string(),
                completed_steps: total_steps,
                total_steps,
                message: "Automatic probing finished".to_string(),
                is_finished: true,
            },
        );
        return;
    }

    let mut tcp_candidates = Vec::new();
    let mut hostfake_family_succeeded = false;
    let baseline_spec = tcp_specs.first().expect("tcp candidate");
    set_progress(
        &shared,
        ScanProgress {
            session_id: session_id.clone(),
            phase: "tcp".to_string(),
            completed_steps,
            total_steps,
            message: format!("Testing {}", baseline_spec.label),
            is_finished: false,
        },
    );
    push_event(
        &shared,
        "strategy_probe",
        "info",
        format!("Testing TCP candidate {}", baseline_spec.label),
    );
    let baseline_execution = execute_tcp_candidate(
        baseline_spec,
        &request.domain_targets,
        runtime_context.as_ref(),
        probe_seed,
    );
    let baseline_failure = classify_strategy_probe_baseline_results(&baseline_execution.results);
    results.extend(baseline_execution.results);
    if let Some(failure) = &baseline_failure {
        push_event(
            &shared,
            "strategy_probe",
            "warn",
            format!(
                "Baseline classified as {} with {}",
                failure.class.as_str(),
                failure.action.as_str(),
            ),
        );
        results.push(classified_failure_probe_result(baseline_spec.label, failure));
    }
    if baseline_execution.summary.family == "hostfake"
        && baseline_execution.summary.succeeded_targets == baseline_execution.summary.total_targets
    {
        hostfake_family_succeeded = true;
    }
    tcp_candidates.push(baseline_execution.summary);
    completed_steps += 1;
    if !tcp_specs.is_empty() && tcp_specs.len() > 1 {
        thread::sleep(Duration::from_millis(candidate_pause_ms(
            probe_seed,
            baseline_spec,
            baseline_failure.is_some(),
        )));
    }

    let ordered_tcp_specs = interleave_candidate_families(
        reorder_tcp_candidates_for_failure(&tcp_specs, baseline_failure.as_ref().map(|value| value.class))
            .into_iter()
            .skip(1)
            .collect(),
        probe_seed,
    );
    let mut pending_tcp_specs = ordered_tcp_specs;
    let mut blocked_tcp_family = None::<&'static str>;
    let mut last_failed_tcp_family = None::<&'static str>;
    let mut consecutive_tcp_family_failures = 0usize;
    while !pending_tcp_specs.is_empty() {
        let spec = pending_tcp_specs.remove(next_candidate_index(&pending_tcp_specs, blocked_tcp_family));
        if cancel.load(Ordering::Acquire) {
            persist_cancelled_report(shared, session_id, request, started_at, results);
            return;
        }

        if short_circuit_hostfake && spec.family == "hostfake" && hostfake_family_succeeded {
            tcp_candidates.push(skipped_candidate_summary(
                &spec,
                request.domain_targets.len() * 2,
                6,
                "Earlier hostfake candidate already achieved full success",
            ));
            completed_steps += 1;
            set_progress(
                &shared,
                ScanProgress {
                    session_id: session_id.clone(),
                    phase: "tcp".to_string(),
                    completed_steps,
                    total_steps,
                    message: format!("Skipping {}", spec.label),
                    is_finished: false,
                },
            );
            continue;
        }

        set_progress(
            &shared,
            ScanProgress {
                session_id: session_id.clone(),
                phase: "tcp".to_string(),
                completed_steps,
                total_steps,
                message: format!("Testing {}", spec.label),
                is_finished: false,
            },
        );
        push_event(&shared, "strategy_probe", "info", format!("Testing TCP candidate {}", spec.label));
        let execution = execute_tcp_candidate(&spec, &request.domain_targets, runtime_context.as_ref(), probe_seed);
        results.extend(execution.results);
        if execution.summary.family == "hostfake"
            && execution.summary.succeeded_targets == execution.summary.total_targets
        {
            hostfake_family_succeeded = true;
        }
        tcp_candidates.push(execution.summary);
        completed_steps += 1;
        let failed = tcp_candidates.last().is_some_and(|summary| summary.outcome == "failed");
        if failed {
            if last_failed_tcp_family == Some(spec.family) {
                consecutive_tcp_family_failures += 1;
            } else {
                last_failed_tcp_family = Some(spec.family);
                consecutive_tcp_family_failures = 1;
            }
            if consecutive_tcp_family_failures >= 2 {
                blocked_tcp_family = Some(spec.family);
                consecutive_tcp_family_failures = 0;
            }
        } else {
            last_failed_tcp_family = None;
            consecutive_tcp_family_failures = 0;
            blocked_tcp_family = None;
        }
        if blocked_tcp_family.is_some() && spec.family != blocked_tcp_family.unwrap_or_default() {
            blocked_tcp_family = None;
        }
        if !pending_tcp_specs.is_empty() {
            thread::sleep(Duration::from_millis(candidate_pause_ms(probe_seed, &spec, failed)));
        }
    }

    let winning_tcp = winning_candidate_index(&tcp_candidates).unwrap_or(0);
    let tcp_winner_spec = tcp_specs
        .iter()
        .find(|spec| spec.id == tcp_candidates[winning_tcp].id)
        .unwrap_or_else(|| tcp_specs.first().expect("tcp candidates"));

    let mut quic_candidates = Vec::new();
    let mut quic_family_succeeded = false;
    let quic_specs = filter_quic_candidates_for_failure(
        build_quic_candidates_for_suite(suite_id, &tcp_winner_spec.config).unwrap_or_else(|_| preview_quic_specs.clone()),
        baseline_failure.as_ref().map(|value| value.class),
    );
    let mut pending_quic_specs = interleave_candidate_families(quic_specs.clone(), stable_probe_hash(probe_seed, "quic"));
    let mut blocked_quic_family = None::<&'static str>;
    let mut last_failed_quic_family = None::<&'static str>;
    let mut consecutive_quic_family_failures = 0usize;
    while !pending_quic_specs.is_empty() {
        let spec = pending_quic_specs.remove(next_candidate_index(&pending_quic_specs, blocked_quic_family));
        if cancel.load(Ordering::Acquire) {
            persist_cancelled_report(shared, session_id, request, started_at, results);
            return;
        }

        if short_circuit_quic_burst && spec.family == "quic_burst" && quic_family_succeeded {
            quic_candidates.push(skipped_candidate_summary(
                &spec,
                request.quic_targets.len(),
                2,
                "Earlier QUIC burst candidate already achieved full success",
            ));
            completed_steps += 1;
            set_progress(
                &shared,
                ScanProgress {
                    session_id: session_id.clone(),
                    phase: "quic".to_string(),
                    completed_steps,
                    total_steps,
                    message: format!("Skipping {}", spec.label),
                    is_finished: false,
                },
            );
            continue;
        }

        set_progress(
            &shared,
            ScanProgress {
                session_id: session_id.clone(),
                phase: "quic".to_string(),
                completed_steps,
                total_steps,
                message: format!("Testing {}", spec.label),
                is_finished: false,
            },
        );
        push_event(&shared, "strategy_probe", "info", format!("Testing QUIC candidate {}", spec.label));
        let execution = execute_quic_candidate(&spec, &request.quic_targets, runtime_context.as_ref(), probe_seed);
        results.extend(execution.results);
        if execution.summary.family == "quic_burst"
            && execution.summary.succeeded_targets == execution.summary.total_targets
            && execution.summary.total_targets > 0
        {
            quic_family_succeeded = true;
        }
        quic_candidates.push(execution.summary);
        completed_steps += 1;
        let failed = quic_candidates.last().is_some_and(|summary| summary.outcome == "failed");
        if failed {
            if last_failed_quic_family == Some(spec.family) {
                consecutive_quic_family_failures += 1;
            } else {
                last_failed_quic_family = Some(spec.family);
                consecutive_quic_family_failures = 1;
            }
            if consecutive_quic_family_failures >= 2 {
                blocked_quic_family = Some(spec.family);
                consecutive_quic_family_failures = 0;
            }
        } else {
            last_failed_quic_family = None;
            consecutive_quic_family_failures = 0;
            blocked_quic_family = None;
        }
        if blocked_quic_family.is_some() && spec.family != blocked_quic_family.unwrap_or_default() {
            blocked_quic_family = None;
        }
        if !pending_quic_specs.is_empty() {
            thread::sleep(Duration::from_millis(candidate_pause_ms(probe_seed, &spec, failed)));
        }
    }

    if let Some(quic_failure) = classify_strategy_probe_baseline_results(
        &results
            .iter()
            .filter(|result| result.probe_type == "strategy_quic")
            .cloned()
            .collect::<Vec<_>>(),
    )
    .filter(|failure| failure.class == FailureClass::QuicBreakage)
    {
        push_event(
            &shared,
            "strategy_probe",
            "warn",
            format!(
                "QUIC classified as {} with {}",
                quic_failure.class.as_str(),
                quic_failure.action.as_str(),
            ),
        );
        results.push(classified_failure_probe_result("QUIC strategy family", &quic_failure));
    }

    let winning_quic = winning_candidate_index(&quic_candidates).unwrap_or(0);
    let quic_winner_spec = quic_specs
        .iter()
        .find(|spec| spec.id == quic_candidates[winning_quic].id)
        .unwrap_or_else(|| quic_specs.first().expect("quic candidates"));
    let recommended_proxy_config_json = strategy_probe_config_json(&quic_winner_spec.config);
    let recommendation = StrategyProbeRecommendation {
        tcp_candidate_id: tcp_candidates[winning_tcp].id.clone(),
        tcp_candidate_label: tcp_candidates[winning_tcp].label.clone(),
        quic_candidate_id: quic_candidates[winning_quic].id.clone(),
        quic_candidate_label: quic_candidates[winning_quic].label.clone(),
        rationale: format!(
            "{} with {} weighted TCP success and {} weighted QUIC success",
            tcp_candidates[winning_tcp].label,
            tcp_candidates[winning_tcp].weighted_success_score,
            quic_candidates[winning_quic].weighted_success_score,
        ),
        recommended_proxy_config_json,
    };
    let summary = build_strategy_probe_summary(suite_id, &tcp_candidates, &quic_candidates, &recommendation);
    let strategy_probe_report = StrategyProbeReport {
        suite_id: strategy_probe.suite_id,
        tcp_candidates,
        quic_candidates,
        recommendation: recommendation.clone(),
    };
    let report = ScanReport {
        session_id: session_id.clone(),
        profile_id: request.profile_id,
        path_mode: request.path_mode,
        started_at,
        finished_at: now_ms(),
        summary,
        results,
        strategy_probe_report: Some(strategy_probe_report),
    };

    set_report(&shared, report);
    push_event(&shared, "strategy_probe", "info", "Automatic probing finished".to_string());
    set_progress(
        &shared,
        ScanProgress {
            session_id,
            phase: "finished".to_string(),
            completed_steps: total_steps,
            total_steps,
            message: "Automatic probing finished".to_string(),
            is_finished: true,
        },
    );
}

pub(crate) fn detect_strategy_probe_dns_tampering(
    targets: &[DomainTarget],
    runtime_context: Option<&ProxyRuntimeContext>,
) -> Option<StrategyProbeBaseline> {
    if targets.is_empty() {
        return None;
    }

    let resolver_context = strategy_probe_encrypted_dns_context(runtime_context);
    let resolver_endpoint = strategy_probe_encrypted_dns_endpoint(&resolver_context).ok()?;
    let resolver_label = strategy_probe_encrypted_dns_label(&resolver_context);
    let mut results = Vec::new();
    let mut classified = None;

    for target in targets {
        if target.host.parse::<IpAddr>().is_ok() || target.host.eq_ignore_ascii_case("localhost") {
            continue;
        }
        let system_started = std::time::Instant::now();
        let system_targets = match domain_connect_target(target) {
            TargetAddress::Ip(ip) => vec![SocketAddr::new(ip, target.https_port.unwrap_or(443))],
            TargetAddress::Host(host) => {
                resolve_addresses(&TargetAddress::Host(host), target.https_port.unwrap_or(443)).unwrap_or_default()
            }
        };
        let system_latency_ms = system_started.elapsed().as_millis().to_string();
        if system_targets.is_empty() {
            continue;
        }

        let encrypted_started = std::time::Instant::now();
        let encrypted_result = resolve_via_encrypted_dns(&target.host, resolver_endpoint.clone(), &TransportConfig::Direct);
        let encrypted_latency_ms = encrypted_started.elapsed().as_millis().to_string();
        let encrypted_addresses = encrypted_result
            .as_ref()
            .ok()
            .into_iter()
            .flat_map(|value| value.iter())
            .cloned()
            .collect::<Vec<_>>();
        let encrypted_ips = encrypted_addresses
            .iter()
            .filter_map(|value| value.parse::<IpAddr>().ok())
            .collect::<Vec<_>>();
        let system_ips = system_targets.iter().map(SocketAddr::ip).collect::<Vec<_>>();
        let substitution = system_ips.iter().all(|ip| !encrypted_ips.iter().any(|answer| answer == ip));
        let outcome = if substitution { "dns_substitution" } else { "dns_match" };
        results.push(ProbeResult {
            probe_type: "dns_integrity".to_string(),
            target: target.host.clone(),
            outcome: outcome.to_string(),
            details: vec![
                ProbeDetail {
                    key: "udpAddresses".to_string(),
                    value: system_targets.iter().map(ToString::to_string).collect::<Vec<_>>().join("|"),
                },
                ProbeDetail { key: "udpLatencyMs".to_string(), value: system_latency_ms },
                ProbeDetail {
                    key: "encryptedResolverId".to_string(),
                    value: resolver_context.resolver_id.clone().unwrap_or_default(),
                },
                ProbeDetail {
                    key: "encryptedProtocol".to_string(),
                    value: resolver_context.protocol.clone(),
                },
                ProbeDetail {
                    key: "encryptedEndpoint".to_string(),
                    value: resolver_label.clone(),
                },
                ProbeDetail {
                    key: "encryptedHost".to_string(),
                    value: resolver_context.host.clone(),
                },
                ProbeDetail {
                    key: "encryptedPort".to_string(),
                    value: resolver_context.port.to_string(),
                },
                ProbeDetail {
                    key: "encryptedTlsServerName".to_string(),
                    value: resolver_context.tls_server_name.clone().unwrap_or_default(),
                },
                ProbeDetail {
                    key: "encryptedBootstrapIps".to_string(),
                    value: resolver_context.bootstrap_ips.join("|"),
                },
                ProbeDetail {
                    key: "encryptedBootstrapValidated".to_string(),
                    value: (!encrypted_addresses.is_empty() && !resolver_context.bootstrap_ips.is_empty()).to_string(),
                },
                ProbeDetail {
                    key: "encryptedDohUrl".to_string(),
                    value: resolver_context.doh_url.clone().unwrap_or_default(),
                },
                ProbeDetail {
                    key: "encryptedDnscryptProviderName".to_string(),
                    value: resolver_context.dnscrypt_provider_name.clone().unwrap_or_default(),
                },
                ProbeDetail {
                    key: "encryptedDnscryptPublicKey".to_string(),
                    value: resolver_context.dnscrypt_public_key.clone().unwrap_or_default(),
                },
                ProbeDetail {
                    key: "encryptedAddresses".to_string(),
                    value: if encrypted_addresses.is_empty() {
                        encrypted_result.as_ref().err().cloned().unwrap_or_else(|| "[]".to_string())
                    } else {
                        encrypted_addresses.join("|")
                    },
                },
                ProbeDetail { key: "encryptedLatencyMs".to_string(), value: encrypted_latency_ms },
            ],
        });
        if substitution && classified.is_none() {
            for system_ip in &system_ips {
                if let Some(failure) = confirm_dns_tampering(&target.host, *system_ip, &encrypted_ips, &resolver_label) {
                    classified = Some(failure);
                    break;
                }
            }
        }
    }

    classified.map(|failure| StrategyProbeBaseline { failure, results })
}
