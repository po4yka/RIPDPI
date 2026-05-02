use std::collections::{BTreeMap, VecDeque};
use std::net::IpAddr;

use ripdpi_config::DesyncGroup;

use super::connection::{cache_bits, is_expired};
use super::*;
use crate::runtime_policy::now_unix;
use crate::runtime_policy::test_support::{config_with_groups, sample_dest};
use crate::runtime_policy::types::GroupPolicy;

#[test]
fn lookup_prunes_expired_records() {
    let dest = sample_dest(443);
    let mut group = DesyncGroup::new(0);
    group.policy.cache_ttl = 1;
    let config = config_with_groups(vec![group]);
    let mut policy = RuntimePolicy {
        records: vec![CacheRecord {
            entry: ripdpi_config::CacheEntry {
                addr: dest.ip(),
                bits: 32,
                port: dest.port(),
                time: now_unix() - 5,
                host: None,
            },
            group_index: 0,
            attempted_mask: 0,
        }],
        groups: vec![GroupPolicy { detect: 0, fail_count: 0, pri: 0 }],
        order: vec![0],
        learned_hosts_by_scope: BTreeMap::default(),
        pending_blocked_hosts_by_scope: BTreeMap::default(),
        autolearn_events: VecDeque::default(),
        last_persist_at_ms: 0,
    };

    assert!(policy.lookup_and_prune(&config, dest).is_none());
    assert!(policy.records.is_empty());
}

#[test]
fn cache_bits_with_prefix() {
    let mut config = RuntimeConfig::default();
    config.adaptive.cache_prefix = 8;
    assert_eq!(cache_bits(&config, IpAddr::from([192, 168, 1, 1])), 24);
    let config = RuntimeConfig::default();
    assert_eq!(cache_bits(&config, IpAddr::from([192, 168, 1, 1])), 32);
    assert_eq!(cache_bits(&config, IpAddr::from([0u16, 0, 0, 0, 0, 0, 0, 1])), 128);
}

#[test]
fn clear_connection_cache_drops_all_records() {
    let dest = sample_dest(443);
    let config = config_with_groups(vec![DesyncGroup::new(0)]);
    let mut policy = RuntimePolicy {
        records: vec![CacheRecord {
            entry: ripdpi_config::CacheEntry {
                addr: dest.ip(),
                bits: 32,
                port: dest.port(),
                time: now_unix(),
                host: Some("example.org".to_string()),
            },
            group_index: 0,
            attempted_mask: 1,
        }],
        groups: vec![GroupPolicy { detect: 0, fail_count: 0, pri: 0 }],
        order: vec![0],
        learned_hosts_by_scope: BTreeMap::default(),
        pending_blocked_hosts_by_scope: BTreeMap::default(),
        autolearn_events: VecDeque::default(),
        last_persist_at_ms: 0,
    };

    assert_eq!(policy.clear_connection_cache(&config), 1);
    assert!(policy.records.is_empty());
}

#[test]
fn is_expired_ttl_boundary() {
    let group = {
        let mut g = DesyncGroup::new(0);
        g.policy.cache_ttl = 100;
        g
    };
    let config = config_with_groups(vec![group]);
    let record = CacheRecord {
        entry: ripdpi_config::CacheEntry {
            addr: IpAddr::from([1, 2, 3, 4]),
            bits: 32,
            port: 443,
            time: 1000,
            host: None,
        },
        group_index: 0,
        attempted_mask: 0,
    };
    assert!(!is_expired(&config, &record, 1100));
    assert!(is_expired(&config, &record, 1101));
    let mut config2 = config.clone();
    config2.groups[0].policy.cache_ttl = 0;
    config2.adaptive.cache_ttl = 0;
    assert!(!is_expired(&config2, &record, 999_999));
}

#[test]
fn runtime_policy_store_preserves_cached_hostnames() {
    let dest = sample_dest(443);
    let mut group = DesyncGroup::new(0);
    group.policy.cache_file = Some("-".to_string());
    let config = config_with_groups(vec![group]);
    let mut policy = RuntimePolicy::load(&config);

    policy.store(&config, dest, 0, 0, Some("docs.example.test".to_string()));

    assert_eq!(policy.records[0].entry.host.as_deref(), Some("docs.example.test"));

    let mut dumped = Vec::new();
    policy.dump_stdout_groups(&config, &mut dumped).expect("dump cache entries");
    let dumped = String::from_utf8(dumped).expect("cache dump utf8");
    assert!(dumped.contains("docs.example.test"));
}
