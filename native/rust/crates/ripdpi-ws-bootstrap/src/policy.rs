use std::net::IpAddr;
use std::sync::LazyLock;

use ripdpi_proxy_config::{
    active_network_scope, ProxyDirectPathCapability, ProxyEncryptedDnsContext, ProxyRuntimeContext,
};
use ripdpi_runtime_dns_cache::{ResolvedMapping, ResolverMappingCache, ResolverMappingKey, ResolverMappingSource};

use crate::catalog::{
    default_encrypted_dns_context, primary_encrypted_dns_context, secondary_encrypted_dns_context,
    PRIMARY_DOH_RESOLVER_ID, SECONDARY_DOH_RESOLVER_ID, WS_TUNNEL_PORT,
};

/// Process-wide learned resolver-selection cache, keyed by `(host, NetProfile)`
/// where the NetProfile component is the ambient network scope
/// ([`active_network_scope`]). It is consulted as the *default* resolver
/// preference when no explicit per-host capability or runtime override applies,
/// and populated on successful encrypted-DNS resolution. Entries age out on the
/// family-cache 7-day TTL ([`ResolverMappingCache::DEFAULT_TTL`]).
static RESOLVER_SELECTION_CACHE: LazyLock<ResolverMappingCache> = LazyLock::new(ResolverMappingCache::default);

pub fn runtime_encrypted_dns_context_for_host(
    host: &str,
    runtime_context: Option<&ProxyRuntimeContext>,
) -> ProxyEncryptedDnsContext {
    runtime_encrypted_dns_context_for_host_with_default(host, runtime_context, default_encrypted_dns_context)
}

pub(crate) fn runtime_encrypted_dns_context_for_host_with_default(
    host: &str,
    runtime_context: Option<&ProxyRuntimeContext>,
    default_context: impl FnOnce() -> ProxyEncryptedDnsContext,
) -> ProxyEncryptedDnsContext {
    select_encrypted_dns_context(host, runtime_context, &RESOLVER_SELECTION_CACHE, default_context)
}

/// Record the resolver that produced a successful answer for `host` so future
/// resolutions on the same network prefer it. No-op when no network scope is
/// published (caching stays disabled until the runtime publishes one) or when
/// the context is not a learnable DoH preference.
pub(crate) fn record_successful_resolver(host: &str, context: &ProxyEncryptedDnsContext, resolved_ip: IpAddr) {
    record_successful_resolver_in(
        &RESOLVER_SELECTION_CACHE,
        active_network_scope().as_deref(),
        host,
        context,
        resolved_ip,
    );
}

// ── selection / recording internals (cache + scope injected for tests) ─────────

pub(crate) fn select_encrypted_dns_context(
    host: &str,
    runtime_context: Option<&ProxyRuntimeContext>,
    cache: &ResolverMappingCache,
    default_context: impl FnOnce() -> ProxyEncryptedDnsContext,
) -> ProxyEncryptedDnsContext {
    select_encrypted_dns_context_scoped(
        host,
        runtime_context,
        cache,
        active_network_scope().as_deref(),
        default_context,
    )
}

pub(crate) fn select_encrypted_dns_context_scoped(
    host: &str,
    runtime_context: Option<&ProxyRuntimeContext>,
    cache: &ResolverMappingCache,
    network_scope: Option<&str>,
    default_context: impl FnOnce() -> ProxyEncryptedDnsContext,
) -> ProxyEncryptedDnsContext {
    let capability = direct_path_capability_for_host(runtime_context, host);
    // Precedence: explicit per-host capability > runtime-configured DNS >
    // learned (host, NetProfile) preference > static default.
    let base_context = capability
        .and_then(capability_encrypted_dns_context)
        .or_else(|| runtime_context.and_then(|context| context.encrypted_dns.clone()))
        .or_else(|| cached_encrypted_dns_context(host, network_scope, cache))
        .unwrap_or_else(default_context);
    gate_doq_for_capability(base_context, capability)
}

pub(crate) fn record_successful_resolver_in(
    cache: &ResolverMappingCache,
    network_scope: Option<&str>,
    host: &str,
    context: &ProxyEncryptedDnsContext,
    resolved_ip: IpAddr,
) {
    // No usable network scope => `(host, NetProfile)` caching is a no-op, so
    // selection behaves exactly as it did before the cache existed.
    let Some(scope) = network_scope.filter(|value| !value.trim().is_empty()) else {
        return;
    };
    let source = resolver_source_for_context(context);
    // Only learn explicit DoH preferences; the default/system path carries no
    // per-network signal worth persisting (and recording it would let a stale
    // default mask a later capability change).
    if matches!(source, ResolverMappingSource::System) {
        return;
    }
    let key = ResolverMappingKey::new(host, scope);
    cache.insert_default_ttl(key, ResolvedMapping { best_ip: resolved_ip, ip_family: resolved_ip.into(), source });
    // Observability marker (per-resolution, not per-packet — infrequent). Lets a
    // device/instrumented test confirm the learned-resolver path actually fired.
    tracing::info!(
        target: "ripdpi::resolver_selection",
        event = "resolver_selection_recorded",
        host = host,
        network_scope = scope,
        resolver_id = context.resolver_id.as_deref().unwrap_or_default(),
        "recorded successful encrypted-DNS resolver for (host, NetProfile)"
    );
}

