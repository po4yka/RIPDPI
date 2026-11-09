use std::cell::Cell;
use std::net::{IpAddr, Ipv4Addr};
use std::sync::Arc;
use std::time::{Duration, Instant};

use crate::types::{ResolverNetworkScope, ResolverOracleObservation};

use super::*;

// A manual clock: time moves only when a test calls `set_ms` or `advance_ms`.
thread_local! {
    static CLOCK_BASE: Instant = Instant::now();
    static CLOCK_MILLIS: Cell<u64> = const { Cell::new(0) };
}

fn manual_clock() -> Instant {
    CLOCK_BASE.with(|base| *base + Duration::from_millis(CLOCK_MILLIS.with(Cell::get)))
}

fn set_ms(ms: u64) {
    CLOCK_MILLIS.with(|c| c.set(ms));
}

fn advance_ms(ms: u64) {
    CLOCK_MILLIS.with(|c| c.set(c.get() + ms));
}

fn manual_registry(half_life: Duration) -> HealthRegistry {
    set_ms(0);
    HealthRegistry::with_clock(half_life, manual_clock)
}

#[test]
fn ewma_converges_toward_full_success_after_repeated_successes() {
    let reg = manual_registry(Duration::from_secs(60));
    for _ in 0..20 {
        reg.record_endpoint_outcome("ep", true, 10);
        advance_ms(10);
    }
    let snap = reg.snapshot("ep").unwrap();
    assert!(snap.ewma_success_rate > 0.95, "expected success_rate > 0.95, got {}", snap.ewma_success_rate);
}

#[test]
fn ewma_converges_toward_zero_after_repeated_failures() {
    let reg = manual_registry(Duration::from_secs(60));
    for _ in 0..20 {
        reg.record_endpoint_outcome("ep", false, 500);
        advance_ms(10);
    }
    let snap = reg.snapshot("ep").unwrap();
    assert!(snap.ewma_success_rate < 0.05, "expected success_rate < 0.05, got {}", snap.ewma_success_rate);
}

#[test]
fn burst_of_failures_close_in_time_is_not_ignored() {
    let reg = manual_registry(Duration::from_secs(60));
    set_ms(600_000);
    reg.record_endpoint_outcome("failing", true, 20);
    for _ in 0..50 {
        advance_ms(10);
        reg.record_endpoint_outcome("failing", false, 4000);
    }
    let snap = reg.snapshot("failing").unwrap();
    assert!(snap.ewma_success_rate < 0.4, "50 close failures must lower success_rate, got {}", snap.ewma_success_rate);
    assert_eq!(reg.rank_indices(&["failing", "never-seen"])[0], 1, "failing endpoint must not outrank an unseen one");
}

#[test]
fn one_sample_after_full_decay_moves_score_by_at_most_alpha() {
    let reg = manual_registry(Duration::from_secs(60));
    for _ in 0..100 {
        advance_ms(60_000);
        reg.record_endpoint_outcome("ep", false, 3000);
    }
    advance_ms(3_600_000);
    reg.record_endpoint_outcome("ep", true, 20);
    let snap = reg.snapshot("ep").unwrap();
    // After the 1 h pause the history is back at the 0.5 prior; one success adds 0.3 * (1 - 0.5).
    assert!((snap.ewma_success_rate - 0.65).abs() < 1e-6, "got {}", snap.ewma_success_rate);
}

#[test]
fn idle_scores_decay_toward_the_prior_on_read() {
    let reg = manual_registry(Duration::from_secs(60));
    for _ in 0..20 {
        reg.record_endpoint_outcome("ep", false, 4000);
        advance_ms(10);
    }
    let fresh = reg.snapshot("ep").unwrap();
    assert!(fresh.ewma_success_rate < 0.05);
    advance_ms(24 * 3_600_000);
    let idle = reg.snapshot("ep").unwrap();
    assert!((idle.ewma_success_rate - 0.5).abs() < 0.01, "got {}", idle.ewma_success_rate);
    assert!((idle.ewma_latency_ms - 200.0).abs() < 5.0, "got {}", idle.ewma_latency_ms);
}

