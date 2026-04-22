// Adaptive hint resolution for the runtime send path.
//
// Hint priority chain (highest to lowest):
//
// 1. Strategy evolver hints (`StrategyEvolver::suggest_hints`) -- session-wide,
//    when `config.adaptive.strategy_evolution` is enabled. Overrides per-flow
//    tuning for every dimension the evolver sets.
// 2. Per-flow adaptive hints (`AdaptivePlannerResolver::resolve_*_hints`) --
//    per (host, group, flow-kind) tuple. Used when the evolver is disabled or
//    returns `None`.
// 3. Group defaults -- static values from the `DesyncGroup` configuration.

use std::io;
use std::net::SocketAddr;

use ripdpi_config::{DesyncGroup, QuicFakeProfile, RuntimeConfig};
use ripdpi_desync::{AdaptivePlannerHints, AdaptiveUdpBurstProfile};
use ripdpi_packets::{is_quic_initial, parse_quic_initial, tls_marker_info};
use ripdpi_proxy_config::{ProxyDirectPathCapability, ProxyRuntimeContext};

use super::morph::{apply_tcp_morph_policy_to_hints, apply_udp_morph_policy_to_hints, emit_morph_rollback};
use super::state::RuntimeState;
use crate::runtime_policy::is_tls_client_hello_payload;
use crate::strategy_evolver::{
    CapabilityContext, LearningAlpnClass, LearningContext, LearningHostingFamily, LearningReachabilitySet,
    LearningTargetBucket, LearningTransportKind, ResolverHealthClass,
};

