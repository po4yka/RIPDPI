use std::net::SocketAddr;

use ripdpi_config::{QuicFakeProfile, RuntimeConfig};
use ripdpi_desync::{AdaptivePlannerHints, AdaptiveUdpBurstProfile};
use ripdpi_packets::{is_quic_initial, parse_quic_initial, tls_marker_info};
use ripdpi_proxy_config::{ProxyDirectPathCapability, ProxyRuntimeContext};
use ripdpi_runtime_policy::direct_path_learning::direct_path_ip_set_digest;
use ripdpi_runtime_policy::runtime_policy::{is_tls_client_hello_payload, TransportProtocol};
use ripdpi_runtime_strategy::strategy_evolver::{
    CapabilityContext, LearningAlpnClass, LearningContext, LearningHostingFamily, LearningReachabilitySet,
    LearningTargetBucket, LearningTransportKind, ResolverHealthClass,
};

pub fn network_scope_key(config: &RuntimeConfig) -> Option<&str> {
    config.adaptive.network_scope_key.as_deref().map(str::trim).filter(|value| !value.is_empty())
}

pub fn tcp_learning_context(
    config: &RuntimeConfig,
    runtime_context: Option<&ProxyRuntimeContext>,
    target: SocketAddr,
    host: Option<&str>,
    payload: &[u8],
) -> LearningContext {
    let capability = direct_path_capability_for_route(runtime_context, host, target);
    let is_tls = is_tls_client_hello_payload(payload);
    let has_ech = is_tls && tls_marker_info(payload).and_then(|markers| markers.ech_ext_start).is_some();
    LearningContext {
        network_identity: network_scope_key(config).map(ToOwned::to_owned),
        target_bucket: if host == Some("control") {
            LearningTargetBucket::Control
        } else if has_ech {
            LearningTargetBucket::Ech
        } else if is_tls {
            LearningTargetBucket::Tls
        } else {
            LearningTargetBucket::Generic
        },
        transport: LearningTransportKind::Tcp,
        alpn_class: if is_tls { LearningAlpnClass::H2Http11 } else { LearningAlpnClass::Unknown },
        hosting_family: hosting_family_context(host),
        reachability_set: reachability_set_context(host),
        ech_capable: has_ech,
        resolver_health: resolver_health_context(runtime_context),
        rooted: config.process.root_mode,
        capability_context: capability_context(capability),
        environment: config.process.environment_kind,
    }
}

pub fn udp_learning_context(
    config: &RuntimeConfig,
    runtime_context: Option<&ProxyRuntimeContext>,
    target: SocketAddr,
    host: Option<&str>,
    payload: &[u8],
) -> LearningContext {
    let capability = direct_path_capability_for_route(runtime_context, host, target);
    let parsed_quic = parse_quic_initial(payload);
    let has_ech = parsed_quic.as_ref().and_then(|info| info.tls_info.ech_ext_start).is_some();
    let is_quic = is_quic_initial(payload);
    LearningContext {
        network_identity: network_scope_key(config).map(ToOwned::to_owned),
        target_bucket: if is_quic {
            if has_ech {
                LearningTargetBucket::Ech
            } else {
                LearningTargetBucket::Quic
            }
        } else {
            LearningTargetBucket::Generic
        },
        transport: if is_quic { LearningTransportKind::UdpQuic } else { LearningTransportKind::Unknown },
        alpn_class: if is_quic { LearningAlpnClass::H3 } else { LearningAlpnClass::Unknown },
        hosting_family: hosting_family_context(host),
        reachability_set: reachability_set_context(host),
        ech_capable: has_ech,
        resolver_health: resolver_health_context(runtime_context),
        rooted: config.process.root_mode,
        capability_context: capability_context(capability),
        environment: config.process.environment_kind,
    }
}

pub fn direct_path_capability_for_route<'a>(
    runtime_context: Option<&'a ProxyRuntimeContext>,
    host: Option<&str>,
    target: SocketAddr,
) -> Option<&'a ProxyDirectPathCapability> {
    let capabilities = runtime_context?.direct_path_capabilities.as_slice();
    let candidates = direct_path_authority_candidates(host, target);
    capabilities.iter().find(|capability| candidates.contains(&capability.authority))
}

