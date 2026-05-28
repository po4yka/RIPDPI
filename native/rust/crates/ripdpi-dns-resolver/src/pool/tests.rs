use std::net::{IpAddr, Ipv4Addr};
use std::time::{Duration, Instant};

use super::fallback_order::{fallback_key, FallbackEntry};
use super::*;
use crate::health::HealthRegistry;
use crate::types::{EncryptedDnsEndpoint, EncryptedDnsProtocol, EncryptedDnsTransport, ResolverNetworkScope};

fn google_doh_endpoint() -> EncryptedDnsEndpoint {
    EncryptedDnsEndpoint {
        protocol: EncryptedDnsProtocol::Doh,
        resolver_id: Some("google".to_string()),
        host: "dns.google".to_string(),
        port: 0,
        tls_server_name: None,
        bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::new(8, 8, 8, 8)), IpAddr::V4(Ipv4Addr::new(8, 8, 4, 4))],
        doh_url: Some("https://dns.google/dns-query".to_string()),
        dnscrypt_provider_name: None,
        dnscrypt_public_key: None,
        odoh: None,
    }
}

fn cloudflare_doh_endpoint() -> EncryptedDnsEndpoint {
    EncryptedDnsEndpoint {
        protocol: EncryptedDnsProtocol::Doh,
        resolver_id: Some("cloudflare".to_string()),
        host: "cloudflare-dns.com".to_string(),
        port: 0,
        tls_server_name: None,
        bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1)), IpAddr::V4(Ipv4Addr::new(1, 0, 0, 1))],
        doh_url: Some("https://cloudflare-dns.com/dns-query".to_string()),
        dnscrypt_provider_name: None,
        dnscrypt_public_key: None,
        odoh: None,
    }
}

#[test]
fn builder_creates_pool_with_correct_length() {
    let pool = ResolverPool::builder()
        .add_endpoint(google_doh_endpoint(), EncryptedDnsTransport::Direct)
        .add_endpoint(cloudflare_doh_endpoint(), EncryptedDnsTransport::Direct)
        .build()
        .unwrap();
    assert_eq!(pool.len(), 2);
    assert!(!pool.is_empty());
}

#[test]
fn empty_pool_returns_error_from_exchange_blocking() {
    let pool = ResolverPool::builder().build().unwrap();
    assert!(pool.is_empty());
    let query = b"\x00\x01\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00";
    assert!(pool.exchange_blocking(query).is_err());
}

#[test]
fn shared_health_registry_has_same_arc_identity() {
    let pool =
        ResolverPool::builder().add_endpoint(google_doh_endpoint(), EncryptedDnsTransport::Direct).build().unwrap();
    pool.health_registry().record_endpoint_outcome("test", true, 100);
    assert_eq!(pool.health_registry().observation_count("test"), 1);
}

#[test]
fn external_health_registry_is_used_when_provided() {
    let shared = HealthRegistry::new(Duration::from_secs(60));
    shared.record_endpoint_outcome("https://dns.google/dns-query", true, 30);
    let pool = ResolverPool::builder()
        .add_endpoint(google_doh_endpoint(), EncryptedDnsTransport::Direct)
        .health_registry(shared.clone())
        .build()
        .unwrap();
    assert_eq!(pool.health_registry().observation_count("https://dns.google/dns-query"), 1);
}

#[test]
fn try_order_prefers_cold_start_fallback_when_no_health_data() {
    let pool = ResolverPool::builder()
        .add_endpoint(google_doh_endpoint(), EncryptedDnsTransport::Direct)
        .add_endpoint(cloudflare_doh_endpoint(), EncryptedDnsTransport::Direct)
        .build()
        .unwrap();

    {
        let cf_label = &pool.inner.labels[1];
        if let Ok(mut cache) = pool.inner.fallback_cache.lock() {
            cache.put(fallback_key(pool.network_scope(), cf_label), FallbackEntry { last_success: Instant::now() });
        }
    }

    let order = pool.try_order();
    assert_eq!(order[0], 1, "cached resolver should be tried first on cold start");
}

#[test]
fn cold_start_fallback_cache_is_network_scoped() {
    let wifi = ResolverNetworkScope::new("wifi:alpha");
    let cellular = ResolverNetworkScope::new("cell:beta");
    let pool = ResolverPool::builder()
        .add_endpoint(google_doh_endpoint(), EncryptedDnsTransport::Direct)
        .add_endpoint(cloudflare_doh_endpoint(), EncryptedDnsTransport::Direct)
        .network_scope(cellular)
        .build()
        .unwrap();

    {
        let cf_label = &pool.inner.labels[1];
        if let Ok(mut cache) = pool.inner.fallback_cache.lock() {
            cache.put(fallback_key(&wifi, cf_label), FallbackEntry { last_success: Instant::now() });
        }
    }

    let order = pool.try_order();
    assert_eq!(order[0], 0, "cellular scope must not inherit wifi fallback success");
}

