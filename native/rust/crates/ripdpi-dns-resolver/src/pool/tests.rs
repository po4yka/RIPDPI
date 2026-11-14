use std::net::{IpAddr, Ipv4Addr};
use std::time::{Duration, Instant};

use super::fallback_order::{self, FallbackEntry, fallback_key};
use super::*;
use crate::health::HealthRegistry;
use crate::types::{
    EncryptedDnsEndpoint, EncryptedDnsError, EncryptedDnsProtocol, EncryptedDnsTransport, ResolverNetworkScope,
};

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
        let cf_label = &pool.inner.keys[1];
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
        let cf_label = &pool.inner.keys[1];
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

    let g_label = &pool.inner.keys[0];
    for _ in 0..50 {
        pool.inner.health.record_endpoint_outcome_in_scope(pool.network_scope(), g_label, true, 20);
    }
    let cf_label = &pool.inner.keys[1];
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

    let google_label = pool.inner.keys[0].clone();
    let cloudflare_label = pool.inner.keys[1].clone();
    for _ in 0..20 {
        pool.inner.health.record_endpoint_outcome_in_scope(pool.network_scope(), &google_label, true, 15);
        pool.inner.health.record_endpoint_outcome_in_scope(pool.network_scope(), &cloudflare_label, true, 30);
    }

    let public_label = pool.inner.labels[0].clone();
    pool.record_oracle_observation(&public_label, ResolverOracleObservation::Disagreement);
    pool.record_oracle_observation(&public_label, ResolverOracleObservation::Poisoned);

    let order = pool.try_order();
    assert_eq!(order[0], 1, "quarantined resolver should not dominate rank 0");
}

// ---------------------------------------------------------------------------
// DoQ per-resolver demotion tests. DoQ resolvers need a Tokio runtime to build.
// ---------------------------------------------------------------------------

fn tls_endpoint(protocol: EncryptedDnsProtocol, host: &str, port: u16) -> EncryptedDnsEndpoint {
    EncryptedDnsEndpoint {
        protocol,
        resolver_id: None,
        host: host.to_string(),
        port,
        tls_server_name: None,
        bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::new(94, 140, 14, 14))],
        doh_url: None,
        dnscrypt_provider_name: None,
        dnscrypt_public_key: None,
        odoh: None,
    }
}

fn doq_endpoint(host: &str) -> EncryptedDnsEndpoint {
    tls_endpoint(EncryptedDnsProtocol::Doq, host, 853)
}

fn doh_and_doq_pool() -> ResolverPool {
    ResolverPool::builder()
        .add_endpoint(google_doh_endpoint(), EncryptedDnsTransport::Direct)
        .add_endpoint(doq_endpoint("dns.adguard-dns.com"), EncryptedDnsTransport::Direct)
        .build()
        .unwrap()
}

fn expire_doq_demotion(pool: &ResolverPool, idx: usize) {
    let key = pool.inner.keys[idx].clone();
    pool.inner
        .doq_demoted_until
        .lock()
        .unwrap()
        .insert(key, Instant::now().checked_sub(Duration::from_secs(1)).unwrap_or_else(Instant::now));
}

#[tokio::test]
async fn doq_connect_failure_demotes_only_that_resolver_until_ttl_expires() {
    let pool = doh_and_doq_pool();
    assert!(pool.try_order().contains(&1), "fresh DoQ resolver is offered");

    pool.record_failure(1, &EncryptedDnsError::Request("DoQ connect timeout".into()), 10);
    for _ in 0..4 {
        assert!(!pool.try_order().contains(&1), "demoted DoQ resolver must not be offered");
    }
    assert!(pool.try_order().contains(&0), "the DoH resolver stays available");

    expire_doq_demotion(&pool, 1);
    assert!(pool.try_order().contains(&1), "an expired demotion no longer filters");
}

#[tokio::test]
async fn doq_query_level_failures_do_not_demote() {
    let pool = doh_and_doq_pool();
    for message in
        ["DoQ query timeout", "DoQ open_bi: closed", "DoQ write: reset", "DoQ query is shorter than a DNS header"]
    {
        pool.record_failure(1, &EncryptedDnsError::Request(message.into()), 10);
    }
    for _ in 0..4 {
        assert!(pool.try_order().contains(&1));
    }
    // A handshake failure does demote.
    pool.record_failure(1, &EncryptedDnsError::Tls("DoQ handshake: closed".into()), 10);
    assert!(!pool.try_order().contains(&1));
}

