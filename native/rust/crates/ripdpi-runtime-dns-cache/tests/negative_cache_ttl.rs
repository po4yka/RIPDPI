use std::time::Duration;

use ripdpi_runtime_dns_cache::{NegativeCachePolicy, ResolverPath};

const CAP: Duration = Duration::from_secs(30);

/// TTL larger than cap is reduced to cap.
#[test]
fn large_ttl_is_capped() {
    let policy = NegativeCachePolicy::default();
    let capped = policy.cap_ttl(Duration::from_secs(300));
    assert!(capped <= CAP, "expected capped TTL ≤ {CAP:?}, got {capped:?}");
}

/// TTL at zero is still bounded to a small positive value (no zero-TTL storms).
#[test]
fn zero_ttl_returns_positive_bounded_value() {
    let policy = NegativeCachePolicy::default();
    let capped = policy.cap_ttl(Duration::ZERO);
    assert!(capped > Duration::ZERO);
    assert!(capped <= CAP);
}

/// TTL already below cap is returned unchanged.
#[test]
fn small_ttl_below_cap_unchanged() {
    let policy = NegativeCachePolicy::default();
    let input = Duration::from_secs(10);
    let capped = policy.cap_ttl(input);
    assert_eq!(capped, input);
}

/// NegativeCachePolicy carries a resolver_path field.
#[test]
fn negative_cache_policy_preserves_resolver_path() {
    let policy = NegativeCachePolicy { resolver_path: ResolverPath::SystemFallback, ..NegativeCachePolicy::default() };
    assert!(matches!(policy.resolver_path, ResolverPath::SystemFallback));
}
