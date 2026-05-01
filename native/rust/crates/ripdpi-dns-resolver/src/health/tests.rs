use std::cell::Cell;
use std::net::{IpAddr, Ipv4Addr};
use std::sync::Arc;
use std::time::{Duration, Instant};

use crate::types::{ResolverNetworkScope, ResolverOracleObservation};

use super::*;

// A fake clock that advances by one half-life (1 second) per call, keeping each
// EWMA update in a fresh time window (alpha ≈ 0.632 per observation).
thread_local! {
    static FAKE_CLOCK_MILLIS: Cell<u64> = const { Cell::new(0) };
}

fn advancing_fake_clock() -> Instant {
    FAKE_CLOCK_MILLIS.with(|c| {
        let ms = c.get();
        c.set(ms + 1_000);
        Instant::now() + Duration::from_millis(ms)
    })
}

#[test]
fn ewma_converges_toward_full_success_after_repeated_successes() {
    FAKE_CLOCK_MILLIS.with(|c| c.set(0));
    let reg = HealthRegistry::with_clock(Duration::from_secs(1), advancing_fake_clock);
    for _ in 0..20 {
        reg.record_endpoint_outcome("ep", true, 10);
    }
    let snap = reg.snapshot("ep").unwrap();
    assert!(snap.ewma_success_rate > 0.95, "expected success_rate > 0.95, got {}", snap.ewma_success_rate);
}

#[test]
fn ewma_converges_toward_zero_after_repeated_failures() {
    FAKE_CLOCK_MILLIS.with(|c| c.set(0));
    let reg = HealthRegistry::with_clock(Duration::from_secs(1), advancing_fake_clock);
    for _ in 0..20 {
        reg.record_endpoint_outcome("ep", false, 500);
    }
    let snap = reg.snapshot("ep").unwrap();
    assert!(snap.ewma_success_rate < 0.05, "expected success_rate < 0.05, got {}", snap.ewma_success_rate);
}

#[test]
fn ranking_places_healthy_endpoint_before_unhealthy() {
    let reg = HealthRegistry::new(Duration::from_secs(60));
    for _ in 0..30 {
        reg.record_endpoint_outcome("good", true, 30);
    }
    for _ in 0..30 {
        reg.record_endpoint_outcome("bad", false, 3000);
    }
    let ranked = reg.rank_indices(&["bad", "good"]);
    assert_eq!(ranked[0], 1, "good (index 1) should be ranked first");
}

#[test]
fn bootstrap_ranking_places_healthy_ip_first() {
    let reg = HealthRegistry::new(Duration::from_secs(60));
    let good = IpAddr::V4(Ipv4Addr::new(8, 8, 8, 8));
    let bad = IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1));
    for _ in 0..30 {
        reg.record_bootstrap_outcome(good, true, 20);
    }
    for _ in 0..30 {
        reg.record_bootstrap_outcome(bad, false, 4000);
    }
    let ranked = reg.rank_bootstrap_ips(&[bad, good]);
    assert_eq!(ranked[0], good, "good IP should be ranked first");
}

#[test]
fn snapshot_returns_none_for_unknown_label() {
    let reg = HealthRegistry::new(Duration::from_secs(60));
    assert!(reg.snapshot("unknown").is_none());
}

#[test]
fn observation_count_increments_per_record() {
    let reg = HealthRegistry::new(Duration::from_secs(60));
    assert_eq!(reg.observation_count("ep"), 0);
    reg.record_endpoint_outcome("ep", true, 100);
    reg.record_endpoint_outcome("ep", false, 200);
    assert_eq!(reg.observation_count("ep"), 2);
}

#[test]
fn rank_indices_returns_all_indices() {
    let reg = HealthRegistry::new(Duration::from_secs(60));
    let ranked = reg.rank_indices(&["a", "b", "c"]);
    let mut sorted = ranked.clone();
    sorted.sort_unstable();
    assert_eq!(sorted, vec![0, 1, 2]);
}