#[tokio::test]
async fn quarantined_cached_success_is_not_promoted_to_rank_zero() {
    let pool = ResolverPool::builder()
        .add_endpoint(google_doh_endpoint(), EncryptedDnsTransport::Direct)
        .add_endpoint(cloudflare_doh_endpoint(), EncryptedDnsTransport::Direct)
        .build()
        .unwrap();
    let key = pool.inner.keys[1].clone();
    pool.inner
        .fallback_cache
        .lock()
        .unwrap()
        .put(fallback_key(pool.network_scope(), &key), FallbackEntry { last_success: Instant::now() });
    let label = pool.inner.labels[1].clone();
    pool.record_oracle_observation(&label, ResolverOracleObservation::Poisoned);
    let mut ranked = vec![0, 1];
    fallback_order::promote_cached_success_if_cold(&pool.inner, &mut ranked);
    assert_eq!(ranked, vec![0, 1]);
}

#[tokio::test]
async fn doq_decode_failure_does_not_demote() {
    let pool = doh_and_doq_pool();
    pool.record_failure(1, &EncryptedDnsError::DnsParse("bad".into()), 10);
    pool.record_failure(1, &EncryptedDnsError::NoAnswer, 10);
    for _ in 0..4 {
        assert!(pool.try_order().contains(&1));
    }
}

#[tokio::test]
async fn doq_demotion_does_not_cross_pools() {
    let session_a = doh_and_doq_pool();
    session_a.record_failure(1, &EncryptedDnsError::Request("DoQ connect timeout".into()), 10);
    assert!(!session_a.try_order().contains(&1));
    assert!(doh_and_doq_pool().try_order().contains(&1));
}

#[tokio::test]
async fn cached_success_for_demoted_doq_is_not_promoted() {
    let pool = ResolverPool::builder()
        .add_endpoint(google_doh_endpoint(), EncryptedDnsTransport::Direct)
        .add_endpoint(doq_endpoint("dns.adguard-dns.com"), EncryptedDnsTransport::Direct)
        .add_endpoint(cloudflare_doh_endpoint(), EncryptedDnsTransport::Direct)
        .build()
        .unwrap();
    let doq_key = pool.inner.keys[1].clone();
    pool.inner
        .fallback_cache
        .lock()
        .unwrap()
        .put(fallback_key(pool.network_scope(), &doq_key), FallbackEntry { last_success: Instant::now() });
    pool.record_failure(1, &EncryptedDnsError::Request("DoQ connect timeout".into()), 10);
    for _ in 0..4 {
        let order = pool.try_order();
        assert_ne!(order[0], 1);
        assert!(!order.contains(&1));
    }
}

#[tokio::test]
async fn exchange_without_eligible_resolver_reports_it() {
    let pool = ResolverPool::builder()
        .add_endpoint(doq_endpoint("dns.adguard-dns.com"), EncryptedDnsTransport::Direct)
        .add_endpoint(doq_endpoint("dns.example.net"), EncryptedDnsTransport::Direct)
        .build()
        .unwrap();
    for idx in 0..2 {
        pool.record_failure(idx, &EncryptedDnsError::Request("DoQ connect timeout".into()), 10);
    }
    let query = b"\x12\x34\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00\x07example\x03com\x00\x00\x01\x00\x01";
    let err = pool.exchange(query).await.unwrap_err();
    assert!(matches!(&err, EncryptedDnsError::InvalidEndpoint(m) if m == "no eligible resolver in pool"), "{err:?}");
}

#[tokio::test]
async fn dot_and_doq_on_one_host_port_keep_separate_health() {
    let pool = ResolverPool::builder()
        .add_endpoint(
            tls_endpoint(EncryptedDnsProtocol::Dot, "dns.adguard-dns.com", 853),
            EncryptedDnsTransport::Direct,
        )
        .add_endpoint(doq_endpoint("dns.adguard-dns.com"), EncryptedDnsTransport::Direct)
        .build()
        .unwrap();
    assert_ne!(pool.inner.keys[0], pool.inner.keys[1]);
    assert_eq!(pool.inner.labels[0], pool.inner.labels[1], "the public label stays shared");

    pool.record_failure(1, &EncryptedDnsError::Request("DoQ connect timeout".into()), 10);
    let health = pool.health_registry();
    assert_eq!(health.observation_count_in_scope(pool.network_scope(), &pool.inner.keys[0]), 0);
    assert_eq!(health.observation_count_in_scope(pool.network_scope(), &pool.inner.keys[1]), 1);
    assert!(pool.try_order().contains(&0), "DoT is not demoted by the DoQ failure");

    pool.record_oracle_observation("dns.adguard-dns.com:853", ResolverOracleObservation::Agreement);
    let dot = health.snapshot_in_scope(pool.network_scope(), &pool.inner.keys[0]).unwrap();
    let doq = health.snapshot_in_scope(pool.network_scope(), &pool.inner.keys[1]).unwrap();
    assert_eq!((dot.oracle_observation_count, doq.oracle_observation_count), (1, 1));
}

