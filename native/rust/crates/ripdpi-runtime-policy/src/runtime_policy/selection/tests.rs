use std::collections::BTreeMap;
use std::net::IpAddr;

use ripdpi_config::{DETECT_RECONN, DesyncGroup};
use ripdpi_failure_classifier::BlockSignal;

use crate::runtime_policy::test_support::{autolearn_config, config_with_groups, sample_dest};
use crate::runtime_policy::types::LearnedGroupStats;
use crate::runtime_policy::{
    ConnectionRoute, GeoMatcher, RetrySelectionPenalty, RuntimePolicy, TransportProtocol, now_millis,
};

#[test]
fn select_initial_group_skips_detect_only_groups() {
    let mut first = DesyncGroup::new(0);
    first.matches.detect = DETECT_RECONN;
    let second = DesyncGroup::new(1);
    let config = config_with_groups(vec![first, second]);
    let mut policy = RuntimePolicy::load(&config);

    let route = policy
        .select_initial(sample_dest(80), None, None, true, TransportProtocol::Tcp, &config)
        .expect("fallback route");

    assert_eq!(route.group_index, 1);
    assert_eq!(route.attempted_mask, 0);
}

#[test]
fn select_next_group_advances_to_matching_trigger_group() {
    let first = DesyncGroup::new(0);
    let mut second = DesyncGroup::new(1);
    second.matches.detect = DETECT_RECONN;
    let config = config_with_groups(vec![first, second]);
    let policy = RuntimePolicy::load(&config);
    let route = ConnectionRoute { group_index: 0, attempted_mask: 0 };

    let next = policy
        .select_next(&config, &route, sample_dest(443), None, None, TransportProtocol::Tcp, DETECT_RECONN, true, None)
        .expect("next route");

    assert_eq!(next.group_index, 1);
    assert_eq!(next.attempted_mask, config.groups[0].bit);
}

#[test]
fn select_next_group_prefers_non_cooled_candidate_when_retry_penalties_exist() {
    let first = DesyncGroup::new(0);
    let mut second = DesyncGroup::new(1);
    second.matches.detect = DETECT_RECONN;
    let mut third = DesyncGroup::new(2);
    third.matches.detect = DETECT_RECONN;
    let config = config_with_groups(vec![first, second, third]);
    let policy = RuntimePolicy::load(&config);
    let route = ConnectionRoute { group_index: 0, attempted_mask: 0 };
    let penalties = BTreeMap::from([
        (
            1usize,
            RetrySelectionPenalty {
                same_signature_cooldown_ms: 1_000,
                family_cooldown_ms: 0,
                diversification_rank: 10,
            },
        ),
        (
            2usize,
            RetrySelectionPenalty { same_signature_cooldown_ms: 0, family_cooldown_ms: 0, diversification_rank: 20 },
        ),
    ]);

    let next = policy
        .select_next(
            &config,
            &route,
            sample_dest(443),
            None,
            None,
            TransportProtocol::Tcp,
            DETECT_RECONN,
            true,
            Some(&penalties),
        )
        .expect("next route");

    assert_eq!(next.group_index, 2);
}

#[test]
fn select_next_group_uses_diversification_rank_as_tiebreaker() {
    let first = DesyncGroup::new(0);
    let mut second = DesyncGroup::new(1);
    second.matches.detect = DETECT_RECONN;
    let mut third = DesyncGroup::new(2);
    third.matches.detect = DETECT_RECONN;
    let config = config_with_groups(vec![first, second, third]);
    let policy = RuntimePolicy::load(&config);
    let route = ConnectionRoute { group_index: 0, attempted_mask: 0 };
    let penalties = BTreeMap::from([
        (
            1usize,
            RetrySelectionPenalty { same_signature_cooldown_ms: 0, family_cooldown_ms: 0, diversification_rank: 25 },
        ),
        (
            2usize,
            RetrySelectionPenalty { same_signature_cooldown_ms: 0, family_cooldown_ms: 0, diversification_rank: 5 },
        ),
    ]);

    let next = policy
        .select_next(
            &config,
            &route,
            sample_dest(443),
            None,
            None,
            TransportProtocol::Tcp,
            DETECT_RECONN,
            true,
            Some(&penalties),
        )
        .expect("next route");

    assert_eq!(next.group_index, 2);
}

