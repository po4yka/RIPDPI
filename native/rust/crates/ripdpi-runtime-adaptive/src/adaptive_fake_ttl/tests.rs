use super::*;

fn config(delta: i8, min_ttl: u8, max_ttl: u8) -> AutoTtlConfig {
    AutoTtlConfig { delta, min_ttl, max_ttl }
}

fn addr(port: u16) -> SocketAddr {
    SocketAddr::from(([127, 0, 0, 1], port))
}

#[test]
fn resolver_keeps_at_most_the_most_recent_targets() {
    let mut resolver = AdaptiveFakeTtlResolver::default();
    let cfg = config(-1, 3, 12);
    resolver.resolve(0, addr(443), Some("first.example"), cfg, Some(8));
    for index in 0..MAX_FAKE_TTL_STATES {
        resolver.resolve(0, addr(443), Some(&format!("host-{index}.example")), cfg, Some(8));
    }
    assert_eq!(resolver.states.len(), MAX_FAKE_TTL_STATES);
    let first = AdaptiveFakeTtlKey { group_index: 0, target: AdaptiveFakeTtlTarget::Host("first.example".into()) };
    assert!(!resolver.states.contains_key(&first), "the least recently resolved target is evicted");
}

#[test]
fn negative_delta_prefers_lower_candidates_first() {
    assert_eq!(candidate_order(config(-1, 3, 12), Some(8)), vec![8, 7, 6, 5, 4, 3, 9, 10, 11, 12]);
}

#[test]
fn positive_delta_prefers_higher_candidates_first() {
    assert_eq!(candidate_order(config(1, 3, 12), Some(8)), vec![8, 9, 10, 11, 12, 7, 6, 5, 4, 3]);
}

#[test]
fn zero_delta_alternates_outward_from_seed() {
    assert_eq!(candidate_order(config(0, 3, 12), Some(8)), vec![8, 7, 9, 6, 10, 5, 11, 4, 12, 3]);
}

#[test]
fn success_pins_and_failure_advances_or_wraps() {
    let mut resolver = AdaptiveFakeTtlResolver::default();
    let host = Some("Example.com");
    let target = addr(443);
    let cfg = config(-1, 3, 5);
    let first = resolver.resolve(0, target, host, cfg, Some(5));
    assert_eq!(first.ttl, 5);
    resolver.note_failure(0, target, host, first);
    let second = resolver.resolve(0, target, host, cfg, Some(5));
    assert_eq!(second.ttl, 4);
    resolver.note_success(0, target, host, second);
    let third = resolver.resolve(0, target, host, cfg, Some(5));
    resolver.note_failure(0, target, host, third);
    let fourth = resolver.resolve(0, target, host, cfg, Some(5));
    assert_eq!(fourth.ttl, 3);
    resolver.note_failure(0, target, host, fourth);
    assert_eq!(resolver.resolve(0, target, host, cfg, Some(5)).ttl, 5);
}

#[test]
fn host_and_address_keys_remain_isolated() {
    let mut resolver = AdaptiveFakeTtlResolver::default();
    let config = config(-1, 3, 6);
    let target = addr(443);
    let attempt = resolver.resolve(0, target, Some("video.example"), config, Some(6));
    assert_eq!(attempt.ttl, 6);
    resolver.note_failure(0, target, Some("video.example"), attempt);
    assert_eq!(resolver.resolve(0, target, Some("video.example"), config, Some(6)).ttl, 5);
    assert_eq!(resolver.resolve(0, target, None, config, Some(6)).ttl, 6);
    assert_eq!(resolver.resolve(1, target, Some("video.example"), config, Some(6)).ttl, 6);
}

#[test]
fn detected_from_observed_ttl_computes_hop_count_correctly() {
    assert_eq!(detected_from_observed_ttl(56), 7); // 64-56=8 hops, detected=7
    assert_eq!(detected_from_observed_ttl(117), 10); // 128-117=11 hops, detected=10
    assert_eq!(detected_from_observed_ttl(230), 24); // 255-230=25 hops, detected=24
    assert_eq!(detected_from_observed_ttl(64), 1); // 64-64=0 hops, detected=max(1,0-1)=1
    assert_eq!(detected_from_observed_ttl(1), 62); // 64-1=63 hops, detected=62
}

#[test]
fn note_server_ttl_seeds_candidates_from_detected_hop_count() {
    let mut resolver = AdaptiveFakeTtlResolver::default();
    let cfg = config(-1, 3, 20);
    let target = addr(443);
    let host = Some("hop.example");
    let attempt = resolver.resolve(0, target, host, cfg, Some(8));
    assert_eq!(attempt.ttl, 8);
    resolver.note_server_ttl(0, target, host, 56, attempt);
    resolver.note_failure(0, target, host, attempt);
    assert_eq!(resolver.resolve(0, target, host, cfg, Some(8)).ttl, 7);
}

#[test]
fn note_server_ttl_for_unknown_key_does_not_panic() {
    let mut resolver = AdaptiveFakeTtlResolver::default();
    resolver.note_server_ttl(
        0,
        addr(443),
        Some("unknown.example"),
        60,
        FakeTtlAttempt { ttl: 8, generation: 0, revision: 0 },
    );
}

#[test]
fn detected_fallback_preserved_across_config_rebuild() {
    let mut resolver = AdaptiveFakeTtlResolver::default();
    let target = addr(443);
    let host = Some("rebuild.example");
    let cfg = config(0, 3, 15);
    let attempt = resolver.resolve(0, target, host, cfg, Some(8));
    resolver.note_server_ttl(0, target, host, 58, attempt);
    resolver.note_failure(0, target, host, attempt);
    assert_eq!(resolver.resolve(0, target, host, cfg, Some(8)).ttl, 5);
}