#[test]
fn quarantined_endpoint_is_never_injected_by_round_robin() {
    let quad9 = EncryptedDnsEndpoint {
        doh_url: Some("https://dns.quad9.net/dns-query".to_string()),
        host: "dns.quad9.net".to_string(),
        ..google_doh_endpoint()
    };
    let pool = ResolverPool::builder()
        .add_endpoint(google_doh_endpoint(), EncryptedDnsTransport::Direct)
        .add_endpoint(cloudflare_doh_endpoint(), EncryptedDnsTransport::Direct)
        .add_endpoint(quad9, EncryptedDnsTransport::Direct)
        .build()
        .unwrap();
    for key in &pool.inner.keys[..2] {
        for _ in 0..5 {
            pool.health_registry().record_endpoint_outcome_in_scope(pool.network_scope(), key, true, 20);
        }
    }
    let quarantined_label = pool.inner.labels[2].clone();
    pool.record_oracle_observation(&quarantined_label, ResolverOracleObservation::Poisoned);

    for _ in 0..9 {
        let order = pool.try_order();
        assert_eq!(order.len(), 3);
        assert!(!order[..2].contains(&2), "quarantined endpoint moved into the top two: {order:?}");
    }
}

#[tokio::test]
async fn dropped_attempts_are_recorded_as_failures() {
    use tokio::net::TcpListener;

    let mut endpoints = Vec::new();
    let mut listeners = Vec::new();
    for _ in 0..2 {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let mut endpoint = tls_endpoint(EncryptedDnsProtocol::Dot, "localhost", port);
        endpoint.bootstrap_ips = vec![IpAddr::V4(Ipv4Addr::LOCALHOST)];
        endpoints.push(endpoint);
        // Accept and keep the socket open without ever answering.
        listeners.push(tokio::spawn(async move {
            let mut held = Vec::new();
            while let Ok((stream, _)) = listener.accept().await {
                held.push(stream);
            }
        }));
    }
    let mut builder = ResolverPool::builder().timeout(Duration::from_secs(2));
    for endpoint in endpoints {
        builder = builder.add_endpoint(endpoint, EncryptedDnsTransport::Direct);
    }
    let pool = builder.build().unwrap();
    let query = b"\x12\x34\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00\x07example\x03com\x00\x00\x01\x00\x01";

    let first_before = pool.try_order()[0];
    drop(pool.exchange(query));
    assert_eq!(pool.try_order()[0], first_before, "an unpolled exchange must not affect ranking");
    for key in &pool.inner.keys {
        assert_eq!(pool.health_registry().observation_count_in_scope(pool.network_scope(), key), 0);
    }
    let _ = tokio::time::timeout(Duration::from_millis(100), pool.exchange(query)).await;
    let first_after = pool.try_order()[0];
    assert_ne!(first_before, first_after, "a cancelled attempt must demote the resolver it hit");
    let hit_key = &pool.inner.keys[first_before];
    assert_eq!(pool.health_registry().observation_count_in_scope(pool.network_scope(), hit_key), 1);
    for _ in 0..2 {
        let _ = tokio::time::timeout(Duration::from_millis(100), pool.exchange(query)).await;
    }
    for key in &pool.inner.keys {
        assert!(pool.health_registry().observation_count_in_scope(pool.network_scope(), key) >= 1, "{key}");
    }
    for listener in listeners {
        listener.abort();
    }
}

#[test]
fn ranking_persists_only_within_the_same_network_scope() {
    let shared = HealthRegistry::new(Duration::from_secs(60));
    let wifi = ResolverNetworkScope::new("wifi:alpha");
    let cellular = ResolverNetworkScope::new("cell:beta");
    let google_label = "doh|https://dns.google/dns-query";
    let cloudflare_label = "doh|https://cloudflare-dns.com/dns-query";

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
        shared.record_bootstrap_outcome_in_scope(&wifi, primary, 443, false, false, 4000);
        shared.record_bootstrap_outcome_in_scope(&wifi, secondary, 443, false, true, 20);
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