#[test]
fn select_initial_with_geo_matches_geoip_group() {
    let mut geo_group = DesyncGroup::new(0);
    geo_group.matches.filters.geoip_countries.push("se".to_string());
    let fallback = DesyncGroup::new(1);
    let config = config_with_groups(vec![geo_group, fallback]);
    let mut policy = RuntimePolicy::load(&config);
    let geo = FakeGeoMatcher { country: Some((sample_dest(443).ip(), "se")), geosite: None };

    let route = policy
        .select_initial_with_geo(sample_dest(443), None, None, true, TransportProtocol::Tcp, &config, Some(&geo))
        .expect("geoip route");

    assert_eq!(route.group_index, 0);
}

#[test]
fn select_initial_with_geo_matches_geosite_group() {
    let mut geosite_group = DesyncGroup::new(0);
    geosite_group.matches.filters.geosite_categories.push("video".to_string());
    let fallback = DesyncGroup::new(1);
    let config = config_with_groups(vec![geosite_group, fallback]);
    let mut policy = RuntimePolicy::load(&config);
    let geo = FakeGeoMatcher { country: None, geosite: Some(("video", "example.test")) };

    let route = policy
        .select_initial_with_geo(
            sample_dest(443),
            Some(b"GET / HTTP/1.1\r\nHost: media.example.test\r\n\r\n"),
            None,
            false,
            TransportProtocol::Tcp,
            &config,
            Some(&geo),
        )
        .expect("geosite route");

    assert_eq!(route.group_index, 0);
}

#[test]
fn host_preference_outranks_destination_cache() {
    let config = autolearn_config(2, 32);
    let dest = sample_dest(443);
    let mut policy = RuntimePolicy::load(&config);

    policy.store(&config, dest, 0, 0, None);
    policy
        .note_route_success(&config, dest, &ConnectionRoute { group_index: 1, attempted_mask: 0 }, Some("Example.org"))
        .expect("learn host route");

    let route = policy
        .select_initial(dest, None, Some("example.org"), true, TransportProtocol::Tcp, &config)
        .expect("host-aware route");

    assert_eq!(route.group_index, 1);
}

struct FakeGeoMatcher {
    country: Option<(IpAddr, &'static str)>,
    geosite: Option<(&'static str, &'static str)>,
}

impl GeoMatcher for FakeGeoMatcher {
    fn country_matches_ip(&self, country_code: &str, ip: IpAddr) -> bool {
        self.country
            .is_some_and(|(candidate_ip, candidate_country)| candidate_ip == ip && candidate_country == country_code)
    }

    fn geosite_matches_host(&self, category: &str, host: &str) -> bool {
        self.geosite.is_some_and(|(candidate_category, root_domain)| {
            candidate_category == category && (host == root_domain || host.ends_with(&format!(".{root_domain}")))
        })
    }
}

#[test]
fn blocked_host_without_learned_winner_prefers_non_penalized_group() {
    let config = autolearn_config(2, 32);
    let dest = sample_dest(443);
    let mut policy = RuntimePolicy::load(&config);
    let now_ms = now_millis();

    let record = policy.learned_hosts_mut(&config).entry("example.org".to_string()).or_default();
    record.blocked_until_ms = Some(now_ms.saturating_add(crate::runtime_policy::BLOCKED_HOST_TTL_MS));
    record.last_blocked_at_ms = Some(now_ms);
    record.last_block_signal = Some(BlockSignal::TcpReset);
    record.group_stats.insert(
        0,
        LearnedGroupStats { failure_count: 1, penalty_until_ms: now_ms.saturating_add(5_000), ..Default::default() },
    );
    record.group_stats.insert(1, LearnedGroupStats::default());

    let route = policy
        .select_initial(dest, None, Some("example.org"), true, TransportProtocol::Tcp, &config)
        .expect("route for blocked host");

    assert_eq!(route.group_index, 1);
}
