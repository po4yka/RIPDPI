use crate::candidates::{
    build_strategy_probe_summary, probe_fake_ttl_capability, probe_ip_fragmentation_capabilities,
    probe_tcp_fast_open_capability,
};
use crate::engine::runtime::{ExecutionPlan, ExecutionRuntime};
use crate::transport::TransportConfig;
use crate::types::{
    ProbeResult, STRATEGY_PROBE_METHODOLOGY_VERSION, ScanPathMode, StrategyActivePathAuthority,
    StrategyActivePathObservation, StrategyProbeCompletionKind, StrategyProbeObservationRole,
    StrategyProbeRecommendation, StrategyProbeReport, StrategyProbeResponseStage, TransportFamily,
    TransportPivotRecommendation, TransportPivotViability,
};

use super::super::support::{
    pilot_bucket_label, resolve_recommended_proxy_config_json, resolve_strategy_probe_audit_assessment,
    select_safe_or_baseline_candidate_index, stratified_pilot_targets,
};

pub(in crate::engine) fn prepare_strategy_probe_report(plan: &ExecutionPlan, runtime: &mut ExecutionRuntime) -> bool {
    let Some(strategy_plan) = plan.strategy.as_ref() else {
        return false;
    };
    if runtime.strategy.strategy_probe_report.is_some() {
        return true;
    }
    if runtime.strategy.tcp_candidates.is_empty() || runtime.strategy.quic_candidates.is_empty() {
        runtime.strategy.summary = Some("Automatic probing finished".to_string());
        return false;
    }
    let fake_ttl_available = probe_fake_ttl_capability();
    let tcp_fast_open_available = probe_tcp_fast_open_capability();
    let ipfrag_caps = probe_ip_fragmentation_capabilities();
    let wi_tcp = select_safe_or_baseline_candidate_index(
        &runtime.strategy.tcp_candidates,
        &strategy_plan.suite.tcp_candidates,
        fake_ttl_available,
        tcp_fast_open_available,
        ipfrag_caps,
    );
    let wi_quic = select_safe_or_baseline_candidate_index(
        &runtime.strategy.quic_candidates,
        &strategy_plan.suite.quic_candidates,
        fake_ttl_available,
        tcp_fast_open_available,
        ipfrag_caps,
    );
    let recommendation = wi_tcp.zip(wi_quic).and_then(|(wi_tcp, wi_quic)| {
        let tcp_w = &runtime.strategy.tcp_candidates[wi_tcp];
        let quic_w = &runtime.strategy.quic_candidates[wi_quic];
        let quic_winner_spec = strategy_plan
            .suite
            .quic_candidates
            .iter()
            .find(|spec| spec.id == quic_w.id)
            .or_else(|| strategy_plan.suite.quic_candidates.first())?;
        let confirm_good_corroborated =
            plan.request.confirm_good_dpi_evidence.is_some() && quic_w.succeeded_targets > 0 && !quic_w.skipped;
        Some(StrategyProbeRecommendation {
            tcp_candidate_id: tcp_w.id.clone(),
            tcp_candidate_label: tcp_w.label.clone(),
            quic_candidate_id: quic_w.id.clone(),
            quic_candidate_label: quic_w.label.clone(),
            quic_candidate_layout_family: quic_w.quic_layout_family.clone(),
            rationale: if confirm_good_corroborated {
                "Reality application data stalled after successful handshakes; QUIC succeeded, so pivot transport family"
                    .to_string()
            } else {
                format!(
                    "{} with {} weighted TCP success and {} weighted QUIC success",
                    tcp_w.label, tcp_w.weighted_success_score, quic_w.weighted_success_score,
                )
            },
            recommended_proxy_config_json: resolve_recommended_proxy_config_json(quic_w, quic_winner_spec),
            transport_pivot: confirm_good_corroborated.then(|| TransportPivotRecommendation {
                reason_code: "confirm_good_dpi_suspected".to_string(),
                preferred_family: TransportFamily::UdpQuic,
                viability: TransportPivotViability::Confirmed,
                selected_relay_role: None,
            }),
        })
    });
    let is_dns_tampered = runtime.strategy.dns_override_domain_targets.is_some();
    let audit_assessment = recommendation.as_ref().and_then(|recommendation| {
        resolve_strategy_probe_audit_assessment(
            &strategy_plan.suite_id,
            &runtime.strategy.tcp_candidates,
            &runtime.strategy.quic_candidates,
            recommendation,
            strategy_plan.suite.tcp_candidates.len(),
            strategy_plan.suite.quic_candidates.len(),
            is_dns_tampered,
        )
    });
    let summary = recommendation.as_ref().map_or_else(
        || "Automatic probing finished without a verified recommendation".to_string(),
        |recommendation| {
            build_strategy_probe_summary(
                &strategy_plan.suite_id,
                &runtime.strategy.tcp_candidates,
                &runtime.strategy.quic_candidates,
                recommendation,
                audit_assessment.as_ref(),
            )
        },
    );
    let pilot_bucket_labels = stratified_pilot_targets(&plan.request.domain_targets)
        .into_iter()
        .map(|target| pilot_bucket_label(&target))
        .collect();
    let active_path_observation = active_path_evidence(plan, runtime);
    let is_partial = runtime.strategy.tcp_candidates.len() < strategy_plan.suite.tcp_candidates.len()
        || runtime.strategy.quic_candidates.len() < strategy_plan.suite.quic_candidates.len();
    runtime.strategy.strategy_probe_report = Some(StrategyProbeReport {
        suite_id: strategy_plan.suite_id.clone(),
        methodology_version: STRATEGY_PROBE_METHODOLOGY_VERSION.to_string(),
        tcp_candidates: runtime.strategy.tcp_candidates.clone(),
        quic_candidates: runtime.strategy.quic_candidates.clone(),
        recommendation,
        completion_kind: match () {
            _ if is_dns_tampered => StrategyProbeCompletionKind::DnsTamperingWithFallback,
            _ if is_partial => StrategyProbeCompletionKind::PartialResults,
            _ => StrategyProbeCompletionKind::Normal,
        },
        audit_assessment,
        connection_concurrency_assessment: runtime.strategy.connection_concurrency_assessment.clone(),
        target_selection: plan.request.strategy_probe.as_ref().and_then(|p| p.target_selection.clone()),
        pilot_bucket_labels,
        active_path_observation,
        domain_strategy_seeds: Vec::new(),
    });
    runtime.strategy.summary = Some(summary);
    true
}

