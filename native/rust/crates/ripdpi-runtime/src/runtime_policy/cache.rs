use std::collections::{BTreeMap, VecDeque};
use std::fs;
use std::io::{self, Write};
use std::net::{IpAddr, SocketAddr};
use std::path::Path;

use ripdpi_config::{dump_cache_entries, load_cache_entries_from_path, prefix_match_bytes, RuntimeConfig};

use super::autolearn::load_learned_host_store;
use super::types::{CacheRecord, GroupPolicy};
use super::{now_unix, HostAutolearnEvent, RuntimePolicy};

impl RuntimePolicy {
    pub fn load(config: &RuntimeConfig) -> Self {
        let mut records = Vec::new();
        let mut learned_hosts_by_scope = BTreeMap::default();
        let mut autolearn_events = VecDeque::new();
        for (group_index, group) in config.groups.iter().enumerate() {
            let Some(path) = group.policy.cache_file.as_deref() else {
                continue;
            };
            if path == "-" {
                continue;
            }
            if let Ok(entries) = load_cache_entries_from_path(Path::new(path)) {
                records.extend(entries.into_iter().map(|entry| CacheRecord { entry, group_index, attempted_mask: 0 }));
            }
        }
        let groups = config
            .groups
            .iter()
            .map(|group| GroupPolicy {
                detect: group.matches.detect,
                fail_count: group.policy.fail_count,
                pri: group.policy.pri,
            })
            .collect();
        let order = (0..config.groups.len()).collect();
        if config.host_autolearn.enabled {
            match load_learned_host_store(config) {
                Ok(hosts) => learned_hosts_by_scope = hosts,
                Err(super::types::LoadLearnedHostStoreError::Invalidated) => {
                    autolearn_events.push_back(HostAutolearnEvent {
                        action: "store_reset",
                        host: None,
                        group_index: None,
                    });
                }
                Err(super::types::LoadLearnedHostStoreError::Io) => {}
            }
        }
        Self { records, groups, order, learned_hosts_by_scope, autolearn_events }
    }

    pub(crate) fn lookup_and_prune(
        &mut self,
        config: &RuntimeConfig,
        dest: SocketAddr,
    ) -> Option<super::ConnectionRoute> {
        let now = now_unix();
        self.records.retain(|record| !is_expired(config, record, now));
        self.records.iter().find(|record| cache_matches(&record.entry, dest)).map(|record| super::ConnectionRoute {
            group_index: record.group_index,
            attempted_mask: record.attempted_mask,
        })
    }

    pub(crate) fn store(
        &mut self,
        config: &RuntimeConfig,
        dest: SocketAddr,
        group_index: usize,
        attempted_mask: u64,
        host: Option<String>,
    ) -> io::Result<()> {
        let entry = ripdpi_config::CacheEntry {
            addr: dest.ip(),
            bits: cache_bits(config, dest.ip()),
            port: dest.port(),
            time: now_unix(),
            host,
        };
        if let Some(existing) = self.records.iter_mut().find(|record| cache_matches(&record.entry, dest)) {
            existing.entry = entry;
            existing.group_index = group_index;
            existing.attempted_mask = attempted_mask;
        } else {
            self.records.push(CacheRecord { entry, group_index, attempted_mask });
        }
        self.persist_group(config, group_index)
    }

    pub(crate) fn clear(&mut self, config: &RuntimeConfig, dest: SocketAddr) -> io::Result<()> {
        let before = self.records.len();
        self.records.retain(|record| !cache_matches(&record.entry, dest));
        if self.records.len() == before {
            return Ok(());
        }
        for group_index in 0..config.groups.len() {
            self.persist_group(config, group_index)?;
        }
        Ok(())
    }

    pub(crate) fn persist_group(&self, config: &RuntimeConfig, group_index: usize) -> io::Result<()> {
        let Some(path) = config.groups[group_index].policy.cache_file.as_deref() else {
            return Ok(());
        };
        if path == "-" {
            return Ok(());
        }
        let entries: Vec<_> = self
            .records
            .iter()
            .filter(|record| record.group_index == group_index)
            .map(|record| record.entry.clone())
            .collect();
        fs::write(path, dump_cache_entries(&entries))
    }