pub fn direct_path_capability_for_targets<'a>(
    runtime_context: Option<&'a ProxyRuntimeContext>,
    host: Option<&str>,
    targets: &[SocketAddr],
) -> Option<&'a ProxyDirectPathCapability> {
    let capabilities = runtime_context?.direct_path_capabilities.as_slice();
    let candidates = direct_path_authority_candidates_for_targets(host, targets);
    let ip_set_digest = direct_path_ip_set_digest(targets);
    capabilities.iter().find(|capability| {
        candidates.contains(&capability.authority)
            && (capability.ip_set_digest.trim().is_empty() || capability.ip_set_digest == ip_set_digest)
    })
}

pub fn capability_requires_desync_fallback(capability: &ProxyDirectPathCapability) -> bool {
    capability.fallback_required == Some(true)
        || capability.repeated_handshake_failure_class.as_deref().is_some_and(|value| !value.trim().is_empty())
        || (matches!(capability.quic_mode.trim().to_ascii_uppercase().as_str(), "SOFT_DISABLE" | "HARD_DISABLE")
            && !capability_preserves_udp_transport(capability))
        || matches!(capability.outcome.trim().to_ascii_uppercase().as_str(), "OWNED_STACK_ONLY" | "NO_DIRECT_SOLUTION")
}

pub fn capability_preserves_udp_transport(capability: &ProxyDirectPathCapability) -> bool {
    capability.reason_code.as_deref() == Some("NO_TCP_FALLBACK")
}

pub fn capability_udp_clean(capability: &ProxyDirectPathCapability) -> bool {
    if capability_preserves_udp_transport(capability) {
        return true;
    }
    capability.udp_usable != Some(false)
        && capability.quic_usable != Some(false)
        && !matches!(capability.quic_mode.trim().to_ascii_uppercase().as_str(), "SOFT_DISABLE" | "HARD_DISABLE")
}

pub fn capability_blocks_transport(
    capability: &ProxyDirectPathCapability,
    transport: TransportProtocol,
    now_millis: i64,
) -> bool {
    let cooldown_active = capability.cooldown_until.is_some_and(|value| value > now_millis);
    let outcome = capability.outcome.trim().to_ascii_uppercase();
    if outcome == "OWNED_STACK_ONLY" {
        return true;
    }
    if outcome == "NO_DIRECT_SOLUTION" && cooldown_active {
        return true;
    }
    match transport {
        TransportProtocol::Udp => {
            if capability_preserves_udp_transport(capability) {
                return false;
            }
            matches!(capability.quic_mode.trim().to_ascii_uppercase().as_str(), "SOFT_DISABLE" | "HARD_DISABLE")
        }
        TransportProtocol::Tcp => false,
    }
}

pub fn merge_udp_hints_with_capability(
    mut hints: AdaptivePlannerHints,
    capability: Option<&ProxyDirectPathCapability>,
) -> AdaptivePlannerHints {
    let Some(capability) = capability else {
        return hints;
    };
    if capability_preserves_udp_transport(capability) {
        return hints;
    }
    let should_conservatively_fallback = capability_requires_desync_fallback(capability)
        || capability.udp_usable == Some(false)
        || capability.quic_usable == Some(false);
    if should_conservatively_fallback {
        hints.udp_burst_profile = Some(AdaptiveUdpBurstProfile::Aggressive);
        hints.quic_fake_profile = Some(QuicFakeProfile::CompatDefault);
        return hints;
    }
    if capability.quic_usable == Some(true) {
        hints.udp_burst_profile.get_or_insert(AdaptiveUdpBurstProfile::Conservative);
    }
    hints
}