pub(super) fn network_scope_key(config: &RuntimeConfig) -> Option<&str> {
    config.adaptive.network_scope_key.as_deref().map(str::trim).filter(|value| !value.is_empty())
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

fn hosting_family_context(host: Option<&str>) -> LearningHostingFamily {
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

fn reachability_set_context(host: Option<&str>) -> LearningReachabilitySet {
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

fn tcp_learning_context(
    state: &RuntimeState,
    target: SocketAddr,
    host: Option<&str>,
    payload: &[u8],
) -> LearningContext {
    let capability = direct_path_capability_for_route(state.runtime_context.as_ref(), host, target);
    let is_tls = is_tls_client_hello_payload(payload);
    let has_ech = is_tls && tls_marker_info(payload).and_then(|markers| markers.ech_ext_start).is_some();
    LearningContext {
        network_identity: network_scope_key(&state.config).map(ToOwned::to_owned),
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
        resolver_health: resolver_health_context(state.runtime_context.as_ref()),
        rooted: state.config.process.root_mode,
        capability_context: capability_context(capability),
    }
}

fn udp_learning_context(
    state: &RuntimeState,
    target: SocketAddr,
    host: Option<&str>,
    payload: &[u8],
) -> LearningContext {
    let capability = direct_path_capability_for_route(state.runtime_context.as_ref(), host, target);
    let parsed_quic = parse_quic_initial(payload);
    let has_ech = parsed_quic.as_ref().and_then(|info| info.tls_info.ech_ext_start).is_some();
    LearningContext {
        network_identity: network_scope_key(&state.config).map(ToOwned::to_owned),
        target_bucket: if is_quic_initial(payload) {
            if has_ech {
                LearningTargetBucket::Ech
            } else {
                LearningTargetBucket::Quic
            }
        } else {
            LearningTargetBucket::Generic
        },
        transport: if is_quic_initial(payload) {
            LearningTransportKind::UdpQuic
        } else {
            LearningTransportKind::Unknown
        },
        alpn_class: if is_quic_initial(payload) { LearningAlpnClass::H3 } else { LearningAlpnClass::Unknown },
        hosting_family: hosting_family_context(host),
        reachability_set: reachability_set_context(host),
        ech_capable: has_ech,
        resolver_health: resolver_health_context(state.runtime_context.as_ref()),
        rooted: state.config.process.root_mode,
        capability_context: capability_context(capability),
    }
}

pub(super) fn resolve_adaptive_fake_ttl(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    group: &DesyncGroup,
    host: Option<&str>,
) -> io::Result<Option<u8>> {
    let Some(auto_ttl) = group.actions.auto_ttl else {
        return Ok(None);
    };
    let mut resolver =
        state.adaptive_fake_ttl.write().map_err(|_| io::Error::other("adaptive fake ttl lock poisoned"))?;
    Ok(Some(resolver.resolve(group_index, target, host, auto_ttl, group.actions.ttl)))
}

pub(super) fn resolve_adaptive_tcp_hints(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    group: &DesyncGroup,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<AdaptivePlannerHints> {
    let mut resolver = state.adaptive_tuning.write().map_err(|_| io::Error::other("adaptive tuning lock poisoned"))?;
    Ok(apply_tcp_morph_policy_to_hints(
        state,
        resolver.resolve_tcp_hints(network_scope_key(&state.config), group_index, target, host, group, payload),
    ))
}

pub(super) fn resolve_adaptive_udp_hints(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    group: &DesyncGroup,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<AdaptivePlannerHints> {
    let mut resolver = state.adaptive_tuning.write().map_err(|_| io::Error::other("adaptive tuning lock poisoned"))?;
    let hints = apply_udp_morph_policy_to_hints(
        state,
        resolver.resolve_udp_hints(network_scope_key(&state.config), group_index, target, host, group, payload),
    );
    let capability = direct_path_capability_for_route(state.runtime_context.as_ref(), host, target);
    let merged = merge_udp_hints_with_capability(hints, capability);
    record_morph_rollback(state, target, hints, merged);
    Ok(merged)
}

pub(super) fn note_adaptive_fake_ttl_success(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
) -> io::Result<()> {
    let mut resolver =
        state.adaptive_fake_ttl.write().map_err(|_| io::Error::other("adaptive fake ttl lock poisoned"))?;
    resolver.note_success(group_index, target, host);
    Ok(())
}

pub(super) fn note_adaptive_fake_ttl_failure(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
) -> io::Result<()> {
    let mut resolver =
        state.adaptive_fake_ttl.write().map_err(|_| io::Error::other("adaptive fake ttl lock poisoned"))?;
    resolver.note_failure(group_index, target, host);
    Ok(())
}

pub(super) fn note_server_ttl_for_route(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    observed_ttl: u8,
) -> io::Result<()> {
    let mut resolver =
        state.adaptive_fake_ttl.write().map_err(|_| io::Error::other("adaptive fake ttl lock poisoned"))?;
    resolver.note_server_ttl(group_index, target, host, observed_ttl);
    Ok(())
}

pub(super) fn note_adaptive_tcp_success(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<()> {
    let mut resolver = state.adaptive_tuning.write().map_err(|_| io::Error::other("adaptive tuning lock poisoned"))?;
    resolver.note_tcp_success(network_scope_key(&state.config), group_index, target, host, payload);
    resolver.persist_if_due(state.config.as_ref());
    Ok(())
}

pub(super) fn note_adaptive_tcp_failure(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<()> {
    let mut resolver = state.adaptive_tuning.write().map_err(|_| io::Error::other("adaptive tuning lock poisoned"))?;
    resolver.note_tcp_failure(network_scope_key(&state.config), group_index, target, host, payload);
    resolver.persist_if_due(state.config.as_ref());
    Ok(())
}

pub(super) fn note_adaptive_udp_success(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<()> {
    let mut resolver = state.adaptive_tuning.write().map_err(|_| io::Error::other("adaptive tuning lock poisoned"))?;
    resolver.note_udp_success(network_scope_key(&state.config), group_index, target, host, payload);
    resolver.persist_if_due(state.config.as_ref());
    Ok(())
}

pub(super) fn note_adaptive_udp_failure(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<()> {
    let mut resolver = state.adaptive_tuning.write().map_err(|_| io::Error::other("adaptive tuning lock poisoned"))?;
    resolver.note_udp_failure(network_scope_key(&state.config), group_index, target, host, payload);
    resolver.persist_if_due(state.config.as_ref());
    Ok(())
}

// ---------------------------------------------------------------------------
// Evolver-aware wrappers (priority level 1 → level 2 fallback)
// ---------------------------------------------------------------------------

pub(super) fn resolve_tcp_hints_with_evolver(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    group: &DesyncGroup,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<AdaptivePlannerHints> {
    if !state.config.adaptive.strategy_evolution {
        return resolve_adaptive_tcp_hints(state, target, group_index, group, host, payload);
    }
    if let Ok(mut evolver) = state.strategy_evolver.write() {
        evolver.set_learning_context(tcp_learning_context(state, target, host, payload));
        if let Some(hints) = evolver.peek_hints() {
            return Ok(apply_tcp_morph_policy_to_hints(state, hints));
        }
        if let Some(hints) = evolver.suggest_hints() {
            return Ok(apply_tcp_morph_policy_to_hints(state, hints));
        }
    }
    resolve_adaptive_tcp_hints(state, target, group_index, group, host, payload)
}

pub(super) fn resolve_udp_hints_with_evolver(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    group: &DesyncGroup,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<AdaptivePlannerHints> {
    if !state.config.adaptive.strategy_evolution {
        return resolve_adaptive_udp_hints(state, target, group_index, group, host, payload);
    }
    if let Ok(mut evolver) = state.strategy_evolver.write() {
        evolver.set_learning_context(udp_learning_context(state, target, host, payload));
        if let Some(hints) = evolver.peek_hints() {
            let hints = apply_udp_morph_policy_to_hints(state, hints);
            let capability = direct_path_capability_for_route(state.runtime_context.as_ref(), host, target);
            let merged = merge_udp_hints_with_capability(hints, capability);
            record_morph_rollback(state, target, hints, merged);
            return Ok(merged);
        }
        if let Some(hints) = evolver.suggest_hints() {
            let hints = apply_udp_morph_policy_to_hints(state, hints);
            let capability = direct_path_capability_for_route(state.runtime_context.as_ref(), host, target);
            let merged = merge_udp_hints_with_capability(hints, capability);
            record_morph_rollback(state, target, hints, merged);
            return Ok(merged);
        }
    }
    resolve_adaptive_udp_hints(state, target, group_index, group, host, payload)
}

pub(super) fn note_evolver_success(state: &RuntimeState, latency_ms: u64) {
    if let Ok(mut evolver) = state.strategy_evolver.write() {
        evolver.record_success(latency_ms);
    }
}

pub(super) fn note_evolver_failure(state: &RuntimeState, class: ripdpi_failure_classifier::FailureClass) {
    if let Ok(mut evolver) = state.strategy_evolver.write() {
        evolver.record_failure(class);
    }
}

pub(super) fn direct_path_capability_for_route<'a>(
    runtime_context: Option<&'a ProxyRuntimeContext>,
    host: Option<&str>,
    target: SocketAddr,
) -> Option<&'a ProxyDirectPathCapability> {
    let capabilities = runtime_context?.direct_path_capabilities.as_slice();
    let candidates = direct_path_authority_candidates(host, target);
    capabilities.iter().find(|capability| candidates.contains(&capability.authority))
}

pub(super) fn capability_requires_desync_fallback(capability: &ProxyDirectPathCapability) -> bool {
    capability.fallback_required == Some(true)
        || capability.repeated_handshake_failure_class.as_deref().is_some_and(|value| !value.trim().is_empty())
}

pub(super) fn merge_udp_hints_with_capability(
    mut hints: AdaptivePlannerHints,
    capability: Option<&ProxyDirectPathCapability>,
) -> AdaptivePlannerHints {
    let Some(capability) = capability else {
        return hints;
    };
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

fn record_morph_rollback(
    state: &RuntimeState,
    target: SocketAddr,
    before: AdaptivePlannerHints,
    after: AdaptivePlannerHints,
) {
    if before.udp_burst_profile != after.udp_burst_profile || before.quic_fake_profile != after.quic_fake_profile {
        emit_morph_rollback(state, target, "direct_path_capability_downgrade");
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

fn normalize_authority(value: Option<&str>) -> Option<String> {
    value.map(str::trim).map(|entry| entry.trim_end_matches('.').to_ascii_lowercase()).filter(|entry| !entry.is_empty())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::strategy_evolver::{LearningHostingFamily, LearningReachabilitySet};

    fn capability(authority: &str) -> ProxyDirectPathCapability {
        ProxyDirectPathCapability {
            authority: authority.to_string(),
            quic_usable: None,
            udp_usable: None,
            fallback_required: None,
            repeated_handshake_failure_class: None,
            updated_at: 0,
        }
    }

    #[test]
    fn direct_path_capability_matches_host_and_target_authorities() {
        let runtime_context = ProxyRuntimeContext {
            encrypted_dns: None,
            protect_path: None,
            preferred_edges: std::collections::BTreeMap::default(),
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
            updated_at: 10,
        };

        let merged = merge_udp_hints_with_capability(hints, Some(&capability));

        assert_eq!(merged.udp_burst_profile, Some(AdaptiveUdpBurstProfile::Aggressive));
        assert_eq!(merged.quic_fake_profile, Some(QuicFakeProfile::CompatDefault));
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