    pub fn dump_stdout_groups<W: Write>(&self, config: &RuntimeConfig, mut writer: W) -> io::Result<()> {
        for (group_index, group) in config.groups.iter().enumerate() {
            if group.policy.cache_file.as_deref() != Some("-") {
                continue;
            }
            let entries: Vec<_> = self
                .records
                .iter()
                .filter(|record| record.group_index == group_index)
                .map(|record| record.entry.clone())
                .collect();
            writer.write_all(dump_cache_entries(&entries).as_bytes())?;
        }
        writer.flush()
    }

    pub fn supports_trigger(&self, trigger: u32) -> bool {
        self.groups.iter().any(|group| group.detect != 0 && (group.detect & trigger) != 0)
    }

    pub(crate) fn detect_for(&self, config: &RuntimeConfig, group_index: usize) -> u32 {
        self.groups.get(group_index).map_or_else(|| config.groups[group_index].matches.detect, |group| group.detect)
    }

    pub(crate) fn ordered_indices(&self) -> &[usize] {
        &self.order
    }

    pub(crate) fn swap_groups(&mut self, lhs: usize, rhs: usize) {
        let Some(lhs_pos) = self.order.iter().position(|&index| index == lhs) else {
            return;
        };
        let Some(rhs_pos) = self.order.iter().position(|&index| index == rhs) else {
            return;
        };
        self.order.swap(lhs_pos, rhs_pos);
        if lhs == rhs {
            return;
        }

        let lhs_detect = self.groups.get(lhs).map(|group| group.detect);
        let rhs_detect = self.groups.get(rhs).map(|group| group.detect);
        if let (Some(lhs_detect), Some(rhs_detect)) = (lhs_detect, rhs_detect) {
            if let Some(group) = self.groups.get_mut(lhs) {
                group.detect = rhs_detect;
            }
            if let Some(group) = self.groups.get_mut(rhs) {
                group.detect = lhs_detect;
            }
        }
    }
}

fn cache_matches(entry: &ripdpi_config::CacheEntry, dest: SocketAddr) -> bool {
    if entry.port != dest.port() {
        return false;
    }
    match (entry.addr, dest.ip()) {
        (IpAddr::V4(lhs), IpAddr::V4(rhs)) => prefix_match_bytes(&lhs.octets(), &rhs.octets(), entry.bits as u8),
        (IpAddr::V6(lhs), IpAddr::V6(rhs)) => prefix_match_bytes(&lhs.octets(), &rhs.octets(), entry.bits as u8),
        _ => false,
    }
}

fn is_expired(config: &RuntimeConfig, record: &CacheRecord, now: i64) -> bool {
    let Some(group) = config.groups.get(record.group_index) else {
        return true;
    };
    let ttl = if group.policy.cache_ttl != 0 { group.policy.cache_ttl } else { config.adaptive.cache_ttl };
    ttl != 0 && now > record.entry.time + ttl
}

fn cache_bits(config: &RuntimeConfig, ip: IpAddr) -> u16 {
    match ip {
        IpAddr::V4(_) if config.adaptive.cache_prefix != 0 => (32 - config.adaptive.cache_prefix as u16).max(1),
        IpAddr::V4(_) => 32,
        IpAddr::V6(_) => 128,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_config::DesyncGroup;

    use crate::runtime_policy::test_support::{config_with_groups, sample_dest};

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
            autolearn_events: VecDeque::default(),
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

        policy.store(&config, dest, 0, 0, Some("docs.example.test".to_string())).expect("store cached host");

        assert_eq!(policy.records[0].entry.host.as_deref(), Some("docs.example.test"));

        let mut dumped = Vec::new();
        policy.dump_stdout_groups(&config, &mut dumped).expect("dump cache entries");
        let dumped = String::from_utf8(dumped).expect("cache dump utf8");
        assert!(dumped.contains("docs.example.test"));
    }
}
