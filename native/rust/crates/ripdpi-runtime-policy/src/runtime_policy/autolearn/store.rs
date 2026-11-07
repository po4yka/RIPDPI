use std::collections::BTreeMap;
use std::fs;
use std::io::{self, Read, Write};
use std::path::Path;

use ring::digest;
use ripdpi_config::RuntimeConfig;

use super::host_filter::normalize_learned_host;
use super::state::{host_record_has_persisted_state, prune_expired_host_state};
use crate::runtime_policy::types::{
    LearnedHostRecord, LearnedHostStore, LearnedNetworkScopeStore, LoadLearnedHostStoreError,
};
use crate::runtime_policy::{
    AUTOLEARN_PERSIST_DEBOUNCE_MS, DEFAULT_NETWORK_SCOPE_KEY, HOST_AUTOLEARN_STORE_VERSION, RuntimePolicy, now_millis,
};

/// Same bound as the adaptive tuning store: a larger file is treated as invalid and replaced.
pub(super) const MAX_AUTOLEARN_STORE_BYTES: u64 = 8 * 1024 * 1024;

impl RuntimePolicy {
    pub(super) fn persist_host_store(&mut self, config: &RuntimeConfig) {
        let now_ms = now_millis();
        if now_ms.saturating_sub(self.last_persist_at_ms) < AUTOLEARN_PERSIST_DEBOUNCE_MS {
            return;
        }
        match self.write_host_store(config) {
            Ok(()) => {
                self.last_persist_at_ms = now_ms;
            }
            Err(err) => {
                tracing::warn!("autolearn store write failed (non-fatal): {err}");
            }
        }
    }

    /// Force-persist the host store, bypassing the debounce window.
    /// Call this on proxy shutdown to avoid losing recent state.
    pub fn flush_host_store(&mut self, config: &RuntimeConfig) {
        match self.write_host_store(config) {
            Ok(()) => {
                self.last_persist_at_ms = now_millis();
            }
            Err(err) => {
                tracing::warn!("autolearn store flush failed (non-fatal): {err}");
            }
        }
    }

    fn write_host_store(&self, config: &RuntimeConfig) -> io::Result<()> {
        if !config.host_autolearn.enabled {
            return Ok(());
        }
        let Some(path) = config.host_autolearn.store_path.as_deref() else {
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
        let payload = serde_json::to_vec(&store)
            .map_err(|err| io::Error::other(format!("failed to serialize host autolearn store: {err}")))?;
        atomic_write(Path::new(path), &payload)
    }
}

pub(in crate::runtime_policy) fn load_learned_host_store(
    config: &RuntimeConfig,
) -> Result<BTreeMap<String, BTreeMap<String, LearnedHostRecord>>, LoadLearnedHostStoreError> {
    let Some(path) = config.host_autolearn.store_path.as_deref() else {
        return Ok(BTreeMap::new());
    };
    let path = Path::new(path);
    if !path.exists() {
        return Ok(BTreeMap::new());
    }
    let mut payload = Vec::new();
    fs::File::open(path)
        .and_then(|file| file.take(MAX_AUTOLEARN_STORE_BYTES + 1).read_to_end(&mut payload))
        .map_err(|_| LoadLearnedHostStoreError::Io)?;
    if payload.len() as u64 > MAX_AUTOLEARN_STORE_BYTES {
        return Err(LoadLearnedHostStoreError::Invalidated);
    }
    let store =
        serde_json::from_slice::<LearnedHostStore>(&payload).map_err(|_| LoadLearnedHostStoreError::Invalidated)?;
    if store.version != HOST_AUTOLEARN_STORE_VERSION || store.fingerprint != config_fingerprint(config) {
        return Err(LoadLearnedHostStoreError::Invalidated);
    }
    let now_ms = now_millis();
    Ok(store
        .scopes
        .into_iter()
        .map(|(scope, scope_store)| {
            let hosts = scope_store
                .hosts
                .into_iter()
                .filter_map(|(host, mut record)| {
                    let normalized_host = normalize_learned_host(&host)?;
                    prune_expired_host_state(&mut record, now_ms);
                    record.preferred_groups.retain(|group_index| *group_index < config.groups.len());
                    record.group_stats.retain(|group_index, _| *group_index < config.groups.len());
                    host_record_has_persisted_state(&record).then_some((normalized_host, record))
                })
                .collect::<BTreeMap<_, _>>();
            (scope, hosts)
        })
        .filter(|(_, hosts)| !hosts.is_empty())
        .collect())
}

pub(super) fn config_fingerprint(config: &RuntimeConfig) -> String {
    let mut input = format!("{:?}", config.groups);
    input.push_str(&format!("|{}", config.groups.len()));
    let d = digest::digest(&digest::SHA256, input.as_bytes());
    d.as_ref().iter().fold(String::new(), |mut s, b| {
        use std::fmt::Write;
        write!(s, "{b:02x}").unwrap();
        s
    })
}

pub(super) fn network_scope_key(config: &RuntimeConfig) -> &str {
    config
        .adaptive
        .network_scope_key
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .unwrap_or(DEFAULT_NETWORK_SCOPE_KEY)
}

fn atomic_write(path: &Path, payload: &[u8]) -> io::Result<()> {
    let parent = path.parent().filter(|parent| !parent.as_os_str().is_empty()).unwrap_or(Path::new("."));
    fs::create_dir_all(parent)?;
    let mut file = tempfile::NamedTempFile::new_in(parent)?;
    file.write_all(payload)?;
    match fs::metadata(path) {
        Ok(metadata) => file.as_file().set_permissions(metadata.permissions())?,
        Err(error) if error.kind() == io::ErrorKind::NotFound => {}
        Err(error) => return Err(error),
    }
    file.as_file().sync_all()?;
    file.persist(path).map_err(|error| error.error)?;
    // Best-effort directory sync keeps the existing durability contract.
    let _ = fs::File::open(parent).and_then(|dir| dir.sync_all());
    Ok(())
}

#[cfg(test)]
mod atomic_file_tests {
    use super::*;

    #[test]
    fn failed_replacement_keeps_destination_and_removes_temporary_file() {
        let temp = tempfile::tempdir().unwrap();
        let destination = temp.path().join("store.json");
        fs::create_dir(&destination).unwrap();
        fs::write(destination.join("marker"), b"old state").unwrap();
        assert!(atomic_write(&destination, b"new state").is_err());
        assert_eq!(fs::read(destination.join("marker")).unwrap(), b"old state");
        assert_eq!(fs::read_dir(temp.path()).unwrap().count(), 1);
    }

    #[cfg(unix)]
    #[test]
    fn replacement_is_private_and_preserves_existing_permissions() {
        use std::os::unix::fs::PermissionsExt;
        let temp = tempfile::tempdir().unwrap();
        let destination = temp.path().join("store.json");
        atomic_write(&destination, b"first").unwrap();
        assert_eq!(fs::metadata(&destination).unwrap().permissions().mode() & 0o777, 0o600);
        fs::set_permissions(&destination, fs::Permissions::from_mode(0o640)).unwrap();
        atomic_write(&destination, b"replacement").unwrap();
        assert_eq!(fs::read(&destination).unwrap(), b"replacement");
        assert_eq!(fs::metadata(&destination).unwrap().permissions().mode() & 0o777, 0o640);
        assert_eq!(fs::read_dir(temp.path()).unwrap().count(), 1);
    }
}
