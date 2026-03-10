use std::io::{self, Write};
use std::net::{IpAddr, SocketAddr};
use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};

use ciadpi_config::{
    dump_cache_entries, load_cache_entries_from_path, prefix_match_bytes, CacheEntry, DesyncGroup, RuntimeConfig,
    AUTO_NOPOST, AUTO_SORT, DETECT_RECONN,
};
use ciadpi_packets::{is_http, is_tls_client_hello, parse_http, parse_tls, IS_HTTP, IS_HTTPS, IS_IPV4, IS_TCP, IS_UDP};

#[derive(Debug, Clone)]
pub struct ConnectionRoute {
    pub group_index: usize,
    pub attempted_mask: u64,
}

#[derive(Debug, Clone)]
struct GroupPolicy {
    detect: u32,
    fail_count: i32,
    pri: i32,
}

#[derive(Debug, Clone)]
struct CacheRecord {
    entry: CacheEntry,
    group_index: usize,
    attempted_mask: u64,
}

pub struct RouteAdvance<'a> {
    pub dest: SocketAddr,
    pub payload: Option<&'a [u8]>,
    pub trigger: u32,
    pub can_reconnect: bool,
    pub host: Option<String>,
}

#[derive(Debug, Default)]
pub struct RuntimeCache {
    records: Vec<CacheRecord>,
    groups: Vec<GroupPolicy>,
    order: Vec<usize>,
}

