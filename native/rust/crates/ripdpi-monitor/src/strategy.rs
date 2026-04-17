use std::net::{IpAddr, SocketAddr};

use ripdpi_failure_classifier::{confirm_dns_tampering, ClassifiedFailure, FailureAction, FailureClass, FailureStage};
use ripdpi_proxy_config::ProxyRuntimeContext;

use crate::candidates::{
    strategy_probe_encrypted_dns_context, strategy_probe_encrypted_dns_endpoint, strategy_probe_encrypted_dns_label,
    StrategyProbeBaseline,
};
use crate::connectivity::{classify_dns_latency_quality, is_dns_injection_suspected};
use crate::dns::{build_fallback_encrypted_dns_endpoints, resolve_via_encrypted_dns};
use crate::dns_oracle::{evaluate_dns_oracles, DnsOracleAssessment};
use crate::transport::{domain_connect_target, resolve_addresses, TargetAddress, TransportConfig};
use crate::types::{DomainTarget, ProbeDetail, ProbeResult};
use crate::util::{classify_dns_answer_overlap, DnsAnswerOverlap};

#[derive(Clone, Debug)]
struct ResolvedStrategyDnsAnswer {
    addresses: Vec<String>,
}

struct StrategyDnsTargetEvaluation {
    result: ProbeResult,
    tampering_detected: bool,
    encrypted_ips: Vec<IpAddr>,
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
    let fallback_endpoints = build_fallback_encrypted_dns_endpoints(resolver_context.resolver_id.as_deref());
    let mut results = Vec::new();
    let mut classified = None;
    let mut encrypted_ip_overrides: Vec<(String, IpAddr)> = Vec::new();

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
        let system_resolution_failed = system_targets.is_empty();
        let oracle_assessment = evaluate_dns_oracles(
            resolver_endpoint.clone(),
            &fallback_endpoints,
            fallback_endpoints.len(),
            |endpoint| {
                resolve_via_encrypted_dns(&target.host, endpoint.clone(), &TransportConfig::Direct)
                    .map(|addresses| ResolvedStrategyDnsAnswer { addresses })
            },
            |answer| answer.addresses.clone(),
        );

        let system_ips = system_targets.iter().map(SocketAddr::ip).collect::<Vec<_>>();
        let Some(evaluation) = evaluate_strategy_dns_target(
            target,
            &resolver_context,
            &resolver_label,
            &system_targets,
            system_resolution_failed,
            &system_latency_ms,
            &oracle_assessment,
        ) else {
            continue;
        };
        let encrypted_ips = evaluation.encrypted_ips;
        let tampering_detected = evaluation.tampering_detected;
        results.push(evaluation.result);
        if tampering_detected {
            // Collect the first encrypted IP as an override for strategy probing.
            if let Some(&override_ip) = encrypted_ips.first() {
                if !encrypted_ip_overrides.iter().any(|(h, _)| h == &target.host) {
                    encrypted_ip_overrides.push((target.host.clone(), override_ip));
                }
            }
            if classified.is_none() {
                if system_resolution_failed {
                    // NXDOMAIN: system says domain doesn't exist, but encrypted resolver proves it does.
                    classified = Some(
                        ClassifiedFailure::new(
                            FailureClass::DnsTampering,
                            FailureStage::Dns,
                            FailureAction::ResolverOverrideRecommended,
                            format!(
                                "System DNS returned NXDOMAIN for {} but encrypted resolver returned valid addresses",
                                target.host
                            ),
                        )
                        .with_tag("host", target.host.clone())
                        .with_tag("targetIp", "nxdomain".to_string())
                        .with_tag(
                            "encryptedAnswers",
                            encrypted_ips.iter().map(ToString::to_string).collect::<Vec<_>>().join("|"),
                        )
                        .with_tag("resolver", resolver_label.clone()),
                    );
                } else {
                    for system_ip in &system_ips {
                        if let Some(failure) =
                            confirm_dns_tampering(&target.host, *system_ip, &encrypted_ips, &resolver_label)
                        {
                            classified = Some(failure);
                            break;
                        }
                    }
                }
            }
        }
    }

    classified.map(|failure| StrategyProbeBaseline { failure, results, encrypted_ip_overrides })
}

