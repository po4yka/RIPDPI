use std::net::IpAddr;

use ripdpi_proxy_config::ProxyRuntimeContext;

pub(crate) mod adapters;
mod classification;
mod details;
mod failure;
mod overrides;
mod resolution;

use self::adapters::candidates::{StrategyProbeBaseline, strategy_probe_encrypted_dns_label};
use self::adapters::dns_oracle::{DnsOracleAssessment, DnsOracleResponse};
use crate::probe_context::ProbeExecutionContext;
use crate::types::{DomainTarget, ProbeDetail, ProbeResult};

use self::classification::classify_target_dns_integrity;
use self::details::build_dns_integrity_result;
use self::failure::build_strategy_dns_failure;
use self::overrides::collect_encrypted_ip_override;
use self::resolution::{
    SystemDnsResolution, fallback_encrypted_dns_assessment, resolve_system_target, should_skip_strategy_dns_target,
};

struct StrategyDnsTargetEvaluation {
    result: ProbeResult,
    resolver_override_needed: bool,
    encrypted_ips: Vec<std::net::IpAddr>,
}

pub fn detect_strategy_probe_dns_tampering(
    targets: &[DomainTarget],
    runtime_context: Option<&ProxyRuntimeContext>,
) -> Option<StrategyProbeBaseline> {
    let probe_context = ProbeExecutionContext::from_runtime_context(
        ripdpi_diagnostics_transport::transport::direct_transport(),
        runtime_context,
    )
    .ok()?;
    detect_strategy_probe_dns_tampering_with_context(targets, runtime_context, &probe_context)
}

pub fn detect_strategy_probe_dns_tampering_with_context(
    targets: &[DomainTarget],
    runtime_context: Option<&ProxyRuntimeContext>,
    probe_context: &ProbeExecutionContext,
) -> Option<StrategyProbeBaseline> {
    detect_strategy_probe_dns_tampering_with_context_and_cancellation(targets, runtime_context, probe_context, || false)
}

pub fn detect_strategy_probe_dns_tampering_with_context_and_cancellation(
    targets: &[DomainTarget],
    runtime_context: Option<&ProxyRuntimeContext>,
    probe_context: &ProbeExecutionContext,
    is_cancelled: impl Fn() -> bool,
) -> Option<StrategyProbeBaseline> {
    if targets.is_empty() {
        return None;
    }

    let (resolver_context, resolver_endpoint) = probe_context.strategy_resolver_context(runtime_context).ok()?;
    let resolver_label = strategy_probe_encrypted_dns_label(&resolver_context);
    let mut results = Vec::new();
    let mut classified = None;
    let mut encrypted_ip_overrides: Vec<(String, IpAddr)> = Vec::new();

    for target in targets {
        if is_cancelled() {
            break;
        }
        if should_skip_strategy_dns_target(target) {
            results.push(ProbeResult {
                probe_type: "dns_integrity".to_string(),
                target: target.host.clone(),
                outcome: "not_applicable".to_string(),
                details: vec![ProbeDetail {
                    key: "reason".to_string(),
                    value: "No system DNS comparison for a numeric, local, or pinned target".to_string(),
                }],
            });
            continue;
        }
        let system_resolution = resolve_system_target(target, &is_cancelled);
        if is_cancelled() {
            break;
        }
        let oracle_assessment = fallback_encrypted_dns_assessment(
            target,
            resolver_endpoint.clone(),
            resolver_context.resolver_id.as_deref(),
            probe_context,
            &is_cancelled,
        );
        if is_cancelled() {
            break;
        }

        let evaluation = evaluate_strategy_dns_target(
            target,
            &resolver_context,
            &resolver_label,
            &system_resolution,
            &oracle_assessment,
        );
        let encrypted_ips = evaluation.encrypted_ips;
        let resolver_override_needed = evaluation.resolver_override_needed;
        results.push(evaluation.result);
        if resolver_override_needed {
            collect_encrypted_ip_override(&mut encrypted_ip_overrides, target, &encrypted_ips);
            if classified.is_none() {
                classified = build_strategy_dns_failure(
                    target,
                    &system_resolution.targets,
                    system_resolution.failed(),
                    system_resolution.error_kind,
                    &encrypted_ips,
                    &resolver_label,
                );
            }
        }
    }

    // Keep the collected DNS-integrity evidence even on a clean network so the
    // baseline stage still records its results and progress step.
    if results.is_empty() {
        return None;
    }
    Some(StrategyProbeBaseline { failure: classified, results, encrypted_ip_overrides })
}

