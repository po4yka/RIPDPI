use std::collections::{BTreeMap, VecDeque};

use ripdpi_config::RuntimeConfig;

use super::store_reset_event;
use crate::runtime_policy::autolearn::load_learned_host_store;
use crate::runtime_policy::types::{LearnedHostRecord, LoadLearnedHostStoreError};
use crate::runtime_policy::HostAutolearnEvent;

pub(super) fn load_autolearn_store(
    config: &RuntimeConfig,
) -> (BTreeMap<String, BTreeMap<String, LearnedHostRecord>>, VecDeque<HostAutolearnEvent>) {
    let mut learned_hosts_by_scope = BTreeMap::new();
    let mut events = VecDeque::new();
    if !config.host_autolearn.enabled {
        return (learned_hosts_by_scope, events);
    }

    match load_learned_host_store(config) {
        Ok(hosts) => learned_hosts_by_scope = hosts,
        Err(LoadLearnedHostStoreError::Invalidated) => {
            events.push_back(store_reset_event());
        }
        Err(LoadLearnedHostStoreError::Io) => {}
    }
    (learned_hosts_by_scope, events)
}
