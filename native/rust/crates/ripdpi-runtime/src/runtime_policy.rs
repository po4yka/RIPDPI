use std::collections::{BTreeMap, VecDeque};
use std::fs;
use std::io::{self, Write};
use std::net::{IpAddr, SocketAddr};
use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::LazyLock;
use std::time::{SystemTime, UNIX_EPOCH};

use ciadpi_config::{
    dump_cache_entries, load_cache_entries_from_path, prefix_match_bytes, CacheEntry, DesyncGroup, QuicInitialMode,
    RuntimeConfig, AUTO_NOPOST, AUTO_SORT, DETECT_RECONN,
};
use ciadpi_packets::{
    is_http, is_tls_client_hello, parse_http, parse_quic_initial, parse_tls, IS_HTTP, IS_HTTPS, IS_IPV4, IS_TCP, IS_UDP,
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

const HOST_AUTOLEARN_STORE_VERSION: u32 = 2;
const DEFAULT_NETWORK_SCOPE_KEY: &str = "default";
static EMPTY_LEARNED_HOSTS: LazyLock<BTreeMap<String, LearnedHostRecord>> = LazyLock::new(BTreeMap::new);

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

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
struct LearnedGroupStats {
    success_count: u32,
    failure_count: u32,
    penalty_until_ms: u64,
    last_success_at_ms: u64,
    last_failure_at_ms: u64,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
struct LearnedHostRecord {
    preferred_groups: Vec<usize>,
    group_stats: BTreeMap<usize, LearnedGroupStats>,
    updated_at_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
struct LearnedNetworkScopeStore {
    #[serde(default)]
    hosts: BTreeMap<String, LearnedHostRecord>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
struct LearnedHostStore {
    version: u32,
    fingerprint: String,
    #[serde(default)]
    scopes: BTreeMap<String, LearnedNetworkScopeStore>,
}

#[derive(Debug, Clone)]
pub struct HostAutolearnEvent {
    pub action: &'static str,
    pub host: Option<String>,
    pub group_index: Option<usize>,
}

pub struct RouteAdvance<'a> {
    pub dest: SocketAddr,
    pub payload: Option<&'a [u8]>,
    pub transport: TransportProtocol,
    pub trigger: u32,
    pub can_reconnect: bool,
    pub host: Option<String>,
    pub penalize_strategy_failure: bool,
    pub retry_penalties: Option<&'a BTreeMap<usize, RetrySelectionPenalty>>,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct RetrySelectionPenalty {
    pub same_signature_cooldown_ms: u64,
    pub family_cooldown_ms: u64,
    pub diversification_rank: u64,
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
    learned_hosts_by_scope: BTreeMap<String, BTreeMap<String, LearnedHostRecord>>,
    autolearn_events: VecDeque<HostAutolearnEvent>,
}

impl RuntimeCache {
    pub fn load(config: &RuntimeConfig) -> Self {
        let mut records = Vec::new();
        let mut learned_hosts_by_scope = BTreeMap::new();
        let mut autolearn_events = VecDeque::new();
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
        if config.host_autolearn_enabled {
            match load_learned_host_store(config) {
                Ok(hosts) => learned_hosts_by_scope = hosts,
                Err(LoadLearnedHostStoreError::Invalidated) => autolearn_events.push_back(HostAutolearnEvent {
                    action: "store_reset",
                    host: None,
                    group_index: None,
                }),
                Err(LoadLearnedHostStoreError::Io) => {}
            }
        }
        Self { records, groups, order, learned_hosts_by_scope, autolearn_events }
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

        if request.penalize_strategy_failure {
            if let Some(group) = self.groups.get_mut(route.group_index) {
                group.fail_count += 1;
            }
        }

        if request.transport == TransportProtocol::Tcp && request.penalize_strategy_failure {
            if let Some(host) = request.host.as_deref() {
                self.note_host_failure(config, host, route.group_index)?;
            }
        }

        let next = select_next_group(
            config,
            self,
            route,
            request.dest,
            request.payload,
            request.host.as_deref(),
            request.transport,
            request.trigger,
            request.can_reconnect,
            request.retry_penalties,
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

    fn select_host_route(
        &self,
        config: &RuntimeConfig,
        host: &str,
        dest: SocketAddr,
        payload: Option<&[u8]>,
        allow_unknown_payload: bool,
        transport: TransportProtocol,
    ) -> Option<ConnectionRoute> {
        let record = self.learned_hosts(config).get(host)?;
        if let Some(route) = self.preferred_host_candidate(
            config,
            record,
            dest,
            payload,
            allow_unknown_payload,
            transport,
            0,
            true,
            false,
            None,
        ) {
            return Some(route);
        }
        let attempted_mask = record
            .preferred_groups
            .iter()
            .fold(0u64, |mask, &index| mask | config.groups.get(index).map_or(0, |group| group.bit));
        let mut rejected_mask = attempted_mask;
        let mut eligible = Vec::new();
        for &idx in self.ordered_indices() {
            let group = config.groups.get(idx)?;
            if rejected_mask & group.bit != 0 || self.detect_for(config, idx) != 0 {
                continue;
            }
            if group_matches(config, group, dest, payload, allow_unknown_payload, transport) {
                eligible.push(idx);
            } else {
                rejected_mask |= group.bit;
            }
        }
        if let Some(route) = select_best_candidate(config, self, &eligible, rejected_mask, None) {
            return Some(route);
        }

        self.preferred_host_candidate(
            config,
            record,
            dest,
            payload,
            allow_unknown_payload,
            transport,
            rejected_mask,
            true,
            true,
            None,
        )
    }

    fn select_host_route_after(
        &self,
        config: &RuntimeConfig,
        route: &ConnectionRoute,
        host: &str,
        dest: SocketAddr,
        payload: Option<&[u8]>,
        transport: TransportProtocol,
        trigger: u32,
        can_reconnect: bool,
        retry_penalties: Option<&BTreeMap<usize, RetrySelectionPenalty>>,
    ) -> Option<ConnectionRoute> {
        let record = self.learned_hosts(config).get(host)?;
        let mut attempted_mask = route.attempted_mask | config.groups[route.group_index].bit;
        if let Some(route) = self.preferred_host_candidate(
            config,
            record,
            dest,
            payload,
            false,
            transport,
            attempted_mask,
            can_reconnect,
            false,
            retry_penalties,
        ) {
            return Some(route);
        }
        attempted_mask |= record
            .preferred_groups
            .iter()
            .fold(0u64, |mask, &index| mask | config.groups.get(index).map_or(0, |group| group.bit));
        let mut rejected_mask = attempted_mask;
        let mut eligible = Vec::new();
        for &idx in self.ordered_indices() {
            let group = config.groups.get(idx)?;
            let detect = self.detect_for(config, idx);
            if rejected_mask & group.bit != 0 {
                continue;
            }
            if detect != 0 && (detect & trigger) == 0 {
                rejected_mask |= group.bit;
                continue;
            }
            if (detect & DETECT_RECONN) != 0 && !can_reconnect {
                rejected_mask |= group.bit;
                continue;
            }
            if group_matches(config, group, dest, payload, false, transport) {
                eligible.push(idx);
            } else {
                rejected_mask |= group.bit;
            }
        }
        if let Some(route) = select_best_candidate(config, self, &eligible, rejected_mask, retry_penalties) {
            return Some(route);
        }

        self.preferred_host_candidate(
            config,
            record,
            dest,
            payload,
            false,
            transport,
            rejected_mask,
            can_reconnect,
            true,
            retry_penalties,
        )
    }

    fn preferred_host_candidate(
        &self,
        config: &RuntimeConfig,
        record: &LearnedHostRecord,
        dest: SocketAddr,
        payload: Option<&[u8]>,
        allow_unknown_payload: bool,
        transport: TransportProtocol,
        attempted_mask: u64,
        can_reconnect: bool,
        penalized: bool,
        retry_penalties: Option<&BTreeMap<usize, RetrySelectionPenalty>>,
    ) -> Option<ConnectionRoute> {
        let now_ms = now_millis();
        let mut next_mask = attempted_mask;
        let mut eligible = Vec::new();
        for &idx in &record.preferred_groups {
            let group = config.groups.get(idx)?;
            if next_mask & group.bit != 0 {
                continue;
            }
            if (self.detect_for(config, idx) & DETECT_RECONN) != 0 && !can_reconnect {
                next_mask |= group.bit;
                continue;
            }
            let is_penalized = record.group_stats.get(&idx).is_some_and(|stats| stats.penalty_until_ms > now_ms);
            if is_penalized != penalized {
                next_mask |= group.bit;
                continue;
            }
            if group_matches(config, group, dest, payload, allow_unknown_payload, transport) {
                eligible.push(idx);
            } else {
                next_mask |= group.bit;
            }
        }
        select_best_candidate(config, self, &eligible, next_mask, retry_penalties)
    }

    pub fn note_route_success(
        &mut self,
        config: &RuntimeConfig,
        dest: SocketAddr,
        route: &ConnectionRoute,
        host: Option<&str>,
    ) -> io::Result<()> {
        self.note_route_success_for_transport(config, dest, route, host, TransportProtocol::Tcp)
    }

    pub fn note_route_success_for_transport(
        &mut self,
        config: &RuntimeConfig,
        dest: SocketAddr,
        route: &ConnectionRoute,
        host: Option<&str>,
        transport: TransportProtocol,
    ) -> io::Result<()> {
        if transport == TransportProtocol::Tcp {
            let normalized_host = host.and_then(normalize_learned_host);
            self.store(config, dest, route.group_index, route.attempted_mask, normalized_host.clone())?;
            if let Some(host) = normalized_host {
                self.note_host_success(config, &host, route.group_index)?;
            }
        } else {
            self.store(config, dest, route.group_index, route.attempted_mask, host.map(str::to_owned))?;
        }
        Ok(())
    }

    pub fn drain_autolearn_events(&mut self) -> Vec<HostAutolearnEvent> {
        self.autolearn_events.drain(..).collect()
    }

    pub fn autolearn_state(&self, config: &RuntimeConfig) -> (bool, usize, usize) {
        let now_ms = now_millis();
        let penalized =
            self.learned_hosts(config).values().filter(|record| host_has_active_penalty(record, now_ms)).count();
        (config.host_autolearn_enabled, self.learned_hosts(config).len(), penalized)
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

    fn learned_hosts(&self, config: &RuntimeConfig) -> &BTreeMap<String, LearnedHostRecord> {
        self.learned_hosts_by_scope.get(network_scope_key(config)).unwrap_or(&EMPTY_LEARNED_HOSTS)
    }

    fn learned_hosts_mut(&mut self, config: &RuntimeConfig) -> &mut BTreeMap<String, LearnedHostRecord> {
        self.learned_hosts_by_scope.entry(network_scope_key(config).to_owned()).or_default()
    }

    fn note_host_failure(&mut self, config: &RuntimeConfig, host: &str, group_index: usize) -> io::Result<()> {
        if !config.host_autolearn_enabled {
            return Ok(());
        }
        let Some(host) = normalize_learned_host(host) else {
            return Ok(());
        };
        let now_ms = now_millis();
        let record = self.learned_hosts_mut(config).entry(host.clone()).or_default();
        let stats = record.group_stats.entry(group_index).or_default();
        stats.failure_count = stats.failure_count.saturating_add(1);
        stats.last_failure_at_ms = now_ms;
        stats.penalty_until_ms = now_ms.saturating_add(config.host_autolearn_penalty_ttl_secs.max(1) as u64 * 1_000);
        record.updated_at_ms = now_ms;
        ensure_host_order(record, group_index);
        self.enforce_autolearn_limit(config, now_ms);
        self.persist_host_store(config)?;
        self.autolearn_events.push_back(HostAutolearnEvent {
            action: "group_penalized",
            host: Some(host),
            group_index: Some(group_index),
        });
        Ok(())
    }

    fn note_host_success(&mut self, config: &RuntimeConfig, host: &str, group_index: usize) -> io::Result<()> {
        if !config.host_autolearn_enabled {
            return Ok(());
        }
        let now_ms = now_millis();
        let record = self.learned_hosts_mut(config).entry(host.to_owned()).or_default();
        let stats = record.group_stats.entry(group_index).or_default();
        stats.success_count = stats.success_count.saturating_add(1);
        stats.last_success_at_ms = now_ms;
        stats.penalty_until_ms = 0;
        record.updated_at_ms = now_ms;
        promote_group(record, group_index);
        self.enforce_autolearn_limit(config, now_ms);
        self.persist_host_store(config)?;
        self.autolearn_events.push_back(HostAutolearnEvent {
            action: "host_promoted",
            host: Some(host.to_owned()),
            group_index: Some(group_index),
        });
        Ok(())
    }

    fn enforce_autolearn_limit(&mut self, config: &RuntimeConfig, now_ms: u64) {
        let max_hosts = config.host_autolearn_max_hosts.max(1);
        while self.learned_hosts(config).len() > max_hosts {
            let host_to_remove = {
                let hosts = self.learned_hosts(config);
                hosts
                    .iter()
                    .filter(|(_, record)| !host_has_active_penalty(record, now_ms))
                    .min_by_key(|(_, record)| record.updated_at_ms)
                    .or_else(|| hosts.iter().min_by_key(|(_, record)| record.updated_at_ms))
                    .map(|(host, _)| host.clone())
            };
            let Some(host) = host_to_remove else {
                break;
            };
            self.learned_hosts_mut(config).remove(&host);
        }
    }

    fn persist_host_store(&self, config: &RuntimeConfig) -> io::Result<()> {
        if !config.host_autolearn_enabled {
            return Ok(());
        }
        let Some(path) = config.host_autolearn_store_path.as_deref() else {
            return Ok(());
        };
        let store = LearnedHostStore {
            version: HOST_AUTOLEARN_STORE_VERSION,
            fingerprint: config_fingerprint(config),
            scopes: self
                .learned_hosts_by_scope
                .iter()
                .map(|(scope, hosts)| (scope.clone(), LearnedNetworkScopeStore { hosts: hosts.clone() }))
                .collect(),
        };
        let payload = serde_json::to_vec_pretty(&store)
            .map_err(|err| io::Error::other(format!("failed to serialize host autolearn store: {err}")))?;
        atomic_write(Path::new(path), &payload)
    }
}

pub fn select_initial_group(
    config: &RuntimeConfig,
    cache: &mut RuntimeCache,
    dest: SocketAddr,
    payload: Option<&[u8]>,
    host: Option<&str>,
    allow_unknown_payload: bool,
    transport: TransportProtocol,
) -> Option<ConnectionRoute> {
    if let Some(normalized_host) = host.filter(|_| transport == TransportProtocol::Tcp).and_then(normalize_learned_host)
    {
        if let Some(route) =
            cache.select_host_route(config, &normalized_host, dest, payload, allow_unknown_payload, transport)
        {
            return Some(route);
        }
    } else if let Some(route) = cache.lookup_and_prune(config, dest) {
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
    host: Option<&str>,
    transport: TransportProtocol,
    trigger: u32,
    can_reconnect: bool,
    retry_penalties: Option<&BTreeMap<usize, RetrySelectionPenalty>>,
) -> Option<ConnectionRoute> {
    if let Some(normalized_host) = host.filter(|_| transport == TransportProtocol::Tcp).and_then(normalize_learned_host)
    {
        if let Some(next) = cache.select_host_route_after(
            config,
            route,
            &normalized_host,
            dest,
            payload,
            transport,
            trigger,
            can_reconnect,
            retry_penalties,
        ) {
            return Some(next);
        }
    }
    let mut attempted_mask = route.attempted_mask | config.groups[route.group_index].bit;
    let mut eligible = Vec::new();
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
            eligible.push(idx);
        } else {
            attempted_mask |= group.bit;
        }
    }
    select_best_candidate(config, cache, &eligible, attempted_mask, retry_penalties)
}

fn select_best_candidate(
    _config: &RuntimeConfig,
    cache: &RuntimeCache,
    eligible: &[usize],
    attempted_mask: u64,
    retry_penalties: Option<&BTreeMap<usize, RetrySelectionPenalty>>,
) -> Option<ConnectionRoute> {
    let mut ranked = eligible.to_vec();
    ranked.sort_by_key(|index| {
        let penalty = retry_penalty(retry_penalties, *index);
        let group = cache.groups.get(*index);
        (
            penalty.same_signature_cooldown_ms > 0,
            penalty.family_cooldown_ms > 0,
            group.map_or(0, |value| value.fail_count),
            group.map_or(0, |value| value.pri),
            penalty.diversification_rank,
            *index,
        )
    });
    ranked
        .into_iter()
        .next()
        .map(|group_index| ConnectionRoute { group_index, attempted_mask })
}

fn retry_penalty(
    retry_penalties: Option<&BTreeMap<usize, RetrySelectionPenalty>>,
    group_index: usize,
) -> RetrySelectionPenalty {
    retry_penalties.and_then(|value| value.get(&group_index).copied()).unwrap_or_default()
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

enum LoadLearnedHostStoreError {
    Invalidated,
    Io,
}

fn load_learned_host_store(
    config: &RuntimeConfig,
) -> Result<BTreeMap<String, BTreeMap<String, LearnedHostRecord>>, LoadLearnedHostStoreError> {
    let Some(path) = config.host_autolearn_store_path.as_deref() else {
        return Ok(BTreeMap::new());
    };
    let path = Path::new(path);
    if !path.exists() {
        return Ok(BTreeMap::new());
    }
    let payload = fs::read(path).map_err(|_| LoadLearnedHostStoreError::Io)?;
    let store =
        serde_json::from_slice::<LearnedHostStore>(&payload).map_err(|_| LoadLearnedHostStoreError::Invalidated)?;
    if store.version != HOST_AUTOLEARN_STORE_VERSION || store.fingerprint != config_fingerprint(config) {
        return Err(LoadLearnedHostStoreError::Invalidated);
    }
    Ok(store
        .scopes
        .into_iter()
        .map(|(scope, scope_store)| {
            let hosts = scope_store
                .hosts
                .into_iter()
                .filter_map(|(host, mut record)| {
                    let Some(normalized_host) = normalize_learned_host(&host) else {
                        return None;
                    };
                    record.preferred_groups.retain(|group_index| *group_index < config.groups.len());
                    record.group_stats.retain(|group_index, _| *group_index < config.groups.len());
                    (!record.preferred_groups.is_empty() || !record.group_stats.is_empty())
                        .then_some((normalized_host, record))
                })
                .collect::<BTreeMap<_, _>>();
            (scope, hosts)
        })
        .filter(|(_, hosts)| !hosts.is_empty())
        .collect())
}

fn ensure_host_order(record: &mut LearnedHostRecord, group_index: usize) {
    if !record.preferred_groups.contains(&group_index) {
        record.preferred_groups.push(group_index);
    }
}

fn promote_group(record: &mut LearnedHostRecord, group_index: usize) {
    record.preferred_groups.retain(|current| *current != group_index);
    record.preferred_groups.insert(0, group_index);
}

fn host_has_active_penalty(record: &LearnedHostRecord, now_ms: u64) -> bool {
    record.group_stats.values().any(|stats| stats.penalty_until_ms > now_ms)
}

fn normalize_learned_host(host: &str) -> Option<String> {
    let trimmed = host.trim().trim_end_matches('.');
    if trimmed.is_empty() {
        return None;
    }
    let normalized = trimmed.to_ascii_lowercase();
    if normalized.parse::<IpAddr>().is_ok() {
        return None;
    }
    Some(normalized)
}

fn config_fingerprint(config: &RuntimeConfig) -> String {
    let mut hasher = Sha256::new();
    hasher.update(format!("{:?}", config.groups).as_bytes());
    hasher.update(format!("|{}", config.groups.len()).as_bytes());
    format!("{:x}", hasher.finalize())
}

fn network_scope_key(config: &RuntimeConfig) -> &str {
    config
        .network_scope_key
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .unwrap_or(DEFAULT_NETWORK_SCOPE_KEY)
}

fn atomic_write(path: &Path, payload: &[u8]) -> io::Result<()> {
    let Some(parent) = path.parent() else {
        return fs::write(path, payload);
    };
    fs::create_dir_all(parent)?;
    let tmp_name = format!(
        ".{}.tmp-{}-{}",
        path.file_name().and_then(|name| name.to_str()).unwrap_or("autolearn"),
        std::process::id(),
        next_temp_file_nonce()
    );
    let tmp_path = parent.join(tmp_name);
    fs::write(&tmp_path, payload)?;
    if path.exists() {
        let _ = fs::remove_file(path);
    }
    fs::rename(tmp_path, path)
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

fn now_millis() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}

fn next_temp_file_nonce() -> u64 {
    static TEMP_FILE_NONCE: AtomicU64 = AtomicU64::new(0);
    let timestamp = now_millis() << 16;
    let sequence = TEMP_FILE_NONCE.fetch_add(1, Ordering::Relaxed) & 0xFFFF;
    timestamp | sequence
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

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

    fn autolearn_config(group_count: usize, max_hosts: usize) -> RuntimeConfig {
        let groups = (0..group_count).map(DesyncGroup::new).collect();
        let mut config = config_with_groups(groups);
        config.host_autolearn_enabled = true;
        config.host_autolearn_penalty_ttl_secs = 3_600;
        config.host_autolearn_max_hosts = max_hosts;
        let mut path = std::env::temp_dir();
        path.push(format!(
            "ripdpi-host-autolearn-{}-{group_count}-{max_hosts}.json",
            next_temp_file_nonce()
        ));
        config.host_autolearn_store_path = Some(path.to_string_lossy().into_owned());
        config
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
            learned_hosts_by_scope: std::collections::BTreeMap::new(),
            autolearn_events: std::collections::VecDeque::new(),
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

        let route =
            select_initial_group(&config, &mut cache, sample_dest(80), None, None, true, TransportProtocol::Tcp)
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
            None,
            TransportProtocol::Tcp,
            DETECT_RECONN,
            true,
            None,
        )
        .expect("next route");

        assert_eq!(next.group_index, 1);
        assert_eq!(next.attempted_mask, config.groups[0].bit);
    }

    #[test]
    fn select_next_group_prefers_non_cooled_candidate_when_retry_penalties_exist() {
        let first = DesyncGroup::new(0);
        let mut second = DesyncGroup::new(1);
        second.detect = DETECT_RECONN;
        let mut third = DesyncGroup::new(2);
        third.detect = DETECT_RECONN;
        let config = config_with_groups(vec![first, second, third]);
        let cache = RuntimeCache::load(&config);
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
                RetrySelectionPenalty {
                    same_signature_cooldown_ms: 0,
                    family_cooldown_ms: 0,
                    diversification_rank: 20,
                },
            ),
        ]);

        let next = select_next_group(
            &config,
            &cache,
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
        second.detect = DETECT_RECONN;
        let mut third = DesyncGroup::new(2);
        third.detect = DETECT_RECONN;
        let config = config_with_groups(vec![first, second, third]);
        let cache = RuntimeCache::load(&config);
        let route = ConnectionRoute { group_index: 0, attempted_mask: 0 };
        let penalties = BTreeMap::from([
            (
                1usize,
                RetrySelectionPenalty {
                    same_signature_cooldown_ms: 0,
                    family_cooldown_ms: 0,
                    diversification_rank: 25,
                },
            ),
            (
                2usize,
                RetrySelectionPenalty {
                    same_signature_cooldown_ms: 0,
                    family_cooldown_ms: 0,
                    diversification_rank: 5,
                },
            ),
        ]);

        let next = select_next_group(
            &config,
            &cache,
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

    #[test]
    fn host_preference_outranks_destination_cache() {
        let config = autolearn_config(2, 32);
        let dest = sample_dest(443);
        let mut cache = RuntimeCache::load(&config);

        cache.store(&config, dest, 0, 0, None).expect("store destination cache");
        cache
            .note_route_success(
                &config,
                dest,
                &ConnectionRoute { group_index: 1, attempted_mask: 0 },
                Some("Example.org"),
            )
            .expect("learn host route");

        let route =
            select_initial_group(&config, &mut cache, dest, None, Some("example.org"), true, TransportProtocol::Tcp)
                .expect("host-aware route");

        assert_eq!(route.group_index, 1);
    }

    #[test]
    fn successful_fallback_promotes_final_group_for_host() {
        let config = autolearn_config(3, 32);
        let dest = sample_dest(443);
        let mut cache = RuntimeCache::load(&config);

        cache
            .note_route_success(
                &config,
                dest,
                &ConnectionRoute { group_index: 0, attempted_mask: 0 },
                Some("example.org"),
            )
            .expect("learn first group");
        cache
            .note_route_success(
                &config,
                dest,
                &ConnectionRoute { group_index: 2, attempted_mask: config.groups[0].bit },
                Some("example.org"),
            )
            .expect("promote fallback winner");

        let learned = cache.learned_hosts(&config).get("example.org").expect("learned host");
        assert_eq!(learned.preferred_groups.first().copied(), Some(2));
    }

    #[test]
    fn penalties_suppress_group_until_ttl_expiry() {
        let config = autolearn_config(2, 32);
        let dest = sample_dest(443);
        let mut cache = RuntimeCache::load(&config);

        cache
            .note_route_success(
                &config,
                dest,
                &ConnectionRoute { group_index: 1, attempted_mask: 0 },
                Some("example.org"),
            )
            .expect("learn preferred group");
        cache.note_host_failure(&config, "example.org", 1).expect("penalize preferred group");

        let penalized_route =
            select_initial_group(&config, &mut cache, dest, None, Some("example.org"), true, TransportProtocol::Tcp)
                .expect("fallback while penalized");
        assert_eq!(penalized_route.group_index, 0);

        cache
            .learned_hosts_mut(&config)
            .get_mut("example.org")
            .and_then(|record| record.group_stats.get_mut(&1))
            .expect("penalized stats")
            .penalty_until_ms = now_millis().saturating_sub(1);

        let recovered_route =
            select_initial_group(&config, &mut cache, dest, None, Some("example.org"), true, TransportProtocol::Tcp)
                .expect("preferred route after penalty expiry");
        assert_eq!(recovered_route.group_index, 1);
    }

    #[test]
    fn fingerprint_mismatch_invalidates_learned_state() {
        let mut config = autolearn_config(1, 32);
        let dest = sample_dest(443);
        {
            let mut cache = RuntimeCache::load(&config);
            cache
                .note_route_success(
                    &config,
                    dest,
                    &ConnectionRoute { group_index: 0, attempted_mask: 0 },
                    Some("example.org"),
                )
                .expect("persist learned host");
        }

        config.groups.push(DesyncGroup::new(1));
        let mut cache = RuntimeCache::load(&config);

        assert!(cache.learned_hosts(&config).is_empty());
        let events = cache.drain_autolearn_events();
        assert!(events.iter().any(|event| event.action == "store_reset"));
    }

    #[test]
    fn max_host_eviction_removes_oldest_records() {
        let config = autolearn_config(1, 1);
        let dest = sample_dest(443);
        let mut cache = RuntimeCache::load(&config);

        cache
            .note_route_success(
                &config,
                dest,
                &ConnectionRoute { group_index: 0, attempted_mask: 0 },
                Some("first.example"),
            )
            .expect("learn first host");
        cache.learned_hosts_mut(&config).get_mut("first.example").expect("first host").updated_at_ms = 1;
        cache
            .note_route_success(
                &config,
                dest,
                &ConnectionRoute { group_index: 0, attempted_mask: 0 },
                Some("second.example"),
            )
            .expect("learn second host");

        assert!(!cache.learned_hosts(&config).contains_key("first.example"));
        assert!(cache.learned_hosts(&config).contains_key("second.example"));
    }

    #[test]
    fn host_autolearn_is_scoped_by_network_scope_key() {
        let mut config_a = autolearn_config(1, 32);
        let path = config_a.host_autolearn_store_path.clone().expect("store path");
        config_a.network_scope_key = Some("scope-a".to_string());
        let dest = sample_dest(443);

        let mut cache_a = RuntimeCache::load(&config_a);
        cache_a
            .note_route_success(
                &config_a,
                dest,
                &ConnectionRoute { group_index: 0, attempted_mask: 0 },
                Some("alpha.example"),
            )
            .expect("learn scope a host");

        let mut config_b = autolearn_config(1, 32);
        config_b.host_autolearn_store_path = Some(path);
        config_b.network_scope_key = Some("scope-b".to_string());
        let mut cache_b = RuntimeCache::load(&config_b);
        assert!(cache_b.learned_hosts(&config_b).is_empty());
        cache_b
            .note_route_success(
                &config_b,
                dest,
                &ConnectionRoute { group_index: 0, attempted_mask: 0 },
                Some("beta.example"),
            )
            .expect("learn scope b host");

        let reloaded_a = RuntimeCache::load(&config_a);
        assert!(reloaded_a.learned_hosts(&config_a).contains_key("alpha.example"));
        assert!(!reloaded_a.learned_hosts(&config_a).contains_key("beta.example"));

        let reloaded_b = RuntimeCache::load(&config_b);
        assert!(reloaded_b.learned_hosts(&config_b).contains_key("beta.example"));
        assert!(!reloaded_b.learned_hosts(&config_b).contains_key("alpha.example"));
    }

    #[test]
    fn legacy_v1_host_autolearn_store_is_invalidated() {
        let config = autolearn_config(1, 32);
        let path = config.host_autolearn_store_path.clone().expect("store path");
        let payload = json!({
            "version": 1,
            "fingerprint": config_fingerprint(&config),
            "hosts": {
                "example.org": {
                    "preferred_groups": [0],
                    "group_stats": {},
                    "updated_at_ms": 1
                }
            }
        });
        fs::write(&path, serde_json::to_vec_pretty(&payload).expect("serialize legacy payload"))
            .expect("write legacy store");

        let mut cache = RuntimeCache::load(&config);

        assert!(cache.learned_hosts(&config).is_empty());
        assert!(cache.drain_autolearn_events().iter().any(|event| event.action == "store_reset"));
    }
}