fn evaluate_strategy_dns_target(
    target: &DomainTarget,
    resolver_context: &ripdpi_proxy_config::ProxyEncryptedDnsContext,
    resolver_label: &str,
    system_targets: &[SocketAddr],
    system_resolution_failed: bool,
    system_latency_ms: &str,
    oracle_assessment: &DnsOracleAssessment<ResolvedStrategyDnsAnswer>,
) -> Option<StrategyDnsTargetEvaluation> {
    let encrypted_addresses =
        oracle_assessment.selected.as_ref().map(|selected| selected.value.addresses.clone()).unwrap_or_default();
    let encrypted_ips = encrypted_addresses.iter().filter_map(|value| value.parse::<IpAddr>().ok()).collect::<Vec<_>>();

    let (tampering_detected, outcome) = if system_resolution_failed
        && oracle_assessment.trust.allows_tampering_classification()
    {
        (true, "dns_nxdomain_mismatch")
    } else if system_resolution_failed {
        // Both failed or the encrypted oracle was not trusted enough to prove
        // an NXDOMAIN mismatch. Skip to avoid false positives.
        return None;
    } else if !oracle_assessment.trust.allows_tampering_classification() {
        (false, "dns_oracle_unavailable")
    } else if encrypted_ips.is_empty() {
        return None;
    } else {
        let system_ip_strings = system_targets.iter().map(SocketAddr::ip).map(|ip| ip.to_string()).collect::<Vec<_>>();
        let encrypted_ip_strings = encrypted_ips.iter().map(ToString::to_string).collect::<Vec<_>>();
        match classify_dns_answer_overlap(&system_ip_strings, &encrypted_ip_strings) {
            DnsAnswerOverlap::Match => (false, "dns_match"),
            DnsAnswerOverlap::CompatibleDivergence => {
                if system_latency_ms.parse::<u64>().unwrap_or(u64::MAX) <= 5 {
                    (false, "dns_suspicious_divergence")
                } else {
                    (false, "dns_compatible_divergence")
                }
            }
            DnsAnswerOverlap::SinkholeSubstitution => (true, "dns_sinkhole_substitution"),
        }
    };

    let encrypted_latency_ms = oracle_assessment.preferred_latency_ms().to_string();
    let encrypted_addresses_detail = if encrypted_addresses.is_empty() {
        "dns_oracle_unavailable".to_string()
    } else {
        encrypted_addresses.join("|")
    };

    let mut result = ProbeResult {
        probe_type: "dns_integrity".to_string(),
        target: target.host.clone(),
        outcome: outcome.to_string(),
        details: vec![
            ProbeDetail {
                key: "udpAddresses".to_string(),
                value: if system_resolution_failed {
                    "nxdomain".to_string()
                } else {
                    system_targets.iter().map(ToString::to_string).collect::<Vec<_>>().join("|")
                },
            },
            ProbeDetail { key: "udpLatencyMs".to_string(), value: system_latency_ms.to_string() },
            ProbeDetail {
                key: "encryptedResolverId".to_string(),
                value: resolver_context.resolver_id.clone().unwrap_or_default(),
            },
            ProbeDetail { key: "encryptedProtocol".to_string(), value: resolver_context.protocol.clone() },
            ProbeDetail { key: "encryptedEndpoint".to_string(), value: resolver_label.to_string() },
            ProbeDetail { key: "encryptedHost".to_string(), value: resolver_context.host.clone() },
            ProbeDetail { key: "encryptedPort".to_string(), value: resolver_context.port.to_string() },
            ProbeDetail {
                key: "encryptedTlsServerName".to_string(),
                value: resolver_context.tls_server_name.clone().unwrap_or_default(),
            },
            ProbeDetail { key: "encryptedBootstrapIps".to_string(), value: resolver_context.bootstrap_ips.join("|") },
            ProbeDetail {
                key: "encryptedBootstrapValidated".to_string(),
                value: (oracle_assessment.selected.is_some() && !resolver_context.bootstrap_ips.is_empty()).to_string(),
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
            ProbeDetail { key: "encryptedAddresses".to_string(), value: encrypted_addresses_detail },
            ProbeDetail { key: "encryptedLatencyMs".to_string(), value: encrypted_latency_ms.clone() },
            ProbeDetail {
                key: "dnsLatencyQuality".to_string(),
                value: classify_dns_latency_quality(system_latency_ms, encrypted_latency_ms.as_str()),
            },
            ProbeDetail {
                key: "dnsInjectionSuspected".to_string(),
                value: is_dns_injection_suspected(system_latency_ms, outcome).to_string(),
            },
            ProbeDetail {
                key: "resolverFallbackUsed".to_string(),
                value: oracle_assessment.fallback_resolver_used().unwrap_or_default(),
            },
        ],
    };
    result.details.extend(oracle_assessment.detail_entries());

    Some(StrategyDnsTargetEvaluation { result, tampering_detected, encrypted_ips })
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;
    use std::net::{IpAddr, Ipv4Addr, SocketAddr};

    use ripdpi_dns_resolver::{EncryptedDnsEndpoint, EncryptedDnsProtocol};
    use ripdpi_proxy_config::ProxyEncryptedDnsContext;

    use crate::dns_oracle::evaluate_dns_oracles;
    use crate::types::DomainTarget;

    use super::{evaluate_strategy_dns_target, ResolvedStrategyDnsAnswer};

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
        }
    }

    #[test]
    fn strategy_baseline_skips_single_fallback_oracle_without_classifying_tampering() {
        let answers = BTreeMap::from([
            ("primary".to_string(), Err("connection reset".to_string())),
            ("fallback".to_string(), Ok(ResolvedStrategyDnsAnswer { addresses: vec!["198.51.100.77".to_string()] })),
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

        let evaluation = evaluate_strategy_dns_target(
            &target(),
            &resolver_context(),
            "primary",
            &[SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 10)), 443)],
            false,
            "20",
            &assessment,
        )
        .expect("probe result");

        assert_eq!(evaluation.result.outcome, "dns_oracle_unavailable");
        assert!(!evaluation.tampering_detected);
    }

    #[test]
    fn strategy_baseline_allows_trusted_oracle_agreement_to_confirm_tampering() {
        let answers = BTreeMap::from([
            ("primary".to_string(), Ok(ResolvedStrategyDnsAnswer { addresses: vec!["198.51.100.77".to_string()] })),
            ("fallback".to_string(), Ok(ResolvedStrategyDnsAnswer { addresses: vec!["198.51.100.77".to_string()] })),
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

        let evaluation = evaluate_strategy_dns_target(
            &target(),
            &resolver_context(),
            "primary",
            &[SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 10)), 443)],
            false,
            "20",
            &assessment,
        )
        .expect("probe result");

        assert_eq!(evaluation.result.outcome, "dns_sinkhole_substitution");
        assert!(evaluation.tampering_detected);
        assert_eq!(evaluation.encrypted_ips, vec![IpAddr::V4(Ipv4Addr::new(198, 51, 100, 77))]);
    }
}