impl RuntimeCache {
    pub fn load(config: &RuntimeConfig) -> Self {
        let mut records = Vec::new();
        for (group_index, group) in config.groups.iter().enumerate() {
            let Some(path) = group.cache_file.as_deref() else {
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
            .map(|group| GroupPolicy { detect: group.detect, fail_count: group.fail_count, pri: group.pri })
            .collect();
        let order = (0..config.groups.len()).collect();
        Self { records, groups, order }
    }

    pub fn lookup_and_prune(&mut self, config: &RuntimeConfig, dest: SocketAddr) -> Option<ConnectionRoute> {
        let now = now_unix();
        self.records.retain(|record| !is_expired(config, record, now));
        self.records
            .iter()
            .find(|record| cache_matches(&record.entry, dest))
            .map(|record| ConnectionRoute { group_index: record.group_index, attempted_mask: record.attempted_mask })
    }

    pub fn store(
        &mut self,
        config: &RuntimeConfig,
        dest: SocketAddr,
        group_index: usize,
        attempted_mask: u64,
        host: Option<String>,
    ) -> io::Result<()> {
        let entry = CacheEntry {
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

    pub fn clear(&mut self, config: &RuntimeConfig, dest: SocketAddr) -> io::Result<()> {
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

    fn persist_group(&self, config: &RuntimeConfig, group_index: usize) -> io::Result<()> {
        let Some(path) = config.groups[group_index].cache_file.as_deref() else {
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
        std::fs::write(path, dump_cache_entries(&entries))
    }

    pub fn dump_stdout_groups<W: Write>(&self, config: &RuntimeConfig, mut writer: W) -> io::Result<()> {
        for (group_index, group) in config.groups.iter().enumerate() {
            if group.cache_file.as_deref() != Some("-") {
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

    pub fn advance_route(
        &mut self,
        config: &RuntimeConfig,
        route: &ConnectionRoute,
        request: RouteAdvance<'_>,
    ) -> io::Result<Option<ConnectionRoute>> {
        if !request.can_reconnect && (config.auto_level & AUTO_NOPOST) != 0 {
            return Ok(None);
        }

        if let Some(group) = self.groups.get_mut(route.group_index) {
            group.fail_count += 1;
        }

        let next = select_next_group(
            config,
            self,
            route,
            request.dest,
            request.payload,
            request.trigger,
            request.can_reconnect,
        );

        if (config.auto_level & AUTO_SORT) != 0 {
            if let Some(ref next_route) = next {
                let current_pri = self.groups.get(route.group_index).map(|group| group.pri).unwrap_or_default();
                let next_pri = self.groups.get(next_route.group_index).map(|group| group.pri).unwrap_or_default();
                if current_pri > next_pri {
                    self.swap_groups(route.group_index, next_route.group_index);
                }
            }
            if let Some(group) = self.groups.get_mut(route.group_index) {
                group.pri += 1;
            }
        }

        match next {
            Some(next_route) => {
                self.store(config, request.dest, next_route.group_index, next_route.attempted_mask, request.host)?;
                Ok(Some(next_route))
            }
            None => {
                self.clear(config, request.dest)?;
                Ok(None)
            }
        }
    }

    fn detect_for(&self, config: &RuntimeConfig, group_index: usize) -> u32 {
        self.groups.get(group_index).map_or_else(|| config.groups[group_index].detect, |group| group.detect)
    }

    fn ordered_indices(&self) -> &[usize] {
        &self.order
    }

    fn swap_groups(&mut self, lhs: usize, rhs: usize) {
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

pub fn select_initial_group(
    config: &RuntimeConfig,
    cache: &mut RuntimeCache,
    dest: SocketAddr,
    payload: Option<&[u8]>,
    allow_unknown_payload: bool,
) -> Option<ConnectionRoute> {
    if let Some(route) = cache.lookup_and_prune(config, dest) {
        let group = config.groups.get(route.group_index)?;
        if group_matches(group, dest, payload, allow_unknown_payload) {
            return Some(route);
        }
    }

    let mut attempted_mask = 0u64;
    for &idx in cache.ordered_indices() {
        let group = config.groups.get(idx)?;
        if cache.detect_for(config, idx) != 0 {
            continue;
        }
        if group_matches(group, dest, payload, allow_unknown_payload) {
            return Some(ConnectionRoute { group_index: idx, attempted_mask });
        }
        attempted_mask |= group.bit;
    }
    None
}

pub fn select_next_group(
    config: &RuntimeConfig,
    cache: &RuntimeCache,
    route: &ConnectionRoute,
    dest: SocketAddr,
    payload: Option<&[u8]>,
    trigger: u32,
    can_reconnect: bool,
) -> Option<ConnectionRoute> {
    let mut attempted_mask = route.attempted_mask | config.groups[route.group_index].bit;
    for &idx in cache.ordered_indices() {
        let group = config.groups.get(idx)?;
        let detect = cache.detect_for(config, idx);
        if attempted_mask & group.bit != 0 {
            continue;
        }
        if detect != 0 && (detect & trigger) == 0 {
            attempted_mask |= group.bit;
            continue;
        }
        if (detect & DETECT_RECONN) != 0 && !can_reconnect {
            attempted_mask |= group.bit;
            continue;
        }
        if group_matches(group, dest, payload, false) {
            return Some(ConnectionRoute { group_index: idx, attempted_mask });
        }
        attempted_mask |= group.bit;
    }
    None
}

pub fn extract_host(payload: &[u8]) -> Option<String> {
    parse_http(payload)
        .map(|host| String::from_utf8_lossy(host.host).into_owned())
        .or_else(|| parse_tls(payload).map(|host| String::from_utf8_lossy(host).into_owned()))
}

pub fn group_requires_payload(group: &DesyncGroup) -> bool {
    !group.filters.hosts.is_empty() || group.proto != 0
}

pub fn route_matches_payload(config: &RuntimeConfig, group_index: usize, dest: SocketAddr, payload: &[u8]) -> bool {
    config.groups.get(group_index).is_some_and(|group| group_matches(group, dest, Some(payload), false))
}

fn group_matches(group: &DesyncGroup, dest: SocketAddr, payload: Option<&[u8]>, allow_unknown_payload: bool) -> bool {
    if !matches_l34(group, dest) {
        return false;
    }
    match payload {
        Some(payload) => matches_payload(group, payload),
        None if allow_unknown_payload => true,
        None => group.filters.hosts.is_empty() && payload_proto_known(group),
    }
}

fn payload_proto_known(group: &DesyncGroup) -> bool {
    group.proto == 0 || (group.proto & (IS_HTTP | IS_HTTPS)) == 0
}

fn matches_l34(group: &DesyncGroup, dest: SocketAddr) -> bool {
    if (group.proto & IS_UDP) != 0 {
        return false;
    }
    if (group.proto & IS_TCP) != 0 {
        // Every TCP tunnel in the Rust runtime is stream-based.
    }
    if (group.proto & IS_IPV4) != 0 && !dest.is_ipv4() {
        return false;
    }
    if let Some((start, end)) = group.port_filter {
        let port = dest.port();
        if port < start || port > end {
            return false;
        }
    }
    if !group.filters.ipset.is_empty() && !group.filters.ipset_match(dest.ip()) {
        return false;
    }
    true
}

fn matches_payload(group: &DesyncGroup, payload: &[u8]) -> bool {
    if group.proto != 0 {
        let l7 = group.proto & !(IS_TCP | IS_UDP | IS_IPV4);
        if l7 != 0 {
            let http = is_http(payload);
            let tls = is_tls_client_hello(payload);
            if ((l7 & IS_HTTP) != 0 && http) || ((l7 & IS_HTTPS) != 0 && tls) {
                // allowed
            } else {
                return false;
            }
        }
    }
    if group.filters.hosts.is_empty() {
        return true;
    }
    extract_host(payload).as_deref().is_some_and(|host| group.filters.hosts_match(host))
}

fn cache_matches(entry: &CacheEntry, dest: SocketAddr) -> bool {
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
    let ttl = if group.cache_ttl != 0 { group.cache_ttl } else { config.cache_ttl };
    ttl != 0 && now > record.entry.time + ttl
}

fn cache_bits(config: &RuntimeConfig, ip: IpAddr) -> u16 {
    match ip {
        IpAddr::V4(_) if config.cache_prefix != 0 => (32 - config.cache_prefix as u16).max(1),
        IpAddr::V4(_) => 32,
        IpAddr::V6(_) => 128,
    }
}

fn now_unix() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs() as i64
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_dest(port: u16) -> SocketAddr {
        SocketAddr::from(([203, 0, 113, 10], port))
    }

    fn config_with_groups(groups: Vec<DesyncGroup>) -> RuntimeConfig {
        RuntimeConfig { groups, ..RuntimeConfig::default() }
    }

    #[test]
    fn lookup_prunes_expired_records() {
        let dest = sample_dest(443);
        let mut group = DesyncGroup::new(0);
        group.cache_ttl = 1;
        let config = config_with_groups(vec![group]);
        let mut cache = RuntimeCache {
            records: vec![CacheRecord {
                entry: CacheEntry { addr: dest.ip(), bits: 32, port: dest.port(), time: now_unix() - 5, host: None },
                group_index: 0,
                attempted_mask: 0,
            }],
            groups: vec![GroupPolicy { detect: 0, fail_count: 0, pri: 0 }],
            order: vec![0],
        };

        assert!(cache.lookup_and_prune(&config, dest).is_none());
        assert!(cache.records.is_empty());
    }

    #[test]
    fn select_initial_group_skips_detect_only_groups() {
        let mut first = DesyncGroup::new(0);
        first.detect = DETECT_RECONN;
        let second = DesyncGroup::new(1);
        let config = config_with_groups(vec![first, second]);
        let mut cache = RuntimeCache::load(&config);

        let route = select_initial_group(&config, &mut cache, sample_dest(80), None, true).expect("fallback route");

        assert_eq!(route.group_index, 1);
        assert_eq!(route.attempted_mask, 0);
    }

    #[test]
    fn select_next_group_advances_to_matching_trigger_group() {
        let first = DesyncGroup::new(0);
        let mut second = DesyncGroup::new(1);
        second.detect = DETECT_RECONN;
        let config = config_with_groups(vec![first, second]);
        let cache = RuntimeCache::load(&config);
        let route = ConnectionRoute { group_index: 0, attempted_mask: 0 };

        let next = select_next_group(&config, &cache, &route, sample_dest(443), None, DETECT_RECONN, true)
            .expect("next route");

        assert_eq!(next.group_index, 1);
        assert_eq!(next.attempted_mask, config.groups[0].bit);
    }

    #[test]
    fn route_matches_payload_checks_host_filters() {
        let mut group = DesyncGroup::new(0);
        group.filters.hosts.push("example.com".to_string());
        let config = config_with_groups(vec![group]);
        let matching = b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";
        let non_matching = b"GET / HTTP/1.1\r\nHost: other.example\r\n\r\n";

        assert!(route_matches_payload(&config, 0, sample_dest(80), matching));
        assert!(!route_matches_payload(&config, 0, sample_dest(80), non_matching,));
    }
}