#[test]
fn repeated_oracle_disagreement_quarantines_endpoint() {
    FAKE_CLOCK_MILLIS.with(|c| c.set(0));
    let reg = HealthRegistry::with_clock(Duration::from_secs(1), advancing_fake_clock);
    for _ in 0..12 {
        reg.record_endpoint_outcome("ep", true, 15);
    }

    reg.record_oracle_observation("ep", ResolverOracleObservation::Disagreement);
    let first = reg.snapshot("ep").unwrap();
    assert!(!first.quarantined);

    reg.record_oracle_observation("ep", ResolverOracleObservation::Disagreement);
    let second = reg.snapshot("ep").unwrap();
    assert!(second.quarantined);
    assert!(second.ewma_oracle_score < 0.2);
}

#[test]
fn partial_overlap_is_less_harsh_than_full_disagreement() {
    let reg = HealthRegistry::new(Duration::from_secs(60));
    for _ in 0..20 {
        reg.record_endpoint_outcome("partial", true, 20);
        reg.record_endpoint_outcome("bad", true, 20);
    }

    reg.record_oracle_observation(
        "partial",
        ResolverOracleObservation::PartialOverlap {
            shared_answers: 2,
            resolver_only_answers: 1,
            oracle_only_answers: 1,
        },
    );
    reg.record_oracle_observation("bad", ResolverOracleObservation::Disagreement);
    reg.record_oracle_observation("bad", ResolverOracleObservation::Disagreement);

    let partial = reg.snapshot("partial").unwrap();
    let bad = reg.snapshot("bad").unwrap();
    assert!(!partial.quarantined, "partial overlap should tolerate CDN-style variance");
    assert!(bad.quarantined, "repeated disagreement should quarantine");

    let ranked = reg.rank_indices(&["bad", "partial"]);
    assert_eq!(ranked[0], 1);
}

#[test]
fn scoped_health_is_isolated_by_network_scope() {
    let reg = HealthRegistry::new(Duration::from_secs(60));
    let wifi = ResolverNetworkScope::new("wifi:alpha");
    let cellular = ResolverNetworkScope::new("cell:carrier");

    for _ in 0..20 {
        reg.record_endpoint_outcome_in_scope(&wifi, "resolver-a", false, 3000);
        reg.record_endpoint_outcome_in_scope(&wifi, "resolver-b", true, 25);
    }

    let wifi_ranked = reg.rank_indices_in_scope(&wifi, &["resolver-a", "resolver-b"]);
    assert_eq!(wifi_ranked[0], 1);

    let cellular_ranked = reg.rank_indices_in_scope(&cellular, &["resolver-a", "resolver-b"]);
    assert_eq!(cellular_ranked[0], 0, "fresh scope should preserve configured order");
}

#[test]
fn network_scope_can_separate_ipv4_and_ipv6_rankings() {
    let reg = HealthRegistry::new(Duration::from_secs(60));
    let wifi_ipv4 = ResolverNetworkScope::new("wifi:alpha|carrier:none|ip:v4|transport:doh");
    let wifi_ipv6 = ResolverNetworkScope::new("wifi:alpha|carrier:none|ip:v6|transport:doh");

    for _ in 0..20 {
        reg.record_endpoint_outcome_in_scope(&wifi_ipv4, "resolver-a", false, 4000);
        reg.record_endpoint_outcome_in_scope(&wifi_ipv4, "resolver-b", true, 20);
        reg.record_endpoint_outcome_in_scope(&wifi_ipv6, "resolver-a", true, 25);
        reg.record_endpoint_outcome_in_scope(&wifi_ipv6, "resolver-b", false, 4000);
    }

    assert_eq!(
        reg.rank_indices_in_scope(&wifi_ipv4, &["resolver-a", "resolver-b"])[0],
        1,
        "ipv4 scope should prefer resolver-b"
    );
    assert_eq!(
        reg.rank_indices_in_scope(&wifi_ipv6, &["resolver-a", "resolver-b"])[0],
        0,
        "ipv6 scope should keep its own preference history"
    );
}

#[test]
fn thread_safety_concurrent_record_and_rank() {
    let reg = Arc::new(HealthRegistry::new(Duration::from_secs(60)));
    let mut handles = vec![];
    for i in 0..8 {
        let reg = Arc::clone(&reg);
        let label = format!("ep-{}", i % 3);
        handles.push(std::thread::spawn(move || {
            for _ in 0..100 {
                reg.record_endpoint_outcome(&label, i % 2 == 0, 50 + i as u64 * 10);
                let _ = reg.rank_indices(&["ep-0", "ep-1", "ep-2"]);
            }
        }));
    }
    for h in handles {
        h.join().unwrap();
    }
}