fn active_path_evidence(plan: &ExecutionPlan, runtime: &ExecutionRuntime) -> Option<StrategyActivePathObservation> {
    let route = plan.request.in_path_route.as_ref()?;
    let authenticated_route_matches = authenticated_in_path_transport_matches(route, &plan.transport);
    classify_active_path_evidence(&plan.request.path_mode, authenticated_route_matches, &runtime.results)
}

fn authenticated_in_path_transport_matches(route: &crate::types::InPathRoute, transport: &TransportConfig) -> bool {
    let TransportConfig::Socks5 { host, port, credentials: Some(credentials) } = transport else {
        return false;
    };
    host == &route.host
        && port == &route.port
        && credentials.username() == route.credentials.username.as_str()
        && credentials.password() == route.credentials.password.as_str()
}

fn classify_active_path_evidence(
    path_mode: &ScanPathMode,
    has_in_path_route: bool,
    results: &[ProbeResult],
) -> Option<StrategyActivePathObservation> {
    if !matches!(path_mode, ScanPathMode::InPath) || !has_in_path_route {
        return None;
    }
    let outcomes = results
        .iter()
        .filter(|result| result.probe_type == "domain_reachability")
        .map(|result| result.outcome.as_str())
        .collect::<Vec<_>>();
    if outcomes.is_empty() {
        return None;
    }
    let mut response_observed_targets = 0;
    let mut response_not_observed_targets = 0;
    let mut successful_targets = 0;
    let mut route_reached_targets = 0;
    for result in results.iter().filter(|result| result.probe_type == "domain_reachability") {
        let outcome = result.outcome.as_str();
        let route_reached = result.details.iter().any(|detail| detail.key == "connectedIp")
            || matches!(
                outcome,
                "tls_ok" | "tls_version_split" | "http_ok" | "tls_ech_only" | "tls_cert_invalid" | "http_blockpage"
            );
        route_reached_targets += usize::from(route_reached);
        match outcome {
            "tls_ok" | "tls_version_split" | "http_ok" => {
                response_observed_targets += 1;
                successful_targets += 1;
            }
            "tls_ech_only" | "tls_cert_invalid" | "http_blockpage" => response_observed_targets += 1,
            "unreachable" => response_not_observed_targets += 1,
            _ => {}
        }
    }
    let response_stage = if response_observed_targets > 0 {
        StrategyProbeResponseStage::ResponseObserved
    } else if response_not_observed_targets == outcomes.len() {
        StrategyProbeResponseStage::ResponseNotObserved
    } else {
        StrategyProbeResponseStage::Unavailable
    };
    Some(StrategyActivePathObservation {
        role: StrategyProbeObservationRole::ActiveServiceInPath,
        response_stage,
        active_path_authority: StrategyActivePathAuthority::Unverified,
        attempted_targets: outcomes.len(),
        route_reached_targets,
        response_observed_targets,
        successful_targets,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::transport::Socks5Credentials;

    #[test]
    fn active_service_observation_is_separate_and_unverified_until_route_lease_is_correlated() {
        let results = vec![ProbeResult {
            probe_type: "domain_reachability".to_string(),
            target: "redacted-target".to_string(),
            outcome: "http_ok".to_string(),
            details: Vec::new(),
        }];

        let observation =
            classify_active_path_evidence(&ScanPathMode::InPath, true, &results).expect("active observation");

        assert_eq!(observation.role, StrategyProbeObservationRole::ActiveServiceInPath);
        assert_eq!(observation.response_stage, StrategyProbeResponseStage::ResponseObserved);
        assert_eq!(observation.active_path_authority, StrategyActivePathAuthority::Unverified);
        assert_eq!(observation.attempted_targets, 1);
        assert_eq!(observation.route_reached_targets, 1);
        assert_eq!(observation.response_observed_targets, 1);
        assert_eq!(observation.successful_targets, 1);
        assert!(classify_active_path_evidence(&ScanPathMode::RawPath, true, &results).is_none());
        assert!(classify_active_path_evidence(&ScanPathMode::InPath, false, &results).is_none());
    }

    #[test]
    fn active_service_observation_records_response_absence_without_strategy_attribution() {
        let results = vec![ProbeResult {
            probe_type: "domain_reachability".to_string(),
            target: "redacted-target".to_string(),
            outcome: "unreachable".to_string(),
            details: Vec::new(),
        }];

        let observation =
            classify_active_path_evidence(&ScanPathMode::InPath, true, &results).expect("active observation");

        assert_eq!(observation.response_stage, StrategyProbeResponseStage::ResponseNotObserved);
        assert_eq!(observation.route_reached_targets, 0);
        assert_eq!(observation.response_observed_targets, 0);
        assert_eq!(observation.successful_targets, 0);
    }

    #[test]
    fn active_service_response_absence_preserves_reached_route_stage() {
        let results = vec![ProbeResult {
            probe_type: "domain_reachability".to_string(),
            target: "redacted-target".to_string(),
            outcome: "unreachable".to_string(),
            details: vec![crate::types::ProbeDetail { key: "connectedIp".to_string(), value: "redacted".to_string() }],
        }];

        let observation =
            classify_active_path_evidence(&ScanPathMode::InPath, true, &results).expect("active observation");

        assert_eq!(observation.response_stage, StrategyProbeResponseStage::ResponseNotObserved);
        assert_eq!(observation.route_reached_targets, 1);
    }

    #[test]
    fn active_service_failure_responses_are_observed_but_not_successful() {
        for outcome in ["http_blockpage", "tls_cert_invalid", "tls_ech_only"] {
            let results = vec![ProbeResult {
                probe_type: "domain_reachability".to_string(),
                target: "redacted-target".to_string(),
                outcome: outcome.to_string(),
                details: Vec::new(),
            }];

            let observation =
                classify_active_path_evidence(&ScanPathMode::InPath, true, &results).expect("active observation");

            assert_eq!(observation.response_stage, StrategyProbeResponseStage::ResponseObserved);
            assert_eq!(observation.response_observed_targets, 1);
            assert_eq!(observation.successful_targets, 0);
        }
    }

    #[test]
    fn active_service_observation_requires_the_exact_authenticated_plan_transport() {
        let route = crate::types::InPathRoute {
            host: "127.0.0.1".to_string(),
            port: 19_080,
            credentials: crate::types::InPathProxyCredentials {
                username: "diagnostics".to_string(),
                password: "bounded-secret".to_string(),
            },
        };
        let matching = TransportConfig::Socks5 {
            host: route.host.clone(),
            port: route.port,
            credentials: Socks5Credentials::new("diagnostics", "bounded-secret"),
        };
        let direct = TransportConfig::Direct { route_experiment: None };
        let missing_auth = TransportConfig::Socks5 { host: route.host.clone(), port: route.port, credentials: None };

        assert!(authenticated_in_path_transport_matches(&route, &matching));
        assert!(!authenticated_in_path_transport_matches(&route, &direct));
        assert!(!authenticated_in_path_transport_matches(&route, &missing_auth));
    }
}
