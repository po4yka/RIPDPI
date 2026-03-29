use std::net::{IpAddr, SocketAddr};

use ripdpi_failure_classifier::{confirm_dns_tampering, ClassifiedFailure, FailureAction, FailureClass, FailureStage};
use ripdpi_proxy_config::ProxyRuntimeContext;

use crate::candidates::{
    strategy_probe_encrypted_dns_context, strategy_probe_encrypted_dns_endpoint, strategy_probe_encrypted_dns_label,
    StrategyProbeBaseline,
};
use crate::connectivity::classify_dns_latency_quality;
use crate::dns::resolve_via_encrypted_dns;
use crate::transport::{domain_connect_target, resolve_addresses, TargetAddress, TransportConfig};
use crate::types::{DomainTarget, ProbeDetail, ProbeResult};

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
        let system_resolution_failed = system_targets.is_empty();

        let encrypted_started = std::time::Instant::now();
        let encrypted_result =
            resolve_via_encrypted_dns(&target.host, resolver_endpoint.clone(), &TransportConfig::Direct);
        let encrypted_latency_ms = encrypted_started.elapsed().as_millis().to_string();
        let encrypted_addresses =
            encrypted_result.as_ref().ok().into_iter().flat_map(|value| value.iter()).cloned().collect::<Vec<_>>();
        let encrypted_ips =
            encrypted_addresses.iter().filter_map(|value| value.parse::<IpAddr>().ok()).collect::<Vec<_>>();
        let system_ips = system_targets.iter().map(SocketAddr::ip).collect::<Vec<_>>();

        // DNS record deletion (NXDOMAIN): system returns nothing but encrypted resolves fine.
        // DNS substitution: system returns IPs that don't match encrypted answers.
        let (tampering_detected, outcome) = if system_resolution_failed && !encrypted_ips.is_empty() {
            (true, "dns_nxdomain")
        } else if system_resolution_failed {
            // Both failed or encrypted also empty -- skip this target.
            continue;
        } else if system_ips.iter().all(|ip| !encrypted_ips.iter().any(|answer| answer == ip)) {
            (true, "dns_substitution")
        } else {
            (false, "dns_match")
        };

        results.push(ProbeResult {
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
                ProbeDetail { key: "udpLatencyMs".to_string(), value: system_latency_ms.clone() },
                ProbeDetail {
                    key: "encryptedResolverId".to_string(),
                    value: resolver_context.resolver_id.clone().unwrap_or_default(),
                },
                ProbeDetail { key: "encryptedProtocol".to_string(), value: resolver_context.protocol.clone() },
                ProbeDetail { key: "encryptedEndpoint".to_string(), value: resolver_label.clone() },
                ProbeDetail { key: "encryptedHost".to_string(), value: resolver_context.host.clone() },
                ProbeDetail { key: "encryptedPort".to_string(), value: resolver_context.port.to_string() },
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
                ProbeDetail { key: "encryptedLatencyMs".to_string(), value: encrypted_latency_ms.clone() },
                ProbeDetail {
                    key: "dnsLatencyQuality".to_string(),
                    value: classify_dns_latency_quality(&system_latency_ms, &encrypted_latency_ms),
                },
            ],
        });
        if tampering_detected && classified.is_none() {
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

    classified.map(|failure| StrategyProbeBaseline { failure, results })
}
