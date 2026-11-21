use crate::candidates::{
    build_strategy_probe_summary, probe_fake_ttl_capability, probe_ip_fragmentation_capabilities,
    probe_tcp_fast_open_capability,
};
use crate::engine::runtime::{ExecutionPlan, ExecutionRuntime, authenticated_in_path_transport_matches};
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
    let (planned_tcp, planned_quic) = plan.strategy_candidate_steps();
    let is_partial = runtime.strategy.stage_interrupted
        || runtime.strategy.tcp_candidates.len() < planned_tcp
        || runtime.strategy.quic_candidates.len() < planned_quic;
    if runtime.strategy.tcp_candidates.is_empty()
        && runtime.strategy.quic_candidates.is_empty()
        && !runtime.strategy.stage_interrupted
    {
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
    let tcp_winner = wi_tcp.map(|index| &runtime.strategy.tcp_candidates[index]);
    let quic_winner = wi_quic.map(|index| &runtime.strategy.quic_candidates[index]);
    let recommendation = (tcp_winner.is_some() || quic_winner.is_some()).then(|| {
        let tcp = tcp_winner.and_then(|winner| {
            strategy_plan.suite.tcp_candidates.iter().find(|spec| spec.id == winner.id).map(|spec| (winner, spec))
        });
        let quic = quic_winner.and_then(|winner| {
            strategy_plan.suite.quic_candidates.iter().find(|spec| spec.id == winner.id).map(|spec| (winner, spec))
        });
        let config_json = resolve_recommended_proxy_config_json(&strategy_plan.base_config, tcp, quic)?;
        let confirm_good_corroborated = plan.request.confirm_good_dpi_evidence.is_some() && quic_winner.is_some();
        let labels = tcp_winner.into_iter().chain(quic_winner).map(|winner| winner.label.as_str()).collect::<Vec<_>>();
        Some(StrategyProbeRecommendation {
            tcp_candidate_id: tcp_winner.map(|winner| winner.id.clone()),
            tcp_candidate_label: tcp_winner.map(|winner| winner.label.clone()),
            quic_candidate_id: quic_winner.map(|winner| winner.id.clone()),
            quic_candidate_label: quic_winner.map(|winner| winner.label.clone()),
            quic_candidate_layout_family: quic_winner.and_then(|winner| winner.quic_layout_family.clone()),
            rationale: if confirm_good_corroborated {
                "Reality application data stalled after successful handshakes; a QUIC Initial response supports testing another transport family".to_string()
            } else {
                format!("Recommended measured lanes: {}", labels.join(" + "))
            },
            recommended_proxy_config_json: config_json,
            transport_pivot: confirm_good_corroborated.then(|| TransportPivotRecommendation {
                reason_code: "confirm_good_dpi_suspected".to_string(),
                preferred_family: TransportFamily::UdpQuic,
                viability: TransportPivotViability::Unknown,
                selected_relay_role: None,
            }),
        })
    }).flatten();
    let has_dns_fallback = runtime.strategy.dns_override_domain_targets.is_some();
    let is_dns_tampered = has_dns_fallback
        && runtime
            .results
            .iter()
            .any(|result| result.probe_type == "dns_integrity" && is_confirmed_dns_tampering_outcome(&result.outcome));
    let audit_assessment = resolve_strategy_probe_audit_assessment(
        &strategy_plan.suite_id,
        &runtime.strategy.tcp_candidates,
        &runtime.strategy.quic_candidates,
        recommendation.as_ref(),
        planned_tcp,
        planned_quic,
        is_dns_tampered,
    );
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
    runtime.strategy.strategy_probe_report = Some(StrategyProbeReport {
        suite_id: strategy_plan.suite_id.clone(),
        methodology_version: STRATEGY_PROBE_METHODOLOGY_VERSION.to_string(),
        tcp_candidates: runtime.strategy.tcp_candidates.clone(),
        quic_candidates: runtime.strategy.quic_candidates.clone(),
        recommendation,
        completion_kind: match () {
            _ if is_partial => StrategyProbeCompletionKind::PartialResults,
            _ if is_dns_tampered => StrategyProbeCompletionKind::DnsTamperingWithFallback,
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

fn is_confirmed_dns_tampering_outcome(outcome: &str) -> bool {
    matches!(outcome, "dns_nxdomain_mismatch" | "dns_sinkhole_substitution")
}

fn active_path_evidence(plan: &ExecutionPlan, runtime: &ExecutionRuntime) -> Option<StrategyActivePathObservation> {
    let route = plan.request.in_path_route.as_ref()?;
    let authenticated_route_matches = authenticated_in_path_transport_matches(route, &plan.transport);
    classify_active_path_evidence(&plan.request.path_mode, authenticated_route_matches, &runtime.results)
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
        let transfer_stage = result
            .details
            .iter()
            .find(|detail| detail.key == "activeControlTransferStage")
            .filter(|_| result.details.iter().any(|detail| detail.key == "isControl" && detail.value == "true"))
            .map(|detail| detail.value.as_str());
        let route_reached = result.details.iter().any(|detail| detail.key == "connectedIp")
            || matches!(transfer_stage, Some("tls" | "http_headers" | "first_body_byte" | "body"))
            || matches!(
                outcome,
                "tls_ok" | "tls_version_split" | "http_ok" | "tls_ech_only" | "tls_cert_invalid" | "http_blockpage"
            );
        route_reached_targets += usize::from(route_reached);
        match (outcome, transfer_stage) {
            ("tls_ok" | "tls_version_split" | "http_ok", _) | (_, Some("body")) => {
                response_observed_targets += 1;
                successful_targets += 1;
            }
            ("tls_ech_only" | "tls_cert_invalid" | "http_blockpage", _)
            | (_, Some("tls" | "http_headers" | "first_body_byte")) => response_observed_targets += 1,
            ("unreachable", _) => response_not_observed_targets += 1,
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
    use crate::transport::{Socks5Credentials, TransportConfig};

    #[test]
    fn control_transfer_can_confirm_a_response_even_if_the_separate_reachability_probe_failed() {
        for (stage, expected_successes) in [("http_headers", 0), ("body", 1)] {
            let result = ProbeResult {
                probe_type: "domain_reachability".into(),
                target: "control.example".into(),
                outcome: "unreachable".into(),
                details: [("isControl", "true"), ("activeControlTransferStage", stage)]
                    .into_iter()
                    .map(|(key, value)| crate::types::ProbeDetail { key: key.into(), value: value.into() })
                    .collect(),
            };
            let evidence = classify_active_path_evidence(&ScanPathMode::InPath, true, &[result]).unwrap();
            assert_eq!(evidence.response_stage, StrategyProbeResponseStage::ResponseObserved);
            assert_eq!(evidence.route_reached_targets, 1);
            assert_eq!(evidence.successful_targets, expected_successes);
        }
    }

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

    #[test]
    fn system_resolver_failure_is_not_confirmed_dns_tampering() {
        assert!(is_confirmed_dns_tampering_outcome("dns_nxdomain_mismatch"));
        assert!(is_confirmed_dns_tampering_outcome("dns_sinkhole_substitution"));
        assert!(!is_confirmed_dns_tampering_outcome("dns_system_resolution_failed"));
        assert!(!is_confirmed_dns_tampering_outcome("dns_compatible_divergence"));
    }
}