fn cached_encrypted_dns_context(
    host: &str,
    network_scope: Option<&str>,
    cache: &ResolverMappingCache,
) -> Option<ProxyEncryptedDnsContext> {
    let scope = network_scope.filter(|value| !value.trim().is_empty())?;
    let mapping = cache.lookup(&ResolverMappingKey::new(host, scope))?;
    let context = encrypted_dns_context_for_source(&mapping.source)?;
    // Observability marker (per-resolution, not per-packet — infrequent).
    tracing::info!(
        target: "ripdpi::resolver_selection",
        event = "resolver_selection_cache_hit",
        host = host,
        network_scope = scope,
        resolver_id = context.resolver_id.as_deref().unwrap_or_default(),
        "applied learned encrypted-DNS resolver preference for (host, NetProfile)"
    );
    Some(context)
}

fn resolver_source_for_context(context: &ProxyEncryptedDnsContext) -> ResolverMappingSource {
    match context.resolver_id.as_deref() {
        Some(id) if id == PRIMARY_DOH_RESOLVER_ID => ResolverMappingSource::DohPrimary,
        Some(id) if id == SECONDARY_DOH_RESOLVER_ID => ResolverMappingSource::DohSecondary,
        _ => ResolverMappingSource::System,
    }
}

fn encrypted_dns_context_for_source(source: &ResolverMappingSource) -> Option<ProxyEncryptedDnsContext> {
    match source {
        ResolverMappingSource::DohPrimary => Some(primary_encrypted_dns_context()),
        ResolverMappingSource::DohSecondary => Some(secondary_encrypted_dns_context()),
        ResolverMappingSource::System => None,
    }
}

fn capability_encrypted_dns_context(capability: &ProxyDirectPathCapability) -> Option<ProxyEncryptedDnsContext> {
    match capability.dns_mode.trim().to_ascii_uppercase().as_str() {
        "DOH_PRIMARY" => Some(primary_encrypted_dns_context()),
        "DOH_SECONDARY" => Some(secondary_encrypted_dns_context()),
        _ => None,
    }
}

fn gate_doq_for_capability(
    mut context: ProxyEncryptedDnsContext,
    capability: Option<&ProxyDirectPathCapability>,
) -> ProxyEncryptedDnsContext {
    if !context.protocol.eq_ignore_ascii_case("doq") {
        return context;
    }
    let udp_clean = capability.is_none_or(capability_udp_clean_for_resolver);
    if udp_clean {
        return context;
    }
    context.protocol = "doh".to_string();
    context.port = WS_TUNNEL_PORT;
    context.tls_server_name = context.tls_server_name.or_else(|| Some(context.host.clone()));
    if context.doh_url.as_deref().is_none_or(|value| value.trim().is_empty()) {
        context.doh_url = Some(format!("https://{}/dns-query", context.host));
    }
    context
}

fn direct_path_capability_for_host<'a>(
    runtime_context: Option<&'a ProxyRuntimeContext>,
    host: &str,
) -> Option<&'a ProxyDirectPathCapability> {
    let normalized_host = normalize_authority(host)?;
    let candidates = [normalized_host.clone(), format!("{normalized_host}:{WS_TUNNEL_PORT}")];
    runtime_context?.direct_path_capabilities.iter().find(|capability| candidates.contains(&capability.authority))
}

fn capability_udp_clean_for_resolver(capability: &ProxyDirectPathCapability) -> bool {
    if capability.reason_code.as_deref() == Some("NO_TCP_FALLBACK") {
        return true;
    }
    capability.udp_usable != Some(false)
        && capability.quic_usable != Some(false)
        && !matches!(capability.quic_mode.trim().to_ascii_uppercase().as_str(), "SOFT_DISABLE" | "HARD_DISABLE")
}

fn normalize_authority(value: &str) -> Option<String> {
    let normalized = value.trim().trim_end_matches('.').to_ascii_lowercase();
    (!normalized.is_empty()).then_some(normalized)
}

#[cfg(test)]
mod cache_tests {
    use super::*;

    fn doh_secondary_mapping() -> ResolvedMapping {
        ResolvedMapping {
            best_ip: IpAddr::from([203, 0, 113, 9]),
            ip_family: IpAddr::from([203, 0, 113, 9]).into(),
            source: ResolverMappingSource::DohSecondary,
        }
    }

