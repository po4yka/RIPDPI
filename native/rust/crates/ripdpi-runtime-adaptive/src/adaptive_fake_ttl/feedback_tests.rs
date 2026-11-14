use super::*;

fn config() -> AutoTtlConfig {
    AutoTtlConfig { delta: -1, min_ttl: 3, max_ttl: 8 }
}

fn target() -> SocketAddr {
    "127.0.0.1:443".parse().expect("target address")
}

#[test]
fn late_success_pins_the_ttl_that_was_sent() {
    let mut resolver = AdaptiveFakeTtlResolver::default();
    let host = Some("feedback.example");
    let first = resolver.resolve(0, target(), host, config(), Some(8));
    let second = resolver.resolve(0, target(), host, config(), Some(8));
    assert_eq!((first.ttl, second.ttl), (8, 8));
    resolver.note_failure(0, target(), host, second);
    resolver.note_success(0, target(), host, first);
    let next = resolver.resolve(0, target(), host, config(), Some(8));
    assert_eq!(next.ttl, 8);
    resolver.note_failure(0, target(), host, next);
    assert_eq!(resolver.resolve(0, target(), host, config(), Some(8)).ttl, 7);
}

#[test]
fn late_failure_does_not_clear_a_successful_pin() {
    let mut resolver = AdaptiveFakeTtlResolver::default();
    let host = Some("feedback.example");
    let first = resolver.resolve(0, target(), host, config(), Some(8));
    let second = resolver.resolve(0, target(), host, config(), Some(8));
    resolver.note_success(0, target(), host, first);
    resolver.note_failure(0, target(), host, second);
    let next = resolver.resolve(0, target(), host, config(), Some(8));
    assert_eq!(next.ttl, 8);
    resolver.note_failure(0, target(), host, next);
    assert_eq!(resolver.resolve(0, target(), host, config(), Some(8)).ttl, 7);
}

fn evict_first_target(resolver: &mut AdaptiveFakeTtlResolver) {
    for index in 0..MAX_FAKE_TTL_STATES {
        resolver.resolve(0, target(), Some(&format!("other-{index}.example")), config(), Some(8));
    }
}

#[test]
fn evicted_failure_does_not_change_the_recreated_target() {
    let mut resolver = AdaptiveFakeTtlResolver::default();
    let host = Some("feedback.example");
    let old = resolver.resolve(0, target(), host, config(), Some(8));
    evict_first_target(&mut resolver);
    assert_eq!(resolver.resolve(0, target(), host, config(), Some(5)).ttl, 5);
    resolver.note_failure(0, target(), host, old);
    assert_eq!(resolver.resolve(0, target(), host, config(), Some(5)).ttl, 5);
}

#[test]
fn evicted_server_ttl_does_not_seed_the_recreated_target() {
    let mut resolver = AdaptiveFakeTtlResolver::default();
    let host = Some("feedback.example");
    let old = resolver.resolve(0, target(), host, config(), Some(8));
    evict_first_target(&mut resolver);
    assert_eq!(resolver.resolve(0, target(), host, config(), Some(5)).ttl, 5);
    resolver.note_server_ttl(0, target(), host, 56, old);
    assert_eq!(resolver.resolve(0, target(), host, config(), Some(5)).ttl, 5);
}

#[test]
fn measured_server_ttl_does_not_erase_a_successful_attempt() {
    let mut resolver = AdaptiveFakeTtlResolver::default();
    let host = Some("feedback.example");
    let attempt = resolver.resolve(0, target(), host, config(), Some(8));
    resolver.note_server_ttl(0, target(), host, 56, attempt);
    resolver.note_success(0, target(), host, attempt);
    assert_eq!(resolver.resolve(0, target(), host, config(), Some(8)).ttl, 8);
}

#[test]
fn config_rebuild_ignores_old_success_and_server_ttl() {
    let mut resolver = AdaptiveFakeTtlResolver::default();
    let host = Some("feedback.example");
    let old = resolver.resolve(0, target(), host, config(), Some(8));
    let updated = AutoTtlConfig { delta: 1, min_ttl: 3, max_ttl: 5 };
    assert_eq!(resolver.resolve(0, target(), host, updated, Some(5)).ttl, 5);
    resolver.note_success(0, target(), host, old);
    resolver.note_server_ttl(0, target(), host, 60, old);
    assert_eq!(resolver.resolve(0, target(), host, updated, Some(5)).ttl, 5);
}

#[test]
fn older_server_ttl_does_not_change_a_newer_successful_revision() {
    let mut resolver = AdaptiveFakeTtlResolver::default();
    let host = Some("feedback.example");
    let first = resolver.resolve(0, target(), host, config(), Some(8));
    let second = resolver.resolve(0, target(), host, config(), Some(8));
    resolver.note_success(0, target(), host, second);
    resolver.note_server_ttl(0, target(), host, 60, first);
    let current = resolver.resolve(0, target(), host, config(), Some(8));
    resolver.note_failure(0, target(), host, current);
    assert_eq!(resolver.resolve(0, target(), host, config(), Some(8)).ttl, 7);
}