#[test]
fn try_order_prefers_healthier_endpoint_over_fallback() {
    let pool = ResolverPool::builder()
        .add_endpoint(google_doh_endpoint(), EncryptedDnsTransport::Direct)
        .add_endpoint(cloudflare_doh_endpoint(), EncryptedDnsTransport::Direct)
        .build()
        .unwrap();

    let g_label = &pool.inner.labels[0];
    for _ in 0..50 {
        pool.inner.health.record_endpoint_outcome_in_scope(pool.network_scope(), g_label, true, 20);
    }
    let cf_label = &pool.inner.labels[1];
    if let Ok(mut cache) = pool.inner.fallback_cache.lock() {
        cache.put(fallback_key(pool.network_scope(), cf_label), FallbackEntry { last_success: Instant::now() });
    }

    let order = pool.try_order();
    assert_eq!(order[0], 0, "healthy endpoint should be tried first");
}

#[test]
fn oracle_quarantine_changes_pool_selection_order() {
    let pool = ResolverPool::builder()
        .add_endpoint(google_doh_endpoint(), EncryptedDnsTransport::Direct)
        .add_endpoint(cloudflare_doh_endpoint(), EncryptedDnsTransport::Direct)
        .network_scope(ResolverNetworkScope::new("wifi:lab"))
        .build()
        .unwrap();

    let google_label = pool.inner.labels[0].clone();
    let cloudflare_label = pool.inner.labels[1].clone();
    for _ in 0..20 {
        pool.inner.health.record_endpoint_outcome_in_scope(pool.network_scope(), &google_label, true, 15);
        pool.inner.health.record_endpoint_outcome_in_scope(pool.network_scope(), &cloudflare_label, true, 30);
    }

    pool.record_oracle_observation(&google_label, ResolverOracleObservation::Disagreement);
    pool.record_oracle_observation(&google_label, ResolverOracleObservation::Poisoned);

    let order = pool.try_order();
    assert_eq!(order[0], 1, "quarantined resolver should not dominate rank 0");
}

// ---------------------------------------------------------------------------
// DoQ session-level demotion tests.
//
// These tests exercise the demotion state API directly using DoH endpoints.
// The demotion logic is keyed by ResolverNetworkScope and lives in PoolInner,
// independent of whether actual DoQ resolvers are present in the pool.
// Using DoH endpoints avoids the Quinn UDP socket initialisation that requires
// a Tokio runtime in plain #[test] contexts.
// ---------------------------------------------------------------------------

/// Fresh session with no recorded failures: DoQ is offered (not suppressed).
#[test]
fn doq_not_suppressed_in_fresh_session() {
    let scope = ResolverNetworkScope::new("wifi:home");
    let pool = ResolverPool::builder()
        .add_endpoint(google_doh_endpoint(), EncryptedDnsTransport::Direct)
        .network_scope(scope.clone())
        .build()
        .unwrap();

    assert!(!pool.is_doq_suppressed_for_scope(&scope), "DoQ must not be suppressed in a fresh session");
}

/// After `record_doq_failure`, DoQ is suppressed for the same scope in the same session.
#[test]
fn doq_suppressed_after_failure_in_same_session() {
    let scope = ResolverNetworkScope::new("wifi:home");
    let pool = ResolverPool::builder()
        .add_endpoint(google_doh_endpoint(), EncryptedDnsTransport::Direct)
        .network_scope(scope.clone())
        .build()
        .unwrap();

    pool.record_doq_failure(&scope);

    assert!(
        pool.is_doq_suppressed_for_scope(&scope),
        "DoQ must be suppressed after a recorded failure in the same scope"
    );
}

/// Demotion does not cross sessions: a new pool (new session) can offer DoQ again.
#[test]
fn doq_demotion_does_not_cross_sessions() {
    let scope = ResolverNetworkScope::new("wifi:home");
    let session_a = ResolverPool::builder()
        .add_endpoint(google_doh_endpoint(), EncryptedDnsTransport::Direct)
        .network_scope(scope.clone())
        .build()
        .unwrap();

    session_a.record_doq_failure(&scope);
    assert!(session_a.is_doq_suppressed_for_scope(&scope), "session A must be demoted");

    // New pool = new session; demotion state is not shared across Arc instances.
    let session_b = ResolverPool::builder()
        .add_endpoint(google_doh_endpoint(), EncryptedDnsTransport::Direct)
        .network_scope(scope.clone())
        .build()
        .unwrap();

    assert!(
        !session_b.is_doq_suppressed_for_scope(&scope),
        "new session must not inherit DoQ demotion from previous session"
    );
}

