use std::fs;
use std::net::{IpAddr, SocketAddr};
use std::path::Path;

use ripdpi_config::{RuntimeConfig, dump_cache_entries, load_cache_entries_from_path, prefix_match_bytes};

use super::route_from_record;
use crate::runtime_policy::ConnectionRoute;
use crate::runtime_policy::now_unix;
use crate::runtime_policy::types::CacheRecord;

pub(super) fn load_records(config: &RuntimeConfig) -> Vec<CacheRecord> {
    let mut records = Vec::new();
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
    records
}

pub(super) fn lookup_record(
    records: &mut Vec<CacheRecord>,
    config: &RuntimeConfig,
    dest: SocketAddr,
) -> Option<ConnectionRoute> {
    let now = now_unix();
    records.retain(|record| !is_expired(config, record, now));
    records.iter().find(|record| cache_matches(&record.entry, dest)).map(route_from_record)
}

pub(super) fn store_record(
    records: &mut Vec<CacheRecord>,
    config: &RuntimeConfig,
    dest: SocketAddr,
    group_index: usize,
    attempted_mask: u64,
    host: Option<String>,
) {
    let entry = ripdpi_config::CacheEntry {
        addr: dest.ip(),
        bits: cache_bits(config, dest.ip()),
        port: dest.port(),
        time: now_unix(),
        host,
    };
    if let Some(existing) = records.iter_mut().find(|record| cache_matches(&record.entry, dest)) {
        existing.entry = entry;
        existing.group_index = group_index;
        existing.attempted_mask = attempted_mask;
    } else {
        records.push(CacheRecord { entry, group_index, attempted_mask });
    }
}

pub(super) fn clear_record(records: &mut Vec<CacheRecord>, dest: SocketAddr) -> bool {
    let before = records.len();
    records.retain(|record| !cache_matches(&record.entry, dest));
    records.len() != before
}

pub(super) fn clear_all_records(records: &mut Vec<CacheRecord>) -> usize {
    let cleared = records.len();
    records.clear();
    cleared
}

pub(super) fn persist_records_for_group(records: &[CacheRecord], config: &RuntimeConfig, group_index: usize) {
    let Some(path) = config.groups[group_index].policy.cache_file.as_deref() else {
        return;
    };
    if path == "-" {
        return;
    }
    let entries: Vec<_> =
        records.iter().filter(|record| record.group_index == group_index).map(|record| record.entry.clone()).collect();
    if let Err(err) = fs::write(path, dump_cache_entries(&entries)) {
        tracing::warn!("cache persist failed (non-fatal): {err}");
    }
}

pub(super) fn cache_matches(entry: &ripdpi_config::CacheEntry, dest: SocketAddr) -> bool {
    if entry.port != dest.port() {
        return false;
    }
    match (entry.addr, dest.ip()) {
        (IpAddr::V4(lhs), IpAddr::V4(rhs)) => prefix_match_bytes(&lhs.octets(), &rhs.octets(), entry.bits as u8),
        (IpAddr::V6(lhs), IpAddr::V6(rhs)) => prefix_match_bytes(&lhs.octets(), &rhs.octets(), entry.bits as u8),
        _ => false,
    }
}

pub(super) fn is_expired(config: &RuntimeConfig, record: &CacheRecord, now: i64) -> bool {
    let Some(group) = config.groups.get(record.group_index) else {
        return true;
    };
    let ttl = if group.policy.cache_ttl != 0 { group.policy.cache_ttl } else { config.adaptive.cache_ttl };
    ttl != 0 && now > record.entry.time + ttl
}

pub(super) fn cache_bits(config: &RuntimeConfig, ip: IpAddr) -> u16 {
    match ip {
        IpAddr::V4(_) if config.adaptive.cache_prefix != 0 => (32 - config.adaptive.cache_prefix as u16).max(1),
        IpAddr::V4(_) => 32,
        IpAddr::V6(_) => 128,
    }
}
