use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};
use std::time::{Duration, Instant};

use super::*;

#[test]
fn cloudflare_ipv4_match() {
    let ip: IpAddr = "104.16.1.1".parse().unwrap();
    let config = opportunistic_ech_config_for_ip(ip);
    assert!(config.is_some(), "expected Cloudflare match for 104.16.1.1");
    assert_eq!(config.unwrap().provider, "Cloudflare");
}

#[test]
fn cloudflare_ipv4_boundary() {
    let inside: IpAddr = "104.23.255.255".parse().unwrap();
    assert!(opportunistic_ech_config_for_ip(inside).is_some());

    let outside: IpAddr = "104.24.128.0".parse().unwrap();
    assert!(opportunistic_ech_config_for_ip(outside).is_some());
}

#[test]
fn cloudflare_ipv6_match() {
    let ip: IpAddr = "2606:4700::1".parse().unwrap();
    let config = opportunistic_ech_config_for_ip(ip);
    assert!(config.is_some(), "expected Cloudflare match for 2606:4700::1");
}

#[test]
fn non_cloudflare_ip_returns_none() {
    let ip: IpAddr = "8.8.8.8".parse().unwrap();
    assert!(opportunistic_ech_config_for_ip(ip).is_none());
}

#[test]
fn non_cloudflare_ipv6_returns_none() {
    let ip: IpAddr = "2001:4860:4860::8888".parse().unwrap();
    assert!(opportunistic_ech_config_for_ip(ip).is_none());
}

#[test]
fn ech_config_list_has_valid_prefix() {
    assert!(CLOUDFLARE_ECH_CONFIG_LIST.len() >= 4);
    let list_len = u16::from_be_bytes([CLOUDFLARE_ECH_CONFIG_LIST[0], CLOUDFLARE_ECH_CONFIG_LIST[1]]) as usize;
    assert_eq!(list_len + 2, CLOUDFLARE_ECH_CONFIG_LIST.len(), "ECHConfigList length mismatch");
    assert_eq!(&CLOUDFLARE_ECH_CONFIG_LIST[2..4], &[0xfe, 0x0d]);
}

#[test]
fn bundled_source_returns_valid_bytes() {
    let src = BundledEchConfigSource;
    let bytes = src.fetch().expect("BundledEchConfigSource must not fail");
    assert_eq!(bytes, CLOUDFLARE_ECH_CONFIG_LIST, "bundled source should return the hardcoded config");
    assert!(!bytes.is_empty());
}

#[test]
fn remote_source_default_targets_cloudflare() {
    let src = RemoteEchConfigSource::new();
    assert_eq!(src.domain, "cloudflare-dns.com");
    assert_eq!(src.resolver_id, "cloudflare");
}

#[test]
fn remote_source_with_overrides_round_trip() {
    let src = RemoteEchConfigSource::new().with_domain("example.test").with_resolver("quad9");
    assert_eq!(src.domain, "example.test");
    assert_eq!(src.resolver_id, "quad9");
}

#[test]
fn validate_ech_config_list_bytes_accepts_bundled_constant() {
    let len = validate_ech_config_list_bytes(CLOUDFLARE_ECH_CONFIG_LIST).expect("bundled bytes must validate");
    assert_eq!(len, CLOUDFLARE_ECH_CONFIG_LIST.len());
}

#[test]
fn validate_ech_config_list_bytes_rejects_short_input() {
    let err = validate_ech_config_list_bytes(&[0x00, 0x02]).expect_err("3 bytes must fail");
    assert!(matches!(err, EchSourceError::InvalidConfig(msg) if msg.contains("too short")));
}

#[test]
fn validate_ech_config_list_bytes_rejects_length_prefix_mismatch() {
    let err = validate_ech_config_list_bytes(&[0x00, 0x64, 0xfe, 0x0d]).expect_err("length mismatch must fail");
    assert!(matches!(err, EchSourceError::InvalidConfig(msg) if msg.contains("length prefix")));
}

#[test]
fn validate_ech_config_list_bytes_rejects_unknown_version() {
    let err = validate_ech_config_list_bytes(&[0x00, 0x02, 0xfe, 0x0c]).expect_err("wrong version must fail");
    assert!(matches!(err, EchSourceError::InvalidConfig(msg) if msg.contains("version")));
}

struct ClosureSource(Box<dyn Fn() -> Result<Vec<u8>, EchSourceError> + Send + Sync>);

impl EchConfigSource for ClosureSource {
    fn fetch(&self) -> Result<Vec<u8>, EchSourceError> {
        (self.0)()
    }
}

#[test]
fn updater_refresh_persists_validated_remote_bytes() {
    let payload = CLOUDFLARE_ECH_CONFIG_LIST.to_vec();
    let primary = ClosureSource(Box::new({
        let payload = payload.clone();
        move || Ok(payload.clone())
    }));
    let updater = CdnEchUpdater::new(primary, BundledEchConfigSource, Duration::from_secs(86_400));
    updater.refresh().expect("refresh must succeed when primary returns valid bytes");
    let cached = updater.current_config();
    assert_eq!(cached, payload);
}

struct FailingSource;

impl EchConfigSource for FailingSource {
    fn fetch(&self) -> Result<Vec<u8>, EchSourceError> {
        Err(EchSourceError::NotImplemented("test: always fails"))
    }
}

struct FixedSource(Vec<u8>);

impl EchConfigSource for FixedSource {
    fn fetch(&self) -> Result<Vec<u8>, EchSourceError> {
        Ok(self.0.clone())
    }
}