pub fn hosting_family_context(host: Option<&str>) -> LearningHostingFamily {
    let Some(host) = host.map(str::trim).filter(|value| !value.is_empty()) else {
        return LearningHostingFamily::Unknown;
    };
    let host = host.to_ascii_lowercase();
    if host.ends_with(".workers.dev")
        || host.ends_with(".pages.dev")
        || host.contains("cloudflare")
        || host.ends_with(".cloudflare.com")
    {
        LearningHostingFamily::Cloudflare
    } else if host.ends_with(".google.com")
        || host.ends_with(".googlevideo.com")
        || host.ends_with(".googleapis.com")
        || host.ends_with(".gstatic.com")
        || host.ends_with(".youtube.com")
        || host.ends_with(".ytimg.com")
        || host.ends_with(".1e100.net")
    {
        LearningHostingFamily::Google
    } else if host.ends_with(".yandex.ru")
        || host.ends_with(".yandex.net")
        || host.ends_with(".ya.ru")
        || host.ends_with(".vk.com")
        || host.ends_with(".vk.ru")
        || host.ends_with(".mail.ru")
        || host.ends_with(".ok.ru")
        || host.ends_with(".rutube.ru")
    {
        LearningHostingFamily::DomesticCdn
    } else if host.ends_with(".cdn77.org")
        || host.ends_with(".akamai.net")
        || host.ends_with(".akamaized.net")
        || host.ends_with(".fastly.net")
        || host.ends_with(".cloudfront.net")
        || host.ends_with(".edgekey.net")
        || host.contains("cdn")
    {
        LearningHostingFamily::ForeignCdn
    } else {
        LearningHostingFamily::Direct
    }
}

pub fn reachability_set_context(host: Option<&str>) -> LearningReachabilitySet {
    let Some(host) = host.map(str::trim).filter(|value| !value.is_empty()) else {
        return LearningReachabilitySet::Unknown;
    };
    if host.eq_ignore_ascii_case("control") {
        return LearningReachabilitySet::Control;
    }
    let host = host.to_ascii_lowercase();
    if host.ends_with(".ru") || host.ends_with(".su") || host.ends_with(".xn--p1ai") {
        LearningReachabilitySet::Domestic
    } else {
        LearningReachabilitySet::Foreign
    }
}

fn resolver_health_context(runtime_context: Option<&ProxyRuntimeContext>) -> ResolverHealthClass {
    match runtime_context.and_then(|context| context.encrypted_dns.as_ref()) {
        Some(_) => ResolverHealthClass::Healthy,
        None => ResolverHealthClass::Unknown,
    }
}

fn capability_context(capability: Option<&ProxyDirectPathCapability>) -> CapabilityContext {
    let Some(capability) = capability else {
        return CapabilityContext::Unknown;
    };
    if capability_requires_desync_fallback(capability) {
        CapabilityContext::Degraded
    } else {
        CapabilityContext::Full
    }
}

fn direct_path_authority_candidates(host: Option<&str>, target: SocketAddr) -> Vec<String> {
    let mut candidates = Vec::new();
    if let Some(host) = normalize_authority(host) {
        candidates.push(host.clone());
        candidates.push(format!("{host}:{}", target.port()));
    }
    let target_authority = target.to_string();
    if let Some(target_authority) = normalize_authority(Some(target_authority.as_str())) {
        candidates.push(target_authority);
    }
    let target_ip = target.ip().to_string();
    if let Some(target_ip) = normalize_authority(Some(target_ip.as_str())) {
        candidates.push(target_ip);
    }
    candidates.sort();
    candidates.dedup();
    candidates
}

fn direct_path_authority_candidates_for_targets(host: Option<&str>, targets: &[SocketAddr]) -> Vec<String> {
    let mut candidates = Vec::new();
    if let Some(host) = normalize_authority(host) {
        candidates.push(host.clone());
        for target in targets {
            candidates.push(format!("{host}:{}", target.port()));
        }
    }
    for target in targets {
        let target_authority = target.to_string();
        if let Some(normalized) = normalize_authority(Some(target_authority.as_str())) {
            candidates.push(normalized);
        }
        let target_ip = target.ip().to_string();
        if let Some(normalized) = normalize_authority(Some(target_ip.as_str())) {
            candidates.push(normalized);
        }
    }
    candidates.sort();
    candidates.dedup();
    candidates
}

