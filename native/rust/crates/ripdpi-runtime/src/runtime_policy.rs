use std::io::{self, Write};
use std::net::{IpAddr, SocketAddr};
use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};

use ciadpi_config::{
    dump_cache_entries, load_cache_entries_from_path, prefix_match_bytes, CacheEntry, DesyncGroup, QuicInitialMode,
    RuntimeConfig, AUTO_NOPOST, AUTO_SORT, DETECT_RECONN,
};
use ciadpi_packets::{
    is_http, is_tls_client_hello, parse_http, parse_quic_initial, parse_tls, IS_HTTP, IS_HTTPS, IS_IPV4, IS_TCP, IS_UDP,
};

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
    pub transport: TransportProtocol,
    pub trigger: u32,
    pub can_reconnect: bool,
    pub host: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TransportProtocol {
    Tcp,
    Udp,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExtractedHost {
    pub host: String,
    pub source: HostSource,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HostSource {
    Http,
    Tls,
    Quic,
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
            request.transport,
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
    transport: TransportProtocol,
) -> Option<ConnectionRoute> {
    if let Some(route) = cache.lookup_and_prune(config, dest) {
        let group = config.groups.get(route.group_index)?;
        if group_matches(config, group, dest, payload, allow_unknown_payload, transport) {
            return Some(route);
        }
    }

    let mut attempted_mask = 0u64;
    for &idx in cache.ordered_indices() {
        let group = config.groups.get(idx)?;
        if cache.detect_for(config, idx) != 0 {
            continue;
        }
        if group_matches(config, group, dest, payload, allow_unknown_payload, transport) {
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
    transport: TransportProtocol,
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
        if group_matches(config, group, dest, payload, false, transport) {
            return Some(ConnectionRoute { group_index: idx, attempted_mask });
        }
        attempted_mask |= group.bit;
    }
    None
}

pub fn extract_host_info(config: &RuntimeConfig, payload: &[u8]) -> Option<ExtractedHost> {
    parse_http(payload)
        .map(|host| ExtractedHost { host: String::from_utf8_lossy(host.host).into_owned(), source: HostSource::Http })
        .or_else(|| {
            parse_tls(payload)
                .map(|host| ExtractedHost { host: String::from_utf8_lossy(host).into_owned(), source: HostSource::Tls })
        })
        .or_else(|| extract_quic_host(config, payload))
}

pub fn extract_host(config: &RuntimeConfig, payload: &[u8]) -> Option<String> {
    extract_host_info(config, payload).map(|host| host.host)
}

pub fn group_requires_payload(group: &DesyncGroup) -> bool {
    !group.filters.hosts.is_empty() || group.proto != 0
}

pub fn route_matches_payload(
    config: &RuntimeConfig,
    group_index: usize,
    dest: SocketAddr,
    payload: &[u8],
    transport: TransportProtocol,
) -> bool {
    config
        .groups
        .get(group_index)
        .is_some_and(|group| group_matches(config, group, dest, Some(payload), false, transport))
}

fn group_matches(
    config: &RuntimeConfig,
    group: &DesyncGroup,
    dest: SocketAddr,
    payload: Option<&[u8]>,
    allow_unknown_payload: bool,
    transport: TransportProtocol,
) -> bool {
    if !matches_l34(group, dest, transport) {
        return false;
    }
    match payload {
        Some(payload) => matches_payload(config, group, payload),
        None if allow_unknown_payload => true,
        None => group.filters.hosts.is_empty() && payload_proto_known(group),
    }
}

fn payload_proto_known(group: &DesyncGroup) -> bool {
    group.proto == 0 || (group.proto & (IS_HTTP | IS_HTTPS)) == 0
}

fn matches_l34(group: &DesyncGroup, dest: SocketAddr, transport: TransportProtocol) -> bool {
    if (group.proto & IS_UDP) != 0 && transport != TransportProtocol::Udp {
        return false;
    }
    if (group.proto & IS_TCP) != 0 && transport != TransportProtocol::Tcp {
        return false;
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

fn matches_payload(config: &RuntimeConfig, group: &DesyncGroup, payload: &[u8]) -> bool {
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
    extract_host(config, payload).as_deref().is_some_and(|host| group.filters.hosts_match(host))
}

fn extract_quic_host(config: &RuntimeConfig, payload: &[u8]) -> Option<ExtractedHost> {
    if matches!(config.quic_initial_mode, QuicInitialMode::Disabled)
        || (!config.quic_support_v1 && !config.quic_support_v2)
    {
        return None;
    }
    let info = parse_quic_initial(payload)?;
    let allowed = (info.version == 0x0000_0001 && config.quic_support_v1)
        || (info.version == 0x6b33_43cf && config.quic_support_v2);
    allowed.then(|| ExtractedHost { host: String::from_utf8_lossy(info.host()).into_owned(), source: HostSource::Quic })
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

    #[allow(dead_code)]
    mod rust_packet_seeds {
        include!(concat!(env!("CARGO_MANIFEST_DIR"), "/../../third_party/byedpi/tests/rust_packet_seeds.rs"));
    }

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

        let route = select_initial_group(&config, &mut cache, sample_dest(80), None, true, TransportProtocol::Tcp)
            .expect("fallback route");

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

        let next = select_next_group(
            &config,
            &cache,
            &route,
            sample_dest(443),
            None,
            TransportProtocol::Tcp,
            DETECT_RECONN,
            true,
        )
        .expect("next route");

        assert_eq!(next.group_index, 1);
        assert_eq!(next.attempted_mask, config.groups[0].bit);
    }

    #[test]
    fn matches_l34_rejects_udp_proto() {
        let mut group = DesyncGroup::new(0);
        group.proto = IS_UDP;
        assert!(!matches_l34(&group, sample_dest(443), TransportProtocol::Tcp));
        assert!(matches_l34(&group, sample_dest(443), TransportProtocol::Udp));
    }

    #[test]
    fn matches_l34_port_filter_boundaries() {
        let mut group = DesyncGroup::new(0);
        group.port_filter = Some((80, 443));
        // At start boundary
        assert!(matches_l34(&group, sample_dest(80), TransportProtocol::Tcp));
        // At end boundary
        assert!(matches_l34(&group, sample_dest(443), TransportProtocol::Tcp));
        // Below start
        assert!(!matches_l34(&group, sample_dest(79), TransportProtocol::Tcp));
        // Above end
        assert!(!matches_l34(&group, sample_dest(444), TransportProtocol::Tcp));
    }

    #[test]
    fn matches_l34_ipv4_only_filter() {
        let mut group = DesyncGroup::new(0);
        group.proto = IS_IPV4;
        // IPv4 destination matches
        assert!(matches_l34(&group, sample_dest(443), TransportProtocol::Tcp));
        // IPv6 destination does not
        let ipv6_dest = SocketAddr::from(([0, 0, 0, 0, 0, 0, 0, 1], 443));
        assert!(!matches_l34(&group, ipv6_dest, TransportProtocol::Tcp));
    }

    #[test]
    fn payload_proto_known_values() {
        let mut group = DesyncGroup::new(0);
        // proto == 0 -> true (no filter)
        assert!(payload_proto_known(&group));
        // proto == IS_TCP -> true (L3/4 only)
        group.proto = IS_TCP;
        assert!(payload_proto_known(&group));
        // proto == IS_HTTP -> false (L7 protocol requires payload)
        group.proto = IS_HTTP;
        assert!(!payload_proto_known(&group));
    }

    #[test]
    fn cache_bits_with_prefix() {
        let mut config = RuntimeConfig::default();
        config.cache_prefix = 8;
        // IPv4 with prefix=8 -> bits = 32 - 8 = 24
        assert_eq!(cache_bits(&config, IpAddr::from([192, 168, 1, 1])), 24);
        // prefix=0 -> bits=32
        config.cache_prefix = 0;
        assert_eq!(cache_bits(&config, IpAddr::from([192, 168, 1, 1])), 32);
        // IPv6 always 128
        assert_eq!(cache_bits(&config, IpAddr::from([0u16, 0, 0, 0, 0, 0, 0, 1])), 128);
    }

    #[test]
    fn is_expired_ttl_boundary() {
        let group = {
            let mut g = DesyncGroup::new(0);
            g.cache_ttl = 100;
            g
        };
        let config = config_with_groups(vec![group]);
        let record = CacheRecord {
            entry: CacheEntry { addr: IpAddr::from([1, 2, 3, 4]), bits: 32, port: 443, time: 1000, host: None },
            group_index: 0,
            attempted_mask: 0,
        };
        // Exactly at TTL boundary: 1000 + 100 = 1100, now=1100 -> NOT expired (now > time+ttl is false)
        assert!(!is_expired(&config, &record, 1100));
        // Past TTL: now=1101 -> expired
        assert!(is_expired(&config, &record, 1101));
        // ttl=0 -> never expires
        let mut config2 = config.clone();
        config2.groups[0].cache_ttl = 0;
        config2.cache_ttl = 0;
        assert!(!is_expired(&config2, &record, 999_999));
    }

    #[test]
    fn group_matches_no_payload_allow_unknown_false() {
        let mut group = DesyncGroup::new(0);
        let config = RuntimeConfig::default();
        // Empty hosts + known proto -> true
        assert!(group_matches(&config, &group, sample_dest(80), None, false, TransportProtocol::Tcp));
        // Non-empty hosts -> false (can't verify without payload)
        group.filters.hosts.push("example.com".to_string());
        assert!(!group_matches(&config, &group, sample_dest(80), None, false, TransportProtocol::Tcp));
    }

    #[test]
    fn matches_payload_l7_proto_filtering() {
        let mut group = DesyncGroup::new(0);
        group.proto = IS_TCP | IS_HTTP;
        let config = RuntimeConfig::default();
        let http_payload = b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";
        let tls_payload = ciadpi_packets::DEFAULT_FAKE_TLS;
        // HTTP payload matches IS_HTTP
        assert!(matches_payload(&config, &group, http_payload));
        // TLS payload does not match IS_HTTP
        assert!(!matches_payload(&config, &group, tls_payload));
    }

    #[test]
    fn route_matches_payload_checks_host_filters() {
        let mut group = DesyncGroup::new(0);
        group.filters.hosts.push("example.com".to_string());
        let config = config_with_groups(vec![group]);
        let matching = b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";
        let non_matching = b"GET / HTTP/1.1\r\nHost: other.example\r\n\r\n";

        assert!(route_matches_payload(&config, 0, sample_dest(80), matching, TransportProtocol::Tcp));
        assert!(!route_matches_payload(&config, 0, sample_dest(80), non_matching, TransportProtocol::Tcp));
    }

    #[test]
    fn extract_host_reads_quic_initial_sni() {
        let packet = rust_packet_seeds::quic_initial_v1();

        assert_eq!(extract_host(&RuntimeConfig::default(), &packet).as_deref(), Some("docs.example.test"));
    }

    #[test]
    fn udp_host_filters_match_quic_initial_payloads() {
        let mut group = DesyncGroup::new(0);
        group.proto = IS_UDP;
        group.filters.hosts.push("docs.example.test".to_string());
        let config = config_with_groups(vec![group]);
        let packet = rust_packet_seeds::quic_initial_v1();

        assert!(route_matches_payload(&config, 0, sample_dest(443), &packet, TransportProtocol::Udp));
    }

    #[test]
    fn extract_host_skips_quic_when_disabled() {
        let packet = rust_packet_seeds::quic_initial_v1();
        let mut config = RuntimeConfig::default();
        config.quic_initial_mode = QuicInitialMode::Disabled;

        assert_eq!(extract_host(&config, &packet), None);
    }

    #[test]
    fn extract_host_respects_quic_version_toggles() {
        let packet = rust_packet_seeds::quic_initial_v2();
        let mut config = RuntimeConfig::default();
        config.quic_support_v2 = false;

        assert_eq!(extract_host(&config, &packet), None);
    }

    #[test]
    fn runtime_cache_store_preserves_cached_hostnames() {
        let dest = sample_dest(443);
        let mut group = DesyncGroup::new(0);
        group.cache_file = Some("-".to_string());
        let config = config_with_groups(vec![group]);
        let mut cache = RuntimeCache::load(&config);

        cache.store(&config, dest, 0, 0, Some("docs.example.test".to_string())).expect("store cached host");

        assert_eq!(cache.records[0].entry.host.as_deref(), Some("docs.example.test"));

        let mut dumped = Vec::new();
        cache.dump_stdout_groups(&config, &mut dumped).expect("dump cache entries");
        let dumped = String::from_utf8(dumped).expect("cache dump utf8");
        assert!(dumped.contains("docs.example.test"));
    }
}
