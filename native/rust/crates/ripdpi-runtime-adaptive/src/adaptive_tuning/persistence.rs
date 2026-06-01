use std::cmp::Ordering;
use std::collections::{BTreeMap, HashMap};
use std::io;

use super::key::{adaptive_seed, normalize_scope_key};
use super::state::AdaptivePlannerState;
use super::types::AdaptivePlannerKey;

mod file_io;
mod location;
mod schema;

pub(super) use location::adaptive_store_path;
#[cfg(test)]
pub(super) use schema::{StoredOffsetBase, restore_offset_base};

use super::types::{AdaptiveFlowKind, AdaptivePlannerTarget};
use file_io::{read_store, write_store};
use location::adaptive_store_fingerprint;
use schema::{
    ADAPTIVE_TUNING_STORE_VERSION, StoredAdaptiveNetworkScope, StoredAdaptivePlannerEntry, StoredAdaptivePlannerStore,
};

pub(super) fn load_adaptive_store(
    config: &ripdpi_config::RuntimeConfig,
) -> Result<HashMap<AdaptivePlannerKey, AdaptivePlannerState>, io::Error> {
    let Some(path) = adaptive_store_path(config) else {
        return Ok(HashMap::new());
    };
    if !path.exists() {
        return Ok(HashMap::new());
    }
    let store = read_store(&path)?;
    if store.version != ADAPTIVE_TUNING_STORE_VERSION || store.fingerprint != adaptive_store_fingerprint(config) {
        return Ok(HashMap::new());
    }
    let mut states = HashMap::new();
    for (network_scope_key, scope) in store.scopes {
        let scope_key = normalize_scope_key(Some(&network_scope_key)).to_string();
        for entry in scope.entries {
            if entry.group_index >= config.groups.len() {
                continue;
            }
            let key = AdaptivePlannerKey {
                network_scope_key: scope_key.clone(),
                group_index: entry.group_index,
                flow_kind: entry.flow_kind,
                target: entry.target,
            };
            let seed = adaptive_seed(&key);
            states.insert(key, AdaptivePlannerState::from_persisted(entry.state, seed));
        }
    }
    Ok(states)
}

pub(super) fn write_adaptive_store(
    config: &ripdpi_config::RuntimeConfig,
    states: &HashMap<AdaptivePlannerKey, AdaptivePlannerState>,
) -> io::Result<()> {
    let Some(path) = adaptive_store_path(config) else {
        return Ok(());
    };
    let mut scopes: BTreeMap<String, StoredAdaptiveNetworkScope> = BTreeMap::new();
    for (key, state) in states {
        scopes.entry(key.network_scope_key.clone()).or_default().entries.push(StoredAdaptivePlannerEntry {
            group_index: key.group_index,
            flow_kind: key.flow_kind,
            target: key.target.clone(),
            state: state.to_persisted(),
        });
    }
    for scope in scopes.values_mut() {
        scope.entries.sort_by(|left, right| {
            left.group_index
                .cmp(&right.group_index)
                .then_with(|| compare_flow_kind_for_store(left.flow_kind, right.flow_kind))
                .then_with(|| compare_target_for_store(&left.target, &right.target))
        });
    }
    let store = StoredAdaptivePlannerStore {
        version: ADAPTIVE_TUNING_STORE_VERSION,
        fingerprint: adaptive_store_fingerprint(config),
        scopes,
    };
    write_store(&path, &store)
}

fn compare_flow_kind_for_store(left: AdaptiveFlowKind, right: AdaptiveFlowKind) -> Ordering {
    flow_kind_debug_rank(left).cmp(&flow_kind_debug_rank(right))
}

fn flow_kind_debug_rank(flow_kind: AdaptiveFlowKind) -> u8 {
    match flow_kind {
        AdaptiveFlowKind::TcpOther => 0,
        AdaptiveFlowKind::TcpTls => 1,
        AdaptiveFlowKind::UdpOther => 2,
        AdaptiveFlowKind::UdpQuic => 3,
    }
}

fn compare_target_for_store(left: &AdaptivePlannerTarget, right: &AdaptivePlannerTarget) -> Ordering {
    match (left, right) {
        (AdaptivePlannerTarget::Address(left), AdaptivePlannerTarget::Address(right)) => left.cmp(right),
        (AdaptivePlannerTarget::Address(_), AdaptivePlannerTarget::Host(_)) => Ordering::Less,
        (AdaptivePlannerTarget::Host(_), AdaptivePlannerTarget::Address(_)) => Ordering::Greater,
        (AdaptivePlannerTarget::Host(left), AdaptivePlannerTarget::Host(right)) => left.cmp(right),
    }
}
