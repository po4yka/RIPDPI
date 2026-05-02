mod autolearn_store;
mod connection;
mod export;
mod ordering;

#[cfg(test)]
mod tests;

use std::collections::BTreeMap;
use std::io::{self, Write};
use std::net::SocketAddr;

use ripdpi_config::RuntimeConfig;

use self::autolearn_store::load_autolearn_store;
use self::connection::{clear_all_records, clear_record, lookup_record, persist_records_for_group, store_record};
use self::export::dump_stdout_cache_groups;
use self::ordering::{build_group_policies, initial_group_order, swap_group_order};
use super::types::CacheRecord;
use super::{ConnectionRoute, HostAutolearnEvent, RuntimePolicy};

impl RuntimePolicy {
    pub fn load(config: &RuntimeConfig) -> Self {
        let records = connection::load_records(config);
        let groups = build_group_policies(config);
        let order = initial_group_order(config);
        let (learned_hosts_by_scope, autolearn_events) = load_autolearn_store(config);
        Self {
            records,
            groups,
            order,
            learned_hosts_by_scope,
            pending_blocked_hosts_by_scope: BTreeMap::new(),
            autolearn_events,
            last_persist_at_ms: 0,
        }
    }

    pub(crate) fn lookup_and_prune(&mut self, config: &RuntimeConfig, dest: SocketAddr) -> Option<ConnectionRoute> {
        lookup_record(&mut self.records, config, dest)
    }

    pub fn store(
        &mut self,
        config: &RuntimeConfig,
        dest: SocketAddr,
        group_index: usize,
        attempted_mask: u64,
        host: Option<String>,
    ) {
        store_record(&mut self.records, config, dest, group_index, attempted_mask, host);
        self.persist_group(config, group_index);
    }

    pub(crate) fn clear(&mut self, config: &RuntimeConfig, dest: SocketAddr) {
        if !clear_record(&mut self.records, dest) {
            return;
        }
        for group_index in 0..config.groups.len() {
            self.persist_group(config, group_index);
        }
    }

    pub fn clear_connection_cache(&mut self, config: &RuntimeConfig) -> usize {
        let cleared = clear_all_records(&mut self.records);
        if cleared == 0 {
            return 0;
        }
        for group_index in 0..config.groups.len() {
            self.persist_group(config, group_index);
        }
        cleared
    }

    pub(crate) fn persist_group(&self, config: &RuntimeConfig, group_index: usize) {
        persist_records_for_group(&self.records, config, group_index);
    }

    pub fn dump_stdout_groups<W: Write>(&self, config: &RuntimeConfig, writer: W) -> io::Result<()> {
        dump_stdout_cache_groups(&self.records, config, writer)
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
        swap_group_order(&mut self.order, &mut self.groups, lhs, rhs);
    }
}

fn route_from_record(record: &CacheRecord) -> ConnectionRoute {
    ConnectionRoute { group_index: record.group_index, attempted_mask: record.attempted_mask }
}

fn store_reset_event() -> HostAutolearnEvent {
    HostAutolearnEvent { action: "store_reset", host: None, group_index: None }
}