/// Demotion is keyed by scope: switching scope mid-session re-opens DoQ for the new scope.
#[test]
fn doq_demotion_is_keyed_by_scope() {
    let wifi = ResolverNetworkScope::new("wifi:home");
    let cell = ResolverNetworkScope::new("cell:lte");
    let pool = ResolverPool::builder()
        .add_endpoint(google_doh_endpoint(), EncryptedDnsTransport::Direct)
        .network_scope(wifi.clone())
        .build()
        .unwrap();

    pool.record_doq_failure(&wifi);

    assert!(pool.is_doq_suppressed_for_scope(&wifi), "wifi scope must be demoted after failure");
    assert!(!pool.is_doq_suppressed_for_scope(&cell), "cell scope must not be demoted; demotion is scope-keyed");
}

#[test]
fn ranking_persists_only_within_the_same_network_scope() {
    let shared = HealthRegistry::new(Duration::from_secs(60));
    let wifi = ResolverNetworkScope::new("wifi:alpha");
    let cellular = ResolverNetworkScope::new("cell:beta");
    let google_label = "https://dns.google/dns-query";
    let cloudflare_label = "https://cloudflare-dns.com/dns-query";

    for _ in 0..30 {
        shared.record_endpoint_outcome_in_scope(&wifi, google_label, false, 4000);
        shared.record_endpoint_outcome_in_scope(&wifi, cloudflare_label, true, 20);
    }

    let wifi_pool = ResolverPool::builder()
        .add_endpoint(google_doh_endpoint(), EncryptedDnsTransport::Direct)
        .add_endpoint(cloudflare_doh_endpoint(), EncryptedDnsTransport::Direct)
        .health_registry(shared.clone())
        .network_scope(wifi.clone())
        .build()
        .unwrap();
    assert_eq!(wifi_pool.try_order()[0], 1);

    let rebuilt_wifi_pool = ResolverPool::builder()
        .add_endpoint(google_doh_endpoint(), EncryptedDnsTransport::Direct)
        .add_endpoint(cloudflare_doh_endpoint(), EncryptedDnsTransport::Direct)
        .health_registry(shared.clone())
        .network_scope(wifi)
        .build()
        .unwrap();
    assert_eq!(rebuilt_wifi_pool.try_order()[0], 1);

    let cellular_pool = ResolverPool::builder()
        .add_endpoint(google_doh_endpoint(), EncryptedDnsTransport::Direct)
        .add_endpoint(cloudflare_doh_endpoint(), EncryptedDnsTransport::Direct)
        .health_registry(shared)
        .network_scope(cellular)
        .build()
        .unwrap();
    assert_eq!(cellular_pool.try_order()[0], 0, "different scope should not inherit wifi ranking");
}

#[test]
fn bootstrap_ip_ranking_persists_only_within_the_same_network_scope() {
    let shared = HealthRegistry::new(Duration::from_secs(60));
    let wifi = ResolverNetworkScope::new("wifi:alpha");
    let cellular = ResolverNetworkScope::new("cell:beta");
    let primary = IpAddr::V4(Ipv4Addr::new(8, 8, 8, 8));
    let secondary = IpAddr::V4(Ipv4Addr::new(8, 8, 4, 4));

    for _ in 0..30 {
        shared.record_bootstrap_outcome_in_scope(&wifi, primary, false, 4000);
        shared.record_bootstrap_outcome_in_scope(&wifi, secondary, true, 20);
    }

    let wifi_pool = ResolverPool::builder()
        .add_endpoint(google_doh_endpoint(), EncryptedDnsTransport::Direct)
        .health_registry(shared.clone())
        .network_scope(wifi.clone())
        .build()
        .unwrap();
    assert_eq!(wifi_pool.inner.resolvers[0].ranked_bootstrap_ips_for_test()[0], secondary);

    let rebuilt_wifi_pool = ResolverPool::builder()
        .add_endpoint(google_doh_endpoint(), EncryptedDnsTransport::Direct)
        .health_registry(shared.clone())
        .network_scope(wifi)
        .build()
        .unwrap();
    assert_eq!(rebuilt_wifi_pool.inner.resolvers[0].ranked_bootstrap_ips_for_test()[0], secondary);

    let cellular_pool = ResolverPool::builder()
        .add_endpoint(google_doh_endpoint(), EncryptedDnsTransport::Direct)
        .health_registry(shared)
        .network_scope(cellular)
        .build()
        .unwrap();
    assert_eq!(
        cellular_pool.inner.resolvers[0].ranked_bootstrap_ips_for_test()[0],
        primary,
        "different scope should not inherit wifi bootstrap ranking",
    );
}