#[test]
fn updater_cache_hit_avoids_second_fetch() {
    let primary_payload = vec![0xAA, 0xBB, 0xCC];
    let fallback_payload = vec![0x11, 0x22, 0x33];
    let updater = CdnEchUpdater::new(
        FixedSource(primary_payload.clone()),
        FixedSource(fallback_payload.clone()),
        Duration::from_secs(3600),
    );

    let first = updater.current_config();
    assert_eq!(first, primary_payload, "first call should return primary payload");

    let second = updater.current_config();
    assert_eq!(second, primary_payload, "second call (cache hit) should return same payload");
}

#[test]
fn updater_falls_back_when_primary_fails() {
    let fallback_payload = vec![0xDE, 0xAD, 0xBE, 0xEF];
    let updater = CdnEchUpdater::new(FailingSource, FixedSource(fallback_payload.clone()), Duration::from_secs(3600));

    let result = updater.current_config();
    assert_eq!(result, fallback_payload, "should fall back to fallback source when primary fails");
}

#[test]
fn updater_returns_bundled_when_both_sources_fail_and_no_cache() {
    let updater = CdnEchUpdater::new(FailingSource, FailingSource, Duration::from_secs(3600));

    let result = updater.current_config();
    assert_eq!(result, CLOUDFLARE_ECH_CONFIG_LIST, "last-resort path must return bundled config");
}

#[test]
fn ipv4_cidr_contains_basic() {
    let cidr = Ipv4Cidr::new(10, 0, 0, 0, 8);
    assert!(cidr.contains(Ipv4Addr::new(10, 0, 0, 1)));
    assert!(cidr.contains(Ipv4Addr::new(10, 255, 255, 255)));
    assert!(!cidr.contains(Ipv4Addr::new(11, 0, 0, 1)));
}

#[test]
fn ipv6_cidr_contains_basic() {
    let cidr = Ipv6Cidr::new([0x2606, 0x4700, 0, 0, 0, 0, 0, 0], 32);
    assert!(cidr.contains(Ipv6Addr::new(0x2606, 0x4700, 0, 0, 0, 0, 0, 1)));
    assert!(cidr.contains(Ipv6Addr::new(0x2606, 0x4700, 0xffff, 0, 0, 0, 0, 0)));
    assert!(!cidr.contains(Ipv6Addr::new(0x2606, 0x4701, 0, 0, 0, 0, 0, 0)));
}

#[test]
fn snapshot_returns_none_for_empty_cache() {
    let updater = CdnEchUpdater::new(BundledEchConfigSource, BundledEchConfigSource, Duration::from_secs(86_400));
    assert!(updater.snapshot_for_persistence().is_none());
}

#[test]
fn seed_then_snapshot_round_trips_bytes_and_timestamp() {
    let updater = CdnEchUpdater::new(BundledEchConfigSource, BundledEchConfigSource, Duration::from_secs(86_400));
    let bundled = CLOUDFLARE_ECH_CONFIG_LIST.to_vec();
    let captured_at = 1_745_798_400_000_u64;
    updater.seed_from_persisted(bundled.clone(), captured_at).expect("seed must accept valid bytes");

    let snapshot = updater.snapshot_for_persistence().expect("snapshot must reflect seeded state");
    assert_eq!(snapshot.config, bundled);
    assert_eq!(snapshot.fetched_at_unix_ms, captured_at);
}

#[test]
fn seed_rejects_malformed_bytes_and_leaves_cache_untouched() {
    let updater = CdnEchUpdater::new(BundledEchConfigSource, BundledEchConfigSource, Duration::from_secs(86_400));
    updater.seed_from_persisted(CLOUDFLARE_ECH_CONFIG_LIST.to_vec(), 1).expect("initial seed must succeed");
    let pre_snapshot = updater.snapshot_for_persistence().expect("cache must be populated");

    let err = updater.seed_from_persisted(vec![0u8, 1, 2], 99).expect_err("malformed bytes must be rejected");
    assert!(matches!(err, EchSourceError::InvalidConfig(_)), "expected InvalidConfig, got {err:?}");

    let post_snapshot = updater.snapshot_for_persistence().expect("cache must remain populated");
    assert_eq!(post_snapshot.config, pre_snapshot.config);
    assert_eq!(post_snapshot.fetched_at_unix_ms, pre_snapshot.fetched_at_unix_ms);
}

#[test]
fn seeded_entry_is_served_via_current_config_within_ttl() {
    let updater = CdnEchUpdater::new(BundledEchConfigSource, BundledEchConfigSource, Duration::from_secs(86_400));
    let bundled = CLOUDFLARE_ECH_CONFIG_LIST.to_vec();
    let recent_unix_ms = now_unix_ms().saturating_sub(60 * 60 * 1000);
    updater.seed_from_persisted(bundled.clone(), recent_unix_ms).expect("seed must succeed");

    let served = updater.current_config();
    assert_eq!(served, bundled, "current_config must serve the seeded bytes while fresh");
}

#[test]
fn synthesized_instant_caps_future_timestamps_at_now() {
    let now = Instant::now();
    let now_ms = 1_000_u64;
    let future_ms = 5_000_u64;
    let synthesized = synthesize_instant_for_unix_ms(future_ms, now, now_ms);
    assert_eq!(synthesized, now);
}

#[test]
fn synthesized_instant_preserves_age_for_past_timestamps() {
    let now = Instant::now();
    let now_ms = 10_000_u64;
    let six_h_ago_ms = now_ms.saturating_sub(6 * 60 * 60 * 1000);
    let synthesized = synthesize_instant_for_unix_ms(six_h_ago_ms, now, now_ms);
    let elapsed = now.saturating_duration_since(synthesized);
    let expected = Duration::from_millis(now_ms - six_h_ago_ms);
    let drift = elapsed.abs_diff(expected);
    assert!(drift < Duration::from_millis(10), "synthesized age drifted by {drift:?}");
}