fn normalize_authority(value: Option<&str>) -> Option<String> {
    value.map(str::trim).map(|entry| entry.trim_end_matches('.').to_ascii_lowercase()).filter(|entry| !entry.is_empty())
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use ripdpi_config::QuicFakeProfile;
    use ripdpi_desync::{AdaptivePlannerHints, AdaptiveUdpBurstProfile};
    use ripdpi_proxy_config::{ProxyDirectPathCapability, ProxyRuntimeContext};
    use ripdpi_runtime_policy::direct_path_learning::direct_path_ip_set_digest;
    use ripdpi_runtime_policy::runtime_policy::TransportProtocol;
    use ripdpi_runtime_strategy::strategy_evolver::{LearningHostingFamily, LearningReachabilitySet};

    use super::*;

    fn capability(authority: &str) -> ProxyDirectPathCapability {
        ProxyDirectPathCapability {
            authority: authority.to_string(),
            quic_usable: None,
            udp_usable: None,
            fallback_required: None,
            repeated_handshake_failure_class: None,
            transport_policy_version: 0,
            ip_set_digest: String::new(),
            dns_classification: None,
            quic_mode: "ALLOW".to_string(),
            preferred_stack: "H3".to_string(),
            dns_mode: "SYSTEM".to_string(),
            tcp_family: "NONE".to_string(),
            outcome: "TRANSPARENT_OK".to_string(),
            transport_class: None,
            reason_code: None,
            cooldown_until: None,
            updated_at: 0,
        }
    }

    #[test]
    fn direct_path_capability_matches_host_and_target_authorities() {
        let runtime_context = ProxyRuntimeContext {
            encrypted_dns: None,
            protect_path: None,
            preferred_edges: BTreeMap::default(),
            direct_path_capabilities: vec![capability("example.org:443"), capability("203.0.113.10:443")],
            morph_policy: None,
        };

        let host_match = direct_path_capability_for_route(
            Some(&runtime_context),
            Some("Example.org"),
            "203.0.113.10:443".parse().expect("target"),
        )
        .expect("host capability");
        let target_match =
            direct_path_capability_for_route(Some(&runtime_context), None, "203.0.113.10:443".parse().expect("target"))
                .expect("target capability");

        assert_eq!(host_match.authority, "example.org:443");
        assert_eq!(target_match.authority, "203.0.113.10:443");
    }

    #[test]
    fn udp_hints_are_hardened_when_capability_requires_fallback() {
        let hints = AdaptivePlannerHints {
            udp_burst_profile: Some(AdaptiveUdpBurstProfile::Conservative),
            ..AdaptivePlannerHints::default()
        };
        let capability = ProxyDirectPathCapability {
            authority: "example.org:443".to_string(),
            quic_usable: Some(false),
            udp_usable: Some(false),
            fallback_required: Some(true),
            repeated_handshake_failure_class: Some("tcp_reset".to_string()),
            transport_policy_version: 0,
            ip_set_digest: String::new(),
            dns_classification: None,
            quic_mode: "SOFT_DISABLE".to_string(),
            preferred_stack: "H2".to_string(),
            dns_mode: "SYSTEM".to_string(),
            tcp_family: "NONE".to_string(),
            outcome: "TRANSPARENT_OK".to_string(),
            transport_class: Some("QUIC_BLOCK_SUSPECT".to_string()),
            reason_code: Some("QUIC_BLOCKED".to_string()),
            cooldown_until: None,
            updated_at: 10,
        };

        let merged = merge_udp_hints_with_capability(hints, Some(&capability));

        assert_eq!(merged.udp_burst_profile, Some(AdaptiveUdpBurstProfile::Aggressive));
        assert_eq!(merged.quic_fake_profile, Some(QuicFakeProfile::CompatDefault));
    }

    #[test]
    fn capability_blocks_udp_for_soft_disable_but_respects_no_tcp_fallback() {
        let mut capability = capability("example.org:443");
        capability.quic_mode = "SOFT_DISABLE".to_string();
        assert!(capability_blocks_transport(&capability, TransportProtocol::Udp, 0));
        assert!(capability_requires_desync_fallback(&capability));
        assert!(!capability_udp_clean(&capability));

        capability.reason_code = Some("NO_TCP_FALLBACK".to_string());
        assert!(!capability_blocks_transport(&capability, TransportProtocol::Udp, 0));
        assert!(!capability_requires_desync_fallback(&capability));
        assert!(capability_udp_clean(&capability));
    }

    #[test]
    fn no_tcp_fallback_keeps_udp_hints_intact() {
        let hints = AdaptivePlannerHints {
            udp_burst_profile: Some(AdaptiveUdpBurstProfile::Conservative),
            quic_fake_profile: Some(QuicFakeProfile::CompatDefault),
            ..AdaptivePlannerHints::default()
        };
        let mut capability = capability("example.org:443");
        capability.quic_mode = "SOFT_DISABLE".to_string();
        capability.reason_code = Some("NO_TCP_FALLBACK".to_string());

        let merged = merge_udp_hints_with_capability(hints, Some(&capability));

        assert_eq!(merged, hints);
    }

    #[test]
    fn capability_blocks_tcp_for_owned_stack_and_active_no_direct_solution() {
        let mut owned_stack = capability("example.org:443");
        owned_stack.outcome = "OWNED_STACK_ONLY".to_string();
        assert!(capability_blocks_transport(&owned_stack, TransportProtocol::Tcp, 0));

        let mut no_direct = capability("example.org:443");
        no_direct.outcome = "NO_DIRECT_SOLUTION".to_string();
        no_direct.cooldown_until = Some(500);
        assert!(capability_blocks_transport(&no_direct, TransportProtocol::Tcp, 100));
        assert!(!capability_blocks_transport(&no_direct, TransportProtocol::Tcp, 1000));
    }

    #[test]
    fn direct_path_capability_matches_targets_with_ip_set_digest() {
        let targets =
            vec!["203.0.113.10:443".parse().expect("first target"), "203.0.113.11:443".parse().expect("second target")];
        let digest = direct_path_ip_set_digest(&targets);
        assert_eq!(digest, "ae7c89389f929dcb");
        let runtime_context = ProxyRuntimeContext {
            encrypted_dns: None,
            protect_path: None,
            preferred_edges: BTreeMap::default(),
            direct_path_capabilities: vec![ProxyDirectPathCapability {
                authority: "example.org:443".to_string(),
                ip_set_digest: digest,
                ..capability("example.org:443")
            }],
            morph_policy: None,
        };

        let matched = direct_path_capability_for_targets(Some(&runtime_context), Some("example.org"), &targets)
            .expect("capability");

        assert_eq!(matched.authority, "example.org:443");
    }

    #[test]
    fn hosting_family_context_identifies_known_cdn_buckets() {
        assert_eq!(hosting_family_context(Some("video.cloudflare.com")), LearningHostingFamily::Cloudflare);
        assert_eq!(hosting_family_context(Some("fonts.gstatic.com")), LearningHostingFamily::Google);
        assert_eq!(hosting_family_context(Some("portal.yandex.ru")), LearningHostingFamily::DomesticCdn);
        assert_eq!(hosting_family_context(Some("assets.fastly.net")), LearningHostingFamily::ForeignCdn);
        assert_eq!(hosting_family_context(Some("origin.example.com")), LearningHostingFamily::Direct);
    }

    #[test]
    fn reachability_set_context_identifies_domestic_and_control_hosts() {
        assert_eq!(reachability_set_context(Some("control")), LearningReachabilitySet::Control);
        assert_eq!(reachability_set_context(Some("service.gov.ru")), LearningReachabilitySet::Domestic);
        assert_eq!(reachability_set_context(Some("example.com")), LearningReachabilitySet::Foreign);
        assert_eq!(reachability_set_context(None), LearningReachabilitySet::Unknown);
    }
}