#[test]
fn oracle_observation_does_not_change_transport_decay() {
    let run = |with_oracle: bool| {
        let reg = manual_registry(Duration::from_secs(60));
        reg.record_endpoint_outcome("ep", true, 20);
        advance_ms(10_000);
        if with_oracle {
            reg.record_oracle_observation("ep", ResolverOracleObservation::Agreement);
        }
        advance_ms(1);
        reg.record_endpoint_outcome("ep", false, 4000);
        reg.snapshot("ep").unwrap().ewma_success_rate
    };
    let without = run(false);
    let with = run(true);
    assert!((with - without).abs() < 1e-12, "oracle observation changed success_rate: {with} vs {without}");
}

#[test]
fn scope_count_is_bounded_and_oldest_write_is_evicted() {
    let reg = manual_registry(Duration::from_secs(60));
    for i in 0..100 {
        advance_ms(1);
        reg.record_endpoint_outcome_in_scope(&ResolverNetworkScope::new(format!("scope-{i}")), "ep", true, 10);
    }
    assert_eq!(reg.scope_count(), 32);
    assert!(reg.snapshot_in_scope(&ResolverNetworkScope::new("scope-0"), "ep").is_none());
    assert!(reg.snapshot_in_scope(&ResolverNetworkScope::new("scope-99"), "ep").is_some());
}

#[test]
fn read_paths_do_not_create_scopes() {
    let reg = manual_registry(Duration::from_secs(60));
    let scope = ResolverNetworkScope::new("read-only");
    let _ = reg.rank_indices_in_scope(&scope, &["a", "b"]);
    let _ = reg.rank_bootstrap_ips_in_scope(&scope, &[Ipv4Addr::new(1, 2, 3, 4).into()], 443, false);
    assert_eq!(reg.scope_count(), 0);
}

#[test]
fn bootstrap_health_is_keyed_by_port() {
    let reg = HealthRegistry::new(Duration::from_secs(60));
    let flaky: IpAddr = Ipv4Addr::new(1, 2, 3, 4).into();
    let other: IpAddr = Ipv4Addr::new(5, 6, 7, 8).into();
    for _ in 0..10 {
        reg.record_bootstrap_outcome(flaky, 853, false, false, 4000);
    }
    assert_eq!(reg.rank_bootstrap_ips(&[flaky, other], 853, false), vec![other, flaky]);
    assert_eq!(reg.rank_bootstrap_ips(&[flaky, other], 443, false), vec![flaky, other], "port 443 has no history");
    assert_eq!(reg.rank_bootstrap_ips(&[flaky, other], 853, true), vec![flaky, other], "UDP 853 has no history");
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
        reg.record_bootstrap_outcome(good, 443, false, true, 20);
    }
    for _ in 0..30 {
        reg.record_bootstrap_outcome(bad, 443, false, false, 4000);
    }
    let ranked = reg.rank_bootstrap_ips(&[bad, good], 443, false);
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
    let reg = manual_registry(Duration::from_secs(60));
    for _ in 0..12 {
        reg.record_endpoint_outcome("ep", true, 15);
        advance_ms(10);
    }

    reg.record_oracle_observation("ep", ResolverOracleObservation::Disagreement);
    let first = reg.snapshot("ep").unwrap();
    assert!(!first.quarantined);

    reg.record_oracle_observation("ep", ResolverOracleObservation::Disagreement);
    let second = reg.snapshot("ep").unwrap();
    assert!(second.quarantined);
    // 0.5 * 0.7 * 0.7, derived by hand.
    assert!((second.ewma_oracle_score - 0.245).abs() < 1e-3, "got {}", second.ewma_oracle_score);
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
