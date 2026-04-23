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
use std::time::{SystemTime, UNIX_EPOCH};

use ring::digest;
use ripdpi_config::{DesyncGroup, QuicFakeProfile, RuntimeConfig};
use ripdpi_desync::{AdaptivePlannerHints, AdaptiveUdpBurstProfile};
use ripdpi_packets::{is_quic_initial, parse_quic_initial, tls_marker_info};
use ripdpi_proxy_config::{ProxyDirectPathCapability, ProxyRuntimeContext};

use super::morph::{apply_tcp_morph_policy_to_hints, apply_udp_morph_policy_to_hints, emit_morph_rollback};
use super::state::RuntimeState;
use crate::runtime_policy::{is_tls_client_hello_payload, TransportProtocol};
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

pub(super) fn direct_path_capability_for_targets<'a>(
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

pub(super) fn capability_requires_desync_fallback(capability: &ProxyDirectPathCapability) -> bool {
    capability.fallback_required == Some(true)
        || capability.repeated_handshake_failure_class.as_deref().is_some_and(|value| !value.trim().is_empty())
        || (matches!(capability.quic_mode.trim().to_ascii_uppercase().as_str(), "SOFT_DISABLE" | "HARD_DISABLE")
            && !capability_preserves_udp_transport(capability))
        || matches!(capability.outcome.trim().to_ascii_uppercase().as_str(), "OWNED_STACK_ONLY" | "NO_DIRECT_SOLUTION")
}

pub(super) fn capability_preserves_udp_transport(capability: &ProxyDirectPathCapability) -> bool {
    capability.reason_code.as_deref() == Some("NO_TCP_FALLBACK")
}

#[cfg(test)]
fn capability_udp_clean(capability: &ProxyDirectPathCapability) -> bool {
    if capability_preserves_udp_transport(capability) {
        return true;
    }
    capability.udp_usable != Some(false)
        && capability.quic_usable != Some(false)
        && !matches!(capability.quic_mode.trim().to_ascii_uppercase().as_str(), "SOFT_DISABLE" | "HARD_DISABLE")
}

pub(super) fn capability_blocks_transport(
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

pub(super) fn merge_udp_hints_with_capability(
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

pub(super) fn direct_path_ip_set_digest(targets: &[SocketAddr]) -> String {
    if targets.is_empty() {
        return String::new();
    }
    let mut members = targets.iter().map(|target| target.ip().to_string()).collect::<Vec<_>>();
    members.sort();
    members.dedup();
    let joined = members.join(",");
    let digest = digest::digest(&digest::SHA256, joined.as_bytes());
    digest.as_ref()[..8].iter().map(|byte| format!("{byte:02x}")).collect()
}

fn normalize_authority(value: Option<&str>) -> Option<String> {
    value.map(str::trim).map(|entry| entry.trim_end_matches('.').to_ascii_lowercase()).filter(|entry| !entry.is_empty())
}

pub(super) fn now_millis() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|value| value.as_millis() as i64).unwrap_or(0)
}

pub(super) fn note_direct_path_transport_attempt(
    state: &RuntimeState,
    host: Option<&str>,
    targets: &[SocketAddr],
    transport: TransportProtocol,
) -> io::Result<()> {
    let mut learner =
        state.direct_path_learning.write().map_err(|_| io::Error::other("direct path learning lock poisoned"))?;
    learner.note_transport_attempt(state, host, targets, transport);
    Ok(())
}

pub(super) fn note_direct_path_udp_suppressed(
    state: &RuntimeState,
    host: Option<&str>,
    targets: &[SocketAddr],
) -> io::Result<()> {
    let mut learner =
        state.direct_path_learning.write().map_err(|_| io::Error::other("direct path learning lock poisoned"))?;
    learner.note_udp_suppressed(host, targets, now_millis().max(0) as u64);
    Ok(())
}

pub(super) fn note_direct_path_udp_failure(
    state: &RuntimeState,
    host: Option<&str>,
    targets: &[SocketAddr],
) -> io::Result<()> {
    let mut learner =
        state.direct_path_learning.write().map_err(|_| io::Error::other("direct path learning lock poisoned"))?;
    learner.note_udp_failure(host, targets);
    Ok(())
}

pub(super) fn note_direct_path_quic_success(
    state: &RuntimeState,
    host: Option<&str>,
    targets: &[SocketAddr],
) -> io::Result<()> {
    let mut learner =
        state.direct_path_learning.write().map_err(|_| io::Error::other("direct path learning lock poisoned"))?;
    learner.note_quic_success(state, host, targets);
    Ok(())
}

pub(super) fn note_direct_path_tcp_success(
    state: &RuntimeState,
    host: Option<&str>,
    targets: &[SocketAddr],
    strategy_family: Option<&str>,
) -> io::Result<()> {
    let mut learner =
        state.direct_path_learning.write().map_err(|_| io::Error::other("direct path learning lock poisoned"))?;
    learner.note_tcp_success(state, host, targets, strategy_family);
    Ok(())
}

pub(super) fn note_direct_path_tls_post_client_hello_failure(
    state: &RuntimeState,
    host: Option<&str>,
    targets: &[SocketAddr],
) -> io::Result<()> {
    let mut learner =
        state.direct_path_learning.write().map_err(|_| io::Error::other("direct path learning lock poisoned"))?;
    learner.note_tls_post_client_hello_failure(host, targets);
    Ok(())
}

pub(super) fn note_direct_path_all_ips_failed(
    state: &RuntimeState,
    host: Option<&str>,
    targets: &[SocketAddr],
) -> io::Result<()> {
    let mut learner =
        state.direct_path_learning.write().map_err(|_| io::Error::other("direct path learning lock poisoned"))?;
    learner.note_all_ips_failed(state, host, targets);
    Ok(())
}

pub(super) fn emit_due_direct_path_learning_timeouts(state: &RuntimeState) -> io::Result<()> {
    let mut learner =
        state.direct_path_learning.write().map_err(|_| io::Error::other("direct path learning lock poisoned"))?;
    learner.emit_due_timeouts(state, now_millis().max(0) as u64);
    Ok(())
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

        let merged = merge_udp_hints_with_capability(hints.clone(), Some(&capability));

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
            preferred_edges: std::collections::BTreeMap::default(),
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
