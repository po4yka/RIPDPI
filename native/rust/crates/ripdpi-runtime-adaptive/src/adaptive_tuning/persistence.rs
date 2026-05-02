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
pub(super) use schema::{restore_offset_base, StoredOffsetBase};

use file_io::{read_store, write_store};
use location::adaptive_store_fingerprint;
use schema::{
    StoredAdaptiveNetworkScope, StoredAdaptivePlannerEntry, StoredAdaptivePlannerStore, ADAPTIVE_TUNING_STORE_VERSION,
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
        scope.entries.sort_by_key(|entry| format!("{}|{:?}|{:?}", entry.group_index, entry.flow_kind, entry.target));
    }
    let store = StoredAdaptivePlannerStore {
        version: ADAPTIVE_TUNING_STORE_VERSION,
        fingerprint: adaptive_store_fingerprint(config),
        scopes,
    };
    write_store(&path, &store)
}