    fn capability_with_dns_mode(authority: &str, dns_mode: &str) -> ProxyDirectPathCapability {
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
            dns_mode: dns_mode.to_string(),
            tcp_family: "NONE".to_string(),
            outcome: "TRANSPARENT_OK".to_string(),
            transport_class: None,
            reason_code: None,
            cooldown_until: None,
            updated_at: 0,
        }
    }

    #[test]
    fn cached_preference_fills_the_default_gap_when_scoped() {
        let cache = ResolverMappingCache::default();
        let host = "cache-fill.example";
        let scope = "wifi:scope-a";
        cache.insert_default_ttl(ResolverMappingKey::new(host, scope), doh_secondary_mapping());

        let context =
            select_encrypted_dns_context_scoped(host, None, &cache, Some(scope), default_encrypted_dns_context);
        // The learned DohSecondary preference replaces what would have been the
        // static default.
        assert_eq!(context.resolver_id.as_deref(), Some(SECONDARY_DOH_RESOLVER_ID));
    }

    #[test]
    fn no_scope_disables_the_cache_no_regression() {
        let cache = ResolverMappingCache::default();
        let host = "cache-fill.example";
        cache.insert_default_ttl(ResolverMappingKey::new(host, "wifi:scope-a"), doh_secondary_mapping());

        // With no published scope the cache is bypassed entirely: behaviour is
        // identical to the pre-cache default.
        let context = select_encrypted_dns_context_scoped(host, None, &cache, None, default_encrypted_dns_context);
        assert_eq!(context.resolver_id.as_deref(), default_encrypted_dns_context().resolver_id.as_deref());
    }

    #[test]
    fn different_scope_misses_the_cache() {
        let cache = ResolverMappingCache::default();
        let host = "cache-fill.example";
        cache.insert_default_ttl(ResolverMappingKey::new(host, "wifi:scope-a"), doh_secondary_mapping());

        let context = select_encrypted_dns_context_scoped(
            host,
            None,
            &cache,
            Some("cell:scope-b"),
            default_encrypted_dns_context,
        );
        assert_eq!(context.resolver_id.as_deref(), default_encrypted_dns_context().resolver_id.as_deref());
    }

    #[test]
    fn explicit_capability_overrides_cached_preference() {
        let cache = ResolverMappingCache::default();
        let host = "cache-fill.example";
        let scope = "wifi:scope-a";
        // Cache says secondary, but an explicit DOH_PRIMARY capability must win.
        cache.insert_default_ttl(ResolverMappingKey::new(host, scope), doh_secondary_mapping());
        let runtime_context = ProxyRuntimeContext {
            direct_path_capabilities: vec![capability_with_dns_mode(host, "DOH_PRIMARY")],
            ..Default::default()
        };

        let context = select_encrypted_dns_context_scoped(
            host,
            Some(&runtime_context),
            &cache,
            Some(scope),
            default_encrypted_dns_context,
        );
        assert_eq!(context.resolver_id.as_deref(), Some(PRIMARY_DOH_RESOLVER_ID));
    }

    #[test]
    fn successful_primary_resolution_is_recorded_under_scope() {
        let cache = ResolverMappingCache::default();
        let host = "record.example";
        let scope = "wifi:scope-c";
        record_successful_resolver_in(
            &cache,
            Some(scope),
            host,
            &primary_encrypted_dns_context(),
            IpAddr::from([198, 51, 100, 7]),
        );

        let context =
            select_encrypted_dns_context_scoped(host, None, &cache, Some(scope), default_encrypted_dns_context);
        assert_eq!(context.resolver_id.as_deref(), Some(PRIMARY_DOH_RESOLVER_ID));
    }

    #[test]
    fn default_context_resolution_is_not_recorded() {
        let cache = ResolverMappingCache::default();
        let host = "record-default.example";
        let scope = "wifi:scope-d";
        // The Cloudflare default maps to System and must not be learned.
        record_successful_resolver_in(
            &cache,
            Some(scope),
            host,
            &default_encrypted_dns_context(),
            IpAddr::from([198, 51, 100, 8]),
        );

        assert!(cache.lookup(&ResolverMappingKey::new(host, scope)).is_none());
    }

    #[test]
    fn recording_without_scope_is_a_no_op() {
        let cache = ResolverMappingCache::default();
        let host = "record-noscope.example";
        record_successful_resolver_in(
            &cache,
            None,
            host,
            &primary_encrypted_dns_context(),
            IpAddr::from([198, 51, 100, 9]),
        );
        assert!(cache.lookup(&ResolverMappingKey::new(host, "any")).is_none());
    }
}