fn evaluate_strategy_dns_target(
    target: &DomainTarget,
    resolver_context: &ripdpi_proxy_config::ProxyEncryptedDnsContext,
    resolver_label: &str,
    system_resolution: &SystemDnsResolution,
    oracle_assessment: &DnsOracleAssessment<DnsOracleResponse>,
) -> StrategyDnsTargetEvaluation {
    let classification = classify_target_dns_integrity(
        &system_resolution.targets,
        system_resolution.failed(),
        system_resolution.error_kind,
        oracle_assessment,
    );
    let result = build_dns_integrity_result(
        target,
        resolver_context,
        resolver_label,
        system_resolution,
        oracle_assessment,
        &classification,
    );

    StrategyDnsTargetEvaluation {
        result,
        resolver_override_needed: classification.resolver_override_needed,
        encrypted_ips: classification.encrypted_ips,
    }
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;
    use std::net::{IpAddr, Ipv4Addr, SocketAddr};

    use ripdpi_dns_resolver::{EncryptedDnsEndpoint, EncryptedDnsProtocol};
    use ripdpi_proxy_config::ProxyEncryptedDnsContext;

    use crate::strategy::adapters::dns_oracle::{DnsOracleConfig, DnsOracleResponse, evaluate_dns_oracles};
    use crate::types::DomainTarget;

    use super::resolution::SystemDnsResolution;
    use super::{
        ProbeExecutionContext, detect_strategy_probe_dns_tampering_with_context_and_cancellation,
        evaluate_strategy_dns_target,
    };

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
            odoh: None,
        }
    }

    fn resolver_context() -> ProxyEncryptedDnsContext {
        ProxyEncryptedDnsContext {
            resolver_id: Some("primary".to_string()),
            protocol: "doh".to_string(),
            host: "primary.example".to_string(),
            port: 443,
            tls_server_name: None,
            bootstrap_ips: vec!["1.1.1.1".to_string()],
            doh_url: Some("https://primary.example/dns-query".to_string()),
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        }
    }

    fn target() -> DomainTarget {
        DomainTarget {
            host: "blocked.example".to_string(),
            connect_ip: None,
            connect_ips: Vec::new(),
            https_port: Some(443),
            http_port: Some(80),
            http_path: "/".to_string(),
            is_control: false,
            concurrency_probe: None,
        }
    }

    #[test]
    fn cancelled_strategy_dns_does_not_start_system_resolution() {
        let context = ProbeExecutionContext::new(ripdpi_diagnostics_transport::transport::direct_transport());
        let baseline = detect_strategy_probe_dns_tampering_with_context_and_cancellation(
            &[target(), target()],
            None,
            &context,
            || true,
        );

        assert!(baseline.is_none());
    }

    #[test]
    fn strategy_baseline_skips_single_fallback_oracle_without_classifying_tampering() {
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
            DnsOracleConfig::default(),
            || false,
            |endpoint, _| {
                answers
                    .get(endpoint.resolver_id.as_deref().unwrap_or_default())
                    .cloned()
                    .unwrap_or_else(|| Err("missing".to_string()))
            },
            |answer| answer.addresses.clone(),
        );

        let evaluation = evaluate_strategy_dns_target(
            &target(),
            &resolver_context(),
            "primary",
            &SystemDnsResolution {
                targets: vec![SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 10)), 443)],
                latency_ms: "20".to_string(),
                error_kind: None,
            },
            &assessment,
        );

        assert_eq!(evaluation.result.outcome, "dns_oracle_unavailable");
        assert!(!evaluation.resolver_override_needed);
    }

    #[test]
    fn strategy_baseline_allows_trusted_oracle_agreement_to_confirm_tampering() {
        let answers = BTreeMap::from([
            (
                "primary".to_string(),
                Ok(DnsOracleResponse { addresses: vec!["198.51.100.77".to_string()], raw_response: None }),
            ),
            (
                "fallback".to_string(),
                Ok(DnsOracleResponse { addresses: vec!["198.51.100.77".to_string()], raw_response: None }),
            ),
        ]);
        let assessment = evaluate_dns_oracles(
            endpoint("primary"),
            &[endpoint("fallback")],
            1,
            DnsOracleConfig::default(),
            || false,
            |endpoint, _| {
                answers
                    .get(endpoint.resolver_id.as_deref().unwrap_or_default())
                    .cloned()
                    .unwrap_or_else(|| Err("missing".to_string()))
            },
            |answer| answer.addresses.clone(),
        );

        let evaluation = evaluate_strategy_dns_target(
            &target(),
            &resolver_context(),
            "primary",
            &SystemDnsResolution {
                targets: vec![SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 10)), 443)],
                latency_ms: "20".to_string(),
                error_kind: None,
            },
            &assessment,
        );

        assert_eq!(evaluation.result.outcome, "dns_sinkhole_substitution");
        assert!(evaluation.resolver_override_needed);
        assert_eq!(evaluation.encrypted_ips, vec![IpAddr::V4(Ipv4Addr::new(198, 51, 100, 77))]);
    }

    #[test]
    fn strategy_baseline_preserves_system_dns_timeout_without_claiming_nxdomain() {
        let answers = BTreeMap::from([
            (
                "primary".to_string(),
                Ok(DnsOracleResponse { addresses: vec!["198.51.100.77".to_string()], raw_response: None }),
            ),
            (
                "fallback".to_string(),
                Ok(DnsOracleResponse { addresses: vec!["198.51.100.77".to_string()], raw_response: None }),
            ),
        ]);
        let assessment = evaluate_dns_oracles(
            endpoint("primary"),
            &[endpoint("fallback")],
            1,
            DnsOracleConfig::default(),
            || false,
            |endpoint, _| {
                answers
                    .get(endpoint.resolver_id.as_deref().unwrap_or_default())
                    .cloned()
                    .unwrap_or_else(|| Err("missing".to_string()))
            },
            |answer| answer.addresses.clone(),
        );

        let evaluation = evaluate_strategy_dns_target(
            &target(),
            &resolver_context(),
            "primary",
            &SystemDnsResolution {
                targets: Vec::new(),
                latency_ms: "5000".to_string(),
                error_kind: Some(crate::strategy::adapters::transport::DnsResolveError::Timeout),
            },
            &assessment,
        );

        let details = evaluation
            .result
            .details
            .iter()
            .map(|detail| (detail.key.as_str(), detail.value.as_str()))
            .collect::<BTreeMap<_, _>>();

        assert_eq!(evaluation.result.outcome, "dns_system_resolution_failed");
        assert!(evaluation.resolver_override_needed);
        assert_eq!(details.get("systemDnsErrorKind"), Some(&"dns_resolve_timeout"));
        assert_eq!(details.get("udpAddresses"), Some(&""));
    }

    #[test]
    fn strategy_baseline_records_empty_oracle_answers_as_inconclusive() {
        let answers = BTreeMap::from([
            ("primary".to_string(), Ok(DnsOracleResponse { addresses: Vec::new(), raw_response: None })),
            ("fallback".to_string(), Ok(DnsOracleResponse { addresses: Vec::new(), raw_response: None })),
        ]);
        let assessment = evaluate_dns_oracles(
            endpoint("primary"),
            &[endpoint("fallback")],
            1,
            DnsOracleConfig::default(),
            || false,
            |endpoint, _| {
                answers
                    .get(endpoint.resolver_id.as_deref().unwrap_or_default())
                    .cloned()
                    .unwrap_or_else(|| Err("missing".to_string()))
            },
            |answer| answer.addresses.clone(),
        );

        let evaluation = evaluate_strategy_dns_target(
            &target(),
            &resolver_context(),
            "primary",
            &SystemDnsResolution {
                targets: Vec::new(),
                latency_ms: "5000".to_string(),
                error_kind: Some(crate::strategy::adapters::transport::DnsResolveError::Timeout),
            },
            &assessment,
        );

        assert_eq!(evaluation.result.outcome, "dns_oracle_unavailable");
        assert!(!evaluation.resolver_override_needed);
    }
}
